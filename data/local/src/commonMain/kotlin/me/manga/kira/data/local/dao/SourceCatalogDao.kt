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
    @Query("SELECT * FROM active_source_catalog WHERE id = 0 AND typeof(catalogRevision) = 'integer' " +
        "AND catalogRevision > 0 AND typeof(checksum) = 'text' AND length(CAST(checksum AS BLOB)) = 64 " +
        "AND (SELECT count(*) FROM active_source_catalog) = 1 LIMIT 1")
    suspend fun activePointer(): ActiveSourceCatalogEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM active_source_catalog)")
    suspend fun activePointerExists(): Boolean

    @Query("SELECT source_catalog_manifests.* FROM source_catalog_manifests " +
        "INNER JOIN active_source_catalog ON active_source_catalog.catalogRevision = source_catalog_manifests.catalogRevision " +
        "WHERE active_source_catalog.id = 0 AND " + BOUNDED_MANIFEST + " LIMIT 1")
    suspend fun activeManifest(): SourceCatalogManifestEntity?

    @Query("SELECT * FROM source_catalog_entries WHERE catalogRevision = :catalogRevision ORDER BY displayOrder")
    suspend fun entries(catalogRevision: Long): List<SourceCatalogEntryEntity>

    @Query(
        """
        SELECT * FROM source_revision_artifacts
        WHERE api = :api AND sourceRevision = :sourceRevision AND checksum = :checksum
          AND typeof(rawPayload) = 'text' AND length(CAST(rawPayload AS BLOB)) <= 262144
          AND typeof(checksum) = 'text' AND length(CAST(checksum AS BLOB)) = 64
          AND typeof(canonVersion) = 'text' AND length(CAST(canonVersion AS BLOB)) <= 16
          AND typeof(sourceRevision) = 'integer' AND sourceRevision > 0
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
          AND typeof(rawPayload) = 'text' AND length(CAST(rawPayload AS BLOB)) <= 262144
          AND typeof(checksum) = 'text' AND length(CAST(checksum AS BLOB)) = 64
          AND typeof(canonVersion) = 'text' AND length(CAST(canonVersion AS BLOB)) <= 16
          AND typeof(sourceRevision) = 'integer' AND sourceRevision > 0
        LIMIT 1
        """,
    )
    suspend fun sourceByIdentity(
        api: String,
        sourceRevision: Long,
    ): SourceRevisionArtifactEntity?

    @Query("SELECT * FROM source_catalog_manifests WHERE catalogRevision = :catalogRevision AND " +
        BOUNDED_MANIFEST + " LIMIT 1")
    suspend fun manifestByRevision(catalogRevision: Long): SourceCatalogManifestEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM source_catalog_manifests WHERE catalogRevision = :revision)")
    suspend fun manifestExists(revision: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM source_revision_artifacts WHERE api = :api AND sourceRevision = :revision)")
    suspend fun sourceExists(api: String, revision: Long): Boolean

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

private const val BOUNDED_MANIFEST =
    "typeof(rawPayload) = 'text' AND length(CAST(rawPayload AS BLOB)) <= 5242880 " +
        "AND typeof(format) = 'text' AND length(CAST(format AS BLOB)) <= 64 " +
        "AND typeof(algorithm) = 'text' AND length(CAST(algorithm AS BLOB)) <= 16 " +
        "AND typeof(signingKeyId) = 'text' AND length(CAST(signingKeyId AS BLOB)) <= 64 " +
        "AND typeof(signatureBase64) = 'text' AND length(CAST(signatureBase64 AS BLOB)) <= 128 " +
        "AND typeof(source_catalog_manifests.checksum) = 'text' AND length(CAST(source_catalog_manifests.checksum AS BLOB)) = 64 " +
        "AND typeof(createdAt) = 'text' AND length(CAST(createdAt AS BLOB)) <= 32 " +
        "AND (previousChecksum IS NULL OR (typeof(previousChecksum) = 'text' AND length(CAST(previousChecksum AS BLOB)) = 64)) " +
        "AND (previousRevision IS NULL OR (typeof(previousRevision) = 'integer' AND previousRevision > 0))"
