package com.tvloja.signage.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.tvloja.signage.domain.model.ImageScaleMode
import com.tvloja.signage.domain.model.MediaItem
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.domain.model.SignageSettings
import com.tvloja.signage.domain.model.TransitionType
import com.tvloja.signage.player.PlaybackState
import com.tvloja.signage.player.PlaybackStatus
import com.tvloja.signage.security.AdminConfig
import com.tvloja.signage.sync.ServerState
import com.tvloja.signage.sync.ServerStatus
import com.tvloja.signage.ui.components.ConfirmDialog
import com.tvloja.signage.ui.components.InfoLine
import com.tvloja.signage.ui.components.LABEL_WIDTH
import com.tvloja.signage.ui.components.OptionRow
import com.tvloja.signage.ui.components.SectionTitle
import com.tvloja.signage.ui.components.StepperRow
import com.tvloja.signage.ui.components.TextInputDialog
import com.tvloja.signage.ui.components.TvButton
import com.tvloja.signage.ui.theme.SignageColors
import com.tvloja.signage.util.AppLogger
import com.tvloja.signage.util.Formatters
import kotlinx.coroutines.delay

private enum class AdminTab(val label: String) {
    MEDIA("Mídia"),
    SETTINGS("Configurações"),
    STATUS("Status"),
    LOGS("Logs"),
}

private sealed interface AdminDialog {
    data class Rename(val item: MediaItem) : AdminDialog
    data class Delete(val item: MediaItem) : AdminDialog
    data object AddUrl : AdminDialog
    data object ServerUrl : AdminDialog
    data object Disconnect : AdminDialog
    data object ChangePin : AdminDialog
}

/**
 * Painel administrativo, 100% navegável por D-pad / OK / VOLTAR.
 *
 * As alterações são salvas imediatamente no banco (não existe estado "não salvo" que possa se perder
 * se a TV for desligada). O botão "Salvar e reproduzir" volta para a reprodução já com a playlist atualizada.
 */
