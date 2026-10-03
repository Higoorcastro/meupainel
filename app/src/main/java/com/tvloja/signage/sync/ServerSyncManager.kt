package com.tvloja.signage.sync

import android.content.Context
import android.os.SystemClock
import com.tvloja.signage.BuildConfig
import com.tvloja.signage.data.local.files.MediaFileStore
import com.tvloja.signage.domain.repository.DeviceReport
import com.tvloja.signage.domain.repository.PlaylistRepository
import com.tvloja.signage.domain.repository.ServerSyncResponse
import com.tvloja.signage.domain.repository.SettingsRepository
import com.tvloja.signage.domain.repository.SignageServerApi
import com.tvloja.signage.player.PlaylistPlayer
import com.tvloja.signage.util.AppLogger
import com.tvloja.signage.util.DeviceInfo
import com.tvloja.signage.work.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ServerStatus(val label: String) {
    NOT_CONFIGURED("Não configurado (somente conteúdo local)"),
    CONNECTING("Conectando..."),
    PENDING("Aguardando aprovação no painel"),
    CONNECTED("Conectado"),
    REJECTED("Recusado pelo servidor"),
    ERROR("Sem conexão com o servidor"),
}

data class ServerState(
    val status: ServerStatus = ServerStatus.NOT_CONFIGURED,
    val serverUrl: String = "",
    val pairingCode: String? = null,
    val tvName: String? = null,
    val lastSyncAt: Long? = null,
    val message: String? = null,
)

/**
 * Mantém a TV sincronizada com o servidor central enquanto o app estiver aberto.
 *
 * - A cada [CONNECTED_INTERVAL_MS] (ou [PENDING_INTERVAL_MS] enquanto aguarda pareamento) envia o status
 *   e recebe playlist + configurações + comandos.
 * - Mídias novas são baixadas em segundo plano (WorkManager) e ficam salvas para tocar sem internet.
 * - Sem internet ou com o servidor fora do ar, a TV continua exibindo o último conteúdo baixado,
 *   com novas tentativas em intervalos crescentes (até [MAX_BACKOFF_MS]).
 */
class ServerSyncManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: SignageServerApi,
    private val settings: SettingsRepository,
    private val playlistRepository: PlaylistRepository,
    private val fileStore: MediaFileStore,
    private val scheduler: WorkScheduler,
    private val player: PlaylistPlayer,
) {
    companion object {
        const val CONNECTED_INTERVAL_MS = 60_000L
        const val PENDING_INTERVAL_MS = 10_000L
        private const val MIN_BACKOFF_MS = 15_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L

        /** Normaliza o que foi digitado no controle remoto: adiciona https:// e remove barras finais. */
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        }
    }

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    private val startedAt = SystemClock.elapsedRealtime()
    private var backoffMs = MIN_BACKOFF_MS

    fun start(serverUrls: Flow<String>) {
        scope.launch {
            serverUrls.map { it.trim() }.distinctUntilChanged().collectLatest { url ->
                if (url.isEmpty()) {
                    _state.value = ServerState()
                    return@collectLatest
                }
                _state.value = ServerState(status = ServerStatus.CONNECTING, serverUrl = url)
                backoffMs = MIN_BACKOFF_MS
                while (true) {
                    val next = syncOnce(url)
                    withTimeoutOrNull(next) { wakeUp.receive() }
                }
            }
        }
    }

    /** Antecipa a próxima sincronização (botão "Sincronizar agora"). */
    fun syncNow() {
        wakeUp.trySend(Unit)
    }

    /** Executa uma sincronização e retorna em quanto tempo deve ocorrer a próxima. */
    suspend fun syncOnce(): Long = syncOnce(settings.current().serverUrl)

    private suspend fun syncOnce(url: String): Long = mutex.withLock {
        if (url.isBlank()) return CONNECTED_INTERVAL_MS
        try {
            val deviceId = settings.ensureDeviceId()
            val token = settings.ensureDeviceToken()
            when (val response = api.sync(url, deviceId, token, buildReport())) {
                is ServerSyncResponse.Pending -> {
                    if (_state.value.pairingCode != response.pairingCode) {
                        AppLogger.i("Aguardando pareamento no painel. Código: ${response.pairingCode}")
                    }
                    _state.value = ServerState(ServerStatus.PENDING, url, pairingCode = response.pairingCode, lastSyncAt = System.currentTimeMillis())
                    backoffMs = MIN_BACKOFF_MS
                    PENDING_INTERVAL_MS
                }
                is ServerSyncResponse.Approved -> {
                    apply(response)
                    if (_state.value.status != ServerStatus.CONNECTED) AppLogger.i("Conectado ao servidor como \"${response.tvName}\"")
                    _state.value = ServerState(ServerStatus.CONNECTED, url, tvName = response.tvName, lastSyncAt = System.currentTimeMillis())
                    backoffMs = MIN_BACKOFF_MS
                    CONNECTED_INTERVAL_MS
                }
                is ServerSyncResponse.Rejected -> {
                    AppLogger.w("Servidor recusou a TV: ${response.message}")
                    _state.value = ServerState(ServerStatus.REJECTED, url, message = response.message, lastSyncAt = System.currentTimeMillis())
                    MAX_BACKOFF_MS
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val wait = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            if (_state.value.status != ServerStatus.ERROR) AppLogger.w("Falha ao sincronizar com $url", e)
            _state.value = _state.value.copy(
                status = ServerStatus.ERROR,
                serverUrl = url,
                message = e.message ?: e.javaClass.simpleName,
            )
            wait
        }
    }

    private suspend fun apply(response: ServerSyncResponse.Approved) {
        val outcome = playlistRepository.syncRemote(response.playlist)
        outcome.removed.forEach { scheduler.cancelDownload(it.id) }
        outcome.obsoleteUris.forEach { fileStore.deleteIfManaged(it) }
        outcome.needsDownload.forEach { scheduler.enqueueDownload(it) }
        if (outcome.added + outcome.updated + outcome.removed.size > 0) {
            AppLogger.i("Playlist do servidor: +${outcome.added} ~${outcome.updated} -${outcome.removed.size}")
        }
        settings.applyRemoteSettings(response.settings)
        when (response.command) {
            "restart_playlist" -> withContext(Dispatchers.Main) {
                AppLogger.i("Comando do painel: reiniciar playlist")
                player.restart()
            }
            "sync_now", null -> Unit
            else -> AppLogger.w("Comando desconhecido do painel: ${response.command}")
        }
    }

    /** Remove o vínculo com o servidor e o conteúdo baixado dele (itens locais permanecem). */
    suspend fun disconnect() {
        settings.setServerUrl("")
        val outcome = playlistRepository.syncRemote(emptyList())
        outcome.removed.forEach { scheduler.cancelDownload(it.id) }
        outcome.obsoleteUris.forEach { fileStore.deleteIfManaged(it) }
        AppLogger.i("Desconectado do servidor (${outcome.removed.size} mídia(s) remota(s) removida(s))")
    }

    private suspend fun buildReport(): DeviceReport {
        val playback = player.state.value
        val items = playlistRepository.getAll()
        val storage = DeviceInfo.internalStorage(context)
        return DeviceReport(
            model = DeviceInfo.model(),
            appVersion = BuildConfig.VERSION_NAME,
            playbackStatus = playback.status.label,
            currentMedia = playback.current?.name,
            index = playback.index,
            total = playback.total,
            lastError = playback.lastError,
            storageFreeBytes = storage?.freeBytes,
            storageTotalBytes = storage?.totalBytes,
            display = runCatching { DeviceInfo.displayResolution(context) }.getOrDefault("—"),
            downloadsPending = items.count { it.remoteId != null && it.isStreaming },
            localItems = items.count { it.remoteId == null },
            uptimeSec = (SystemClock.elapsedRealtime() - startedAt) / 1000,
        )
    }
}
