package com.tvloja.signage.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.tvloja.signage.domain.model.MediaItem
import com.tvloja.signage.util.AppLogger
import androidx.media3.common.MediaItem as Media3Item

/**
 * Renderizador de vídeo: encapsula UMA instância de ExoPlayer, reutilizada para todos os vídeos
 * da playlist (criar/destruir players a cada vídeo fragmenta memória e decodificadores de hardware).
 *
 * Ciclo de vida: criado quando a tela do player entra em primeiro plano e liberado ([release]) quando sai,
 * liberando o decodificador de hardware para o sistema.
 *
 * Robustez:
 * - erro do ExoPlayer (arquivo corrompido, codec não suportado, rede) → [Callbacks.onError] → próximo item;
 * - fallback de decodificador habilitado (ex.: HEVC falha no decoder principal → tenta outro);
 * - watchdog: buffering por mais de [BUFFERING_TIMEOUT_MS] ou posição parada por [STALL_TIMEOUT_MS]
 *   enquanto deveria estar tocando → trata como erro e avança;
 * - foco de áudio NÃO é gerenciado: outro app pegando o áudio não pode pausar a propaganda para sempre.
 */
@OptIn(UnstableApi::class)
class VideoPlayer(
    context: Context,
    private val sourceFactory: VideoSourceFactory,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onReady(token: Long, durationMs: Long?)
        fun onCompleted(token: Long)
        fun onError(token: Long, reason: String)
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val BUFFERING_TIMEOUT_MS = 45_000L
        private const val STALL_TIMEOUT_MS = 20_000L
    }

    val exoPlayer: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private var currentToken = -1L
    private var readyReported = false
    private var released = false

    private var bufferingSince = 0L
    private var lastPosition = -1L
    private var lastProgressAt = 0L

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val token = currentToken
            if (token < 0) return
            when (playbackState) {
                Player.STATE_READY -> {
                    bufferingSince = 0L
                    if (!readyReported) {
                        readyReported = true
                        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET }
                        AppLogger.d("Vídeo pronto (duração ${duration ?: "?"} ms)")
                        callbacks.onReady(token, duration)
                    }
                }
                Player.STATE_BUFFERING -> if (bufferingSince == 0L) bufferingSince = SystemClock.elapsedRealtime()
                Player.STATE_ENDED -> {
                    currentToken = -1L
                    callbacks.onCompleted(token)
                }
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            fail("${error.errorCodeName}: ${error.message ?: error.cause?.message.orEmpty()}")
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (released) return
            checkHealth()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    init {
        val renderers = DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .build()
        exoPlayer = ExoPlayer.Builder(context.applicationContext, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ false
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        exoPlayer.addListener(listener)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    /** Carrega e (opcionalmente) inicia o vídeo identificado por [token]. Chamadas repetidas com o mesmo token são ignoradas. */
    fun load(item: MediaItem, token: Long, playWhenReady: Boolean) {
        if (released) return
        if (token == currentToken) {
            exoPlayer.playWhenReady = playWhenReady
            return
        }
        currentToken = token
        readyReported = false
        resetWatchdog()
        AppLogger.d("Carregando vídeo: ${item.uri}")
        try {
            val media = Media3Item.Builder()
                .setUri(item.uri)
                .setMediaId(item.id.toString())
                .build()
            exoPlayer.setMediaSource(sourceFactory.create(media))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = playWhenReady
        } catch (t: Throwable) {
            fail("Falha ao preparar o vídeo: ${t.message}")
        }
    }

    fun setPlaying(play: Boolean) {
        if (released) return
        exoPlayer.playWhenReady = play
        if (play) resetWatchdog()
    }

    fun setMuted(muted: Boolean) {
        if (!released) exoPlayer.volume = if (muted) 0f else 1f
    }

    /** Para e descarrega o vídeo (libera buffers enquanto imagens são exibidas). */
    fun clear() {
        if (released) return
        currentToken = -1L
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        exoPlayer.removeListener(listener)
        exoPlayer.release()
        AppLogger.d("ExoPlayer liberado")
    }

    private fun fail(reason: String) {
        val token = currentToken
        currentToken = -1L
        runCatching { exoPlayer.stop() }
        if (token >= 0) callbacks.onError(token, reason)
    }

    private fun resetWatchdog() {
        bufferingSince = 0L
        lastPosition = -1L
        lastProgressAt = SystemClock.elapsedRealtime()
    }

    private fun checkHealth() {
        if (currentToken < 0 || !exoPlayer.playWhenReady) {
            lastProgressAt = SystemClock.elapsedRealtime()
            return
        }
        val now = SystemClock.elapsedRealtime()
        when (exoPlayer.playbackState) {
            Player.STATE_BUFFERING -> {
                if (bufferingSince > 0 && now - bufferingSince > BUFFERING_TIMEOUT_MS) {
                    AppLogger.w("Watchdog: buffering há mais de ${BUFFERING_TIMEOUT_MS / 1000}s")
                    fail("Tempo de carregamento excedido (rede lenta ou arquivo inacessível)")
                }
            }
            Player.STATE_READY -> {
                val pos = exoPlayer.currentPosition
                if (pos != lastPosition) {
                    lastPosition = pos
                    lastProgressAt = now
                } else if (now - lastProgressAt > STALL_TIMEOUT_MS) {
                    AppLogger.w("Watchdog: vídeo travado em ${pos}ms")
                    fail("Reprodução travada")
                }
            }
            else -> lastProgressAt = now
        }
    }
}
