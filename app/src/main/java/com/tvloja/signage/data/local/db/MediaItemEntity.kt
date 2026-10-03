package com.tvloja.signage.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tvloja.signage.domain.model.MediaItem
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.domain.model.NewMedia

@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["position"]),
        Index(value = ["remote_id"], unique = true),
    ]
)
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "IMAGE" ou "VIDEO" (armazenado como texto para facilitar migrações e leitura do banco). */
    val type: String,
    val uri: String,
    @ColumnInfo(name = "source_uri") val sourceUri: String?,
    /** Duração de exibição em ms (somente imagens). */
    val duration: Long,
    val position: Int,
    val enabled: Boolean,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "media_duration") val mediaDurationMs: Long?,
    @ColumnInfo(name = "remote_id") val remoteId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

fun MediaItemEntity.toDomain(): MediaItem = MediaItem(
    id = id,
    name = name,
    type = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.IMAGE),
    uri = uri,
    sourceUri = sourceUri,
    durationMs = duration,
    position = position,
    enabled = enabled,
    width = width,
    height = height,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    mediaDurationMs = mediaDurationMs,
    remoteId = remoteId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun NewMedia.toEntity(position: Int, now: Long): MediaItemEntity = MediaItemEntity(
    name = name,
    type = type.name,
    uri = uri,
    sourceUri = sourceUri,
    duration = durationMs,
    position = position,
    enabled = true,
    width = width,
    height = height,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    mediaDurationMs = mediaDurationMs,
    remoteId = remoteId,
    createdAt = now,
    updatedAt = now,
)
