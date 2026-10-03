package com.tvloja.signage.di

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.tvloja.signage.data.local.db.SignageDatabase
import com.tvloja.signage.data.local.files.LocalMediaScanner
import com.tvloja.signage.data.local.files.MediaFileStore
import com.tvloja.signage.data.local.files.MediaInspector
import com.tvloja.signage.data.local.settings.DataStoreSettingsRepository
import com.tvloja.signage.data.remote.MediaDownloader
import com.tvloja.signage.data.remote.SignageServerClient
import com.tvloja.signage.data.repository.LocalPlaylistRepository
import com.tvloja.signage.domain.model.SignageSettings
import com.tvloja.signage.domain.repository.PlaylistRepository
import com.tvloja.signage.domain.repository.SettingsRepository
import com.tvloja.signage.domain.repository.SignageServerApi
import com.tvloja.signage.sync.ServerSyncManager
import com.tvloja.signage.domain.usecase.AddRemoteMediaUseCase
import com.tvloja.signage.domain.usecase.DeleteMediaUseCase
import com.tvloja.signage.domain.usecase.ImportLocalMediaUseCase
import com.tvloja.signage.domain.usecase.ObservePlayablePlaylistUseCase
import com.tvloja.signage.player.LoggingPlaybackReporter
import com.tvloja.signage.player.MediaAvailabilityChecker
import com.tvloja.signage.player.PlaylistPlayer
import com.tvloja.signage.player.VideoSourceFactory
import com.tvloja.signage.security.AdminSecurity
import com.tvloja.signage.util.AppLogger
import com.tvloja.signage.work.WorkScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Injeção de dependências manual (sem Hilt/Dagger): simples, sem geração de código e fácil de ler.
 * Para trocar a fonte da playlist por uma remota, basta alterar [playlistRepository] aqui.
 */
@OptIn(UnstableApi::class) // SimpleCache/StandaloneDatabaseProvider do Media3 são marcados como "unstable".
class AppContainer(val app: Application) {

    /** Escopo de vida da aplicação. SupervisorJob: a falha de uma corrotina não derruba as demais. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> AppLogger.e("Erro não tratado em corrotina", e) }
    )

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val database by lazy { SignageDatabase.create(app) }

    val mediaFileStore = MediaFileStore(app)
    val mediaInspector = MediaInspector(app)
    val mediaScanner = LocalMediaScanner(app, mediaFileStore)
    val workScheduler = WorkScheduler(app)

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(app)
    val playlistRepository: PlaylistRepository by lazy { LocalPlaylistRepository(database) }

    val mediaDownloader by lazy { MediaDownloader(okHttpClient, mediaFileStore) }
    val serverApi: SignageServerApi by lazy { SignageServerClient(okHttpClient) }

    /** Configurações sempre disponíveis de forma síncrona (valor padrão até o DataStore carregar). */
    val settings: StateFlow<SignageSettings> =
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, SignageSettings())

    val importLocalMedia by lazy { ImportLocalMediaUseCase(mediaFileStore, mediaInspector, playlistRepository, settingsRepository) }
    val addRemoteMedia by lazy { AddRemoteMediaUseCase(playlistRepository, settingsRepository, workScheduler) }
    val deleteMedia by lazy { DeleteMediaUseCase(playlistRepository, mediaFileStore, workScheduler) }
    private val observePlayablePlaylist by lazy { ObservePlayablePlaylistUseCase(playlistRepository) }

    val adminSecurity by lazy { AdminSecurity(settingsRepository) }

    val playbackReporter = LoggingPlaybackReporter()
    val availabilityChecker = MediaAvailabilityChecker(app)
    val playlistPlayer = PlaylistPlayer(appScope, availabilityChecker, playbackReporter)

    /** Sincronização com o servidor central (painel web). */
    val serverSync by lazy {
        ServerSyncManager(app, appScope, serverApi, settingsRepository, playlistRepository, mediaFileStore, workScheduler, playlistPlayer)
    }

    /** Cache em disco para vídeos remotos (instância única por diretório — exigência do SimpleCache). */
    val videoCache: SimpleCache by lazy {
        val dir = File(app.cacheDir, "video_cache")
        val free = mediaFileStore.freeBytes(app.cacheDir)
        val maxBytes = (free / 10).coerceIn(100L * 1024 * 1024, 1024L * 1024 * 1024)
        SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes), StandaloneDatabaseProvider(app))
    }

    val videoSourceFactory by lazy { VideoSourceFactory(app, videoCache) }

    fun start() {
        playlistPlayer.attach(observePlayablePlaylist())

        appScope.launch {
            val id = settingsRepository.ensureDeviceId()
            AppLogger.i("ID desta TV: $id")
        }

        // Cria a pasta de importação (destino de "adb push") e limpa temporários/órfãos, em background.
        appScope.launch(Dispatchers.IO) {
            mediaFileStore.importDir
            runCatching { mediaFileStore.cleanup(playlistRepository.getAll().map { it.uri }) }
                .onFailure { AppLogger.w("Falha na limpeza de arquivos", it) }
        }

        // Servidor central: sincronização contínua enquanto o app roda + WorkManager como reserva.
        serverSync.start(settingsRepository.settings.map { it.serverUrl })
        appScope.launch {
            settings.drop(1).map { it.serverUrl.isNotBlank() }.distinctUntilChanged().collect { enabled ->
                workScheduler.configurePeriodicSync(enabled)
            }
        }
    }
}
