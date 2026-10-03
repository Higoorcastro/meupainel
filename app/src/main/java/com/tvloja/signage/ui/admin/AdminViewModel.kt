package com.tvloja.signage.ui.admin

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.tvloja.signage.boot.AutoStart
import com.tvloja.signage.di.AppContainer
import com.tvloja.signage.domain.model.ImageScaleMode
import com.tvloja.signage.domain.model.MediaItem
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.domain.model.SignageSettings
import com.tvloja.signage.domain.model.TransitionType
import com.tvloja.signage.domain.usecase.ImportResult
import com.tvloja.signage.sync.ServerSyncManager
import com.tvloja.signage.util.AppLogger
import com.tvloja.signage.util.DeviceInfo
import com.tvloja.signage.util.Formatters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DeviceStatus(
    val appVersion: String = "",
    val model: String = "",
    val windowResolution: String = "",
    val displayResolution: String = "",
    val internalStorage: String = "",
    val externalStorage: String = "",
    val videoCache: String = "",
    val importFolder: String = "",
    val overlayPermission: Boolean = false,
    val launcherMode: Boolean = false,
    val usingDefaultPin: Boolean = false,
)

class AdminViewModel(private val c: AppContainer) : ViewModel() {

    private val appContext: Application = c.app

    val items: StateFlow<List<MediaItem>> =
        c.playlistRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<SignageSettings> = c.settings
    val playback = c.playlistPlayer.state
    val logs = AppLogger.entries
    val server = c.serverSync.state

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _status = MutableStateFlow(DeviceStatus())
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshStatus()
                delay(5_000)
            }
        }
    }

    fun select(id: Long) {
        _selectedId.value = id
    }

    fun playCount(id: Long): Int = c.playbackReporter.playCount(id)

    /** null = arquivo disponível; caso contrário, o motivo (arquivo apagado, pendrive removido...). */
    fun availabilityProblem(item: MediaItem): String? = c.availabilityChecker.check(item)

    fun showMessage(text: String?) {
        _message.value = text
    }

    // ------------------------------------------------------------ Playlist

    fun rename(item: MediaItem, name: String) = launchSafe {
        if (name.isBlank()) return@launchSafe
        c.playlistRepository.rename(item.id, name)
        _message.value = "Nome alterado"
    }

    fun toggleEnabled(item: MediaItem) = launchSafe {
        c.playlistRepository.setEnabled(item.id, !item.enabled)
        _message.value = if (item.enabled) "\"${item.name}\" desativado" else "\"${item.name}\" ativado"
    }

    fun move(item: MediaItem, direction: Int) = launchSafe {
        c.playlistRepository.move(item.id, direction)
    }

    fun changeDuration(item: MediaItem, deltaSec: Int) = launchSafe {
        if (item.type != MediaType.IMAGE) return@launchSafe
        val sec = (item.durationMs / 1000 + deltaSec)
            .coerceIn(SignageSettings.MIN_IMAGE_SEC.toLong(), SignageSettings.MAX_IMAGE_SEC.toLong())
        c.playlistRepository.setImageDuration(item.id, sec * 1000)
    }

    fun applyDefaultDurationToAll() = launchSafe {
        val sec = settings.value.defaultImageDurationSec
        items.value.filter { it.type == MediaType.IMAGE }.forEach { c.playlistRepository.setImageDuration(it.id, sec * 1000L) }
        _message.value = "Duração de $sec s aplicada a todas as imagens"
    }

    fun delete(item: MediaItem) = launchSafe {
        c.deleteMedia(item.id)
        if (_selectedId.value == item.id) _selectedId.value = null
        _message.value = "\"${item.name}\" excluído"
    }

    fun addUrl(url: String, downloadOffline: Boolean) = launchSafe {
        when (val result = c.addRemoteMedia(url, null, downloadOffline)) {
            is ImportResult.Success -> {
                _selectedId.value = result.id
                _message.value = "Adicionado: ${result.name}"
            }
            is ImportResult.Failure -> _message.value = result.message
        }
    }

    // ------------------------------------------------------------ Reprodução

    fun play() = c.playlistPlayer.play()
    fun pause() = c.playlistPlayer.pause()
    fun restart() = c.playlistPlayer.restart()
    fun stop() = c.playlistPlayer.stop()

    // ------------------------------------------------------------ Configurações

    fun setScaleMode(mode: ImageScaleMode) = launchSafe { c.settingsRepository.setImageScaleMode(mode) }
    fun setTransition(type: TransitionType) = launchSafe { c.settingsRepository.setTransition(type) }
    fun changeTransitionMs(delta: Int) = launchSafe {
        c.settingsRepository.setTransitionDurationMs(settings.value.transitionDurationMs + delta)
    }
    fun changeDefaultDuration(delta: Int) = launchSafe {
        c.settingsRepository.setDefaultImageDurationSec(settings.value.defaultImageDurationSec + delta)
    }
    fun setMuted(muted: Boolean) = launchSafe { c.settingsRepository.setVideoMuted(muted) }
    fun setAutoStart(enabled: Boolean) = launchSafe { c.settingsRepository.setAutoStartOnBoot(enabled) }

    fun setLauncherMode(enabled: Boolean) = launchSafe {
        AutoStart.setLauncherMode(appContext, enabled)
        refreshStatus()
        _message.value = if (enabled) {
            "Modo launcher ativado. Pressione HOME e escolha \"Digital Signage\" como padrão (se a TV perguntar)."
        } else "Modo launcher desativado."
    }

    fun openOverlaySettings() {
        if (!AutoStart.openOverlaySettings(appContext)) {
            _message.value = "Esta TV não tem a tela de permissão. Use: adb shell appops set ${appContext.packageName} SYSTEM_ALERT_WINDOW allow"
        }
    }

    fun openHomeSettings() {
        if (!AutoStart.openHomeSettings(appContext)) _message.value = "Configuração de tela inicial indisponível nesta TV"
    }

    fun setServerUrl(input: String) = launchSafe {
        val url = ServerSyncManager.normalizeUrl(input)
        if (url.isEmpty()) {
            _message.value = "Informe o endereço do servidor (ex.: signage.seudominio.com)"
            return@launchSafe
        }
        c.settingsRepository.setServerUrl(url)
        AppLogger.i("Servidor configurado: $url")
        _message.value = "Servidor salvo. Conectando..."
    }

    fun syncNow() {
        if (settings.value.serverUrl.isBlank()) {
            _message.value = "Configure o servidor primeiro"
            return
        }
        c.serverSync.syncNow()
        _message.value = "Sincronizando com o servidor..."
    }

    fun disconnectServer() = launchSafe {
        c.serverSync.disconnect()
        _message.value = "TV desconectada do servidor. As mídias locais foram mantidas."
    }

    fun changePin(newPin: String) = launchSafe {
        val error = c.adminSecurity.changePin(newPin.trim())
        _message.value = error ?: "PIN alterado com sucesso"
        if (error == null) AppLogger.i("PIN administrativo alterado")
    }

    @OptIn(UnstableApi::class)
    fun refreshStatus() = launchSafe {
        val s = withContext(Dispatchers.IO) {
            val internal = DeviceInfo.internalStorage(appContext)
            val external = DeviceInfo.externalStorage(appContext)
            DeviceStatus(
                appVersion = DeviceInfo.appVersion(),
                model = DeviceInfo.model(),
                windowResolution = DeviceInfo.windowResolution(appContext),
                displayResolution = DeviceInfo.displayResolution(appContext),
                internalStorage = internal?.let { "${Formatters.bytes(it.freeBytes)} livres de ${Formatters.bytes(it.totalBytes)}" } ?: "—",
                externalStorage = external?.let { "${Formatters.bytes(it.freeBytes)} livres de ${Formatters.bytes(it.totalBytes)}" } ?: "indisponível",
                videoCache = runCatching { Formatters.bytes(c.videoCache.cacheSpace) }.getOrDefault("—"),
                importFolder = c.mediaFileStore.importDir?.absolutePath ?: "indisponível",
                overlayPermission = AutoStart.canStartFromBackground(appContext),
                launcherMode = AutoStart.isLauncherModeEnabled(appContext),
                usingDefaultPin = c.settingsRepository.getPinHash() == null,
            )
        }
        _status.value = s
    }

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Erro no painel administrativo", e)
                _message.value = "Erro: ${e.message}"
            }
        }
    }
}
