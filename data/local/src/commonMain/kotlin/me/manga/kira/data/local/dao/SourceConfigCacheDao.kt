package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.manga.kira.data.local.entity.SourceConfigCacheEntity

/**
 * DAO for the single-row generic-sources config cache (Sources Migration — Phase 1).
 *
 * Backs the durable `cache` tier of the `ConfigStore` port. Reads return the cached document (or
 * null on a fresh install with no cached config yet); writes REPLACE the single row.
 */
@Dao
interface SourceConfigCacheDao {
    @Query("SELECT * FROM source_config_cache WHERE id = 0 LIMIT 1")
    suspend fun getCached(): SourceConfigCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SourceConfigCacheEntity)

    @Query("DELETE FROM source_config_cache")
    suspend fun clear()
}
