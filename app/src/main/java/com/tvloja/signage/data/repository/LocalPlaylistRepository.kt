package com.tvloja.signage.data.repository

import androidx.room.withTransaction
import com.tvloja.signage.data.local.db.MediaItemEntity
import com.tvloja.signage.data.local.db.SignageDatabase
import com.tvloja.signage.data.local.db.toDomain
import com.tvloja.signage.data.local.db.toEntity
import com.tvloja.signage.domain.model.MediaItem
import com.tvloja.signage.domain.model.NewMedia
import com.tvloja.signage.domain.model.RemoteMediaSpec
import com.tvloja.signage.domain.model.SyncOutcome
import com.tvloja.signage.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class LocalPlaylistRepository(private val db: SignageDatabase) : PlaylistRepository {

    private val dao = db.mediaItemDao()
    private fun now() = System.currentTimeMillis()

    override fun observeAll(): Flow<List<MediaItem>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }.distinctUntilChanged()

    override suspend fun getAll(): List<MediaItem> = dao.getAll().map { it.toDomain() }

    override suspend fun getById(id: Long): MediaItem? = dao.getById(id)?.toDomain()

    override suspend fun add(media: NewMedia): Long = db.withTransaction {
        dao.insert(media.toEntity(position = dao.maxPosition() + 1, now = now()))
    }

    override suspend fun rename(id: Long, name: String) = modify(id) { it.copy(name = name.trim().ifBlank { it.name }) }

    override suspend fun setEnabled(id: Long, enabled: Boolean) = modify(id) { it.copy(enabled = enabled) }

    override suspend fun setImageDuration(id: Long, durationMs: Long) = modify(id) { it.copy(duration = durationMs) }

    override suspend fun updatePlaybackUri(id: Long, uri: String, sizeBytes: Long?) =
        modify(id) { it.copy(uri = uri, sizeBytes = sizeBytes ?: it.sizeBytes) }

    private suspend fun modify(id: Long, change: (MediaItemEntity) -> MediaItemEntity) {
        db.withTransaction {
            val current = dao.getById(id) ?: return@withTransaction
            dao.update(change(current).copy(updatedAt = now()))
        }
    }

    override suspend fun move(id: Long, direction: Int) {
        db.withTransaction {
            val list = dao.getAll().toMutableList()
            val from = list.indexOfFirst { it.id == id }
            if (from < 0) return@withTransaction
            val to = (from + direction).coerceIn(0, list.lastIndex)
            if (to == from) return@withTransaction
            val item = list.removeAt(from)
            list.add(to, item)
            renumber(list)
        }
    }

    override suspend fun delete(id: Long): MediaItem? = db.withTransaction {
        val entity = dao.getById(id) ?: return@withTransaction null
        dao.deleteById(id)
        renumber(dao.getAll())
        entity.toDomain()
    }

    /** Regrava posições contínuas (0..n-1) apenas para os itens que mudaram. */
    private suspend fun renumber(list: List<MediaItemEntity>) {
        val ts = now()
        val changed = list.mapIndexedNotNull { index, e ->
            if (e.position != index) e.copy(position = index, updatedAt = ts) else null
        }
        if (changed.isNotEmpty()) dao.updateAll(changed)
    }

    override suspend fun syncRemote(specs: List<RemoteMediaSpec>): SyncOutcome = db.withTransaction {
        val ts = now()
        val existing = dao.getRemoteItems().associateBy { it.remoteId }
        val specIds = specs.map { it.remoteId }.toSet()
        val obsolete = mutableListOf<String>()
        var added = 0
        var updated = 0

        val removed = existing.values.filter { it.remoteId !in specIds }
        removed.forEach {
            dao.deleteById(it.id)
            obsolete += it.uri
        }

        specs.forEach { spec ->
            val current = existing[spec.remoteId]
            if (current == null) {
                dao.insert(
                    NewMedia(
                        name = spec.name, type = spec.type, uri = spec.url, sourceUri = spec.url,
                        durationMs = spec.durationMs, remoteId = spec.remoteId,
                    ).toEntity(position = Int.MAX_VALUE, now = ts).copy(enabled = spec.enabled)
                )
                added++
            } else {
                val urlChanged = current.sourceUri != spec.url
                if (urlChanged && current.uri != spec.url) obsolete += current.uri
                val next = current.copy(
                    name = spec.name,
                    type = spec.type.name,
                    duration = spec.durationMs,
                    enabled = spec.enabled,
                    uri = if (urlChanged) spec.url else current.uri,
                    sourceUri = spec.url,
                )
                if (next != current) {
                    dao.update(next.copy(updatedAt = ts))
                    updated++
                }
            }
        }

        // Ordem final: itens locais (na ordem atual) seguidos dos remotos na ordem do manifesto.
        val all = dao.getAll()
        val order = specs.withIndex().associate { it.value.remoteId to it.index }
        val locals = all.filter { it.remoteId == null }
        val remotes = all.filter { it.remoteId != null }.sortedBy { order[it.remoteId] ?: Int.MAX_VALUE }
        renumber(locals + remotes)

        SyncOutcome(
            added = added,
            updated = updated,
            removed = removed.map { it.toDomain() },
            obsoleteUris = obsolete,
            needsDownload = dao.getRemoteItems()
                .filter { it.uri.startsWith("http://") || it.uri.startsWith("https://") }
                .map { it.id },
        )
    }
}
