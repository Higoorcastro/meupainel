package com.tvloja.signage.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import com.tvloja.signage.BuildConfig
import java.io.File

/** Informações do aparelho exibidas na aba "Status" do painel administrativo. */
object DeviceInfo {

    data class StorageInfo(val freeBytes: Long, val totalBytes: Long)

    fun appVersion(): String = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

    fun model(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Resolução da janela do app (onde imagens são desenhadas). */
    fun windowResolution(context: Context): String {
        val dm = context.resources.displayMetrics
        return "${dm.widthPixels} x ${dm.heightPixels} px"
    }

    /**
     * Resolução física do painel/HDMI. Em muitas TVs 4K a interface roda em 1920x1080, mas os vídeos
     * são decodificados e exibidos em 3840x2160 pelo SurfaceView (camada de vídeo separada).
     */
    fun displayResolution(context: Context): String {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return "—"
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return "—"
        val mode = display.mode
        val max = display.supportedModes.maxByOrNull { it.physicalWidth * it.physicalHeight }
        val current = "${mode.physicalWidth} x ${mode.physicalHeight} @ ${mode.refreshRate.toInt()}Hz"
        return if (max != null && max.physicalWidth > mode.physicalWidth) {
            "$current (máx. ${max.physicalWidth} x ${max.physicalHeight})"
        } else current
    }

    fun storageOf(dir: File?): StorageInfo? {
        if (dir == null) return null
        return runCatching {
            val stat = StatFs(dir.path)
            StorageInfo(stat.availableBytes, stat.totalBytes)
        }.getOrNull()
    }

    fun internalStorage(context: Context): StorageInfo? = storageOf(context.filesDir)

    fun externalStorage(context: Context): StorageInfo? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
        return storageOf(context.getExternalFilesDir(null))
    }
}
