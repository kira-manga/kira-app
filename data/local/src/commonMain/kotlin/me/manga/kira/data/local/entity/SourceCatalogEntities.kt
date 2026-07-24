package me.manga.kira.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Exact signed manifest bytes accepted for one complete catalog revision. */
@Entity(tableName = "source_catalog_manifests")
data class SourceCatalogManifestEntity(
    @PrimaryKey val catalogRevision: Long,
    val rawPayload: String,
    val format: String,
    val algorithm: String,
    val signingKeyId: String,
    val signatureBase64: String,
    val checksum: String,
    val createdAt: String,
    val previousRevision: Long?,
    val previousChecksum: String?,
)

/** Signed manifest mapping from a catalog revision to an immutable source revision. */
@Entity(
    tableName = "source_catalog_entries",
    primaryKeys = ["catalogRevision", "api"],
    indices = [Index(value = ["api", "sourceRevision"])],
)
data class SourceCatalogEntryEntity(
    val catalogRevision: Long,
    val api: String,
    val sourceRevision: Long,
    val checksum: String,
    val displayOrder: Int,
    val lifecycle: String,
    val engine: String,
    val sourceSigningKeyId: String,
    val sourceSignature: String,
)

/** Exact canonical bytes for one immutable source revision. */
@Entity(
    tableName = "source_revision_artifacts",
    primaryKeys = ["api", "sourceRevision"],
    indices = [Index(value = ["checksum"])],
)
data class SourceRevisionArtifactEntity(
    val api: String,
    val sourceRevision: Long,
    val checksum: String,
    val canonVersion: String,
    val rawPayload: String,
)

/** Singleton pointer changed only after a complete catalog has been persisted. */
@Entity(tableName = "active_source_catalog")
data class ActiveSourceCatalogEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val catalogRevision: Long,
    val checksum: String,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
