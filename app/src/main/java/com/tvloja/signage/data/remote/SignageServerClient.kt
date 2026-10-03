package com.tvloja.signage.data.remote

import com.tvloja.signage.domain.model.ImageScaleMode
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.domain.model.RemoteMediaSpec
import com.tvloja.signage.domain.model.TransitionType
import com.tvloja.signage.domain.repository.DeviceReport
import com.tvloja.signage.domain.repository.RemoteSettings
import com.tvloja.signage.domain.repository.ServerSyncResponse
import com.tvloja.signage.domain.repository.SignageServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Cliente HTTP do servidor central (`POST /api/device/sync`). */
class SignageServerClient(private val client: OkHttpClient) : SignageServerApi {

    override suspend fun sync(
        serverUrl: String,
        deviceId: String,
        token: String,
        report: DeviceReport,
    ): ServerSyncResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/device/sync")
            .header("User-Agent", MediaDownloader.USER_AGENT)
            .header("X-Device-Id", deviceId)
            .header("Authorization", "Bearer $token")
            .post(reportJson(report).toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            when {
                response.code == 403 -> ServerSyncResponse.Rejected(json?.optString("error")?.ifBlank { null } ?: "Acesso negado pelo servidor")
                !response.isSuccessful -> throw IOException("HTTP ${response.code}: ${json?.optString("error").orEmpty()}")
                json == null -> throw IOException("Resposta inválida do servidor (não é um servidor de Signage?)")
                else -> parse(json)
            }
        }
    }

    private fun reportJson(r: DeviceReport) = JSONObject().apply {
        put("model", r.model)
        put("appVersion", r.appVersion)
        put("status", JSONObject().apply {
            put("playback", JSONObject().apply {
                put("status", r.playbackStatus)
                put("current", r.currentMedia ?: JSONObject.NULL)
                put("index", r.index)
                put("total", r.total)
                put("lastError", r.lastError ?: JSONObject.NULL)
            })
            put("storageFreeBytes", r.storageFreeBytes ?: JSONObject.NULL)
            put("storageTotalBytes", r.storageTotalBytes ?: JSONObject.NULL)
            put("display", r.display)
            put("downloadsPending", r.downloadsPending)
            put("localItems", r.localItems)
            put("uptimeSec", r.uptimeSec)
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun parse(json: JSONObject): ServerSyncResponse = when (json.optString("status")) {
            "pending" -> ServerSyncResponse.Pending(json.optString("pairingCode"))
            "approved" -> {
                val array = json.optJSONArray("playlist")
                val specs = buildList {
                    for (i in 0 until (array?.length() ?: 0)) {
                        val o = array!!.optJSONObject(i) ?: continue
                        val url = o.optString("url")
                        if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                        add(
                            RemoteMediaSpec(
                                remoteId = o.optString("id").ifBlank { url },
                                name = o.optString("name").ifBlank { "Mídia" },
                                type = if (o.optString("type") == "VIDEO") MediaType.VIDEO else MediaType.IMAGE,
                                url = url,
                                durationMs = o.optInt("durationSec", 10).coerceIn(1, 3600) * 1000L,
                                enabled = o.optBoolean("enabled", true),
                            )
                        )
                    }
                }
                val s = json.optJSONObject("settings")
                ServerSyncResponse.Approved(
                    tvName = json.optString("name"),
                    playlist = specs.distinctBy { it.remoteId },
                    settings = RemoteSettings(
                        imageScaleMode = s?.optString("imageScaleMode")?.let { v -> ImageScaleMode.entries.firstOrNull { it.name == v } },
                        transition = s?.optString("transition")?.let { v -> TransitionType.entries.firstOrNull { it.name == v } },
                        transitionDurationMs = s?.takeIf { it.has("transitionDurationMs") }?.optInt("transitionDurationMs"),
                        videoMuted = s?.takeIf { it.has("videoMuted") }?.optBoolean("videoMuted"),
                    ),
                    command = json.optString("command").takeIf { it.isNotBlank() && it != "null" },
                )
            }
            else -> ServerSyncResponse.Rejected(json.optString("error").ifBlank { "Resposta desconhecida do servidor" })
        }
    }
}
