package com.tvloja.signage.ui.picker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.ui.components.OptionRow
import com.tvloja.signage.ui.components.TvButton
import com.tvloja.signage.ui.theme.SignageColors
import com.tvloja.signage.util.Formatters
import kotlinx.coroutines.delay

private val mediaPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun Context.hasMediaPermission(): Boolean =
    mediaPermissions.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Seletor de mídias próprio do app (não depende do seletor de arquivos do sistema, ausente em muitas TVs).
 * Lista arquivos do pendrive USB, do armazenamento da TV e da pasta de importação.
 */
@Composable
fun MediaPickerScreen(viewModel: PickerViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val refreshFocus = remember { FocusRequester() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refresh(context.hasMediaPermission())
    }

    BackHandler {
        if (state.importingName != null) viewModel.cancelImport() else onBack()
    }

    LaunchedEffect(Unit) {
        if (context.hasMediaPermission()) viewModel.refresh(true)
        else runCatching { permissionLauncher.launch(mediaPermissions) }
            .onFailure { viewModel.refresh(false) }
        delay(150)
        runCatching { refreshFocus.requestFocus() }
    }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(6_000)
            viewModel.clearMessage()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SignageColors.Background)
            .padding(horizontal = 40.dp, vertical = 20.dp)
    ) {
        Text("ADICIONAR MÍDIA", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Selecione um arquivo e pressione OK para adicioná-lo ao final da playlist. VOLTAR retorna ao painel.",
            color = SignageColors.TextMuted,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TvButton("⟳ Atualizar lista", { viewModel.refresh(context.hasMediaPermission()) }, Modifier.focusRequester(refreshFocus))
            if (!state.hasPermission) {
                TvButton("Permitir acesso a fotos e vídeos", { runCatching { permissionLauncher.launch(mediaPermissions) } })
            }
            TvButton("← Voltar ao painel", onBack)
        }
        OptionRow(
            label = "Copiar para a TV",
            options = listOf("Sim (recomendado)" to true, "Não, usar do original" to false),
            selected = state.copyToAppStorage,
            onSelect = viewModel::setCopy,
        )
        Text(
            if (state.copyToAppStorage) "O arquivo é copiado para a memória da TV e continua funcionando mesmo sem o pendrive."
            else "Atenção: se o pendrive for removido, a mídia será pulada automaticamente.",
            color = SignageColors.TextMuted,
            fontSize = 13.sp,
        )

        state.importingName?.let { name ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Copiando \"$name\"... ${(state.progress * 100).toInt()}%  (VOLTAR cancela)",
                color = SignageColors.Accent,
                modifier = Modifier.fillMaxWidth().background(SignageColors.Panel, RoundedCornerShape(8.dp)).padding(12.dp),
            )
        }
        state.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = Color.White,
                modifier = Modifier.fillMaxWidth().background(SignageColors.Accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .fillMaxSize()
                .background(SignageColors.Panel, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            when {
                state.loading -> Text("Procurando arquivos...", color = SignageColors.TextMuted)
                state.files.isEmpty() -> Column {
                    Text("Nenhuma imagem ou vídeo encontrado.", fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Conecte um pendrive com arquivos .jpg, .jpeg, .png, .webp ou .mp4 e pressione \"Atualizar lista\".\n" +
                            "• Ou envie arquivos pelo computador (adb push) para:\n   ${viewModel.importFolderPath ?: "—"}\n" +
                            "• Ou use \"＋ URL\" no painel.",
                        color = SignageColors.TextMuted,
                        fontSize = 14.sp,
                    )
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.files, key = { it.uri.toString() }) { file ->
                        val added = file.uri.toString() in state.added
                        ListItem(
                            selected = false,
                            // Permanece habilitado durante a cópia (desabilitar faria o foco pular);
                            // o ViewModel ignora novos cliques enquanto uma importação está em andamento.
                            onClick = { viewModel.import(file) },
                            leadingContent = {
                                Text(
                                    if (file.type == MediaType.IMAGE) "IMG" else "VID",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (file.type == MediaType.IMAGE) SignageColors.Accent else SignageColors.Warn,
                                )
                            },
                            headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text("${file.location} • ${Formatters.bytes(file.sizeBytes)}", fontSize = 12.sp, color = SignageColors.TextMuted)
                            },
                            trailingContent = { if (added) Text("✓ adicionado", color = SignageColors.Ok, fontSize = 13.sp) },
                        )
                    }
                }
            }
        }
    }
}
