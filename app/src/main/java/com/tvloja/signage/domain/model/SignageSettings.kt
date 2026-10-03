package com.tvloja.signage.domain.model

/** Como a imagem ocupa a tela (nunca distorce a proporção). */
enum class ImageScaleMode {
    /** Preenche toda a tela, cortando as bordas se a proporção for diferente. */
    CENTER_CROP,

    /** Mostra a imagem inteira, com faixas pretas se a proporção for diferente. */
    FIT_CENTER,
}

enum class TransitionType { NONE, FADE }

data class SignageSettings(
    val imageScaleMode: ImageScaleMode = ImageScaleMode.CENTER_CROP,
    val transition: TransitionType = TransitionType.FADE,
    val transitionDurationMs: Int = 800,
    val defaultImageDurationSec: Int = 10,
    val videoMuted: Boolean = false,
    val autoStartOnBoot: Boolean = true,
    /** Endereço do servidor central (ex.: https://signage.seudominio.com). Vazio = somente conteúdo local. */
    val serverUrl: String = "",
    /** Identificador único e persistente desta TV (base para gestão de múltiplas TVs). */
    val deviceId: String = "",
) {
    /** Duração efetiva da transição (0 quando desabilitada). */
    val effectiveTransitionMs: Int
        get() = if (transition == TransitionType.FADE) transitionDurationMs else 0

    companion object {
        const val MIN_IMAGE_SEC = 1
        const val MAX_IMAGE_SEC = 3600
        const val MAX_TRANSITION_MS = 3000
    }
}
