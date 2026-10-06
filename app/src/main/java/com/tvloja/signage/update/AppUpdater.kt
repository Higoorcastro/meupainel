package com.tvloja.signage.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.tvloja.signage.BuildConfig
import com.tvloja.signage.data.remote.MediaDownloader
import com.tvloja.signage.domain.repository.AppUpdateOffer
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class UpdateStatus { IDLE, DOWNLOADING, READY, INSTALLING, ERROR }

data class UpdateState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val progress: Int? = null,
    val error: String? = null,
)

/**
 * Atualização remota do app, enviada pelo painel web.
 *
 * 1. O servidor anuncia uma versão mais nova → o APK é baixado em segundo plano (autenticado com o token da TV);
 * 2. integridade verificada: SHA-256 igual ao do servidor + mesmo pacote + versionCode esperado;
 * 3. quando o painel manda instalar (ou pelo botão na TV), usa o instalador oficial do Android
 *    (PackageInstaller). O Android pede confirmação na tela — basta apertar OK no controle.
 *
 * Pré-requisito (Android 8+): permitir "instalar apps desconhecidos" para o Digital Signage.
 */
class AppUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
) {
    companion object {
        const val ACTION_INSTALL_RESULT = "com.tvloja.signage.INSTALL_RESULT"
        private const val ERROR_RETRY_MS = 10 * 60_000L
    }

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val dir = File(context.cacheDir, "updates")
    private var offer: AppUpdateOffer? = null
    private var readyFile: File? = null
    private var downloadJob: Job? = null
    private var installRequested = false
    private var lastErrorAt = 0L

    /** Chamado a cada sincronização com o que o servidor oferece (null = nada novo). */
    fun onOffer(offer: AppUpdateOffer?, serverUrl: String, deviceId: String, token: String) {
        if (offer == null || offer.versionCode <= BuildConfig.VERSION_CODE) {
            if (_state.value.status != UpdateStatus.INSTALLING && _state.value.status != UpdateStatus.IDLE) {
                this.offer = null
                readyFile = null
                installRequested = false
                _state.value = UpdateState()
                cleanup(keep = null)
            }
            return
        }
        val same = this.offer?.sha256 == offer.sha256
        when {
            same && _state.value.status in setOf(UpdateStatus.DOWNLOADING, UpdateStatus.READY, UpdateStatus.INSTALLING) -> return
            same && _state.value.status == UpdateStatus.ERROR && System.currentTimeMillis() - lastErrorAt < ERROR_RETRY_MS -> return
        }
        this.offer = offer
        downloadJob?.cancel()
        downloadJob = scope.launch { download(offer, serverUrl, deviceId, token) }
    }

    /** Pedido de instalação (comando do painel ou botão na TV). Se ainda estiver baixando, instala ao terminar. */
    fun requestInstall() {
        installRequested = true
        when (_state.value.status) {
            UpdateStatus.READY -> install()
            UpdateStatus.DOWNLOADING -> AppLogger.i("Atualização: instala assim que o download terminar")
            UpdateStatus.INSTALLING -> Unit
            else -> AppLogger.w("Atualização solicitada, mas não há versão nova baixada")
        }
    }

    /** Android 8+: o usuário precisa permitir que este app instale atualizações. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        return intents.any {
            runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }
    }

    private suspend fun download(offer: AppUpdateOffer, serverUrl: String, deviceId: String, token: String) {
        val name = offer.versionName
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val target = File(dir, "signage-${offer.versionCode}.apk")
                if (target.exists() && sha256(target) == offer.sha256) {
                    AppLogger.i("Atualização $name já baixada")
                } else {
                    _state.value = UpdateState(UpdateStatus.DOWNLOADING, name, offer.versionCode, progress = 0)
                    AppLogger.i("Baixando atualização do app: $name")
                    val tmp = File(dir, "${target.name}.part")
                    val request = Request.Builder()
                        .url(serverUrl.trimEnd('/') + offer.downloadPath)
                        .header("User-Agent", MediaDownloader.USER_AGENT)
                        .header("X-Device-Id", deviceId)
                        .header("Authorization", "Bearer $token")
                        .build()
                    val digest = MessageDigest.getInstance("SHA-256")
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} ao baixar a atualização")
                        val body = response.body ?: throw IOException("Resposta vazia")
                        val total = body.contentLength().takeIf { it > 0 } ?: offer.sizeBytes
                        body.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buffer = ByteArray(128 * 1024)
                                var copied = 0L
                                var lastPct = -1
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    copied += read
                                    val pct = if (total > 0) ((copied * 100) / total).toInt().coerceIn(0, 100) else null
                                    if (pct != null && pct != lastPct) {
                                        lastPct = pct
                                        _state.value = _state.value.copy(progress = pct)
                                    }
                                }
                            }
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (hash != offer.sha256) {
                        tmp.delete()
                        throw IOException("Arquivo corrompido no download (SHA-256 diferente)")
                    }
                    if (!tmp.renameTo(target)) throw IOException("Falha ao salvar a atualização")
                }
                verifyPackage(target, offer)
                cleanup(keep = target)
                readyFile = target
            }
            _state.value = UpdateState(UpdateStatus.READY, name, offer.versionCode)
            AppLogger.i("Atualização $name pronta para instalar")
            if (installRequested) install()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastErrorAt = System.currentTimeMillis()
            AppLogger.e("Falha ao baixar a atualização $name", e)
            _state.value = UpdateState(UpdateStatus.ERROR, name, offer.versionCode, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun verifyPackage(file: File, offer: AppUpdateOffer) {
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw IOException("O arquivo baixado não é um APK válido")
        if (info.packageName != context.packageName) throw IOException("APK de outro app (${info.packageName})")
        val code = PackageInfoCompat.getLongVersionCode(info)
        if (code != offer.versionCode.toLong()) throw IOException("Versão do APK ($code) diferente da anunciada (${offer.versionCode})")
    }

    private fun install() {
        val file = readyFile ?: return
        val current = _state.value
        if (!canInstall()) {
            AppLogger.w("Atualização bloqueada: falta permitir 'instalar apps desconhecidos'")
            _state.value = current.copy(error = "Permita \"instalar apps desconhecidos\" para o Digital Signage nas configurações da TV")
            openInstallPermissionSettings()
            return
        }
        installRequested = false
        _state.value = current.copy(status = UpdateStatus.INSTALLING, error = null)
        AppLogger.i("Instalando atualização ${current.versionName}...")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { commitSession(file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onInstallResult(success = false, message = e.message)
            }
        }
    }

    private fun commitSession(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    /** Resultado final da instalação (sucesso normalmente nem chega: o processo é reiniciado). */
    fun onInstallResult(success: Boolean, message: String?) {
        if (success) {
            AppLogger.i("Atualização instalada")
            return
        }
        AppLogger.w("Atualização não instalada: ${message ?: "cancelada"}")
        val friendly = when {
            message == null -> "Instalação cancelada na TV"
            message.contains("INCOMPATIBLE", true) || message.contains("signature", true) ->
                "APK assinado com outra chave. Gere o APK no mesmo computador/keystore da versão instalada."
            message.contains("STORAGE", true) -> "Sem espaço na TV para instalar"
            else -> message
        }
        _state.value = _state.value.copy(
            status = if (readyFile?.exists() == true) UpdateStatus.READY else UpdateStatus.ERROR,
            error = friendly,
        )
    }

    private fun cleanup(keep: File?) {
        dir.listFiles()?.forEach { if (it != keep) it.delete() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
