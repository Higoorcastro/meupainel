package com.tvloja.signage.data.local.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tvloja.signage.domain.model.ImageScaleMode
import com.tvloja.signage.domain.model.SignageSettings
import com.tvloja.signage.domain.model.TransitionType
import com.tvloja.signage.domain.repository.RemoteSettings
import com.tvloja.signage.domain.repository.SettingsRepository
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "signage_settings")

class DataStoreSettingsRepository(context: Context) : SettingsRepository {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val SCALE = stringPreferencesKey("image_scale_mode")
        val TRANSITION = stringPreferencesKey("transition")
        val TRANSITION_MS = intPreferencesKey("transition_ms")
        val DEFAULT_IMAGE_SEC = intPreferencesKey("default_image_sec")
        val VIDEO_MUTED = booleanPreferencesKey("video_muted")
        val AUTO_START = booleanPreferencesKey("auto_start_on_boot")
        val SERVER_URL = stringPreferencesKey("server_url")
        val DEVICE_TOKEN = stringPreferencesKey("device_token")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val PIN_HASH = stringPreferencesKey("pin_hash")
    }

    override val settings: Flow<SignageSettings> = store.data
        .catch { e ->
            // Arquivo corrompido/ilegível: usa padrões em vez de derrubar o app.
            if (e is IOException) {
                AppLogger.e("Falha ao ler configurações; usando padrões", e)
                emit(emptyPreferences())
            } else throw e
        }
        .map { p -> p.toSettings() }

    override suspend fun current(): SignageSettings = settings.first()

    private fun Preferences.toSettings(): SignageSettings {
        val d = SignageSettings()
        return SignageSettings(
            imageScaleMode = this[Keys.SCALE]?.let { runCatching { ImageScaleMode.valueOf(it) }.getOrNull() } ?: d.imageScaleMode,
            transition = this[Keys.TRANSITION]?.let { runCatching { TransitionType.valueOf(it) }.getOrNull() } ?: d.transition,
            transitionDurationMs = this[Keys.TRANSITION_MS] ?: d.transitionDurationMs,
            defaultImageDurationSec = this[Keys.DEFAULT_IMAGE_SEC] ?: d.defaultImageDurationSec,
            videoMuted = this[Keys.VIDEO_MUTED] ?: d.videoMuted,
            autoStartOnBoot = this[Keys.AUTO_START] ?: d.autoStartOnBoot,
            serverUrl = this[Keys.SERVER_URL] ?: d.serverUrl,
            deviceId = this[Keys.DEVICE_ID] ?: d.deviceId,
        )
    }

    override suspend fun setImageScaleMode(mode: ImageScaleMode) {
        store.edit { it[Keys.SCALE] = mode.name }
    }

    override suspend fun setTransition(type: TransitionType) {
        store.edit { it[Keys.TRANSITION] = type.name }
    }

    override suspend fun setTransitionDurationMs(ms: Int) {
        store.edit { it[Keys.TRANSITION_MS] = ms.coerceIn(0, SignageSettings.MAX_TRANSITION_MS) }
    }

    override suspend fun setDefaultImageDurationSec(sec: Int) {
        store.edit {
            it[Keys.DEFAULT_IMAGE_SEC] = sec.coerceIn(SignageSettings.MIN_IMAGE_SEC, SignageSettings.MAX_IMAGE_SEC)
        }
    }

    override suspend fun setVideoMuted(muted: Boolean) {
        store.edit { it[Keys.VIDEO_MUTED] = muted }
    }

    override suspend fun setAutoStartOnBoot(enabled: Boolean) {
        store.edit { it[Keys.AUTO_START] = enabled }
    }

    override suspend fun setServerUrl(url: String) {
        store.edit { it[Keys.SERVER_URL] = url.trim() }
    }

    override suspend fun applyRemoteSettings(remote: RemoteSettings) {
        store.edit { p ->
            remote.imageScaleMode?.let { p[Keys.SCALE] = it.name }
            remote.transition?.let { p[Keys.TRANSITION] = it.name }
            remote.transitionDurationMs?.let { p[Keys.TRANSITION_MS] = it.coerceIn(0, SignageSettings.MAX_TRANSITION_MS) }
            remote.videoMuted?.let { p[Keys.VIDEO_MUTED] = it }
        }
    }

    override suspend fun ensureDeviceToken(): String {
        var token = ""
        store.edit { p ->
            token = p[Keys.DEVICE_TOKEN] ?: (UUID.randomUUID().toString() + UUID.randomUUID().toString())
                .replace("-", "")
                .also { p[Keys.DEVICE_TOKEN] = it }
        }
        return token
    }

    override suspend fun ensureDeviceId(): String {
        var id = ""
        store.edit { p ->
            id = p[Keys.DEVICE_ID] ?: UUID.randomUUID().toString().also { p[Keys.DEVICE_ID] = it }
        }
        return id
    }

    override suspend fun getPinHash(): String? = store.data.catch { emit(emptyPreferences()) }.first()[Keys.PIN_HASH]

    override suspend fun setPinHash(hash: String) {
        store.edit { it[Keys.PIN_HASH] = hash }
    }
}
