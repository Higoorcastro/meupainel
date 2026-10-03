package com.tvloja.signage.player

import android.os.SystemClock
import com.tvloja.signage.domain.model.MediaItem
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Máquina de estados da playlist — responsável EXCLUSIVAMENTE por decidir o que tocar e quando.
 *
 * ```
 * PlaylistPlayer  ──state──▶  PlayerScreen
 *      ▲                        ├── ImageRenderer (Coil)
 *      └──── callbacks ─────────└── VideoPlayer   (Media3/ExoPlayer)
 * ```
 *
 * - Vive no escopo da aplicação (não da Activity): sobrevive à recriação de Activity e
 *   permite que o painel administrativo mostre o status em tempo real.
 * - Imagens: o timer fica aqui (com suporte a pausa/continuação preservando o tempo restante).
 * - Vídeos: a duração é a do próprio vídeo; o [VideoPlayer] avisa o fim via [onMediaCompleted].
 * - Erros: registra, pula para a próxima mídia; se TODAS falharem em sequência, entra em
 *   [PlaybackStatus.RECOVERING] e tenta novamente após [RECOVERY_DELAY_MS] (nunca trava, nunca entra em loop rápido).
 *
 * Todos os métodos devem ser chamados na thread principal (o [scope] usa Dispatchers.Main).
 */
