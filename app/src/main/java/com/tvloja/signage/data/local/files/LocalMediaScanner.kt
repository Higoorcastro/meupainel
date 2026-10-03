package com.tvloja.signage.data.local.files

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Arquivo de mídia encontrado no aparelho (pasta de importação, armazenamento compartilhado ou USB). */
data class LocalMediaFile(
    val uri: Uri,
    val name: String,
    val type: MediaType,
    val sizeBytes: Long,
    val location: String,
)

/**
 * Descobre mídias disponíveis para adicionar à playlist, sem depender de um seletor de arquivos do
 * sistema (muitas TVs Android não possuem o DocumentsUI / ACTION_OPEN_DOCUMENT).
 *
 * Fontes, em ordem:
 * 1. Pasta de importação do app (Android/data/<pacote>/files/import) — não exige permissão (ideal para adb push);
 * 2. MediaStore (armazenamento interno compartilhado + pendrives indexados) — exige permissão de mídia;
 * 3. Varredura direta das raízes de volumes removíveis (fallback quando o MediaStore não indexou o USB).
 */
class LocalMediaScanner(
    private val context: Context,
    private val fileStore: MediaFileStore,
) {
    companion object {
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
        private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts")
        private val IMAGE_MIME = setOf("image/jpeg", "image/png", "image/webp")
        private const val MAX_RESULTS = 1000
        private const val MAX_DEPTH = 5

        fun typeFromName(name: String): MediaType? {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            return when (ext) {
                in IMAGE_EXT -> MediaType.IMAGE
                in VIDEO_EXT -> MediaType.VIDEO
                else -> null
            }
        }
    }

    suspend fun scan(hasMediaPermission: Boolean): List<LocalMediaFile> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, LocalMediaFile>()

        fileStore.importDir?.let { dir -> scanDirectory(dir, "Pasta de importação", results, 0) }

        if (hasMediaPermission) {
            runCatching { queryMediaStore(results) }.onFailure { AppLogger.w("Falha ao consultar MediaStore", it) }
            runCatching { scanRemovableVolumes(results) }.onFailure { AppLogger.w("Falha ao varrer volumes USB", it) }
        }

        results.values.sortedWith(compareBy({ it.location }, { it.name.lowercase(Locale.ROOT) }))
    }

    private fun scanDirectory(dir: File, location: String, out: MutableMap<String, LocalMediaFile>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_RESULTS) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (out.size >= MAX_RESULTS) return
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (f.name == "Android" || f.name == "LOST.DIR") continue
                scanDirectory(f, location, out, depth + 1)
            } else {
                val type = typeFromName(f.name) ?: continue
                val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                if (key !in out) {
                    out[key] = LocalMediaFile(Uri.fromFile(f), f.name, type, f.length(), "$location • ${f.parentFile?.name.orEmpty()}")
                }
            }
        }
    }

    @Suppress("DEPRECATION") // MediaColumns.DATA: usado apenas para exibir o caminho e evitar duplicados.
    private fun queryMediaStore(out: MutableMap<String, LocalMediaFile>) {
        val volumes: List<String?> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).toList()
        } else listOf(null)

        for (volume in volumes) {
            val sources = listOf(
                MediaType.IMAGE to if (volume != null) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaType.VIDEO to if (volume != null) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )
            for ((type, contentUri) in sources) {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATA,
                )
                context.contentResolver.query(
                    contentUri, projection, null, null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    while (c.moveToNext() && out.size < MAX_RESULTS) {
                        val mime = c.getString(mimeCol).orEmpty()
                        if (type == MediaType.IMAGE && mime !in IMAGE_MIME) continue
                        val name = c.getString(nameCol) ?: continue
                        val path = if (dataCol >= 0) c.getString(dataCol) else null
                        val uri = ContentUris.withAppendedId(contentUri, c.getLong(idCol))
                        val key = path ?: uri.toString()
                        if (key in out) continue
                        val folder = path?.let { File(it).parentFile?.name } ?: volume.orEmpty()
                        val where = if (volume == null || volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "Armazenamento" else "USB"
                        out[key] = LocalMediaFile(uri, name, type, c.getLong(sizeCol), "$where • $folder")
                    }
                }
            }
        }
    }

    private fun scanRemovableVolumes(out: MutableMap<String, LocalMediaFile>) {
        // getExternalFilesDirs: [0] = armazenamento primário; os demais = volumes removíveis (USB/SD).
        val roots = context.getExternalFilesDirs(null)
            .drop(1)
            .filterNotNull()
            .mapNotNull { dir -> dir.absolutePath.substringBefore("/Android/data", "").takeIf { it.isNotEmpty() } }
            .map { File(it) }
        roots.forEach { root -> scanDirectory(root, "USB", out, 0) }
    }
}
