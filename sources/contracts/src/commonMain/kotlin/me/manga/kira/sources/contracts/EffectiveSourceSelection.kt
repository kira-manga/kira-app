package me.manga.kira.sources.contracts

import kotlinx.serialization.Serializable
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/** Local identity, deliberately separate from acquisition diagnostics and the signed rollback floor. */
@Serializable
enum class SelectedCatalogKind { BUNDLED, SIGNED }

@Serializable
data class SelectedCatalogIdentity(val kind: SelectedCatalogKind, val revision: Long, val checksum: String) {
    init {
        require(revision >= 0 && (kind != SelectedCatalogKind.SIGNED || revision > 0))
        require(SELECTION_DIGEST.matches(checksum))
    }
}

/** A generation is assigned only by a successful durable selection commit. No compatibility default. */
data class SourceSelectionToken(
    val generation: Long,
    val identity: SelectedCatalogIdentity,
    val payloadDigest: String,
) {
    init {
        require(generation > 0)
        require(SELECTION_DIGEST.matches(payloadDigest))
    }
}

@Serializable
data class SelectedSourceRevision(val revision: Long, val checksum: String) {
    init {
        require(revision > 0 && SELECTION_DIGEST.matches(checksum))
    }
}

/** A signed reference names an ACCEPTED manifest, not an orphan/staged immutable artifact. */
@Serializable
data class SourceSelectionProofRef(
    val catalog: SelectedCatalogIdentity,
    val api: String,
    val source: SelectedSourceRevision?,
) {
    init {
        require(api.isNotBlank())
        require((catalog.kind == SelectedCatalogKind.SIGNED) == (source != null))
    }
}

@Serializable
enum class PreviousHostAuthority { UNKNOWN, PROVEN_COMPATIBLE_ROOT, PROVEN_INCOMPATIBLE }

@Serializable
data class SelectedPreviousHost(
    val host: String,
    val authority: PreviousHostAuthority,
    val proof: SourceSelectionProofRef?,
) {
    init {
        require(host.isNotBlank())
        require((authority == PreviousHostAuthority.UNKNOWN) == (proof == null))
    }
}

@Serializable
data class SelectedSourceAliasRule(
    val api: String,
    val currentBaseUrl: String,
    val currentProof: SourceSelectionProofRef,
    val previousHosts: List<SelectedPreviousHost>,
)

/** Complete finite payload; its digest covers these exact encoded bytes, not backend kcj-1 bytes. */
@Serializable
data class EffectiveSourceSelectionPayload(
    val version: Int,
    val identity: SelectedCatalogIdentity,
    val rules: List<SelectedSourceAliasRule>,
)

/** Process-verified evidence retained with a warm selection. Bundles never invent signed revisions. */
data class VerifiedSourceSelectionProof(
    val reference: SourceSelectionProofRef,
    val descriptor: SourceConfig,
    val manifest: SignedSourceCatalogManifest?,
    val artifact: SourceRevisionArtifact?,
)

/** Prepared outside the writer. The store freezes the payload before acquiring queue exclusion. */
data class VerifiedSourceSelection(
    val document: SourceConfigDocument,
    val payload: EffectiveSourceSelectionPayload,
    val catalog: StoredSourceCatalog?,
    val proofs: List<VerifiedSourceSelectionProof>,
    val advancesSignedFloor: Boolean,
)

/** Expected durable state, including corrupt/missing selected-body recovery with a healthy allocator. */
data class SourceSelectionExpectation(
    val nextGeneration: Long,
    val selectedGeneration: Long?,
    val selectedDigest: String?,
    val signedFloor: SourceCatalogAcceptanceFloor?,
)

data class CommittedSourceSelection(val token: SourceSelectionToken, val payload: EffectiveSourceSelectionPayload)

/** Null expected means allocator unavailable; null selection never means an authenticated empty catalog. */
data class SourceSelectionRead(
    val expected: SourceSelectionExpectation?,
    val selection: CommittedSourceSelection?,
)

class SourceSelectionUnavailable(message: String) : IllegalStateException(message)

/** Local persistence/verification budgets, not a source wire-schema or backend canonicalization change. */
object SourceSelectionLimits {
    const val PAYLOAD_VERSION = 1
    const val PAYLOAD_BYTES = 4 * 1024 * 1024
    const val MANIFEST_BYTES = 5 * 1024 * 1024
    const val ARTIFACT_BYTES = 256 * 1024
    const val PROOF_REFERENCES = 2048
    const val EVIDENCE_BYTES = 32L * 1024 * 1024
    const val SOURCES = 512
    const val PREVIOUS_HOSTS = 256
}

private val SELECTION_DIGEST = Regex("[0-9a-f]{64}")
