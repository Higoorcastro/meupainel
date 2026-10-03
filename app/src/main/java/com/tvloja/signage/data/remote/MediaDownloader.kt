package com.tvloja.signage.data.remote

import com.tvloja.signage.data.local.files.MediaFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Downloads HTTP em streaming direto para o disco (nunca carrega o arquivo inteiro na memória). */
class MediaDownloader(
    private val client: OkHttpClient,
    private val fileStore: MediaFileStore,
) {
    suspend fun download(url: String, suggestedName: String, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} ao baixar $url")
                val body = response.body ?: throw IOException("Resposta vazia de $url")
                val length = body.contentLength()
                val dir = fileStore.requireTargetDir(length.coerceAtLeast(0))
                val name = suggestedName.ifBlank { url.substringAfterLast('/').substringBefore('?') }
                body.byteStream().use { input -> fileStore.writeAtomically(dir, name, input, length, onProgress) }
            }
        }

    suspend fun fetchText(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ao consultar $url")
            (response.body ?: throw IOException("Resposta vazia de $url")).string()
        }
    }

    companion object {
        const val USER_AGENT = "TvLojaSignage/1.0 (Android TV)"
    }
}
