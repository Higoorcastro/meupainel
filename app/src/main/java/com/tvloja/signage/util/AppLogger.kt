package com.tvloja.signage.util

import android.content.Context
import android.util.Log
import com.tvloja.signage.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logger central do app.
 *
 * - DEBUG só é emitido em builds de debug (ou com [verbose] = true), evitando logs excessivos em produção.
 * - INFO/WARN/ERROR vão para o Logcat, para um buffer em memória (exibido no painel admin)
 *   e para um arquivo com rotação (files/logs/signage.log, máx. ~512 KB x 2), útil para diagnóstico em campo.
 * - A escrita em arquivo acontece em uma thread dedicada de baixa prioridade (nunca bloqueia a UI).
 */
object AppLogger {
    private const val TAG = "Signage"
    private const val MAX_MEMORY_ENTRIES = 300
    private const val MAX_FILE_BYTES = 512 * 1024L

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(val timeMs: Long, val level: Level, val message: String)

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "signage-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    @Volatile
    private var logFile: File? = null

    @Volatile
    var verbose: Boolean = BuildConfig.DEBUG

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs")
        dir.mkdirs()
        logFile = File(dir, "signage.log")
    }

    fun d(message: String) {
        if (verbose) log(Level.DEBUG, message, null)
    }

    fun i(message: String) = log(Level.INFO, message, null)
    fun w(message: String, t: Throwable? = null) = log(Level.WARN, message, t)
    fun e(message: String, t: Throwable? = null) = log(Level.ERROR, message, t)

    private fun log(level: Level, message: String, t: Throwable?) {
        val text = if (t != null) "$message — ${t.javaClass.simpleName}: ${t.message}" else message
        when (level) {
            Level.DEBUG -> Log.d(TAG, text)
            Level.INFO -> Log.i(TAG, text)
            Level.WARN -> Log.w(TAG, text, t)
            Level.ERROR -> Log.e(TAG, text, t)
        }
        val entry = Entry(System.currentTimeMillis(), level, text)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_MEMORY_ENTRIES) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
        if (level != Level.DEBUG) {
            val stack = if (level == Level.ERROR && t != null) Log.getStackTraceString(t) else null
            io.execute { appendToFile(entry, stack) }
        }
    }

    /** Escrita síncrona usada pelo handler de crash (o processo vai morrer logo em seguida). */
    fun writeCrashSync(thread: Thread, t: Throwable) {
        val entry = Entry(System.currentTimeMillis(), Level.ERROR, "CRASH na thread ${thread.name}: $t")
        Log.e(TAG, entry.message, t)
        appendToFile(entry, Log.getStackTraceString(t))
    }

    fun readLogFile(): String = runCatching { logFile?.takeIf { it.exists() }?.readText() ?: "" }.getOrDefault("")

    private fun appendToFile(entry: Entry, stack: String?) {
        val file = logFile ?: return
        runCatching {
            if (file.length() > MAX_FILE_BYTES) {
                val old = File(file.parentFile, file.name + ".1")
                old.delete()
                file.renameTo(old)
            }
            val line = buildString {
                append(format(entry.timeMs)).append(' ').append(entry.level.name.first()).append(' ')
                append(entry.message).append('\n')
                if (stack != null) append(stack).append('\n')
            }
            file.appendText(line)
        }
    }

    fun format(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))
}
