package com.tvloja.signage.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object SignageColors {
    val Background = Color(0xFF0B1220)
    val Panel = Color(0xFF131C2E)
    val PanelAlt = Color(0xFF1B263B)
    val Accent = Color(0xFF64B5F6)
    val Ok = Color(0xFF81C784)
    val Warn = Color(0xFFFFB74D)
    val Error = Color(0xFFE57373)
    val TextMuted = Color(0xFF9AA7BD)
}

@Composable
fun SignageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SignageColors.Accent,
            onPrimary = Color(0xFF0B1220),
            background = SignageColors.Background,
            surface = SignageColors.Panel,
            surfaceVariant = SignageColors.PanelAlt,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFDDE3EE),
            error = SignageColors.Error,
        ),
    ) {
        // Fora de um Surface, o TV Material usa cor de conteúdo preta; nossas telas têm fundo escuro.
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}
