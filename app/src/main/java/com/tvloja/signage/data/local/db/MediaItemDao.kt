package com.tvloja.signage.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {

    @Query("SELECT * FROM media_items ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items ORDER BY position ASC, id ASC")
    suspend fun getAll(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun getById(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE remote_id IS NOT NULL")
    suspend fun getRemoteItems(): List<MediaItemEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM media_items")
    suspend fun maxPosition(): Int

    @Insert
    suspend fun insert(entity: MediaItemEntity): Long

    @Update
    suspend fun update(entity: MediaItemEntity)

    @Update
    suspend fun updateAll(entities: List<MediaItemEntity>)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteById(id: Long)
}