@Composable
fun AdminScreen(
    viewModel: AdminViewModel,
    onOpenPicker: () -> Unit,
    onBackToPlayer: () -> Unit,
    onExitApp: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(AdminTab.MEDIA) }
    var dialog by remember { mutableStateOf<AdminDialog?>(null) }
    val firstAction = remember { FocusRequester() }
    val selected = items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()

    BackHandler(onBack = onBackToPlayer)

    LaunchedEffect(message) {
        if (message != null) {
            delay(5_000)
            viewModel.showMessage(null)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SignageColors.Background)
            // Margem de segurança de overscan recomendada para TVs.
            .padding(horizontal = 40.dp, vertical = 20.dp)
    ) {
        Header(playback)
        Spacer(Modifier.height(8.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TvButton("▶ Salvar e reproduzir", { viewModel.play(); onBackToPlayer() }, Modifier.focusRequester(firstAction))
            TvButton("❚❚ Pausar", { viewModel.pause() })
            TvButton("⟲ Testar do início", { viewModel.restart(); onBackToPlayer() })
            TvButton("＋ Arquivo", onOpenPicker)
            TvButton("＋ URL", { dialog = AdminDialog.AddUrl })
            TvButton("Sair do app", onExitApp)
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxSize()) {
            // ---------------------------------------------------- Playlist
            Column(
                Modifier
                    .width(290.dp)
                    .fillMaxHeight()
                    .background(SignageColors.Panel, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                SectionTitle("Playlist (${items.size} mídias • ${items.count { it.enabled }} ativas)")
                if (items.isEmpty()) {
                    Text(
                        "Nenhuma mídia cadastrada.\nUse \"＋ Arquivo\" ou \"＋ URL\" acima.",
                        color = SignageColors.TextMuted,
                        modifier = Modifier.padding(8.dp),
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(items, key = { it.id }) { item ->
                            PlaylistRow(
                                item = item,
                                isSelected = item.id == selected?.id,
                                isPlaying = playback.current?.id == item.id,
                                onFocus = { viewModel.select(item.id) },
                                onClick = {
                                    viewModel.select(item.id)
                                    tab = AdminTab.MEDIA
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // ---------------------------------------------------- Abas
            Column(Modifier.weight(1f).fillMaxHeight()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminTab.entries.forEach { t ->
                        TvButton(t.label, { tab = t }, selected = tab == t)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(SignageColors.Panel, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    when (tab) {
                        AdminTab.MEDIA -> MediaTab(selected, items.size, viewModel, onDialog = { dialog = it })
                        AdminTab.SETTINGS -> SettingsTab(settings, status, server, viewModel, onDialog = { dialog = it })
                        AdminTab.STATUS -> StatusTab(playback, items, settings, status, server)
                        AdminTab.LOGS -> LogsTab(logs)
                    }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SignageColors.Accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    )
                }
            }
        }
    }

    Dialogs(dialog, settings, viewModel, onClose = { dialog = null })

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { firstAction.requestFocus() }
    }
}

@Composable
private fun Header(playback: PlaybackState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("DIGITAL SIGNAGE", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.width(20.dp))
        val color = when (playback.status) {
            PlaybackStatus.PLAYING -> SignageColors.Ok
            PlaybackStatus.RECOVERING -> SignageColors.Error
            PlaybackStatus.EMPTY, PlaybackStatus.STOPPED, PlaybackStatus.PAUSED -> SignageColors.Warn
            else -> SignageColors.TextMuted
        }
        val current = playback.current?.let { " • ${it.name} (${playback.index + 1}/${playback.total})" }.orEmpty()
        Text("● ${playback.status.label}$current", color = color, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaylistRow(
    item: MediaItem,
    isSelected: Boolean,
    isPlaying: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    // Itens ativos usam a cor padrão do ListItem (que inverte para escuro quando focado).
    val muted = if (item.enabled) Color.Unspecified else SignageColors.TextMuted
    ListItem(
        selected = isSelected,
        onClick = onClick,
        modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() },
        leadingContent = {
            Text(
                if (item.type == MediaType.IMAGE) "IMG" else "VID",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (item.type == MediaType.IMAGE) SignageColors.Accent else SignageColors.Warn,
            )
        },
        headlineContent = {
            Text("${item.position + 1}. ${item.name}", color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val duration = if (item.type == MediaType.IMAGE) "${item.durationMs / 1000}s" else "vídeo ${Formatters.duration(item.mediaDurationMs)}"
            val state = if (item.enabled) "ativo" else "INATIVO"
            val stream = if (item.isStreaming) " • streaming" else ""
            Text("$duration • $state$stream", fontSize = 12.sp)
        },
        trailingContent = { if (isPlaying) Text("▶", color = SignageColors.Ok) },
    )
}

@Composable
private fun MediaTab(item: MediaItem?, total: Int, vm: AdminViewModel, onDialog: (AdminDialog) -> Unit) {
    if (item == null) {
        Text("Selecione uma mídia na lista à esquerda.", color = SignageColors.TextMuted)
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(item.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TvButton(if (item.enabled) "Desativar" else "Ativar", { vm.toggleEnabled(item) })
            // Sempre habilitados: desabilitar o botão focado faria o foco "pular" para outro lugar.
            TvButton("▲ Subir", { vm.move(item, -1) })
            TvButton("▼ Descer", { vm.move(item, +1) })
            TvButton("Renomear", { onDialog(AdminDialog.Rename(item)) })
            TvButton("Excluir", { onDialog(AdminDialog.Delete(item)) })
        }

        if (item.type == MediaType.IMAGE) {
            SectionTitle("Duração da imagem")
            StepperRow(
                label = "Exibir por",
                value = "${item.durationMs / 1000} s",
                minus = listOf("−10" to { vm.changeDuration(item, -10) }, "−1" to { vm.changeDuration(item, -1) }),
                plus = listOf("+1" to { vm.changeDuration(item, 1) }, "+10" to { vm.changeDuration(item, 10) }),
            )
        }

        SectionTitle("Informações")
        val problem = vm.availabilityProblem(item)
        InfoLine("Disponibilidade", problem ?: "OK", if (problem == null) SignageColors.Ok else SignageColors.Error)
        InfoLine("Tipo", if (item.type == MediaType.IMAGE) "Imagem" else "Vídeo")
        InfoLine("Status", if (item.enabled) "Ativo" else "Inativo", if (item.enabled) SignageColors.Ok else SignageColors.Warn)
        InfoLine("Posição", "${item.position + 1} de $total")
        if (item.type == MediaType.VIDEO) InfoLine("Duração do vídeo", Formatters.duration(item.mediaDurationMs))
        InfoLine("Resolução", if (item.width != null && item.height != null) "${item.width} x ${item.height}" else "—")
        InfoLine("Formato", item.mimeType ?: "—")
        InfoLine("Tamanho", Formatters.bytes(item.sizeBytes))
        InfoLine("Exibições (sessão)", vm.playCount(item.id).toString())
        InfoLine("Reproduzido de", if (item.isStreaming) "Internet (streaming)" else "Armazenamento local")
        InfoLine("Arquivo", item.uri.takeLast(70))
        if (item.sourceUri != null && item.sourceUri != item.uri) InfoLine("Origem", item.sourceUri.takeLast(70))
        if (item.remoteId != null) InfoLine("ID remoto", item.remoteId)
        InfoLine("Cadastrado em", Formatters.dateTime(item.createdAt))
        InfoLine("Atualizado em", Formatters.dateTime(item.updatedAt))
    }
}

@Composable
private fun SettingsTab(
    settings: SignageSettings,
    status: DeviceStatus,
    server: ServerState,
    vm: AdminViewModel,
    onDialog: (AdminDialog) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("As alterações são salvas automaticamente.", color = SignageColors.TextMuted, fontSize = 14.sp)

        SectionTitle("Servidor central (painel web)")
        ServerSection(server, vm, onDialog)

        SectionTitle("Imagens")
        OptionRow(
            label = "Ajuste na tela",
            options = listOf("Preencher tela" to ImageScaleMode.CENTER_CROP, "Imagem inteira" to ImageScaleMode.FIT_CENTER),
            selected = settings.imageScaleMode,
            onSelect = vm::setScaleMode,
        )
        Text(
            "Preencher = CENTER_CROP (sem bordas, pode cortar) • Imagem inteira = FIT_CENTER (pode ter faixas pretas)",
            color = SignageColors.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = LABEL_WIDTH + 8.dp),
        )
        StepperRow(
            label = "Duração padrão",
            value = "${settings.defaultImageDurationSec} s",
            minus = listOf("−5" to { vm.changeDefaultDuration(-5) }, "−1" to { vm.changeDefaultDuration(-1) }),
            plus = listOf("+1" to { vm.changeDefaultDuration(1) }, "+5" to { vm.changeDefaultDuration(5) }),
        )
        Row(Modifier.padding(start = LABEL_WIDTH + 8.dp, top = 4.dp)) {
            TvButton("Aplicar a todas as imagens", { vm.applyDefaultDurationToAll() })
        }

        SectionTitle("Transição")
        OptionRow(
            label = "Efeito",
            options = listOf("Fade" to TransitionType.FADE, "Nenhuma" to TransitionType.NONE),
            selected = settings.transition,
            onSelect = vm::setTransition,
        )
        if (settings.transition == TransitionType.FADE) {
            StepperRow(
                label = "Duração do fade",
                value = "${settings.transitionDurationMs} ms",
                minus = listOf("−500" to { vm.changeTransitionMs(-500) }, "−100" to { vm.changeTransitionMs(-100) }),
                plus = listOf("+100" to { vm.changeTransitionMs(100) }, "+500" to { vm.changeTransitionMs(500) }),
            )
        }

        SectionTitle("Vídeos")
        OptionRow("Som", listOf("Ligado" to false, "Mudo" to true), settings.videoMuted, vm::setMuted)

        SectionTitle("Inicialização automática")
        OptionRow("Abrir ao ligar a TV", listOf("Sim" to true, "Não" to false), settings.autoStartOnBoot, vm::setAutoStart)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permissão de sobreposição", color = SignageColors.TextMuted, fontSize = 14.sp, modifier = Modifier.width(LABEL_WIDTH))
            Text(
                if (status.overlayPermission) "Concedida" else "Não concedida",
                color = if (status.overlayPermission) SignageColors.Ok else SignageColors.Warn,
                modifier = Modifier.width(120.dp),
            )
            if (!status.overlayPermission) TvButton("Abrir configurações", vm::openOverlaySettings)
        }
        OptionRow("Modo launcher", listOf("Ativado" to true, "Desativado" to false), status.launcherMode, vm::setLauncherMode)
        if (status.launcherMode) {
            Row(Modifier.padding(start = LABEL_WIDTH + 8.dp, top = 4.dp)) {
                TvButton("Escolher tela inicial padrão", vm::openHomeSettings)
            }
        }

        SectionTitle("Segurança")
        if (status.usingDefaultPin) {
            Text(
                "⚠ O PIN padrão de desenvolvimento está em uso. Altere-o antes de deixar a TV na loja.",
                color = SignageColors.Warn,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        TvButton("Alterar PIN", { onDialog(AdminDialog.ChangePin) })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusTab(playback: PlaybackState, items: List<MediaItem>, settings: SignageSettings, status: DeviceStatus, server: ServerState) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle("Reprodução")
        InfoLine("Status", playback.status.label)
        InfoLine("Mídia atual", playback.current?.name ?: "—")
        InfoLine("Posição na playlist", if (playback.index >= 0) "${playback.index + 1} de ${playback.total}" else "—")
        InfoLine("Início", Formatters.time(playback.startedAtMs))
        InfoLine("Término previsto", Formatters.time(playback.endsAtMs))
        InfoLine("Próxima", playback.next?.name ?: "—")
        InfoLine("Anterior", playback.previous?.name ?: "—")
        InfoLine("Último erro", playback.lastError ?: "nenhum", if (playback.lastError != null) SignageColors.Warn else Color.White)
        InfoLine("Mídias cadastradas", "${items.size} (${items.count { it.enabled }} ativas)")

        SectionTitle("Servidor central")
        InfoLine("Status", server.status.label, serverColor(server.status))
        InfoLine("Endereço", server.serverUrl.ifBlank { "—" })
        InfoLine("Última sincronização", Formatters.time(server.lastSyncAt))
        InfoLine("Mídias do servidor", "${items.count { it.remoteId != null }} (${items.count { it.remoteId != null && it.isStreaming }} baixando)")

        SectionTitle("Armazenamento")
        InfoLine("Interno", status.internalStorage)
        InfoLine("Externo (app)", status.externalStorage)
        InfoLine("Cache de vídeo (streaming)", status.videoCache)
        InfoLine("Pasta de importação", status.importFolder)

        SectionTitle("Aparelho")
        InfoLine("Versão do app", status.appVersion)
        InfoLine("Modelo", status.model)
        InfoLine("Resolução da interface", status.windowResolution)
        InfoLine("Resolução da tela", status.displayResolution)
        InfoLine("ID desta TV", settings.deviceId.ifBlank { "—" })
        InfoLine("Início automático", if (settings.autoStartOnBoot) "ligado" else "desligado")
        InfoLine("Acesso ao painel", "OK ${AdminConfig.OK_PRESS_COUNT}x ou ↑↑↓↓←→ durante a reprodução")
        // Item focável no final para permitir rolar até aqui com o D-pad.
        FocusableSpacer()
    }
}

@Composable
private fun FocusableSpacer() {
    var focused by remember { mutableStateOf(false) }
    Text(
        "— fim —",
        color = if (focused) Color.White else SignageColors.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier
            .padding(top = 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable(),
    )
}

@Composable
private fun LogsTab(logs: List<AppLogger.Entry>) {
    if (logs.isEmpty()) {
        Text("Sem registros.", color = SignageColors.TextMuted)
        return
    }
    val reversed = remember(logs) { logs.asReversed() }
    LazyColumn {
        items(reversed) { entry -> LogRow(entry) }
    }
}

@Composable
private fun LogRow(entry: AppLogger.Entry) {
    var focused by remember { mutableStateOf(false) }
    val color = when (entry.level) {
        AppLogger.Level.ERROR -> SignageColors.Error
        AppLogger.Level.WARN -> SignageColors.Warn
        AppLogger.Level.INFO -> Color.White
        AppLogger.Level.DEBUG -> SignageColors.TextMuted
    }
    Text(
        text = "${Formatters.time(entry.timeMs)}  ${entry.message}",
        color = color,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) SignageColors.PanelAlt else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun Dialogs(dialog: AdminDialog?, settings: SignageSettings, vm: AdminViewModel, onClose: () -> Unit) {
    when (dialog) {
        null -> Unit
        is AdminDialog.Rename -> TextInputDialog(
            title = "Renomear mídia",
            initialValue = dialog.item.name,
            hint = "Nome exibido no painel (não aparece na TV).",
            onConfirm = { vm.rename(dialog.item, it); onClose() },
            onDismiss = onClose,
        )
        is AdminDialog.Delete -> ConfirmDialog(
            title = "Excluir mídia?",
            message = "\"${dialog.item.name}\" será removida da playlist e o arquivo copiado para a TV será apagado.",
            confirmText = "Excluir",
            onConfirm = { vm.delete(dialog.item); onClose() },
            onDismiss = onClose,
        )
        AdminDialog.AddUrl -> {
            var offline by remember { mutableStateOf(true) }
            TextInputDialog(
                title = "Adicionar mídia por URL",
                initialValue = "https://",
                hint = "Endereço direto de uma imagem (.jpg, .png, .webp) ou vídeo (.mp4).",
                keyboardType = KeyboardType.Uri,
                onConfirm = { vm.addUrl(it, offline); onClose() },
                onDismiss = onClose,
                extraContent = {
                    OptionRow(
                        label = "Baixar para uso offline",
                        options = listOf("Sim" to true, "Não (streaming)" to false),
                        selected = offline,
                        onSelect = { offline = it },
                    )
                },
            )
        }
        AdminDialog.ServerUrl -> TextInputDialog(
            title = "Endereço do servidor",
            initialValue = settings.serverUrl.ifBlank { "https://" },
            hint = "Ex.: https://signage.seudominio.com — depois digite no painel web o código que aparecerá aqui.",
            keyboardType = KeyboardType.Uri,
            onConfirm = { vm.setServerUrl(it); onClose() },
            onDismiss = onClose,
        )
        AdminDialog.Disconnect -> ConfirmDialog(
            title = "Desconectar do servidor?",
            message = "A TV deixa de receber conteúdo do painel e as mídias baixadas do servidor são apagadas. Mídias adicionadas pelo pendrive continuam.",
            confirmText = "Desconectar",
            onConfirm = { vm.disconnectServer(); onClose() },
            onDismiss = onClose,
        )
        AdminDialog.ChangePin -> TextInputDialog(
            title = "Alterar PIN",
            initialValue = "",
            hint = "Novo PIN com ${AdminConfig.MIN_PIN_LENGTH} a ${AdminConfig.MAX_PIN_LENGTH} dígitos.",
            keyboardType = KeyboardType.NumberPassword,
            onConfirm = { vm.changePin(it); onClose() },
            onDismiss = onClose,
        )
    }
}

private fun serverColor(status: ServerStatus): Color = when (status) {
    ServerStatus.CONNECTED -> SignageColors.Ok
    ServerStatus.PENDING, ServerStatus.CONNECTING -> SignageColors.Warn
    ServerStatus.ERROR, ServerStatus.REJECTED -> SignageColors.Error
    ServerStatus.NOT_CONFIGURED -> SignageColors.TextMuted
}

@Composable
private fun ServerSection(server: ServerState, vm: AdminViewModel, onDialog: (AdminDialog) -> Unit) {
    InfoLine("Status", server.status.label, serverColor(server.status))
    if (server.serverUrl.isNotBlank()) InfoLine("Endereço", server.serverUrl)
    when (server.status) {
        ServerStatus.PENDING -> server.pairingCode?.let { code ->
            Column(
                Modifier
                    .padding(vertical = 8.dp)
                    .background(SignageColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("Código de pareamento", color = SignageColors.TextMuted, fontSize = 13.sp)
                Text(formatPairingCode(code), fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("No painel web: TVs › Adicionar TV › digite este código.", color = SignageColors.TextMuted, fontSize = 13.sp)
            }
        }
        ServerStatus.CONNECTED -> {
            InfoLine("Nome no painel", server.tvName ?: "—")
            InfoLine("Última sincronização", Formatters.time(server.lastSyncAt))
        }
        ServerStatus.ERROR, ServerStatus.REJECTED -> InfoLine("Detalhe", server.message ?: "—", SignageColors.Warn)
        else -> Unit
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        TvButton(if (server.serverUrl.isBlank()) "Conectar ao servidor" else "Alterar endereço", { onDialog(AdminDialog.ServerUrl) })
        if (server.serverUrl.isNotBlank()) {
            TvButton("Sincronizar agora", vm::syncNow)
            TvButton("Desconectar", { onDialog(AdminDialog.Disconnect) })
        }
    }
}

/** "123456" → "123 456" (mais fácil de ler na TV). */
fun formatPairingCode(code: String): String =
    if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code
