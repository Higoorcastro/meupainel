package com.tvloja.signage.boot

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.tvloja.signage.MainActivity
import com.tvloja.signage.util.AppLogger

/**
 * Inicialização automática do player.
 *
 * Restrições do Android:
 * - Até o Android 9: um BroadcastReceiver de BOOT_COMPLETED pode abrir a Activity diretamente.
 * - Android 10+: apps em segundo plano NÃO podem abrir Activities, exceto se tiverem a permissão
 *   "Sobrepor a outros apps" (SYSTEM_ALERT_WINDOW). Em muitas TVs essa tela não existe nas configurações;
 *   ela pode ser concedida via ADB:  adb shell appops set com.tvloja.signage SYSTEM_ALERT_WINDOW allow
 * - Alternativa mais confiável: definir o app como launcher (tela inicial) da TV — o sistema o abre
 *   sempre que a TV liga ou o botão HOME é pressionado. Ativado pelo painel admin (alias HomeLauncherAlias).
 */
object AutoStart {

    private const val LAUNCHER_ALIAS = "com.tvloja.signage.HomeLauncherAlias"

    fun canStartFromBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    fun launchPlayerIfAllowed(context: Context): Boolean {
        if (!canStartFromBackground(context)) {
            AppLogger.w("Início automático bloqueado: conceda 'Sobrepor a outros apps' ou ative o modo launcher")
            return false
        }
        return try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            AppLogger.i("Player aberto automaticamente")
            true
        } catch (t: Throwable) {
            AppLogger.e("Falha ao abrir o player automaticamente", t)
            false
        }
    }

    fun isLauncherModeEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, LAUNCHER_ALIAS)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setLauncherMode(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, LAUNCHER_ALIAS),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        AppLogger.i("Modo launcher ${if (enabled) "ativado" else "desativado"}")
    }

    /** Abre a tela de permissão de sobreposição, se existir nesta TV. */
    fun openOverlaySettings(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        return intents.any { tryStart(context, it) }
    }

    /** Abre a escolha de app de tela inicial, se existir nesta TV. */
    fun openHomeSettings(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_HOME_SETTINGS)) || tryStart(context, Intent(Settings.ACTION_SETTINGS))

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
