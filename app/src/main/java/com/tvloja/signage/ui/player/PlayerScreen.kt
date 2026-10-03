package com.tvloja.signage.ui.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import com.tvloja.signage.di.AppContainer
import com.tvloja.signage.domain.model.ImageScaleMode
import com.tvloja.signage.domain.model.MediaItem
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.player.PlaybackStatus
import com.tvloja.signage.player.VideoPlayer
import com.tvloja.signage.security.AdminConfig
import com.tvloja.signage.sync.ServerState
import com.tvloja.signage.sync.ServerStatus
import com.tvloja.signage.ui.admin.formatPairingCode
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.delay

/**
 * Tela de reprodução — nenhum menu visível; o público vê apenas o anúncio.
 *
 * Camadas (de baixo para cima):
 * 1. Vídeo (PlayerView/SurfaceView): o decodificador de hardware desenha direto na tela, em até 4K,
 *    mesmo quando a interface do Android roda em 1080p.
 * 2. Imagens (Coil) dentro de um AnimatedContent com fade configurável. Ao sair de uma imagem para
 *    um vídeo, a imagem "esmaece" revelando o vídeo; de vídeo para imagem, a imagem surge sobre o
 *    último quadro do vídeo.
 * 3. Mensagens de estado (playlist vazia, recuperação, pausa).
 */
@Composable
fun PlayerScreen(container: AppContainer) {
    val playlistPlayer = container.playlistPlayer
    val state by playlistPlayer.state.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val server by container.serverSync.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var videoPlayer by remember { mutableStateOf<VideoPlayer?>(null) }

    // Ignora VOLTAR durante a reprodução. Necessário além do bloqueio em MainActivity.dispatchKeyEvent:
    // com targetSdk 36 (predictive back), o sistema entrega VOLTAR direto ao OnBackPressedDispatcher,
    // sem passar pelo dispatchKeyEvent — sem isto, VOLTAR fecharia o app.
    BackHandler { AppLogger.d("VOLTAR ignorado durante a reprodução") }

    // ExoPlayer existe somente enquanto a tela está em primeiro plano (libera o decodificador ao sair).
    LifecycleStartEffect(Unit) {
        val vp = VideoPlayer(
            context = context.applicationContext,
            sourceFactory = container.videoSourceFactory,
            callbacks = object : VideoPlayer.Callbacks {
                override fun onReady(token: Long, durationMs: Long?) = playlistPlayer.onVideoReady(token, durationMs)
                override fun onCompleted(token: Long) = playlistPlayer.onMediaCompleted(token)
                override fun onError(token: Long, reason: String) = playlistPlayer.onMediaError(token, reason)
            },
        )
        videoPlayer = vp
        playlistPlayer.setHostActive(true)
        onStopOrDispose {
            playlistPlayer.setHostActive(false)
            videoPlayer = null
            vp.release()
        }
    }

    val current = state.current
    val isVideo = current?.type == MediaType.VIDEO
    val transitionMs = settings.effectiveTransitionMs

    // Carrega o vídeo do token atual, ou descarrega o player depois que a transição para imagem terminou.
    LaunchedEffect(videoPlayer, state.token, isVideo) {
        val vp = videoPlayer ?: return@LaunchedEffect
        if (isVideo) {
            vp.load(current, state.token, state.shouldPlay)
        } else {
            delay(transitionMs + 200L)
            vp.clear()
        }
    }
    LaunchedEffect(videoPlayer, state.shouldPlay, isVideo) {
        videoPlayer?.setPlaying(state.shouldPlay && isVideo)
    }
    LaunchedEffect(videoPlayer, settings.videoMuted) {
        videoPlayer?.setMuted(settings.videoMuted)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }

        videoPlayer?.let { VideoSurface(it.exoPlayer) }

        val imageItem = current?.takeIf { it.type == MediaType.IMAGE }
        val imageKey = imageItem?.let { ImageKey(state.token, it) }
        AnimatedContent(
            targetState = imageKey,
            transitionSpec = {
                if (transitionMs <= 0) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(tween(transitionMs)) togetherWith fadeOut(tween(transitionMs))
            },
            contentKey = { it?.token ?: -1L },
            label = "media-transition",
            modifier = Modifier.fillMaxSize(),
        ) { key ->
            if (key != null) {
                ImageRenderer(
                    item = key.item,
                    scaleMode = settings.imageScaleMode,
                    onError = { reason -> playlistPlayer.onMediaError(key.token, reason) },
                )
            } else {
                Box(Modifier.fillMaxSize()) // transparente: deixa o vídeo aparecer
            }
        }

        // Pré-carrega a próxima imagem no tamanho da tela (troca instantânea, sem "piscar").
        val nextImage = state.next?.takeIf { it.type == MediaType.IMAGE && it.id != current?.id }
        LaunchedEffect(nextImage?.uri, widthPx, heightPx, settings.imageScaleMode) {
            if (nextImage == null || widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
            val request = ImageRequest.Builder(context)
                .data(nextImage.uri)
                .size(widthPx, heightPx)
                .scale(if (settings.imageScaleMode == ImageScaleMode.CENTER_CROP) Scale.FILL else Scale.FIT)
                .build()
            SingletonImageLoader.get(context).enqueue(request)
        }

        StatusOverlay(state.status, hasMedia = current != null, server = server)
    }
}

