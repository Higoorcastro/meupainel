package com.tvloja.signage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tvloja.signage.di.AppContainer
import com.tvloja.signage.ui.admin.AdminScreen
import com.tvloja.signage.ui.admin.AdminViewModel
import com.tvloja.signage.ui.picker.MediaPickerScreen
import com.tvloja.signage.ui.picker.PickerViewModel
import com.tvloja.signage.ui.pin.PinScreen
import com.tvloja.signage.ui.player.PlayerScreen

@Composable
fun AppRoot(navigation: MainViewModel, container: AppContainer, onExitApp: () -> Unit) {
    val screen by navigation.screen.collectAsStateWithLifecycle()

    when (screen) {
        Screen.PLAYER -> PlayerScreen(container)

        Screen.PIN -> PinScreen(
            security = container.adminSecurity,
            onSuccess = { navigation.navigate(Screen.ADMIN) },
            onCancel = { navigation.navigate(Screen.PLAYER) },
        )

        Screen.ADMIN -> {
            val vm: AdminViewModel = viewModel(factory = viewModelFactory { initializer { AdminViewModel(container) } })
            AdminScreen(
                viewModel = vm,
                onOpenPicker = { navigation.navigate(Screen.PICKER) },
                onBackToPlayer = { navigation.navigate(Screen.PLAYER) },
                onExitApp = onExitApp,
            )
        }

        Screen.PICKER -> {
            val vm: PickerViewModel = viewModel(factory = viewModelFactory { initializer { PickerViewModel(container) } })
            MediaPickerScreen(viewModel = vm, onBack = { navigation.navigate(Screen.ADMIN) })
        }
    }
}
