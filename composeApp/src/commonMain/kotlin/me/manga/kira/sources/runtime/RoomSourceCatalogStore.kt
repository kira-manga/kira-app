package me.manga.kira.sources.runtime

import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.dao.SourceCatalogDao
import me.manga.kira.data.local.entity.SourceCatalogEntryEntity
import me.manga.kira.data.local.entity.SourceCatalogManifestEntity
import me.manga.kira.data.local.entity.SourceRevisionArtifactEntity
import me.manga.kira.sources.contracts.CommittedSourceSelection
import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import me.manga.kira.sources.contracts.SourceSelectionExpectation
import me.manga.kira.sources.contracts.SourceSelectionLimits
import me.manga.kira.sources.contracts.SourceSelectionRead
import me.manga.kira.sources.contracts.StoredSourceCatalog
import me.manga.kira.sources.contracts.VerifiedSourceSelection
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/** Bounded accepted-history adapter; all projection/selection mutation has one required coordinator. */
class RoomSourceCatalogStore(
    private val catalogDao: SourceCatalogDao,
    private val selections: RoomSourceSelectionCommit,
    private val bundledJson: String,
) : SourceCatalogStore {
    override fun readBundled(): String = bundledJson
    override fun bundledIdentity(document: SourceConfigDocument) = SelectedCatalogIdentity(
        SelectedCatalogKind.BUNDLED, document.revision, EffectiveSourceSelectionCodec.digest(bundledJson),
    )

    override suspend fun readActive(): StoredSourceCatalog? {
        val floor = readAcceptanceFloor() ?: return null
        val signed = readAcceptedManifest() ?: return null
        if (signed.metadata.revision != floor.catalogRevision || signed.metadata.checksum != floor.checksum) return null
        val manifest = (SourceConfigParser.parseManifest(signed.payload) as? AppResult.Success)?.value ?: return null
        if (manifest.sources.size > SourceSelectionLimits.SOURCES) return null
        val artifacts = mutableListOf<SourceRevisionArtifact>()
        var bytes = signed.payload.encodeToByteArray().size.toLong()
        for (entry in manifest.sources.filter { it.lifecycle == "active" }) {
            val artifact = findSource(entry.api, entry.sourceRevision, entry.checksum) ?: return null
            bytes += artifact.payload.encodeToByteArray().size
            if (bytes > SourceSelectionLimits.EVIDENCE_BYTES) return null
            artifacts += artifact
        }
        return StoredSourceCatalog(signed, artifacts)
    }

    override suspend fun findSource(api: String, sourceRevision: Long, checksum: String): SourceRevisionArtifact? =
        catalogDao.source(api, sourceRevision, checksum)?.toContract()

    override suspend fun readAcceptanceFloor(): SourceCatalogAcceptanceFloor? = selections.floor()
    override suspend fun readAcceptedManifest(): SignedSourceCatalogManifest? = catalogDao.activeManifest()?.toContract()
    override suspend fun readAcceptedManifest(identity: SelectedCatalogIdentity): SignedSourceCatalogManifest? {
        require(identity.kind == SelectedCatalogKind.SIGNED)
        return catalogDao.manifestByRevision(identity.revision)?.takeIf { it.checksum == identity.checksum }?.toContract()
    }

    override suspend fun readSelection(): SourceSelectionRead = selections.read()
    override suspend fun commitSelection(candidate: VerifiedSourceSelection, expected: SourceSelectionExpectation): CommittedSourceSelection =
        selections.commit(candidate, expected)
    override suspend fun adoptSelection(candidate: VerifiedSourceSelection, publish: (CommittedSourceSelection) -> Unit): CommittedSourceSelection? =
        selections.adopt(candidate, publish)
    override fun invalidateSelection() = selections.readiness.invalidate()
}

internal fun SignedSourceCatalogManifest.toEntity() = SourceCatalogManifestEntity(
    catalogRevision = metadata.revision, rawPayload = payload, format = metadata.format, algorithm = metadata.algorithm,
    signingKeyId = metadata.keyId, signatureBase64 = metadata.signatureBase64, checksum = metadata.checksum,
    createdAt = metadata.createdAt, previousRevision = metadata.previousRevision, previousChecksum = metadata.previousChecksum,
)

internal fun SourceCatalogManifestEntity.toContract() = SignedSourceCatalogManifest(
    rawPayload, ConfigSignatureMetadata(
        format = format, algorithm = algorithm, keyId = signingKeyId, signatureBase64 = signatureBase64,
        revision = catalogRevision, checksum = checksum, createdAt = createdAt,
        previousRevision = previousRevision, previousChecksum = previousChecksum,
    ),
)

internal fun SourceRevisionArtifact.toEntity() = SourceRevisionArtifactEntity(api, sourceRevision, checksum, canonVersion, payload)
internal fun SourceRevisionArtifactEntity.toContract() = SourceRevisionArtifact(api, sourceRevision, checksum, canonVersion, rawPayload)
internal fun SourceCatalogEntry.toEntity(catalogRevision: Long) = SourceCatalogEntryEntity(
    catalogRevision, api, sourceRevision, checksum, order, lifecycle, engine, sourceSigningKeyId, sourceSignature,
)

internal fun requireSameImmutableSourceRevision(existing: SourceRevisionArtifactEntity, candidate: SourceRevisionArtifact) {
    require(existing.toContract() == candidate) { "immutable source revision conflict" }
}
