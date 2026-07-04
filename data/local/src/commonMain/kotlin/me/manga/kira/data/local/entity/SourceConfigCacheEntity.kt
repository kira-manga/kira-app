package me.manga.kira.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent cache of the generic-sources config document (Sources Migration — Phase 1).
 *
 * The source-config subsystem's `ConfigStore` port speaks a single raw JSON string (bundled floor <
 * cache < remote). This single-row table is the durable `cache` tier: it survives process death
 * (the previous in-memory cache did not) and is co-located with the `sources` table so a future
 * config refresh can reseed sources + migrate base URLs in one Room transaction.
 *
 * One logical row only (`id` is pinned to [SINGLETON_ID]); a write REPLACEs it. We store the raw
 * JSON verbatim (forward-compatible with newer remote schemas the current parser doesn't know yet)
 * plus lightweight metadata for diagnostics.
 */
@Entity(tableName = "source_config_cache")
data class SourceConfigCacheEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val rawJson: String,
    val revision: Long,
    val updatedAtEpochMs: Long,
) {
    companion object {
        const val SINGLETON_ID: Int = 0
    }
}
