package me.manga.kira.sources.runtime

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.ensureMangaWriteActive
import me.manga.kira.data.local.withMangaWriteCancellation
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.EffectiveSourceSelectionDao
import me.manga.kira.data.local.dao.SourceCatalogDao
import me.manga.kira.data.local.entity.ActiveSourceCatalogEntity
import me.manga.kira.data.local.entity.EffectiveSourceSelectionEntity
import me.manga.kira.sources.contracts.CommittedSourceSelection
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SelectedSourceRevision
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogManifest
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceSelectionExpectation
import me.manga.kira.sources.contracts.SourceSelectionLimits
import me.manga.kira.sources.contracts.SourceSelectionRead
import me.manga.kira.sources.contracts.SourceSelectionToken
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import me.manga.kira.sources.contracts.VerifiedSourceSelection
import me.manga.kira.sources.contracts.VerifiedSourceSelectionProof
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.sourceBaseUrlHost

/** One durable authority; no allocator creation, publication-before-commit or unchecked legacy activation. */
class RoomSourceSelectionCommit(
    private val database: MangaDatabase,
    private val catalog: SourceCatalogDao,
    private val selected: EffectiveSourceSelectionDao,
    private val projection: RoomSourceCatalogProjection,
    private val migration: SourceCatalogSelectionMigration,
) {
    internal val readiness = SourceSelectionReadiness()
    internal val invalidations = combine(selected.selectionInvalidations(), selected.allocatorInvalidations()) { _, _ -> Unit }

    suspend fun read(): SourceSelectionRead = database.useReaderConnection { it.deferredTransaction { readInTransaction() } }

    /** No connection acquisition here: repository providers inherit their CALLER'S owning writer. */
    internal suspend fun readInTransaction(): SourceSelectionRead {
        val next = selected.nextGeneration() ?: return SourceSelectionRead(null, null)
        val expected = SourceSelectionExpectation(next, selected.selectedGeneration(), selected.selectedDigest(), floor())
        val row = selected.boundedSelection(SourceSelectionLimits.PAYLOAD_BYTES) ?: return SourceSelectionRead(expected, null)
        if (row.generation != next - 1) return SourceSelectionRead(expected, null)
        val payload = try { EffectiveSourceSelectionCodec.decode(row.payload, row.payloadDigest) } catch (_: IllegalArgumentException) {
            return SourceSelectionRead(expected, null)
        }
        val token = SourceSelectionToken(row.generation, payload.identity, row.payloadDigest)
        return SourceSelectionRead(expected, CommittedSourceSelection(token, payload))
    }

    internal suspend fun floor(): SourceCatalogAcceptanceFloor? {
        val row = catalog.activePointer()
        if (row == null && catalog.activePointerExists()) throw SourceSelectionUnavailable("signed floor corrupt")
        if (row != null && !Regex("[0-9a-f]{64}").matches(row.checksum)) throw SourceSelectionUnavailable("signed floor corrupt")
        return row?.let { SourceCatalogAcceptanceFloor(it.catalogRevision, it.checksum) }
    }

    suspend fun commit(candidate: VerifiedSourceSelection, expected: SourceSelectionExpectation): CommittedSourceSelection =
        withMangaWriteCancellation {
            val prepared = prepare(candidate)
            migration.withQuiescentSelection(prepared.candidate) {
                database.useWriterConnection { connection ->
                    if (connection.inTransaction()) throw SourceSelectionUnavailable("selection commit cannot nest inside a caller transaction")
                    connection.immediateTransaction {
                        ensureMangaWriteActive()
                        val committed = write(prepared, expected)
                        ensureMangaWriteActive()
                        committed
                    }
                }
            }
        }

    /** Finish the read-only transaction before publication, but retain its writer lease against later winners. */
    suspend fun adopt(candidate: VerifiedSourceSelection, publish: (CommittedSourceSelection) -> Unit): CommittedSourceSelection? {
        val prepared = prepare(candidate)
        return database.useWriterConnection { connection ->
            if (connection.inTransaction()) throw SourceSelectionUnavailable("selection adoption cannot nest inside a caller transaction")
            val current = connection.immediateTransaction<CommittedSourceSelection?> { readInTransaction().selection } ?: return@useWriterConnection null
            if (current.payload != prepared.candidate.payload || current.token.payloadDigest != prepared.digest) return@useWriterConnection null
            publish(current)
            readiness.publish(current, prepared.raw)
            current
        }
    }

    private suspend fun write(prepared: PreparedRoomSelection, expected: SourceSelectionExpectation): CommittedSourceSelection {
        val actual = readInTransaction()
        if (actual.expected != expected) throw SourceSelectionUnavailable("stale selection preparation")
        check(selected.selectionCount() <= 1) { "invalid selection singleton" }
        val existing = actual.selection
        if (existing != null && existing.payload == prepared.candidate.payload && existing.token.payloadDigest == prepared.digest) return existing
        check(expected.nextGeneration < Long.MAX_VALUE) { "selection generation exhausted" }
        val token = SourceSelectionToken(expected.nextGeneration, prepared.candidate.payload.identity, prepared.digest)
        checkEvidence(prepared.candidate)
        checkFloor(prepared.candidate, expected.signedFloor)
        migration.migrateInTransaction(prepared.candidate, token)
        persistCatalog(prepared)
        projection.project(prepared.candidate.document.sources)
        check(selected.advance(expected.nextGeneration, expected.nextGeneration + 1) == 1)
        selected.setSelection(EffectiveSourceSelectionEntity(generation = token.generation, payload = prepared.raw, payloadDigest = prepared.digest))
        return CommittedSourceSelection(token, prepared.candidate.payload)
    }

    private fun prepare(candidate: VerifiedSourceSelection): PreparedRoomSelection {
        val raw = EffectiveSourceSelectionCodec.encode(candidate.payload)
        val digest = EffectiveSourceSelectionCodec.digest(raw)
        val frozen = freezeCandidate(candidate.copy(payload = EffectiveSourceSelectionCodec.decode(raw, digest)))
        val manifests = prepareEvidence(frozen)
        require(frozen.document.revision == frozen.payload.identity.revision)
        require(frozen.document.sources.map { it.api } == frozen.payload.rules.map { it.api })
        frozen.payload.rules.forEach { rule ->
            val source = frozen.document.sources.single { it.api == rule.api }
            require(frozen.proofs.single { it.reference == rule.currentProof }.descriptor == source)
            require(rule.currentBaseUrl == source.baseUrl)
            require(rule.previousHosts.map { it.host }.toSet() == source.previousHosts.map { it.lowercase() }.toSet())
            rule.previousHosts.forEach { host ->
                host.proof?.let { ref -> require(sourceBaseUrlHost(frozen.proofs.single { it.reference == ref }.descriptor.baseUrl) == host.host) }
            }
        }
        val manifest = manifests[frozen.payload.identity]
        requireCompleteCatalog(frozen, manifest)
        return PreparedRoomSelection(frozen, raw, digest, manifest)
    }

    private suspend fun checkEvidence(candidate: VerifiedSourceSelection) {
        val manifests = (listOfNotNull(candidate.catalog?.manifest) + candidate.proofs.mapNotNull { it.manifest }).distinct()
        for (manifest in manifests) {
            val installing = candidate.advancesSignedFloor && manifest.identity() == candidate.payload.identity
            val accepted = catalog.manifestByRevision(manifest.metadata.revision)
            if (accepted == null) check(installing && !catalog.manifestExists(manifest.metadata.revision))
            else require(accepted.toContract() == manifest) { "immutable accepted manifest changed" }
        }
        candidate.proofs.forEach { proof ->
            if (proof.reference.catalog.kind == SelectedCatalogKind.SIGNED) checkSourceEvidence(candidate, proof)
        }
    }

    private suspend fun checkSourceEvidence(candidate: VerifiedSourceSelection, proof: VerifiedSourceSelectionProof) {
        val artifact = requireNotNull(proof.artifact)
        val installing = candidate.advancesSignedFloor && proof.reference.catalog == candidate.payload.identity
        val stored = catalog.sourceByIdentity(artifact.api, artifact.sourceRevision)
        if (stored == null) check(installing && !catalog.sourceExists(artifact.api, artifact.sourceRevision))
        else requireSameImmutableSourceRevision(stored, artifact)
    }

    private fun checkFloor(candidate: VerifiedSourceSelection, floor: SourceCatalogAcceptanceFloor?) {
        if (!candidate.advancesSignedFloor) return
        val identity = candidate.payload.identity
        require(identity.kind == SelectedCatalogKind.SIGNED && candidate.catalog != null)
        if (floor != null) {
            require(identity.revision >= floor.catalogRevision)
            if (identity.revision == floor.catalogRevision) require(identity.checksum == floor.checksum)
        }
    }

    private suspend fun persistCatalog(prepared: PreparedRoomSelection) {
        val candidate = prepared.candidate
        if (!candidate.advancesSignedFloor) return
        val stored = requireNotNull(candidate.catalog)
        val manifest = requireNotNull(prepared.manifest)
        catalog.insertSources(stored.sources.map { it.toEntity() })
        catalog.insertManifest(stored.manifest.toEntity())
        catalog.deleteEntries(manifest.catalogRevision)
        catalog.insertEntries(manifest.sources.map { it.toEntity(manifest.catalogRevision) })
        catalog.setActive(ActiveSourceCatalogEntity(catalogRevision = candidate.payload.identity.revision, checksum = candidate.payload.identity.checksum))
    }
}