private data class ImageKey(val token: Long, val item: MediaItem)

@Composable
private fun ImageRenderer(item: MediaItem, scaleMode: ImageScaleMode, onError: (String) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            contentScale = if (scaleMode == ImageScaleMode.CENTER_CROP) ContentScale.Crop else ContentScale.Fit,
            onError = { error -> onError("Falha ao carregar imagem: ${error.result.throwable.message}") },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: ExoPlayer) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                keepScreenOn = true
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                this.player = player
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        onRelease = { view -> view.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun StatusOverlay(status: PlaybackStatus, hasMedia: Boolean, server: ServerState) {
    when {
        status == PlaybackStatus.EMPTY && server.status == ServerStatus.PENDING && server.pairingCode != null -> CenterMessage(
            title = "Conecte esta TV ao painel",
            body = "Código de pareamento:  ${formatPairingCode(server.pairingCode)}",
            hint = "Acesse ${server.serverUrl} › TVs › Adicionar TV e digite o código acima.",
        )
        status == PlaybackStatus.EMPTY && server.status == ServerStatus.CONNECTED -> CenterMessage(
            title = "Aguardando conteúdo do painel.",
            body = "Escolha uma playlist com mídias para \"${server.tvName.orEmpty()}\" em ${server.serverUrl}",
            hint = "A TV atualiza automaticamente em até 1 minuto.",
        )
        status == PlaybackStatus.EMPTY -> CenterMessage(
            title = "Nenhuma mídia cadastrada.",
            body = "Acesse a Administração para adicionar imagens ou vídeos.",
            hint = "Para abrir a Administração, pressione OK ${AdminConfig.OK_PRESS_COUNT} vezes no controle remoto.",
        )
        status == PlaybackStatus.RECOVERING -> CenterMessage(
            title = "Não foi possível exibir o conteúdo.",
            body = "Nova tentativa automática em instantes.",
            hint = null,
        )
        status == PlaybackStatus.STOPPED -> CenterMessage(title = "Reprodução parada.", body = "", hint = null)
        status == PlaybackStatus.PAUSED && hasMedia -> Badge("❚❚  Pausado")
        else -> Unit
    }
}

@Composable
private fun CenterMessage(title: String, body: String, hint: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(body, color = Color(0xFFCCCCCC), fontSize = 22.sp, textAlign = TextAlign.Center)
        }
        if (hint != null) {
            Spacer(Modifier.height(40.dp))
            Text(hint, color = Color(0xFF888888), fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Badge(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopEnd) {
        Text(
            text,
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier
                .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
