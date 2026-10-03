package com.tvloja.signage.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tvloja.signage.SignageApp
import com.tvloja.signage.data.local.files.StorageFullException
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.CancellationException

/**
 * Baixa uma mídia remota para o armazenamento local (cache offline). Enquanto o download não termina,
 * o item continua sendo reproduzido via streaming. Ao terminar, a playlist passa a usar o arquivo local.
 */
class DownloadMediaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SignageApp).container
        val id = inputData.getLong(KEY_ITEM_ID, -1L)
        val item = container.playlistRepository.getById(id) ?: return Result.success()
        if (!item.isStreaming) return Result.success() // já é local
        val url = item.uri

        return try {
            AppLogger.i("Baixando para uso offline: ${item.name}")
            val file = container.mediaDownloader.download(url, url.substringBefore('?').substringAfterLast('/'))
            val latest = container.playlistRepository.getById(id)
            if (latest == null || latest.uri != url) {
                // Item removido ou alterado durante o download: descarta o arquivo.
                file.delete()
                return Result.success()
            }
            container.playlistRepository.updatePlaybackUri(id, Uri.fromFile(file).toString(), file.length())
            AppLogger.i("Download concluído: ${item.name} (${file.length() / 1024} KB)")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageFullException) {
            AppLogger.e("Sem espaço para baixar ${item.name}; continuará via streaming", e)
            Result.failure()
        } catch (e: Exception) {
            AppLogger.w("Falha no download de ${item.name} (tentativa ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        private const val MAX_ATTEMPTS = 5
    }
}
