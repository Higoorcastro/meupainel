package com.tvloja.signage.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Centraliza o agendamento de tarefas em segundo plano (WorkManager sobrevive a reinícios da TV). */
class WorkScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueDownload(itemId: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadMediaWorker>()
            .setInputData(workDataOf(DownloadMediaWorker.KEY_ITEM_ID to itemId))
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_DOWNLOAD)
            .build()
        workManager.enqueueUniqueWork(downloadName(itemId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(itemId: Long) {
        workManager.cancelUniqueWork(downloadName(itemId))
    }

    /** Liga/desliga a sincronização periódica (a cada 15 min, o mínimo do WorkManager). */
    fun configurePeriodicSync(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_SYNC)
            return
        }
        val request = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_SYNC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun downloadName(id: Long) = "download-media-$id"

    companion object {
        private const val TAG_DOWNLOAD = "media-download"
        private const val PERIODIC_SYNC = "playlist-sync-periodic"
    }
}
