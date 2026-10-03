package com.tvloja.signage.domain.usecase

import com.tvloja.signage.domain.model.MediaItem
import com.tvloja.signage.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Playlist efetivamente reproduzível: itens ativos, na ordem definida.
 *
 * É o ponto único onde futuras regras de negócio serão aplicadas (agendamento de campanhas,
 * horários de exibição, dias da semana, validade), sem tocar no player nem na UI.
 */
class ObservePlayablePlaylistUseCase(private val repository: PlaylistRepository) {
    operator fun invoke(): Flow<List<MediaItem>> =
        repository.observeAll()
            .map { list -> list.filter { it.enabled }.sortedBy { it.position } }
            .distinctUntilChanged()
}