class PlaylistPlayer(
    private val scope: CoroutineScope,
    private val availability: MediaAvailabilityChecker,
    private val reporter: PlaybackReporter,
) {
    companion object {
        const val RECOVERY_DELAY_MS = 30_000L
        private const val MIN_IMAGE_MS = 1_000L
    }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var items: List<MediaItem> = emptyList()
    private var playlistLoaded = false
    private var index = -1
    private var current: MediaItem? = null
    private var token = 0L
    private var startedAtWall = 0L

    // Condições de execução
    private var hostActive = false   // tela do player visível e Activity em primeiro plano
    private var userPaused = false   // pausado pelo administrador
    private var stopped = false      // parado pelo administrador
    private var recovering = false   // aguardando nova tentativa após falhas em sequência

    // Timer de imagem
    private var imageJob: Job? = null
    private var imageRemainingMs = 0L
    private var imageSegmentStartElapsed = 0L
    private var imageSegmentStartWall = 0L

    private var videoDurationMs: Long? = null
    private var consecutiveErrors = 0
    private var lastError: String? = null
    private var retryJob: Job? = null
    private var attached = false

    private val isRunning: Boolean get() = hostActive && !userPaused && !stopped && !recovering

    /** Conecta o player à fonte da playlist (chamado uma única vez pelo AppContainer). */
    fun attach(playlist: Flow<List<MediaItem>>) {
        if (attached) return
        attached = true
        scope.launch { playlist.collect { onPlaylistChanged(it) } }
    }

    // ---------------------------------------------------------------- Controles públicos

    fun play() {
        val wasStopped = stopped
        userPaused = false
        stopped = false
        AppLogger.i("Reprodução iniciada/continuada")
        when {
            recovering -> Unit
            current == null || wasStopped -> startAt(if (index >= 0) index else 0)
            current?.type == MediaType.IMAGE && isRunning -> startImageTimer()
        }
        publish()
    }

    fun pause() {
        if (userPaused) return
        userPaused = true
        pauseImageTimer()
        AppLogger.i("Reprodução pausada")
        publish()
    }

    fun stop() {
        stopped = true
        userPaused = false
        cancelImageTimer()
        retryJob?.cancel()
        recovering = false
        current = null
        AppLogger.i("Reprodução parada")
        publish()
    }

    fun restart() {
        AppLogger.i("Reiniciando playlist")
        stopped = false
        userPaused = false
        recovering = false
        consecutiveErrors = 0
        retryJob?.cancel()
        startAt(0)
    }

    fun next() {
        if (items.isEmpty()) return
        AppLogger.i("Avanço manual")
        startAt(index + 1)
    }

    fun previous() {
        if (items.isEmpty()) return
        AppLogger.i("Retorno manual")
        startAt(index - 1)
    }

    /** Informado pela tela do player: true quando visível e em primeiro plano. */
    fun setHostActive(active: Boolean) {
        if (hostActive == active) return
        hostActive = active
        AppLogger.d("Host do player ${if (active) "ativo" else "inativo"}")
        if (active) {
            if (current == null && !stopped && !recovering && items.isNotEmpty()) {
                startAt(0)
                return
            }
            if (current?.type == MediaType.IMAGE && isRunning) startImageTimer()
        } else {
            pauseImageTimer()
        }
        publish()
    }

    // ---------------------------------------------------------------- Callbacks dos renderizadores

    fun onMediaCompleted(token: Long) {
        if (token != this.token) return
        val item = current ?: return
        consecutiveErrors = 0
        reporter.onItemCompleted(item)
        if (item.type == MediaType.VIDEO) AppLogger.i("Video completed: ${item.name}")
        AppLogger.d("Moving to next media")
        startAt(index + 1)
    }

    fun onMediaError(token: Long, reason: String?) {
        if (token != this.token) return
        val item = current ?: return
        registerError(item, reason ?: "Erro desconhecido")
        if (consecutiveErrors >= items.size) enterRecovery() else startAt(index + 1)
    }

    fun onVideoReady(token: Long, durationMs: Long?) {
        if (token != this.token) return
        videoDurationMs = durationMs?.takeIf { it > 0 }
        consecutiveErrors = 0
        publish()
    }

    // ---------------------------------------------------------------- Lógica interna

    private fun onPlaylistChanged(newItems: List<MediaItem>) {
        val firstLoad = !playlistLoaded
        playlistLoaded = true
        items = newItems
        AppLogger.i("Playlist loaded: ${newItems.size} mídia(s) ativa(s)")

        if (newItems.isEmpty()) {
            cancelImageTimer()
            retryJob?.cancel()
            recovering = false
            current = null
            index = -1
            publish()
            return
        }
        if (recovering) {
            // Conteúdo mudou: vale tentar de novo imediatamente.
            recovering = false
            retryJob?.cancel()
            consecutiveErrors = 0
            startAt(0)
            return
        }
        val cur = current
        if (cur == null) {
            if (!stopped) startAt(if (firstLoad) 0 else index.coerceAtLeast(0)) else publish()
            return
        }
        val newIndex = newItems.indexOfFirst { it.id == cur.id }
        if (newIndex < 0) {
            // Item atual foi removido/desativado: o próximo da fila ocupa a mesma posição.
            startAt(index.coerceIn(0, newItems.lastIndex))
            return
        }
        val updated = newItems[newIndex]
        index = newIndex
        current = updated
        if (updated.uri != cur.uri || updated.type != cur.type) {
            startAt(newIndex) // arquivo trocado (ex.: download concluído) — recarrega
            return
        }
        if (updated.type == MediaType.IMAGE && updated.durationMs != cur.durationMs) {
            val running = imageJob?.isActive == true
            pauseImageTimer()
            val elapsed = cur.durationMs - imageRemainingMs
            imageRemainingMs = (updated.durationMs - elapsed).coerceAtLeast(0)
            if (running) startImageTimer()
        }
        publish()
    }

    /** Inicia a primeira mídia disponível a partir de [startIndex], pulando as indisponíveis. */
    private fun startAt(startIndex: Int) {
        cancelImageTimer()
        retryJob?.cancel()
        if (items.isEmpty()) {
            current = null
            index = -1
            publish()
            return
        }
        var i = Math.floorMod(startIndex, items.size)
        repeat(items.size) {
            val candidate = items[i]
            val problem = availability.check(candidate)
            if (problem == null) {
                begin(i, candidate)
                return
            }
            index = i
            registerError(candidate, problem)
            if (consecutiveErrors >= items.size) {
                enterRecovery()
                return
            }
            i = (i + 1) % items.size
        }
        enterRecovery()
    }

    private fun begin(i: Int, item: MediaItem) {
        index = i
        current = item
        token++
        startedAtWall = System.currentTimeMillis()
        videoDurationMs = item.mediaDurationMs
        reporter.onItemStarted(item)
        if (item.type == MediaType.IMAGE) {
            imageRemainingMs = item.durationMs.coerceAtLeast(MIN_IMAGE_MS)
            AppLogger.d("Image duration: ${imageRemainingMs / 1000}s")
            if (isRunning) startImageTimer()
        }
        publish()
    }

    private fun registerError(item: MediaItem, reason: String) {
        consecutiveErrors++
        lastError = "${item.name}: $reason"
        reporter.onItemFailed(item, reason)
    }

    private fun enterRecovery() {
        cancelImageTimer()
        recovering = true
        current = null
        AppLogger.w("Nenhuma mídia pôde ser reproduzida. Nova tentativa em ${RECOVERY_DELAY_MS / 1000}s")
        publish()
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RECOVERY_DELAY_MS)
            recovering = false
            consecutiveErrors = 0
            AppLogger.i("Tentando retomar a reprodução")
            startAt(index + 1)
        }
    }

    private fun startImageTimer() {
        imageJob?.cancel()
        val expected = token
        imageSegmentStartElapsed = SystemClock.elapsedRealtime()
        imageSegmentStartWall = System.currentTimeMillis()
        val wait = imageRemainingMs
        imageJob = scope.launch {
            delay(wait)
            imageJob = null
            imageRemainingMs = 0
            onMediaCompleted(expected)
        }
    }

    private fun pauseImageTimer() {
        val job = imageJob ?: return
        job.cancel()
        imageJob = null
        val elapsed = SystemClock.elapsedRealtime() - imageSegmentStartElapsed
        imageRemainingMs = (imageRemainingMs - elapsed).coerceAtLeast(0)
    }

    private fun cancelImageTimer() {
        imageJob?.cancel()
        imageJob = null
    }

    private fun publish() {
        val cur = current
        val status = when {
            !playlistLoaded -> PlaybackStatus.LOADING
            items.isEmpty() -> PlaybackStatus.EMPTY
            stopped -> PlaybackStatus.STOPPED
            recovering -> PlaybackStatus.RECOVERING
            userPaused -> PlaybackStatus.PAUSED
            !hostActive -> PlaybackStatus.SUSPENDED
            else -> PlaybackStatus.PLAYING
        }
        val endsAt = when (cur?.type) {
            MediaType.IMAGE -> if (imageJob != null) imageSegmentStartWall + imageRemainingMs else null
            MediaType.VIDEO -> videoDurationMs?.let { startedAtWall + it }
            null -> null
        }
        val size = items.size
        _state.value = PlaybackState(
            status = status,
            current = cur,
            index = if (cur != null) index else -1,
            total = size,
            token = token,
            startedAtMs = if (cur != null) startedAtWall else null,
            endsAtMs = endsAt,
            next = if (size > 0 && index >= 0) items[(index + 1) % size] else null,
            previous = if (size > 0 && index >= 0) items[Math.floorMod(index - 1, size)] else null,
            lastError = lastError,
            consecutiveErrors = consecutiveErrors,
        )
    }
}
