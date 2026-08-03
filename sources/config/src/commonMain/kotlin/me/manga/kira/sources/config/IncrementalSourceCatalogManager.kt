package me.manga.kira.sources.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.ActiveSourceDiagnostics
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogDiagnostics
import me.manga.kira.sources.contracts.SourceCatalogDiagnosticsProvider
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceCatalogSignatureVerifier
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.StoredSourceCatalog
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * Synchronizes a signed lightweight manifest and only its missing immutable source revisions.
 *
 * A candidate remains invisible until every active source verifies and [SourceCatalogStore.activate]
 * commits. Any failure retains the complete previous catalog tier.
 */
class IncrementalSourceCatalogManager(
    private val store: SourceCatalogStore,
    private val verifier: SourceCatalogSignatureVerifier,
    private val validator: SourceConfigValidator,
    private val remote: RemoteSourceCatalog,
    private val onRejected: (String) -> Unit = {},
) : SourceUpdateManager,
    SourceCatalogDiagnosticsProvider {
    private val bundled = requireBundledCatalog()
    private val active = MutableStateFlow(bundled)
    private val catalogDiagnostics =
        MutableStateFlow(bundled.toDiagnostics(UpdateState.Origin.BUNDLED))
    private val updateState =
        MutableStateFlow<UpdateState>(
            UpdateState.Active(bundled.revision, UpdateState.Origin.BUNDLED),
        )
    private val refreshLock = Mutex()

    override val state: StateFlow<UpdateState> = updateState.asStateFlow()
    override val diagnostics: StateFlow<SourceCatalogDiagnostics> = catalogDiagnostics.asStateFlow()

    override fun activeDocument(): SourceConfigDocument = active.value

    override suspend fun refresh(): AppResult<SourceConfigDocument> =
        refreshLock.withLock {
            val previousState = updateState.value
            updateState.value = UpdateState.Refreshing
            try {
                val acceptanceFloor = store.readAcceptanceFloor()
                val cached = loadVerifiedCache()
                val acceptedManifest =
                    cached
                        ?.takeIf {
                            acceptanceFloor != null &&
                                it.document.revision == acceptanceFloor.catalogRevision &&
                                it.etag == acceptanceFloor.checksum
                        }
                        ?.manifest
                        ?: loadAcceptedManifest(acceptanceFloor)
                val current = active.value
                val previousOrigin =
                    (previousState as? UpdateState.Active)?.source
                        ?: UpdateState.Origin.BUNDLED
                val floor =
                    cached
                        ?.document
                        ?.takeIf { it.revision > current.revision }
                        ?: current
                val origin =
                    if (floor === current) previousOrigin else UpdateState.Origin.CACHE
                active.value = floor
                if (floor !== current) {
                    catalogDiagnostics.value =
                        requireNotNull(cached).toDiagnostics(UpdateState.Origin.CACHE)
                }
                if (floor.revision == bundled.revision) {
                    // The bundle is a complete tier, not a merge base. Projecting it before any
                    // network work removes obsolete rows even when the fetch later fails.
                    store.projectBundled(bundled)
                }

                val result = remote.fetchManifest(cached?.etag)
                val accepted =
                    when (result) {
                        SourceCatalogManifestResult.Unavailable -> null
                        SourceCatalogManifestResult.NotModified -> null
                        is SourceCatalogManifestResult.Modified ->
                            acceptRemote(
                                signed = result.manifest,
                                acceptanceFloor = acceptanceFloor,
                                acceptedManifest = acceptedManifest,
                                previousManifest =
                                    cached
                                        ?.takeIf { it.document.revision == floor.revision }
                                        ?.manifest,
                                previousDocument = floor,
                            )
                    }
                val effectiveDocument = accepted?.document ?: floor
                active.value = effectiveDocument
                if (accepted != null) {
                    catalogDiagnostics.value = accepted.toDiagnostics(UpdateState.Origin.REMOTE)
                }
                updateState.value =
                    UpdateState.Active(
                        effectiveDocument.revision,
                        if (accepted == null) origin else UpdateState.Origin.REMOTE,
                    )
                AppResult.Success(effectiveDocument)
            } catch (cancelled: CancellationException) {
                updateState.value = previousState
                throw cancelled
            } catch (failure: Exception) {
                onRejected("catalog refresh failed; retaining the complete previous catalog")
                updateState.value = UpdateState.Failed(REFRESH_FAILED)
                AppResult.Failure(AppError.Unexpected(REFRESH_FAILED, failure))
            }
        }

    private suspend fun acceptRemote(
        signed: SignedSourceCatalogManifest,
        acceptanceFloor: SourceCatalogAcceptanceFloor?,
        acceptedManifest: SourceCatalogManifest?,
        previousManifest: SourceCatalogManifest?,
        previousDocument: SourceConfigDocument,
    ): VerifiedCatalog? {
        val manifest = verifyManifest(signed) ?: return null
        if (manifest.catalogRevision <= bundled.revision) return null
        if (acceptanceFloor != null) {
            when {
                manifest.catalogRevision < acceptanceFloor.catalogRevision -> return null
                manifest.catalogRevision == acceptanceFloor.catalogRevision -> {
                    if (signed.metadata.checksum != acceptanceFloor.checksum) return null
                }
                acceptedManifest == null -> return null
                !chainAdvances(signed, acceptanceFloor) -> return null
            }
        }
        val evolutionBase =
            when {
                acceptanceFloor?.catalogRevision == manifest.catalogRevision -> manifest
                acceptedManifest != null -> acceptedManifest
                else -> previousManifest
            }
        val evolutionErrors = catalogEvolutionErrors(manifest, evolutionBase, previousDocument)
        if (evolutionErrors.isNotEmpty()) return rejected(evolutionErrors.joinToString())

        val activeEntries = manifest.sources.filter { it.lifecycle == LIFECYCLE_ACTIVE }
        val artifacts = fetchRequiredSources(activeEntries)
        val document = assembleDocument(manifest, activeEntries, artifacts) ?: return null
        store.activate(StoredSourceCatalog(signed, artifacts))
        return VerifiedCatalog(
            document = document,
            etag = signed.metadata.checksum,
            manifest = manifest,
            signedManifest = signed,
        )
    }

    private suspend fun fetchRequiredSources(entries: List<SourceCatalogEntry>): List<SourceRevisionArtifact> =
        coroutineScope {
            entries.chunked(MAX_PARALLEL_DOWNLOADS).flatMap { chunk ->
                chunk
                    .map { entry ->
                        async {
                            val cached = store.findSource(entry.api, entry.sourceRevision, entry.checksum)
                            cached?.takeIf { verifier.verifySource(entry, it) }
                                ?: remote.fetchSource(entry).also { artifact ->
                                    require(verifier.verifySource(entry, artifact)) {
                                        "source revision signature verification failed"
                                    }
                                }
                        }
                    }.awaitAll()
            }
        }

    private suspend fun loadVerifiedCache(): VerifiedCatalog? {
        val stored = store.readActive() ?: return null
        val manifest = verifyManifest(stored.manifest) ?: return null
        val entries = manifest.sources.filter { it.lifecycle == LIFECYCLE_ACTIVE }
        val artifactsByKey = stored.sources.associateBy { it.api to it.sourceRevision }
        val artifacts =
            entries.map { entry ->
                artifactsByKey[entry.api to entry.sourceRevision]
                    ?.takeIf { it.checksum == entry.checksum && verifier.verifySource(entry, it) }
                    ?: return null
            }
        val document = assembleDocument(manifest, entries, artifacts) ?: return null
        return VerifiedCatalog(
            document = document,
            etag = stored.manifest.metadata.checksum,
            manifest = manifest,
            signedManifest = stored.manifest,
        )
    }

    private suspend fun loadAcceptedManifest(
        acceptanceFloor: SourceCatalogAcceptanceFloor?,
    ): SourceCatalogManifest? {
        if (acceptanceFloor == null) return null
        val signed = store.readAcceptedManifest() ?: return null
        if (
            signed.metadata.revision != acceptanceFloor.catalogRevision ||
            signed.metadata.checksum != acceptanceFloor.checksum
        ) {
            return null
        }
        return verifyManifest(signed)
    }

    private fun verifyManifest(signed: SignedSourceCatalogManifest): SourceCatalogManifest? {
        if (!verifier.verifyManifest(signed)) return rejected("manifest signature is invalid")
        val manifest =
            when (val parsed = SourceConfigParser.parseManifest(signed.payload)) {
                is AppResult.Success -> parsed.value
                is AppResult.Failure -> return rejected("manifest JSON is invalid")
            }
        val errors = manifestErrors(manifest, signed)
        return manifest.takeIf { errors.isEmpty() } ?: rejected(errors.joinToString())
    }

    private fun manifestErrors(
        manifest: SourceCatalogManifest,
        signed: SignedSourceCatalogManifest,
    ): List<String> = buildList {
        if (manifest.schemaVersion != MANIFEST_SCHEMA_VERSION) add("unsupported manifest schema")
        if (manifest.sourceSchemaVersion != SOURCE_SCHEMA_VERSION) add("unsupported source schema")
        if (manifest.catalogRevision <= 0) add("catalog revision must be positive")
        if (manifest.catalogRevision != signed.metadata.revision) add("catalog revision metadata mismatch")
        if (manifest.generatedAt != signed.metadata.createdAt) add("catalog timestamp metadata mismatch")
        if (manifest.sources.map { it.api }.toSet().size != manifest.sources.size) add("duplicate source api")
        if (manifest.sources.map { it.order } != manifest.sources.indices.toList()) add("source order is not contiguous")
        if (manifest.removedSources.any { it.lifecycle != LIFECYCLE_REMOVED }) add("invalid removed tombstone")
        val removedApis = manifest.removedSources.map { it.api }
        if (removedApis.any(String::isBlank)) add("blank removed source api")
        if (removedApis.toSet().size != removedApis.size) add("duplicate removed source api")
        if (manifest.sources.any { it.api in removedApis }) add("source is both present and removed")
        manifest.sources.forEach { entry ->
            if (entry.api.isBlank() || entry.sourceRevision <= 0) add("invalid source identity")
            if (!CHECKSUM.matches(entry.checksum)) add("invalid source checksum")
            if (entry.lifecycle !in ENTRY_LIFECYCLES) add("invalid source lifecycle")
            if (entry.engine != ENGINE_GENERIC) add("non-generic source is forbidden")
            if (!KEY_ID.matches(entry.sourceSigningKeyId) || entry.sourceSignature.isBlank()) {
                add("invalid source signature metadata")
            }
        }
    }

    private fun catalogEvolutionErrors(
        candidate: SourceCatalogManifest,
        previous: SourceCatalogManifest?,
        previousDocument: SourceConfigDocument,
    ): List<String> = buildList {
        val candidateEntries = candidate.sources.associateBy { it.api }
        val candidateTombstones = candidate.removedSources.mapTo(hashSetOf()) { it.api }
        val previousEntries = previous?.sources?.associateBy { it.api }.orEmpty()
        val previousApis =
            previous
                ?.sources
                ?.mapTo(hashSetOf()) { it.api }
                ?: previousDocument.sources.mapTo(hashSetOf()) { it.api }
        val silentlyOmitted = previousApis - candidateEntries.keys - candidateTombstones
        if (silentlyOmitted.isNotEmpty()) add("previous source is absent without a removed tombstone")

        val previousTombstones = previous?.removedSources?.mapTo(hashSetOf()) { it.api }.orEmpty()
        if (!candidateTombstones.containsAll(previousTombstones)) add("removed tombstone was discarded")
        if (candidateEntries.keys.any { it in previousTombstones }) add("removed source was reintroduced")

        previousEntries.forEach { (api, oldEntry) ->
            val nextEntry = candidateEntries[api] ?: return@forEach
            if (nextEntry.sourceRevision < oldEntry.sourceRevision) {
                add("source revision rollback is forbidden")
            }
            if (
                nextEntry.sourceRevision == oldEntry.sourceRevision &&
                nextEntry.checksum != oldEntry.checksum
            ) {
                add("immutable source revision checksum changed")
            }
        }
    }

    private fun assembleDocument(
        manifest: SourceCatalogManifest,
        entries: List<SourceCatalogEntry>,
        artifacts: List<SourceRevisionArtifact>,
    ): SourceConfigDocument? {
        if (entries.size != artifacts.size) return rejected("catalog is incomplete")
        val configs =
            entries.zip(artifacts).map { (entry, artifact) ->
                if (!verifier.verifySource(entry, artifact)) return rejected("source verification failed")
                val parsed =
                    when (val result = SourceConfigParser.parseSource(artifact.payload)) {
                        is AppResult.Success -> result.value
                        is AppResult.Failure -> return rejected("source JSON is invalid")
                    }
                parsed.takeIf { it.api == entry.api && it.engine == ENGINE_GENERIC }
                    ?.copy(lifecycle = entry.lifecycle, priority = entry.order)
                    ?: return rejected("source payload identity is invalid")
            }
        val document =
            SourceConfigDocument(
                schemaVersion = manifest.sourceSchemaVersion,
                generatedAt = manifest.generatedAt,
                revision = manifest.catalogRevision,
                sources = configs,
            )
        return document.takeIf { validator.validate(it).isValid } ?: rejected("catalog validation failed")
    }

    private fun chainAdvances(
        signed: SignedSourceCatalogManifest,
        acceptanceFloor: SourceCatalogAcceptanceFloor?,
    ): Boolean {
        if (acceptanceFloor == null) return true
        val previous = signed.metadata.previousRevision ?: return false
        return previous >= acceptanceFloor.catalogRevision &&
            (
                previous != acceptanceFloor.catalogRevision ||
                    signed.metadata.previousChecksum == acceptanceFloor.checksum
            )
    }

    private fun requireBundledCatalog(): SourceConfigDocument {
        val raw = requireNotNull(store.readBundled()) { "bundled source catalog is missing" }
        val document =
            when (val parsed = SourceConfigParser.parse(raw)) {
                is AppResult.Success -> parsed.value
                is AppResult.Failure -> error("bundled source catalog is invalid")
            }
        require(document.sources.isNotEmpty())
        require(document.sources.all(SourceConfig::isActiveGeneric))
        require(validator.validate(document).isValid)
        return document
    }

    private fun <T> rejected(reason: String): T? {
        onRejected(reason)
        return null
    }

    private fun SourceConfigDocument.toDiagnostics(origin: UpdateState.Origin): SourceCatalogDiagnostics =
        SourceCatalogDiagnostics(
            origin = origin,
            catalogRevision = revision,
            catalogSchemaVersion = schemaVersion,
            sourceSchemaVersion = schemaVersion,
            generatedAt = generatedAt,
            manifestChecksum = null,
            manifestSigningKeyId = null,
            signatureAlgorithm = null,
            signatureFormat = null,
            previousCatalogRevision = null,
            previousCatalogChecksum = null,
            removedSourceCount = 0,
            inactiveSourceCount = 0,
            activeSources =
                sources.mapIndexed { index, source ->
                    source.toDiagnostics(
                        order = source.priority.takeIf { it >= 0 } ?: index,
                        sourceRevision = null,
                        checksum = null,
                        signingKeyId = null,
                    )
                },
        )

    private fun VerifiedCatalog.toDiagnostics(origin: UpdateState.Origin): SourceCatalogDiagnostics {
        val configsByApi = document.sources.associateBy(SourceConfig::api)
        val activeEntries = manifest.sources.filter { it.lifecycle == LIFECYCLE_ACTIVE }
        return SourceCatalogDiagnostics(
            origin = origin,
            catalogRevision = manifest.catalogRevision,
            catalogSchemaVersion = manifest.schemaVersion,
            sourceSchemaVersion = manifest.sourceSchemaVersion,
            generatedAt = manifest.generatedAt,
            manifestChecksum = signedManifest.metadata.checksum,
            manifestSigningKeyId = signedManifest.metadata.keyId,
            signatureAlgorithm = signedManifest.metadata.algorithm,
            signatureFormat = signedManifest.metadata.format,
            previousCatalogRevision = signedManifest.metadata.previousRevision,
            previousCatalogChecksum = signedManifest.metadata.previousChecksum,
            removedSourceCount = manifest.removedSources.size,
            inactiveSourceCount = manifest.sources.size - activeEntries.size,
            activeSources =
                activeEntries.map { entry ->
                    requireNotNull(configsByApi[entry.api]).toDiagnostics(
                        order = entry.order,
                        sourceRevision = entry.sourceRevision,
                        checksum = entry.checksum,
                        signingKeyId = entry.sourceSigningKeyId,
                    )
                },
        )
    }

    private fun SourceConfig.toDiagnostics(
        order: Int,
        sourceRevision: Long?,
        checksum: String?,
        signingKeyId: String?,
    ): ActiveSourceDiagnostics =
        ActiveSourceDiagnostics(
            api = api,
            displayName = displayName,
            language = language,
            baseUrl = baseUrl,
            engine = engine,
            lifecycle = lifecycle,
            order = order,
            sourceRevision = sourceRevision,
            checksum = checksum,
            signingKeyId = signingKeyId,
        )

    private data class VerifiedCatalog(
        val document: SourceConfigDocument,
        val etag: String,
        val manifest: SourceCatalogManifest,
        val signedManifest: SignedSourceCatalogManifest,
    )

    private companion object {
        const val REFRESH_FAILED = "source catalog refresh failed"
        const val MANIFEST_SCHEMA_VERSION = 1
        const val SOURCE_SCHEMA_VERSION = 1
        const val MAX_PARALLEL_DOWNLOADS = 4
        const val ENGINE_GENERIC = "generic"
        const val LIFECYCLE_ACTIVE = "active"
        const val LIFECYCLE_REMOVED = "removed"
        val ENTRY_LIFECYCLES = setOf("active", "disabled", "retired")
        val CHECKSUM = Regex("[0-9a-f]{64}")
        val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

private fun SourceConfig.isActiveGeneric(): Boolean = engine == "generic" && lifecycle == "active"
