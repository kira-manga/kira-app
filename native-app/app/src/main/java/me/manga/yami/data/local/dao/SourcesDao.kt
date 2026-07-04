package me.manga.yamiapk.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.data.local.entity.SourcesEntity
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourceState

@Dao
interface SourcesDao {

    @Query("SELECT * FROM sources ORDER BY priority")
    fun getAllSources(): Flow<List<SourcesEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: SourcesEntity): Long

    @Update
    suspend fun update(source: SourcesEntity)

    @Delete
    suspend fun delete(source: SourcesEntity)

    // Remove this method since we don't have id anymore
    // @Query("UPDATE sources SET isEnabled = :enabled WHERE id = :id")
    // suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE sources SET isEnabled = :enabled WHERE name = :name")
    suspend fun setEnabledByName(name: String, enabled: Boolean): Int

    @Transaction
    suspend fun enableByName(name: String) {
        setEnabledByName(name, true)
    }

    @Query("""
    UPDATE sources
    SET baseUrl = :baseUrl, baseVersion = :version
    WHERE name = :name
    """)
    suspend fun updateBaseUrlAndVersionByName(name: String, baseUrl: String, version: Int): Int

    @Query("UPDATE sources SET imageBaseUrl = :newImageBaseUrl, imageUrlVersion = :newImageVersion WHERE name = :apiName")
    suspend fun updateImageBaseUrlAndVersionByName(apiName: String, newImageBaseUrl: String, newImageVersion: Int): Int

    @Query("SELECT baseUrl FROM sources WHERE name = :name LIMIT 1")
    suspend fun getBaseUrlFor(name: String): String?

    @Query("SELECT * FROM sources WHERE isEnabled = :enabled ORDER BY priority")
    fun getSourcesByEnabled(enabled: Boolean): Flow<List<SourcesEntity>>

    @Transaction
    suspend fun disableByName(name: String) {
        setEnabledByName(name, false)
    }

    @Query("SELECT name FROM sources WHERE siteState = :state ORDER BY priority")
    fun getSourceNamesByState(state: SourceState): Flow<List<String>>

    @Query("SELECT siteState FROM sources WHERE name = :name LIMIT 1")
    fun getSiteStateByName(name: String): Flow<SourceState?>

    @Query("SELECT siteState FROM sources WHERE name = :name LIMIT 1")
    suspend fun getSiteStateByNameSync(name: String): SourceState?

    @Query("UPDATE sources SET siteState = :siteState WHERE name = :name")
    suspend fun updateSiteStateByName(name: String, siteState: SourceState): Int

    @Query("DELETE FROM sources WHERE name = :name")
    suspend fun deleteByName(name: String): Int

    @Transaction
    suspend fun deleteSourceByName(name: String) {
        deleteByName(name)
    }
}