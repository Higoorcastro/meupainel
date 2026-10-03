package com.tvloja.signage.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.tvloja.signage.data.remote.MediaDownloader

/**
 * Cria MediaSources para o ExoPlayer:
 * - arquivos locais (file://, content://): leitura direta, sem cache (seria duplicar o arquivo);
 * - URLs http(s): streaming progressivo através de um cache em disco LRU ([SimpleCache]),
 *   então um vídeo remoto em loop é baixado apenas uma vez.
 */
@OptIn(UnstableApi::class)
class VideoSourceFactory(context: Context, cache: SimpleCache) {

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(MediaDownloader.USER_AGENT)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(true)

    private val cachedFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context.applicationContext, httpFactory))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val remoteSources = DefaultMediaSourceFactory(cachedFactory)
    private val localSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context.applicationContext))

    fun create(item: MediaItem): MediaSource {
        val scheme = item.localConfiguration?.uri?.scheme
        return if (scheme == "http" || scheme == "https") remoteSources.createMediaSource(item)
        else localSources.createMediaSource(item)
    }
}