private data class PreparedRoomSelection(
    val candidate: VerifiedSourceSelection,
    val raw: String,
    val digest: String,
    val manifest: SourceCatalogManifest?,
)

/** Copies nested descriptor collections too; callers cannot change the queued private candidate. */
private fun freezeCandidate(candidate: VerifiedSourceSelection): VerifiedSourceSelection {
    require(candidate.proofs.size <= SourceSelectionLimits.PROOF_REFERENCES)
    require(candidate.document.sources.size <= SourceSelectionLimits.SOURCES)
    require((candidate.catalog?.sources?.size ?: 0) <= SourceSelectionLimits.SOURCES)
    return candidate.copy(
        document = candidate.document.copy(sources = candidate.document.sources.map(::freezeSource)),
        catalog = candidate.catalog?.let { it.copy(sources = it.sources.toList()) },
        proofs = candidate.proofs.map { it.copy(descriptor = freezeSource(it.descriptor)) },
    )
}

private fun freezeSource(source: SourceConfig): SourceConfig =
    Json.decodeFromString(SourceConfig.serializer(), Json.encodeToString(SourceConfig.serializer(), source))

private fun prepareEvidence(candidate: VerifiedSourceSelection): Map<SelectedCatalogIdentity, SourceCatalogManifest> {
    val references = candidate.payload.rules.flatMap { listOf(it.currentProof) + it.previousHosts.mapNotNull { host -> host.proof } }.toSet()
    require(candidate.proofs.map { it.reference }.toSet() == references && candidate.proofs.size == references.size)
    val signed = (listOfNotNull(candidate.catalog?.manifest) + candidate.proofs.mapNotNull { it.manifest }).distinct()
    require(signed.map { it.identity() }.distinct().size == signed.size)
    val artifacts = candidate.proofs.mapNotNull { it.artifact }.distinct()
    require(artifacts.map { it.api to it.sourceRevision }.distinct().size == artifacts.size)
    val manifestBytes = signed.sumOf { evidenceBytes(it.payload, SourceSelectionLimits.MANIFEST_BYTES) }
    val artifactBytes = artifacts.sumOf { evidenceBytes(it.payload, SourceSelectionLimits.ARTIFACT_BYTES) }
    require(manifestBytes + artifactBytes <= SourceSelectionLimits.EVIDENCE_BYTES)
    val parsed = signed.associate { it.identity() to parseVerifiedManifest(it) }
    candidate.proofs.forEach { requireCompleteProof(it, parsed[it.reference.catalog]) }
    return parsed
}

