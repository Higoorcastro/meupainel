package com.tvloja.signage.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tvloja.signage.SignageApp
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Abre o player quando a TV termina de ligar (BOOT_COMPLETED) ou após uma atualização do app.
 * Veja [AutoStart] para as restrições do Android 10+.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLogger.i("Evento de sistema recebido: $action")
        val app = context.applicationContext as SignageApp
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                val settings = app.container.settingsRepository.current()
                if (!settings.autoStartOnBoot) {
                    AppLogger.i("Início automático desativado nas configurações")
                    return@launch
                }
                // Pequena espera para o launcher/serviços da TV terminarem de subir.
                delay(BOOT_DELAY_MS)
                AutoStart.launchPlayerIfAllowed(app)
            } catch (t: Throwable) {
                AppLogger.e("Falha no início automático", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        // goAsync permite ~10s de execução; mantemos uma margem.
        const val BOOT_DELAY_MS = 4_000L
    }
}
