package com.tvloja.signage.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    fun bytes(value: Long?): String {
        if (value == null || value < 0) return "—"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            value >= gb -> String.format(Locale.US, "%.1f GB", value / gb)
            value >= mb -> String.format(Locale.US, "%.1f MB", value / mb)
            value >= kb -> String.format(Locale.US, "%.0f KB", value / kb)
            else -> "$value B"
        }
    }

    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return "—"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun time(ms: Long?): String =
        if (ms == null) "—" else SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    fun dateTime(ms: Long?): String =
        if (ms == null) "—" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(ms))
}