private fun requireCompleteProof(proof: VerifiedSourceSelectionProof, manifest: SourceCatalogManifest?) {
    require(proof.reference.api == proof.descriptor.api)
    if (proof.reference.catalog.kind == SelectedCatalogKind.BUNDLED) {
        require(proof.manifest == null && proof.artifact == null)
        return
    }
    require(proof.manifest?.identity() == proof.reference.catalog)
    val artifact = requireNotNull(proof.artifact)
    require(artifact.api == proof.reference.api && artifact.canonVersion == "kcj-1")
    require(proof.reference.source == SelectedSourceRevision(artifact.sourceRevision, artifact.checksum))
    val entry = requireNotNull(manifest).sources.single { it.api == proof.reference.api && it.lifecycle == "active" }
    require(entry.sourceRevision == artifact.sourceRevision && entry.checksum == artifact.checksum)
    val descriptor = (SourceConfigParser.parseSource(artifact.payload) as? AppResult.Success)?.value
        ?: error("invalid verified source")
    require(descriptor.copy(lifecycle = entry.lifecycle, priority = entry.order) == proof.descriptor)
}

private fun requireCompleteCatalog(candidate: VerifiedSourceSelection, manifest: SourceCatalogManifest?) {
    val signed = candidate.payload.identity.kind == SelectedCatalogKind.SIGNED
    require(signed == (candidate.catalog != null) && (!candidate.advancesSignedFloor || signed))
    if (!signed) return
    val catalog = requireNotNull(candidate.catalog)
    require(catalog.manifest.identity() == candidate.payload.identity)
    val current = requireNotNull(manifest)
    require(current.sourceSchemaVersion == candidate.document.schemaVersion && current.generatedAt == candidate.document.generatedAt)
    require(current.sources.filter { it.lifecycle == "active" }.map { it.api } == candidate.document.sources.map { it.api })
    val artifacts = candidate.payload.rules.map { rule -> candidate.proofs.single { it.reference == rule.currentProof }.artifact }
    require(catalog.sources.size == artifacts.size && catalog.sources.toSet() == artifacts.toSet())
}

private fun parseVerifiedManifest(signed: SignedSourceCatalogManifest): SourceCatalogManifest {
    val parsed = (SourceConfigParser.parseManifest(signed.payload) as? AppResult.Success)?.value ?: error("invalid verified manifest")
    require(parsed.catalogRevision == signed.metadata.revision && parsed.generatedAt == signed.metadata.createdAt)
    return parsed
}

private fun SignedSourceCatalogManifest.identity() = SelectedCatalogIdentity(SelectedCatalogKind.SIGNED, metadata.revision, metadata.checksum)

private fun evidenceBytes(raw: String, limit: Int): Long {
    require(raw.length <= limit)
    return raw.encodeToByteArray().size.toLong().also { require(it <= limit) }
}
