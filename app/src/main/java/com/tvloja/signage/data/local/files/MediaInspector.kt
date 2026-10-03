package com.tvloja.signage.data.local.files

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lê metadados das mídias SEM carregar o arquivo inteiro na memória:
 * - imagens: apenas o cabeçalho (inJustDecodeBounds);
 * - vídeos: MediaMetadataRetriever (lê só os metadados do contêiner).
 * Também serve para detectar arquivos corrompidos antes de entrarem na playlist.
 */
class MediaInspector(private val context: Context) {

    data class Info(
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
        val mimeType: String?,
    )

    suspend fun inspect(uri: Uri, type: MediaType): Info? = withContext(Dispatchers.IO) {
        when (type) {
            MediaType.IMAGE -> inspectImage(uri)
            MediaType.VIDEO -> inspectVideo(uri)
        }
    }

    private fun inspectImage(uri: Uri): Info? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) null
        else Info(opts.outWidth, opts.outHeight, null, opts.outMimeType)
    }.onFailure { AppLogger.w("Falha ao inspecionar imagem $uri", it) }.getOrNull()

    private fun inspectVideo(uri: Uri): Info? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            if (hasVideo != null && hasVideo != "yes") null else Info(width, height, duration, mime)
        } catch (t: Throwable) {
            AppLogger.w("Falha ao inspecionar vídeo $uri", t)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
