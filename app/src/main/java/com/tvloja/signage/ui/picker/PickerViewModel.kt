package com.tvloja.signage.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvloja.signage.data.local.files.LocalMediaFile
import com.tvloja.signage.di.AppContainer
import com.tvloja.signage.domain.usecase.ImportResult
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PickerUiState(
    val loading: Boolean = false,
    val files: List<LocalMediaFile> = emptyList(),
    val hasPermission: Boolean = false,
    val copyToAppStorage: Boolean = true,
    val importingName: String? = null,
    val progress: Float = 0f,
    val added: Set<String> = emptySet(),
    val message: String? = null,
)

class PickerViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(PickerUiState())
    val state: StateFlow<PickerUiState> = _state.asStateFlow()
    private var importJob: Job? = null

    val importFolderPath: String? get() = c.mediaFileStore.importDir?.absolutePath

    fun refresh(hasPermission: Boolean) {
        _state.update { it.copy(loading = true, hasPermission = hasPermission) }
        viewModelScope.launch {
            val files = runCatching { c.mediaScanner.scan(hasPermission) }
                .onFailure { AppLogger.e("Falha ao procurar mídias", it) }
                .getOrDefault(emptyList())
            _state.update { it.copy(loading = false, files = files) }
        }
    }

    fun setCopy(copy: Boolean) = _state.update { it.copy(copyToAppStorage = copy) }

    fun import(file: LocalMediaFile) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _state.update { it.copy(importingName = file.name, progress = 0f, message = null) }
            val result = c.importLocalMedia(file, _state.value.copyToAppStorage) { p ->
                _state.update { it.copy(progress = p) }
            }
            _state.update {
                when (result) {
                    is ImportResult.Success -> it.copy(
                        importingName = null,
                        added = it.added + file.uri.toString(),
                        message = "Adicionado à playlist: ${result.name}",
                    )
                    is ImportResult.Failure -> it.copy(importingName = null, message = result.message)
                }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        _state.update { it.copy(importingName = null, message = "Importação cancelada") }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
