package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.manga.kira.data.local.entity.SourceConfigCacheEntity

/**
 * Historical v11 whole-document cache retained only because Room must preserve the declared
 * database schema across upgrades. Migration 11→12 clears this table; runtime source delivery uses
 * [SourceCatalogDao] and never reads it.
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
