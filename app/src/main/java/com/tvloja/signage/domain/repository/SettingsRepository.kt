package com.tvloja.signage.domain.repository

import com.tvloja.signage.domain.model.ImageScaleMode
import com.tvloja.signage.domain.model.SignageSettings
import com.tvloja.signage.domain.model.TransitionType
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<SignageSettings>

    suspend fun current(): SignageSettings

    suspend fun setImageScaleMode(mode: ImageScaleMode)
    suspend fun setTransition(type: TransitionType)
    suspend fun setTransitionDurationMs(ms: Int)
    suspend fun setDefaultImageDurationSec(sec: Int)
    suspend fun setVideoMuted(muted: Boolean)
    suspend fun setAutoStartOnBoot(enabled: Boolean)
    suspend fun setServerUrl(url: String)

    /** Aplica as configurações definidas no painel web (somente os campos informados). */
    suspend fun applyRemoteSettings(remote: RemoteSettings)

    /** Segredo desta TV para autenticar no servidor (gerado uma vez e nunca exibido). */
    suspend fun ensureDeviceToken(): String

    /** Garante que exista um id único para esta TV e o retorna. */
    suspend fun ensureDeviceId(): String

    suspend fun getPinHash(): String?
    suspend fun setPinHash(hash: String)
}
