package me.manga.kira.sources.config

import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SelectedSourceRevision
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogSignatureVerifier
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import me.manga.kira.sources.contracts.SourceSelectionLimits
import me.manga.kira.sources.contracts.SourceSelectionProofRef
import me.manga.kira.sources.contracts.StoredSourceCatalog
import me.manga.kira.sources.contracts.VerifiedSourceSelectionProof
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/** Complete artifacts/evidence survive warm retention; a Document alone is not a verified selection. */
internal data class VerifiedCatalog(
    val document: SourceConfigDocument,
    val stored: StoredSourceCatalog?,
    val manifest: SourceCatalogManifest?,
    val proofs: List<VerifiedSourceSelectionProof>,
    val identity: SelectedCatalogIdentity,
)

internal class SourceCatalogVerifier(
    private val store: SourceCatalogStore,
    private val signatures: SourceCatalogSignatureVerifier,
    private val validator: SourceConfigValidator,
    private val rejected: (String) -> Unit,
) {
    fun bundle(): VerifiedCatalog {
        val raw = requireNotNull(store.readBundled())
        val document = (SourceConfigParser.parse(raw) as? AppResult.Success)?.value
            ?: error("bundled source catalog is invalid")
        require(document.sources.isNotEmpty() && document.sources.all { it.engine == "generic" && it.lifecycle == "active" })
        require(validator.validate(document).isValid)
        val identity = store.bundledIdentity(document)
        val proofs = document.sources.map { source ->
            VerifiedSourceSelectionProof(SourceSelectionProofRef(identity, source.api, null), source, null, null)
        }
        return VerifiedCatalog(document, null, null, proofs, identity)
    }

    fun manifest(signed: SignedSourceCatalogManifest): SourceCatalogManifest? {
        if (!bounded(signed.payload, SourceSelectionLimits.MANIFEST_BYTES) || !signatures.verifyManifest(signed)) {
            return reject("manifest signature is invalid")
        }
        val parsed = (SourceConfigParser.parseManifest(signed.payload) as? AppResult.Success)?.value
            ?: return reject("manifest JSON is invalid")
        val errors = manifestErrors(parsed, signed)
        return parsed.takeIf { errors.isEmpty() } ?: reject(errors.joinToString())
    }

    fun source(entry: SourceCatalogEntry, artifact: SourceRevisionArtifact): SourceConfig? {
        if (artifact.api != entry.api || artifact.sourceRevision != entry.sourceRevision ||
            artifact.checksum != entry.checksum || artifact.canonVersion != "kcj-1"
        ) return null
        if (!bounded(artifact.payload, SourceSelectionLimits.ARTIFACT_BYTES) || !signatures.verifySource(entry, artifact)) return null
        val parsed = (SourceConfigParser.parseSource(artifact.payload) as? AppResult.Success)?.value ?: return null
        return parsed.takeIf { it.api == entry.api && it.engine == "generic" }
            ?.copy(lifecycle = entry.lifecycle, priority = entry.order)
    }

    fun stored(stored: StoredSourceCatalog): VerifiedCatalog? {
        if (!boundedStoredEvidence(stored)) return reject("catalog evidence exceeds local budget")
        val parsed = manifest(stored.manifest) ?: return null
        val entries = parsed.sources.filter { it.lifecycle == "active" }
        if (stored.sources.size != entries.size) return reject("catalog is incomplete")
        val byApi = stored.sources.associateBy { it.api }
        if (byApi.size != entries.size) return reject("catalog is incomplete")
        val configs = entries.map { entry -> source(entry, byApi[entry.api] ?: return null) ?: return reject("source verification failed") }
        val document = SourceConfigDocument(parsed.sourceSchemaVersion, parsed.generatedAt, parsed.catalogRevision, configs)
        if (!validator.validate(document).isValid) return reject("catalog validation failed")
        val identity = signedIdentity(stored.manifest)
        val proofs = configs.map { config -> signedProof(identity, config, stored.manifest, byApi.getValue(config.api)) }
        requireEvidenceBound(proofs)
        return VerifiedCatalog(document, stored, parsed, proofs, identity)
    }

    /** Only exact references already retained by a selected payload are discovered; no history scan. */
    suspend fun historical(refs: List<SourceSelectionProofRef>, bundle: VerifiedCatalog): List<VerifiedSourceSelectionProof> {
        require(refs.distinct().size <= SourceSelectionLimits.PROOF_REFERENCES)
        val history = HistoricalEvidenceReads()
        val result = mutableListOf<VerifiedSourceSelectionProof>()
        for (ref in refs.distinct()) {
            val proof = if (ref.catalog.kind == SelectedCatalogKind.BUNDLED) {
                bundle.proofs.singleOrNull { it.reference == ref }
            } else {
                historicalSigned(ref, history)
            }
            if (proof != null) { result += proof; requireEvidenceBound(result) }
        }
        return result
    }

    private suspend fun historicalSigned(
        ref: SourceSelectionProofRef,
        history: HistoricalEvidenceReads,
    ): VerifiedSourceSelectionProof? {
        val accepted = history.accepted
        if (!accepted.containsKey(ref.catalog)) {
            val raw = store.readAcceptedManifest(ref.catalog)
            accepted[ref.catalog] = raw?.let { signed ->
                history.consume(signed.payload, SourceSelectionLimits.MANIFEST_BYTES)
                if (signedIdentity(signed) != ref.catalog) null else manifest(signed)?.let { signed to it }
            }
        }
        val (signed, parsed) = accepted[ref.catalog] ?: return null
        val entry = parsed.sources.singleOrNull { it.api == ref.api && it.lifecycle == "active" } ?: return null
        if (ref.source != SelectedSourceRevision(entry.sourceRevision, entry.checksum)) return null
        val artifact = store.findSource(ref.api, entry.sourceRevision, entry.checksum) ?: return null
        history.consume(artifact.payload, SourceSelectionLimits.ARTIFACT_BYTES)
        val config = source(entry, artifact) ?: return null
        val document = SourceConfigDocument(parsed.sourceSchemaVersion, parsed.generatedAt, parsed.catalogRevision, listOf(config))
        if (!validator.validate(document).isValid) return null
        return signedProof(ref.catalog, config, signed, artifact)
    }

    private fun <T> reject(reason: String): T? { rejected(reason); return null }
}

