package com.tvloja.signage.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Banco local. O esquema é exportado em app/schemas para permitir migrações versionadas.
 *
 * Evoluções futuras previstas (novas tabelas via Migration): campaigns, schedules (horários/dias),
 * playback_reports (prova de exibição), device_groups.
 */
@Database(entities = [MediaItemEntity::class], version = 1, exportSchema = true)
abstract class SignageDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao

    companion object {
        fun create(context: Context): SignageDatabase =
            Room.databaseBuilder(context.applicationContext, SignageDatabase::class.java, "signage.db")
                // Ao adicionar versões, registre as Migrations aqui com .addMigrations(...).
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
    }
}
