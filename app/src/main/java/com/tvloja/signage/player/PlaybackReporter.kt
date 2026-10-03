package com.tvloja.signage.player

import com.tvloja.signage.domain.model.MediaItem
import com.tvloja.signage.util.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Recebe eventos de exibição. Hoje registra em log e mantém contadores em memória;
 * no futuro, uma implementação remota pode enviar "prova de exibição" (relatórios) ao servidor.
 */
interface PlaybackReporter {
    fun onItemStarted(item: MediaItem)
    fun onItemCompleted(item: MediaItem)
    fun onItemFailed(item: MediaItem, reason: String)
}

class LoggingPlaybackReporter : PlaybackReporter {
    private val plays = ConcurrentHashMap<Long, Int>()
    private val failures = ConcurrentHashMap<Long, Int>()

    override fun onItemStarted(item: MediaItem) {
        AppLogger.i("Starting media: ${item.name} [${item.type}]")
    }

    override fun onItemCompleted(item: MediaItem) {
        plays.merge(item.id, 1, Int::plus)
        AppLogger.d("Media completed: ${item.name}")
    }

    override fun onItemFailed(item: MediaItem, reason: String) {
        failures.merge(item.id, 1, Int::plus)
        AppLogger.e("Media failed: ${item.name} — $reason")
    }

    fun playCount(id: Long): Int = plays[id] ?: 0
    fun failureCount(id: Long): Int = failures[id] ?: 0
}
