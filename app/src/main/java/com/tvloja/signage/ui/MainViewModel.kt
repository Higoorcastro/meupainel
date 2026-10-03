package com.tvloja.signage.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Screen { PLAYER, PIN, ADMIN, PICKER }

/** Navegação simples entre as telas (sobrevive à recriação da Activity). */
class MainViewModel : ViewModel() {
    private val _screen = MutableStateFlow(Screen.PLAYER)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    fun navigate(to: Screen) {
        _screen.value = to
    }
}
