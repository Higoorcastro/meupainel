package com.tvloja.signage.player

import com.tvloja.signage.domain.model.MediaItem

enum class PlaybackStatus(val label: String) {
    LOADING("Carregando"),
    EMPTY("Playlist vazia"),
    PLAYING("Reproduzindo"),
    PAUSED("Pausado"),
    SUSPENDED("Em espera (tela do player fechada)"),
    STOPPED("Parado"),
    RECOVERING("Recuperando após erros"),
}

/**
 * Estado público e imutável do [PlaylistPlayer].
 *
 * @param token muda a cada início de mídia (inclusive quando o mesmo item é repetido). Os renderizadores
 *              usam o token para saber o que exibir e para que callbacks atrasados de uma mídia antiga
 *              (ex.: erro do ExoPlayer) sejam ignorados.
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.LOADING,
    val current: MediaItem? = null,
    val index: Int = -1,
    val total: Int = 0,
    val token: Long = 0L,
    val startedAtMs: Long? = null,
    val endsAtMs: Long? = null,
    val next: MediaItem? = null,
    val previous: MediaItem? = null,
    val lastError: String? = null,
    val consecutiveErrors: Int = 0,
) {
    /** true quando a mídia atual deve estar efetivamente tocando. */
    val shouldPlay: Boolean get() = status == PlaybackStatus.PLAYING
}
