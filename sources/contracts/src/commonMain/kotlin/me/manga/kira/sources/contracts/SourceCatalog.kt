package me.manga.kira.sources.contracts

import kotlinx.serialization.Serializable
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * Signed lightweight authority for one complete source-catalog revision.
 *
 * Entries are ordered and immutable by `(api, sourceRevision, checksum)`. The client must fetch
 * and verify every referenced source before it activates this manifest.
 */
@Serializable
data class SourceCatalogManifest(
    val schemaVersion: Int,
    val sourceSchemaVersion: Int,
    val catalogRevision: Long,
    val generatedAt: String,
    val sources: List<SourceCatalogEntry>,
    val removedSources: List<RemovedSourceEntry> = emptyList(),
)

/** One source revision referenced by a signed [SourceCatalogManifest]. */
@Serializable
data class SourceCatalogEntry(
    val api: String,
    val sourceRevision: Long,
    val checksum: String,
    val order: Int,
    val lifecycle: String,
    val engine: String,
    val sourceSigningKeyId: String,
    val sourceSignature: String,
)

/** Signed tombstone for an API that must not resolve to a source client. */
@Serializable
data class RemovedSourceEntry(
    val api: String,
    val lifecycle: String,
)

/** Exact signed manifest response retained for verification after every process restart. */
@Serializable
data class SignedSourceCatalogManifest(
    val payload: String,
    val metadata: ConfigSignatureMetadata,
)

/** Exact immutable source bytes and authenticated response metadata. */
@Serializable
data class SourceRevisionArtifact(
    val api: String,
    val sourceRevision: Long,
    val checksum: String,
    val canonVersion: String,
    val payload: String,
)

/** Complete persisted catalog. A store must expose either all of it or none of it. */
data class StoredSourceCatalog(
    val manifest: SignedSourceCatalogManifest,
    val sources: List<SourceRevisionArtifact>,
)

/** Durable anti-rollback floor retained even if cached payload bytes later become corrupt. */
data class SourceCatalogAcceptanceFloor(
    val catalogRevision: Long,
    val checksum: String,
)

/** Conditional manifest result. `NotModified` never requires source payload requests. */
sealed interface SourceCatalogManifestResult {
    /** Remote delivery is intentionally disabled for this build. */
    data object Unavailable : SourceCatalogManifestResult

    data object NotModified : SourceCatalogManifestResult

    data class Modified(val manifest: SignedSourceCatalogManifest) : SourceCatalogManifestResult
}

/** Bounded transport for the v2 manifest and immutable per-source revisions. */
interface RemoteSourceCatalog {
    suspend fun fetchManifest(etag: String?): SourceCatalogManifestResult

    suspend fun fetchSource(entry: SourceCatalogEntry): SourceRevisionArtifact
}

/**
 * Durable all-or-nothing catalog storage. Immutable SOURCE rows may be staged; historical manifest
 * lookup exposes only successfully ACCEPTED manifests. No unchecked project/activate bypass exists.
 */
interface SourceCatalogStore {
    fun readBundled(): String?

    /** Identity of the exact current embedded bytes, never of a historical unsigned DB copy. */
    fun bundledIdentity(document: SourceConfigDocument): SelectedCatalogIdentity

    suspend fun readActive(): StoredSourceCatalog?
    suspend fun readAcceptanceFloor(): SourceCatalogAcceptanceFloor?
    suspend fun readAcceptedManifest(): SignedSourceCatalogManifest?
    suspend fun readAcceptedManifest(identity: SelectedCatalogIdentity): SignedSourceCatalogManifest?
    suspend fun findSource(api: String, sourceRevision: Long, checksum: String): SourceRevisionArtifact?
    suspend fun readSelection(): SourceSelectionRead

    /** Call outside a transaction; expected state, queue/ownership and every durable write share one owning writer. */
    suspend fun commitSelection(
        candidate: VerifiedSourceSelection,
        expected: SourceSelectionExpectation,
    ): CommittedSourceSelection

    /**
     * Call outside a transaction. Only matching process-verified committed bytes may be adopted.
     * Invoke the non-suspending callback AFTER successful transaction completion, retaining the
     * writer lease so a later winner cannot precede stale publication. Never bootstrap, suspend
     * or call another repository from that callback.
     */
    suspend fun adoptSelection(
        candidate: VerifiedSourceSelection,
        publish: (CommittedSourceSelection) -> Unit,
    ): CommittedSourceSelection?

    /** Clears process readiness, not durable selection/floor. Must invalidate identity observers. */
    fun invalidateSelection()
}

/** Verifies the manifest and each source revision against app-pinned Ed25519 keys. */
interface SourceCatalogSignatureVerifier {
    fun verifyManifest(manifest: SignedSourceCatalogManifest): Boolean

    fun verifySource(
        entry: SourceCatalogEntry,
        artifact: SourceRevisionArtifact,
    ): Boolean
}
