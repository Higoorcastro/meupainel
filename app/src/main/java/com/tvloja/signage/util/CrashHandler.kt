package com.tvloja.signage.util

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tvloja.signage.MainActivity
import com.tvloja.signage.boot.AutoStart
import kotlin.system.exitProcess

/**
 * Registra crashes não tratados em arquivo e agenda a reabertura do app.
 *
 * Proteção contra loop de crash: se houver mais de 3 crashes em 2 minutos, o reinício automático
 * é suspenso (senão a TV ficaria abrindo/fechando o app indefinidamente).
 *
 * Observação: a partir do Android 10, abrir uma Activity a partir de um alarme só é permitido se o app
 * tiver a permissão "Sobrepor a outros apps" (SYSTEM_ALERT_WINDOW). Veja README.
 */
object CrashHandler {
    private const val PREFS = "crash_handler"
    private const val KEY_TIMES = "times"
    private const val WINDOW_MS = 120_000L
    private const val MAX_CRASHES_IN_WINDOW = 3
    private const val RESTART_DELAY_MS = 3_000L

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLogger.writeCrashSync(thread, throwable)
                scheduleRestart(app)
            }
            if (previous != null) previous.uncaughtException(thread, throwable) else exitProcess(10)
        }
    }

    // commit() síncrono é intencional: o processo será encerrado logo após este método.
    // setExact só é usado quando permitido (API < 31 ou canScheduleExactAlarms()).
    @SuppressLint("ApplySharedPref", "MissingPermission")
    private fun scheduleRestart(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = prefs.getString(KEY_TIMES, "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it < WINDOW_MS } + now
        prefs.edit().putString(KEY_TIMES, recent.joinToString(",")).commit()

        if (recent.size > MAX_CRASHES_IN_WINDOW) {
            AppLogger.writeCrashSync(Thread.currentThread(), IllegalStateException("Muitos crashes seguidos; reinício automático suspenso"))
            return
        }
        if (!AutoStart.canStartFromBackground(context)) return

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            context, 1001, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val at = now + RESTART_DELAY_MS
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        if (canExact) alarm.setExact(AlarmManager.RTC, at, pending) else alarm.set(AlarmManager.RTC, at, pending)
    }
}
