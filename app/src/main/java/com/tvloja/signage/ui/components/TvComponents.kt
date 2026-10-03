package com.tvloja.signage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.tvloja.signage.ui.theme.SignageColors
import kotlinx.coroutines.delay

/**
 * Largura da coluna de rótulos. TVs 1080p usam densidade xhdpi: a tela útil tem só 960x540 dp,
 * então todo o layout é dimensionado para caber nisso.
 */
val LABEL_WIDTH = 170.dp

/** Botão compacto para TV, com estado "selecionado" (usado como opção/radio). */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = if (selected) {
            ButtonDefaults.colors(
                containerColor = SignageColors.Accent.copy(alpha = 0.35f),
                contentColor = Color.White,
                focusedContainerColor = SignageColors.Accent,
                focusedContentColor = SignageColors.Background,
            )
        } else ButtonDefaults.colors(
            containerColor = SignageColors.PanelAlt,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = SignageColors.Background,
        ),
    ) {
        Text(text, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(top = 12.dp, bottom = 6.dp),
        color = SignageColors.Accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

@Composable
fun InfoLine(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = SignageColors.TextMuted, fontSize = 14.sp, modifier = Modifier.width(LABEL_WIDTH))
        Text(value, color = valueColor, fontSize = 14.sp)
    }
}

/** Linha "rótulo: [−5] [−1] valor [+1] [+5]" navegável pelo D-pad. */
@Composable
fun StepperRow(
    label: String,
    value: String,
    minus: List<Pair<String, () -> Unit>>,
    plus: List<Pair<String, () -> Unit>>,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(label, color = SignageColors.TextMuted, fontSize = 14.sp, modifier = Modifier.width(LABEL_WIDTH))
        minus.forEach { (text, action) -> TvButton(text, action) }
        Text(
            value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(90.dp),
        )
        plus.forEach { (text, action) -> TvButton(text, action) }
    }
}

/** Linha "rótulo: [opção A] [opção B]" (funciona como um seletor). */
@Composable
fun <T> OptionRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(label, color = SignageColors.TextMuted, fontSize = 14.sp, modifier = Modifier.width(LABEL_WIDTH))
        options.forEach { (text, value) ->
            TvButton(text = if (value == selected) "✓ $text" else text, onClick = { onSelect(value) }, selected = value == selected)
        }
    }
}

@Composable
private fun DialogFrame(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = SignageColors.Panel, contentColor = Color.White),
        modifier = Modifier.border(1.dp, SignageColors.PanelAlt, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(24.dp).width(600.dp)) { content() }
    }
}

/** Diálogo de confirmação com foco inicial em "Cancelar" (evita exclusões acidentais). */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogFrame {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(message, color = SignageColors.TextMuted)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) { Text("Cancelar") }
                Button(onClick = onConfirm) { Text(confirmText) }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { cancelFocus.requestFocus() }
    }
}

/**
 * Diálogo de entrada de texto. Ao focar o campo e pressionar OK, o teclado virtual da TV é exibido.
 */
@Composable
fun TextInputDialog(
    title: String,
    initialValue: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    extraContent: (@Composable () -> Unit)? = null,
) {
    var value by remember { mutableStateOf(TextFieldValue(initialValue, TextRange(initialValue.length))) }
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogFrame {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(hint, color = SignageColors.TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(value.text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .background(SignageColors.Background, RoundedCornerShape(8.dp))
                    .border(
                        2.dp,
                        if (fieldFocused) SignageColors.Accent else SignageColors.PanelAlt,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(14.dp),
            )
            extraContent?.let {
                Spacer(Modifier.height(12.dp))
                it()
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onConfirm(value.text) }) { Text("Confirmar") }
                OutlinedButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { fieldFocus.requestFocus() }
    }
}
