package com.tvloja.signage.ui.pin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.tvloja.signage.security.AdminConfig
import com.tvloja.signage.security.AdminSecurity
import com.tvloja.signage.ui.theme.SignageColors
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PinScreen(security: AdminSecurity, onSuccess: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstKey = remember { FocusRequester() }
    val okKey = remember { FocusRequester() }

    BackHandler(onBack = onCancel)

    // Fecha a tela do PIN sozinha se ninguém digitar nada (volta para a propaganda).
    LaunchedEffect(pin) {
        delay(60_000)
        onCancel()
    }

    fun submit() {
        if (checking || pin.isEmpty()) return
        checking = true
        scope.launch {
            when (val result = security.verify(pin)) {
                AdminSecurity.Result.Ok -> {
                    AppLogger.i("Acesso administrativo liberado")
                    onSuccess()
                }
                is AdminSecurity.Result.Wrong -> {
                    AppLogger.w("PIN incorreto")
                    message = "PIN incorreto. Tentativas restantes: ${result.attemptsLeft}"
                }
                is AdminSecurity.Result.Locked -> {
                    AppLogger.w("Acesso bloqueado temporariamente")
                    message = "Muitas tentativas. Aguarde ${result.secondsLeft}s."
                }
            }
            pin = ""
            checking = false
        }
    }

    fun type(digit: Char) {
        if (pin.length < AdminConfig.MAX_PIN_LENGTH) {
            pin += digit
            message = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SignageColors.Background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = when (event.key) {
                    Key.Zero, Key.NumPad0 -> '0'
                    Key.One, Key.NumPad1 -> '1'
                    Key.Two, Key.NumPad2 -> '2'
                    Key.Three, Key.NumPad3 -> '3'
                    Key.Four, Key.NumPad4 -> '4'
                    Key.Five, Key.NumPad5 -> '5'
                    Key.Six, Key.NumPad6 -> '6'
                    Key.Seven, Key.NumPad7 -> '7'
                    Key.Eight, Key.NumPad8 -> '8'
                    Key.Nine, Key.NumPad9 -> '9'
                    else -> null
                }
                when {
                    digit != null -> {
                        type(digit)
                        // Após digitar pelo teclado numérico do controle, OK confirma o PIN.
                        runCatching { okKey.requestFocus() }
                        true
                    }
                    event.key == Key.Backspace || event.key == Key.Delete -> { pin = pin.dropLast(1); true }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ADMINISTRAÇÃO", color = SignageColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text("Digite o PIN", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (pin.isEmpty()) "—" else "●".repeat(pin.length),
            fontSize = 36.sp,
            letterSpacing = 8.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(message ?: " ", color = SignageColors.Error, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("⌫", "0", "OK"),
        )
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEachIndexed { c, label ->
                    val mod = Modifier.size(width = 96.dp, height = 64.dp)
                        .let {
                            when {
                                r == 0 && c == 0 -> it.focusRequester(firstKey)
                                label == "OK" -> it.focusRequester(okKey)
                                else -> it
                            }
                        }
                    Button(
                        onClick = {
                            when (label) {
                                "⌫" -> pin = pin.dropLast(1)
                                "OK" -> submit()
                                else -> type(label[0])
                            }
                        },
                        modifier = mod,
                        colors = ButtonDefaults.colors(
                            containerColor = SignageColors.PanelAlt,
                            focusedContainerColor = if (label == "OK") SignageColors.Ok else SignageColors.Accent,
                        ),
                    ) {
                        Text(label, fontSize = 24.sp, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("VOLTAR cancela", color = SignageColors.TextMuted, fontSize = 14.sp)
    }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { firstKey.requestFocus() }
    }
}