/** Counts failed/invalid reads too, before signature work or cache retention; no recursive history. */
private class HistoricalEvidenceReads {
    val accepted = mutableMapOf<SelectedCatalogIdentity, Pair<SignedSourceCatalogManifest, SourceCatalogManifest>?>()
    private var remaining = SourceSelectionLimits.EVIDENCE_BYTES

    fun consume(raw: String, limit: Int) {
        require(bounded(raw, limit))
        remaining -= raw.encodeToByteArray().size
        require(remaining >= 0) { "historical evidence exceeds local budget" }
    }
}

private fun boundedStoredEvidence(stored: StoredSourceCatalog): Boolean {
    if (stored.sources.size > SourceSelectionLimits.SOURCES ||
        !bounded(stored.manifest.payload, SourceSelectionLimits.MANIFEST_BYTES)
    ) return false
    var bytes = stored.manifest.payload.encodeToByteArray().size.toLong()
    for (artifact in stored.sources) {
        if (!bounded(artifact.payload, SourceSelectionLimits.ARTIFACT_BYTES)) return false
        bytes += artifact.payload.encodeToByteArray().size
        if (bytes > SourceSelectionLimits.EVIDENCE_BYTES) return false
    }
    return true
}

internal fun signedIdentity(signed: SignedSourceCatalogManifest) = SelectedCatalogIdentity(
    SelectedCatalogKind.SIGNED, signed.metadata.revision, signed.metadata.checksum,
)

private fun signedProof(
    identity: SelectedCatalogIdentity,
    config: SourceConfig,
    manifest: SignedSourceCatalogManifest,
    artifact: SourceRevisionArtifact,
) = VerifiedSourceSelectionProof(
    SourceSelectionProofRef(identity, config.api, SelectedSourceRevision(artifact.sourceRevision, artifact.checksum)),
    config, manifest, artifact,
)

internal fun bounded(raw: String, maxBytes: Int): Boolean = raw.length <= maxBytes && raw.encodeToByteArray().size <= maxBytes

internal fun requireEvidenceBound(proofs: List<VerifiedSourceSelectionProof>) {
    require(proofs.map { it.reference }.distinct().size <= SourceSelectionLimits.PROOF_REFERENCES)
    val manifests = proofs.mapNotNull { it.manifest }.distinctBy { signedIdentity(it) }
    val artifacts = proofs.mapNotNull { it.artifact }.distinctBy { Triple(it.api, it.sourceRevision, it.checksum) }
    val bytes = manifests.sumOf { it.payload.encodeToByteArray().size.toLong() } +
        artifacts.sumOf { it.payload.encodeToByteArray().size.toLong() }
    require(bytes <= SourceSelectionLimits.EVIDENCE_BYTES) { "selection evidence exceeds local budget" }
}
