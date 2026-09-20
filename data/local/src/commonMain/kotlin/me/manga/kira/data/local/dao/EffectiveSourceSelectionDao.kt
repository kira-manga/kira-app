package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.manga.kira.data.local.entity.EffectiveSourceSelectionEntity

/** Scalar primitives only. Authentication/readiness and owning transactions belong above this leaf. */
@Dao
interface EffectiveSourceSelectionDao {
    @Query("SELECT nextGeneration FROM source_selection_generation WHERE id = 0 " +
        "AND typeof(nextGeneration) = 'integer' AND nextGeneration > 0 " +
        "AND (SELECT count(*) FROM source_selection_generation) = 1")
    suspend fun nextGeneration(): Long?

    @Query("SELECT generation FROM effective_source_selection WHERE id = 0 AND typeof(generation) = 'integer'")
    suspend fun selectedGeneration(): Long?

    @Query("SELECT payloadDigest FROM effective_source_selection WHERE id = 0 " +
        "AND typeof(payloadDigest) = 'text' AND length(CAST(payloadDigest AS BLOB)) <= 64")
    suspend fun selectedDigest(): String?

    /** Bounds apply in SQLite BEFORE a corrupt body is copied into a Kotlin String. */
    @Query("SELECT * FROM effective_source_selection WHERE id = 0 AND typeof(generation) = 'integer' " +
        "AND generation > 0 AND typeof(payload) = 'text' AND typeof(payloadDigest) = 'text' " +
        "AND length(CAST(payload AS BLOB)) <= :maxBytes AND length(CAST(payloadDigest AS BLOB)) = 64 " +
        "AND (SELECT count(*) FROM effective_source_selection) = 1")
    suspend fun boundedSelection(maxBytes: Int): EffectiveSourceSelectionEntity?

    @Query("SELECT count(*) FROM effective_source_selection")
    suspend fun selectionCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSelection(selection: EffectiveSourceSelectionEntity)

    /** Caller checks one changed row and commits this CAS with every selection/projection write. */
    @Query("UPDATE source_selection_generation SET nextGeneration = :next " +
        "WHERE id = 0 AND typeof(nextGeneration) = 'integer' AND nextGeneration = :expected")
    suspend fun advance(expected: Long, next: Long): Int

    /** These emissions are invalidations, not authority; even an unchanged ID list must recheck. */
    @Query("SELECT id FROM effective_source_selection")
    fun selectionInvalidations(): Flow<List<Int>>

    @Query("SELECT id FROM source_selection_generation")
    fun allocatorInvalidations(): Flow<List<Int>>
}
