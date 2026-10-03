package com.tvloja.signage.domain.repository

import com.tvloja.signage.domain.model.MediaItem
import com.tvloja.signage.domain.model.NewMedia
import com.tvloja.signage.domain.model.RemoteMediaSpec
import com.tvloja.signage.domain.model.SyncOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Fonte da verdade da playlist usada pelo player.
 *
 * A implementação atual é [com.tvloja.signage.data.repository.LocalPlaylistRepository] (Room).
 * A UI e o player dependem apenas desta interface, então é possível trocar/compor com uma
 * implementação remota (ex.: RemotePlaylistRepository que consulta uma API e grava no cache local)
 * sem alterar o restante do app.
 */
interface PlaylistRepository {
    /** Todos os itens (ativos e inativos), ordenados por posição. */
    fun observeAll(): Flow<List<MediaItem>>

    suspend fun getAll(): List<MediaItem>
    suspend fun getById(id: Long): MediaItem?

    /** Adiciona ao final da playlist e retorna o id. */
    suspend fun add(media: NewMedia): Long

    suspend fun rename(id: Long, name: String)
    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun setImageDuration(id: Long, durationMs: Long)

    /** Move o item uma posição para cima (-1) ou para baixo (+1). */
    suspend fun move(id: Long, direction: Int)

    /** Remove o item e retorna o que foi removido (para limpar arquivos). */
    suspend fun delete(id: Long): MediaItem?

    /** Atualiza a URI reproduzida (ex.: após baixar uma URL para o armazenamento local). */
    suspend fun updatePlaybackUri(id: Long, uri: String, sizeBytes: Long?)

    /** Aplica o conteúdo vindo do servidor (itens com remoteId). Itens locais não são afetados. */
    suspend fun syncRemote(specs: List<RemoteMediaSpec>): SyncOutcome
}
