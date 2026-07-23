package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.manga.kira.data.local.entity.ActiveSourceCatalogEntity
import me.manga.kira.data.local.entity.SourceCatalogEntryEntity
import me.manga.kira.data.local.entity.SourceCatalogManifestEntity
import me.manga.kira.data.local.entity.SourceRevisionArtifactEntity

/** Persistence primitives used by the atomic source-catalog store. */
@Dao
interface SourceCatalogDao {
    @Query("SELECT * FROM active_source_catalog WHERE id = 0 LIMIT 1")
    suspend fun activePointer(): ActiveSourceCatalogEntity?

    @Query(
        """
        SELECT source_catalog_manifests.* FROM source_catalog_manifests
        INNER JOIN active_source_catalog
          ON active_source_catalog.catalogRevision = source_catalog_manifests.catalogRevision
        WHERE active_source_catalog.id = 0
        LIMIT 1
        """,
    )
    suspend fun activeManifest(): SourceCatalogManifestEntity?

    @Query("SELECT * FROM source_catalog_entries WHERE catalogRevision = :catalogRevision ORDER BY displayOrder")
    suspend fun entries(catalogRevision: Long): List<SourceCatalogEntryEntity>

    @Query(
        """
        SELECT * FROM source_revision_artifacts
        WHERE api = :api AND sourceRevision = :sourceRevision AND checksum = :checksum
        LIMIT 1
        """,
    )
    suspend fun source(
        api: String,
        sourceRevision: Long,
        checksum: String,
    ): SourceRevisionArtifactEntity?

    @Query(
        """
        SELECT * FROM source_revision_artifacts
        WHERE api = :api AND sourceRevision = :sourceRevision
        LIMIT 1
        """,
    )
    suspend fun sourceByIdentity(
        api: String,
        sourceRevision: Long,
    ): SourceRevisionArtifactEntity?

    @Query("SELECT * FROM source_catalog_manifests WHERE catalogRevision = :catalogRevision LIMIT 1")
    suspend fun manifestByRevision(catalogRevision: Long): SourceCatalogManifestEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertManifest(manifest: SourceCatalogManifestEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<SourceCatalogEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSources(sources: List<SourceRevisionArtifactEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setActive(active: ActiveSourceCatalogEntity)

    @Query("DELETE FROM source_catalog_entries WHERE catalogRevision = :catalogRevision")
    suspend fun deleteEntries(catalogRevision: Long)
}
