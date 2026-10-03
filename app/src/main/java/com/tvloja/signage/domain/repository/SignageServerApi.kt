package com.tvloja.signage.domain.repository

import com.tvloja.signage.domain.model.ImageScaleMode
import com.tvloja.signage.domain.model.RemoteMediaSpec
import com.tvloja.signage.domain.model.TransitionType

/**
 * Contrato com o servidor central (painel web). Implementação: [com.tvloja.signage.data.remote.SignageServerClient].
 *
 * Um único endpoint é usado periodicamente: a TV envia seu status (heartbeat) e recebe playlist,
 * configurações e comandos. Trocar o protocolo (ex.: WebSocket) exige mudar apenas a implementação.
 */
interface SignageServerApi {
    suspend fun sync(serverUrl: String, deviceId: String, token: String, report: DeviceReport): ServerSyncResponse
}

/** Status enviado ao painel a cada sincronização. */
data class DeviceReport(
    val model: String,
    val appVersion: String,
    val playbackStatus: String,
    val currentMedia: String?,
    val index: Int,
    val total: Int,
    val lastError: String?,
    val storageFreeBytes: Long?,
    val storageTotalBytes: Long?,
    val display: String,
    val downloadsPending: Int,
    val localItems: Int,
    val uptimeSec: Long,
)

/** Configurações de exibição definidas por TV no painel. */
data class RemoteSettings(
    val imageScaleMode: ImageScaleMode?,
    val transition: TransitionType?,
    val transitionDurationMs: Int?,
    val videoMuted: Boolean?,
)

sealed interface ServerSyncResponse {
    /** TV ainda não aprovada: o código deve ser exibido na tela para ser digitado no painel. */
    data class Pending(val pairingCode: String) : ServerSyncResponse

    data class Approved(
        val tvName: String,
        val playlist: List<RemoteMediaSpec>,
        val settings: RemoteSettings,
        val command: String?,
    ) : ServerSyncResponse

    /** TV bloqueada no painel ou token não reconhecido. */
    data class Rejected(val message: String) : ServerSyncResponse
}
