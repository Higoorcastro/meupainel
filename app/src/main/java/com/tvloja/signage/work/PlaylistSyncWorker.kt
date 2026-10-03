package com.tvloja.signage.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tvloja.signage.SignageApp

/**
 * Sincronização de reserva com o servidor central (a cada 15 min, mínimo do WorkManager).
 * A sincronização principal roda a cada minuto dentro do app ([com.tvloja.signage.sync.ServerSyncManager]);
 * este worker garante que a playlist seja atualizada mesmo que aquele ciclo pare por algum motivo.
 */
class PlaylistSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SignageApp).container
        container.serverSync.syncOnce()
        return Result.success()
    }
}
