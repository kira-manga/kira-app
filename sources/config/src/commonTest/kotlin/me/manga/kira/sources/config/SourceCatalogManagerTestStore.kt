package me.manga.kira.sources.config

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

/** Controlled lifecycle fake, NOT persistence/cryptography/queue-exclusion proof. */
internal class FakeCatalogStore(
    private var active: StoredSourceCatalog?,
    floor: SourceCatalogAcceptanceFloor? = active?.let { SourceCatalogAcceptanceFloor(it.manifest.metadata.revision, it.manifest.metadata.checksum) },
    private val failActivation: Boolean = false,
) : SourceCatalogStore {
    private val accepted = active?.let { mutableMapOf(signedIdentity(it.manifest) to it.manifest) } ?: mutableMapOf()
    private val artifacts = active?.sources?.associateBy { Triple(it.api, it.sourceRevision, it.checksum) }?.toMutableMap() ?: mutableMapOf()
    private var acceptanceFloor = floor
    var bundleJson = bundledJson()
    var nextGeneration: Long? = 1L
    var selection: CommittedSourceSelection? = null
        private set
    var ready: CommittedSourceSelection? = null
        private set
    var activationCount = 0
        private set
    var bundleProjectionCount = 0
        private set
    var historicalReads = 0
        private set
    var beforeCommit: suspend (VerifiedSourceSelection) -> Unit = {}
    var afterCommit: suspend (VerifiedSourceSelection) -> Unit = {}
    var beforeAdopt: suspend (VerifiedSourceSelection) -> Unit = {}

    override fun readBundled(): String = bundleJson
    override fun bundledIdentity(document: SourceConfigDocument) =
        SelectedCatalogIdentity(SelectedCatalogKind.BUNDLED, document.revision, syntheticDigest(bundleJson))
    override suspend fun readActive(): StoredSourceCatalog? = active
    override suspend fun readAcceptanceFloor(): SourceCatalogAcceptanceFloor? = acceptanceFloor
    override suspend fun readAcceptedManifest(): SignedSourceCatalogManifest? = acceptanceFloor?.let {
        accepted[SelectedCatalogIdentity(SelectedCatalogKind.SIGNED, it.catalogRevision, it.checksum)]
    }
    override suspend fun readAcceptedManifest(identity: SelectedCatalogIdentity): SignedSourceCatalogManifest? {
        historicalReads++
        return accepted[identity]
    }
    override suspend fun findSource(api: String, sourceRevision: Long, checksum: String): SourceRevisionArtifact? =
        artifacts[Triple(api, sourceRevision, checksum)]

    override suspend fun readSelection(): SourceSelectionRead {
        val next = nextGeneration ?: return SourceSelectionRead(null, null)
        val expected = SourceSelectionExpectation(next, selection?.token?.generation, selection?.token?.payloadDigest, acceptanceFloor)
        return SourceSelectionRead(expected, selection?.takeIf { it.token.generation == next - 1 })
    }

    override suspend fun commitSelection(candidate: VerifiedSourceSelection, expected: SourceSelectionExpectation): CommittedSourceSelection {
        beforeCommit(candidate)
        if (readSelection().expected != expected) throw SourceSelectionUnavailable("stale preparation")
        selection?.takeIf { it.payload == candidate.payload && it.token.generation == expected.nextGeneration - 1 }?.let { return it }
        check(expected.nextGeneration in 1 until Long.MAX_VALUE)
        if (candidate.advancesSignedFloor) accept(candidate)
        if (candidate.payload.identity.kind == SelectedCatalogKind.BUNDLED) bundleProjectionCount++
        val token = SourceSelectionToken(expected.nextGeneration, candidate.payload.identity, syntheticDigest(candidate.payload))
        val receipt = CommittedSourceSelection(token, candidate.payload)
        selection = receipt
        nextGeneration = expected.nextGeneration + 1
        afterCommit(candidate)
        return receipt
    }

    private fun accept(candidate: VerifiedSourceSelection) {
        if (failActivation) error("simulated projection failure")
        val catalog = requireNotNull(candidate.catalog)
        val identity = candidate.payload.identity
        acceptanceFloor?.let { check(identity.revision >= it.catalogRevision) }
        active = catalog
        acceptHistory(catalog.manifest)
        catalog.sources.forEach(::stage)
        acceptanceFloor = SourceCatalogAcceptanceFloor(identity.revision, identity.checksum)
        activationCount++
    }

    override suspend fun adoptSelection(candidate: VerifiedSourceSelection, publish: (CommittedSourceSelection) -> Unit): CommittedSourceSelection? {
        beforeAdopt(candidate)
        return readSelection().selection?.takeIf { it.payload == candidate.payload }?.also { publish(it); ready = it }
    }

    override fun invalidateSelection() { ready = null }
    fun stage(artifact: SourceRevisionArtifact) { artifacts[Triple(artifact.api, artifact.sourceRevision, artifact.checksum)] = artifact }
    fun acceptHistory(manifest: SignedSourceCatalogManifest) { accepted[signedIdentity(manifest)] = manifest }
    fun simulateUnreadableActiveCatalog() { active = null }
    fun loseSelectedPayload() { selection = null; invalidateSelection() }
}
