package me.manga.kira.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Append to the shipped artifact schema; old CONVERT receipts remain distinguishable by NULL. */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chapter_artifacts ADD COLUMN conversionSourceRoster TEXT")
    }
}
