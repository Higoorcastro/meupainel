package com.tvloja.signage

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvloja.signage.security.AdminAccessDetector
import com.tvloja.signage.security.AdminConfig
import com.tvloja.signage.ui.AppRoot
import com.tvloja.signage.ui.MainViewModel
import com.tvloja.signage.ui.Screen
import com.tvloja.signage.ui.theme.SignageTheme
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Activity única. Responsável por:
 * - tela cheia imersiva (sem barras do sistema) e tela sempre ligada (sem descanso de tela);
 * - detectar o gesto secreto do controle remoto durante a reprodução;
 * - bloquear o botão VOLTAR durante a reprodução (ninguém fecha a propaganda sem querer);
 * - fechar o painel administrativo após inatividade.
 */
class MainActivity : ComponentActivity() {

    private val navigation: MainViewModel by viewModels()
    private val adminDetector = AdminAccessDetector()
    private var lastInteraction = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Impede o protetor de tela / descanso do Android TV enquanto o app estiver em primeiro plano.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        val container = (application as SignageApp).container
        setContent {
            SignageTheme {
                AppRoot(navigation = navigation, container = container, onExitApp = { finishAndRemoveTask() })
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(30_000)
                    val idle = SystemClock.elapsedRealtime() - lastInteraction
                    if (navigation.screen.value != Screen.PLAYER && idle > AdminConfig.ADMIN_IDLE_TIMEOUT_MS) {
                        AppLogger.i("Painel inativo por muito tempo; voltando para a reprodução")
                        navigation.navigate(Screen.PLAYER)
                        container.playlistPlayer.play()
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Reaberto pelo boot/launcher: garante que a tela do PIN não fique esquecida aberta.
        if (navigation.screen.value == Screen.PIN) navigation.navigate(Screen.PLAYER)
    }

    // RestrictedApi: falso positivo do lint (ComponentActivity do androidx.core anota o override);
    // Activity.dispatchKeyEvent é API pública da plataforma.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInteraction = SystemClock.elapsedRealtime()
        if (navigation.screen.value == Screen.PLAYER) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                adminDetector.onKeyDown(event.keyCode, event.repeatCount)
            ) {
                AppLogger.i("Gesto de administração detectado")
                navigation.navigate(Screen.PIN)
                return true
            }
            return when (event.keyCode) {
                // Volume e mudo continuam funcionando normalmente.
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE ->
                    super.dispatchKeyEvent(event)
                // Demais teclas (inclusive VOLTAR) são ignoradas durante a reprodução.
                else -> true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
