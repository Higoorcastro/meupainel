package com.tvloja.signage.security

import android.os.SystemClock
import android.view.KeyEvent
import com.tvloja.signage.domain.repository.SettingsRepository
import java.security.MessageDigest

/**
 * ============================================================================================
 *  CONFIGURAÇÃO DE ACESSO AO PAINEL ADMINISTRATIVO
 * ============================================================================================
 *
 *  >>> ALTERE O PIN PADRÃO AQUI (DEFAULT_PIN) antes de instalar nas TVs da loja! <<<
 *
 *  O PIN padrão vale apenas enquanto nenhum PIN tiver sido definido pelo painel
 *  (Administração > Configurações > Alterar PIN). Depois disso, vale o PIN salvo no aparelho.
 * ============================================================================================
 */
object AdminConfig {
    /** PIN padrão de DESENVOLVIMENTO. Troque por um PIN próprio antes de usar em produção. */
    const val DEFAULT_PIN = "1234"

    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 8

    /** Gesto 1: pressionar OK (centro do D-pad) esta quantidade de vezes... */
    const val OK_PRESS_COUNT = 5

    /** ...dentro desta janela de tempo. */
    const val OK_PRESS_WINDOW_MS = 3_000L

    /** Gesto 2: sequência de setas (↑ ↑ ↓ ↓ ← →) dentro de [SEQUENCE_WINDOW_MS]. */
    val KEY_SEQUENCE = listOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )
    const val SEQUENCE_WINDOW_MS = 5_000L

    /** Tentativas erradas antes de bloquear temporariamente. */
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_MS = 60_000L

    /** Sem uso do controle por este tempo, o painel fecha e a reprodução volta sozinha. */
    const val ADMIN_IDLE_TIMEOUT_MS = 10 * 60_000L
}

/** Detecta o gesto secreto no controle remoto durante a reprodução. */
class AdminAccessDetector(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val okPresses = ArrayDeque<Long>()
    private val sequence = ArrayDeque<Pair<Int, Long>>()

    /** @return true quando o gesto foi completado. Deve receber apenas ACTION_DOWN. */
    fun onKeyDown(keyCode: Int, repeatCount: Int): Boolean {
        if (repeatCount > 0) return false
        val now = clock()

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A
        ) {
            okPresses.addLast(now)
            while (okPresses.isNotEmpty() && now - okPresses.first() > AdminConfig.OK_PRESS_WINDOW_MS) okPresses.removeFirst()
            if (okPresses.size >= AdminConfig.OK_PRESS_COUNT) {
                reset()
                return true
            }
        }

        sequence.addLast(keyCode to now)
        while (sequence.size > AdminConfig.KEY_SEQUENCE.size) sequence.removeFirst()
        if (sequence.size == AdminConfig.KEY_SEQUENCE.size &&
            sequence.map { it.first } == AdminConfig.KEY_SEQUENCE &&
            now - sequence.first().second <= AdminConfig.SEQUENCE_WINDOW_MS
        ) {
            reset()
            return true
        }
        return false
    }

    fun reset() {
        okPresses.clear()
        sequence.clear()
    }
}

/** Validação do PIN com bloqueio temporário após várias tentativas erradas. */
class AdminSecurity(private val settings: SettingsRepository) {

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val attemptsLeft: Int) : Result
        data class Locked(val secondsLeft: Long) : Result
    }

    private var failedAttempts = 0
    private var lockedUntil = 0L

    suspend fun verify(pin: String): Result {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) return Result.Locked((lockedUntil - now + 999) / 1000)

        val salt = settings.ensureDeviceId()
        val stored = settings.getPinHash()
        val ok = if (stored == null) pin == AdminConfig.DEFAULT_PIN else hash(pin, salt) == stored
        if (ok) {
            failedAttempts = 0
            return Result.Ok
        }
        failedAttempts++
        if (failedAttempts >= AdminConfig.MAX_ATTEMPTS) {
            failedAttempts = 0
            lockedUntil = now + AdminConfig.LOCKOUT_MS
            return Result.Locked(AdminConfig.LOCKOUT_MS / 1000)
        }
        return Result.Wrong(AdminConfig.MAX_ATTEMPTS - failedAttempts)
    }

    /** @return mensagem de erro ou null se o PIN foi alterado. */
    suspend fun changePin(newPin: String): String? {
        if (newPin.length !in AdminConfig.MIN_PIN_LENGTH..AdminConfig.MAX_PIN_LENGTH || !newPin.all { it.isDigit() }) {
            return "O PIN deve ter de ${AdminConfig.MIN_PIN_LENGTH} a ${AdminConfig.MAX_PIN_LENGTH} dígitos numéricos"
        }
        settings.setPinHash(hash(newPin, settings.ensureDeviceId()))
        return null
    }

    private fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$salt:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }
}
