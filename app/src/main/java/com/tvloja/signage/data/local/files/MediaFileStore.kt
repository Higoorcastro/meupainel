package com.tvloja.signage.data.local.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

class StorageFullException(message: String) : IOException(message)

/**
 * Gerencia os arquivos de mídia pertencentes ao app.
 *
 * - Destino preferencial: armazenamento interno (files/media) — não some se um pendrive for removido.
 * - Se faltar espaço interno, usa o armazenamento externo específico do app (Android/data/<pacote>/files/media).
 * - Cópias e downloads são feitos em streaming (buffer de 256 KB) para arquivos temporários ".tmp-*",
 *   renomeados apenas quando completos: nunca há arquivo pela metade na playlist.
 * - [importDir] é uma pasta onde arquivos podem ser enviados via `adb push` sem nenhuma permissão.
 */
class MediaFileStore(private val context: Context) {

    companion object {
        /** Espaço mínimo que sempre deixamos livre no armazenamento (protege o sistema da TV). */
        const val RESERVED_BYTES = 200L * 1024 * 1024
        private const val TEMP_PREFIX = ".tmp-"
        private const val BUFFER_SIZE = 256 * 1024
        private const val STALE_AGE_MS = 60 * 60 * 1000L
    }

    val internalDir: File get() = File(context.filesDir, "media").apply { mkdirs() }

    val externalDir: File?
        get() = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            context.getExternalFilesDir("media")?.apply { mkdirs() }
        } else null

    val importDir: File?
        get() = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            context.getExternalFilesDir("import")?.apply { mkdirs() }
        } else null

    fun freeBytes(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)

    /** Escolhe onde gravar um arquivo de [requiredBytes] (tamanho desconhecido = 0). */
    fun chooseTargetDir(requiredBytes: Long): File? {
        val needed = requiredBytes.coerceAtLeast(0) + RESERVED_BYTES
        val internal = internalDir
        if (freeBytes(internal) >= needed) return internal
        val external = externalDir
        if (external != null && freeBytes(external) >= needed) return external
        return null
    }

    fun requireTargetDir(requiredBytes: Long): File =
        chooseTargetDir(requiredBytes) ?: throw StorageFullException(
            "Armazenamento cheio: são necessários ${requiredBytes / (1024 * 1024)} MB livres além da reserva de segurança."
        )

    /** Copia um conteúdo (content://, file://) para o armazenamento do app. */
    suspend fun importFromUri(
        source: Uri,
        displayName: String,
        sizeHint: Long?,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val size = sizeHint?.takeIf { it > 0 } ?: querySize(source) ?: -1L
        val dir = requireTargetDir(size.coerceAtLeast(0))
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Não foi possível abrir o arquivo de origem")
        input.use { writeAtomically(dir, displayName, it, size, onProgress) }
    }

    /** Grava um stream (ex.: download HTTP) de forma atômica no diretório indicado. */
    suspend fun writeAtomically(
        dir: File,
        displayName: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ): File {
        val dest = uniqueFile(dir, displayName)
        val tmp = File(dir, TEMP_PREFIX + dest.name)
        try {
            tmp.outputStream().use { out -> copy(input, out, totalBytes, onProgress) }
            if (tmp.length() == 0L) throw IOException("Arquivo vazio")
            if (!tmp.renameTo(dest)) throw IOException("Falha ao finalizar o arquivo")
            return dest
        } catch (t: Throwable) {
            tmp.delete()
            if (t is IOException && isNoSpace(t)) {
                throw StorageFullException("Armazenamento cheio durante a cópia.")
            }
            throw t
        }
    }

    private suspend fun copy(input: InputStream, out: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        var lastReported = -1
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            copied += read
            if (total > 0) {
                val pct = ((copied * 100) / total).toInt()
                if (pct != lastReported) {
                    lastReported = pct
                    onProgress(pct / 100f)
                }
            }
        }
        out.flush()
    }

    private fun isNoSpace(e: IOException): Boolean {
        val msg = e.message.orEmpty()
        return msg.contains("ENOSPC") || msg.contains("No space", ignoreCase = true)
    }

    private fun querySize(uri: Uri): Long? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.path?.let { File(it).length() }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun uniqueFile(dir: File, displayName: String): File {
        val clean = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "media" }
        return File(dir, "${System.currentTimeMillis()}_$clean")
    }

    /** true se a URI aponta para um arquivo gerenciado pelo app (que pode ser apagado com segurança). */
    fun isManaged(uri: String): Boolean {
        val file = fileOf(uri) ?: return false
        val path = file.canonicalPath
        return managedDirs().any { path.startsWith(it.canonicalPath + File.separator) }
    }

    fun deleteIfManaged(uri: String) {
        if (!isManaged(uri)) return
        val file = fileOf(uri) ?: return
        if (file.delete()) AppLogger.i("Arquivo removido: ${file.name}")
    }

    /**
     * Remove arquivos temporários abandonados e arquivos órfãos (sem item na playlist).
     * Só apaga arquivos com mais de 1 hora, para não interferir em cópias/downloads em andamento.
     */
    fun cleanup(referencedUris: Collection<String>) {
        val referenced = referencedUris.mapNotNull { fileOf(it)?.let { f -> runCatching { f.canonicalPath }.getOrNull() } }.toSet()
        val now = System.currentTimeMillis()
        var removed = 0
        managedDirs().forEach { dir ->
            dir.listFiles()?.forEach { f ->
                val old = now - f.lastModified() > STALE_AGE_MS
                val orphan = runCatching { f.canonicalPath }.getOrNull() !in referenced
                if (f.isFile && old && (f.name.startsWith(TEMP_PREFIX) || orphan)) {
                    if (f.delete()) removed++
                }
            }
        }
        if (removed > 0) AppLogger.i("Limpeza: $removed arquivo(s) temporário(s)/órfão(s) removido(s)")
    }

    private fun managedDirs(): List<File> = listOfNotNull(internalDir, externalDir)

    private fun fileOf(uri: String): File? {
        val parsed = Uri.parse(uri)
        return if (parsed.scheme == "file") parsed.path?.let { File(it) } else null
    }
}
