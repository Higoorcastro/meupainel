package com.tvloja.signage.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.tvloja.signage.SignageApp
import com.tvloja.signage.util.AppLogger

/**
 * Recebe o andamento da instalação feita pelo [AppUpdater].
 * STATUS_PENDING_USER_ACTION → abre a tela "Deseja instalar esta atualização?" (confirmar com OK no controle).
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdater.ACTION_INSTALL_RESULT) return
        val updater = (context.applicationContext as SignageApp).container.appUpdater
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    updater.onInstallResult(false, "Tela de confirmação indisponível")
                    return
                }
                AppLogger.i("Atualização: aguardando confirmação na tela da TV")
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (t: Throwable) {
                    updater.onInstallResult(false, "Não foi possível abrir a confirmação: ${t.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updater.onInstallResult(true, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.onInstallResult(false, null)
            else -> updater.onInstallResult(false, message ?: "Falha na instalação (código $status)")
        }
    }
}
