package me.manga.kira.sources.runtime

import me.manga.kira.sources.contracts.CommittedSourceSelection
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import me.manga.kira.sources.contracts.SourceSelectionExpectation
import me.manga.kira.sources.contracts.SourceSelectionRead
import me.manga.kira.sources.contracts.SourceSelectionToken
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import me.manga.kira.sources.contracts.StoredSourceCatalog
import me.manga.kira.sources.contracts.VerifiedSourceSelection
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/** Shared manager test port with the real local codec; NOT Room/queue/process-death evidence. */
internal open class SourceSelectionTestStore(private val bundle: String) : SourceCatalogStore {
    private var active: StoredSourceCatalog? = null
    private var floor: SourceCatalogAcceptanceFloor? = null
    private val accepted = mutableMapOf<SelectedCatalogIdentity, SignedSourceCatalogManifest>()
    private val artifacts = mutableMapOf<Triple<String, Long, String>, SourceRevisionArtifact>()
    private var next = 1L
    var selection: CommittedSourceSelection? = null
        private set
    var ready: CommittedSourceSelection? = null
        private set
    var activationCount = 0
        private set
    var bundleProjectionCount = 0
        private set
    var bundledProjection: SourceConfigDocument? = null
        private set

    override fun readBundled(): String = bundle
    override fun bundledIdentity(document: SourceConfigDocument) =
        SelectedCatalogIdentity(SelectedCatalogKind.BUNDLED, document.revision, EffectiveSourceSelectionCodec.digest(bundle))
    override suspend fun readActive(): StoredSourceCatalog? = active
    override suspend fun readAcceptanceFloor(): SourceCatalogAcceptanceFloor? = floor
    override suspend fun readAcceptedManifest(): SignedSourceCatalogManifest? = floor?.let {
        accepted[SelectedCatalogIdentity(SelectedCatalogKind.SIGNED, it.catalogRevision, it.checksum)]
    }
    override suspend fun readAcceptedManifest(identity: SelectedCatalogIdentity): SignedSourceCatalogManifest? = accepted[identity]
    override suspend fun findSource(api: String, sourceRevision: Long, checksum: String): SourceRevisionArtifact? =
        artifacts[Triple(api, sourceRevision, checksum)]
    override suspend fun readSelection() = SourceSelectionRead(
        SourceSelectionExpectation(next, selection?.token?.generation, selection?.token?.payloadDigest, floor), selection,
    )

    override suspend fun commitSelection(candidate: VerifiedSourceSelection, expected: SourceSelectionExpectation): CommittedSourceSelection {
        if (readSelection().expected != expected) throw SourceSelectionUnavailable("stale test preparation")
        selection?.takeIf { it.payload == candidate.payload }?.let { return it }
        check(next < Long.MAX_VALUE)
        val raw = EffectiveSourceSelectionCodec.encode(candidate.payload)
        val digest = EffectiveSourceSelectionCodec.digest(raw)
        if (candidate.advancesSignedFloor) accept(candidate)
        if (candidate.payload.identity.kind == SelectedCatalogKind.BUNDLED) {
            bundledProjection = candidate.document
            bundleProjectionCount++
        }
        val receipt = CommittedSourceSelection(SourceSelectionToken(next++, candidate.payload.identity, digest),
            EffectiveSourceSelectionCodec.decode(raw, digest))
        selection = receipt
        return receipt
    }

    private fun accept(candidate: VerifiedSourceSelection) {
        val catalog = requireNotNull(candidate.catalog)
        val identity = candidate.payload.identity
        accepted[identity]?.let { check(it == catalog.manifest) }
        catalog.sources.forEach { artifact ->
            val key = Triple(artifact.api, artifact.sourceRevision, artifact.checksum)
            artifacts[key]?.let { check(it == artifact) }
            artifacts[key] = artifact
        }
        accepted[identity] = catalog.manifest
        active = catalog
        floor = SourceCatalogAcceptanceFloor(identity.revision, identity.checksum)
        activationCount++
    }

    override suspend fun adoptSelection(candidate: VerifiedSourceSelection, publish: (CommittedSourceSelection) -> Unit): CommittedSourceSelection? =
        selection?.takeIf { it.payload == candidate.payload }?.also { publish(it); ready = it }
    override fun invalidateSelection() { ready = null }
}
