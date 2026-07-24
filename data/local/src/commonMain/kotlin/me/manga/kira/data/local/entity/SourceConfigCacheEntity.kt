package me.manga.kira.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Historical v11 whole-document cache entity retained for Room schema compatibility. Migration
 * 11→12 clears it, and the v2 runtime stores signed manifests and immutable source revisions in the
 * source-catalog tables instead.
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
