package com.tvloja.signage

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.tvloja.signage.boot.AutoStart
import com.tvloja.signage.di.AppContainer
import com.tvloja.signage.util.AppLogger
import com.tvloja.signage.util.CrashHandler
import com.tvloja.signage.util.DeviceInfo
import okio.Path.Companion.toOkioPath

class SignageApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        CrashHandler.install(this)
        container = AppContainer(this)
        container.start()
        registerScreenOnReceiver()
        AppLogger.i("App iniciado — versão ${DeviceInfo.appVersion()} — ${DeviceInfo.model()}")
    }

    /**
     * Carregador de imagens global (Coil).
     * - Decodifica no tamanho da tela (nunca maior que o necessário, nunca reduz abaixo da resolução da janela);
     * - Cache de memória limitado a 20% do heap do app e cache em disco de 256 MB para imagens remotas.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttpClient })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()

    /**
     * Quando a TV volta do standby, o processo costuma continuar vivo, mas a TV mostra o launcher.
     * Se o início automático estiver ligado e houver permissão, trazemos o player de volta à frente.
     */
    private fun registerScreenOnReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_ON) return
                if (!container.settings.value.autoStartOnBoot) return
                AppLogger.i("Tela ligada (retorno do standby)")
                Handler(Looper.getMainLooper()).postDelayed({ AutoStart.launchPlayerIfAllowed(context) }, 3_000)
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
