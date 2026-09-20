package me.manga.kira.sources.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.CommittedSourceSelection
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogDiagnostics
import me.manga.kira.sources.contracts.SourceCatalogDiagnosticsProvider
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceCatalogSignatureVerifier
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import me.manga.kira.sources.contracts.SourceSelectionExpectation
import me.manga.kira.sources.contracts.SourceSelectionLimits
import me.manga.kira.sources.contracts.SourceSelectionRead
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.StoredSourceCatalog
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.VerifiedSourceSelection
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/** Trusted bundle execution is synchronous; identity readiness requires a verified durable selection. */
class IncrementalSourceCatalogManager(
    private val store: SourceCatalogStore,
    verifier: SourceCatalogSignatureVerifier,
    validator: SourceConfigValidator,
    private val remote: RemoteSourceCatalog,
    private val onRejected: (String) -> Unit = {},
) : SourceUpdateManager, SourceCatalogDiagnosticsProvider {
    private val verification = SourceCatalogVerifier(store, verifier, validator, onRejected)
    private val bundled = verification.bundle()
    private val active = MutableStateFlow(bundled.document)
    private val catalogDiagnostics = MutableStateFlow(catalogDiagnostics(bundled, UpdateState.Origin.BUNDLED))
    private val updateState = MutableStateFlow<UpdateState>(UpdateState.Active(bundled.document.revision, UpdateState.Origin.BUNDLED))
    private val refreshLock = Mutex()
    private var retained: SelectionPublication? = null
    private var pending: SelectionPublication? = null
    private var retainedReceipt: CommittedSourceSelection? = null

    override val state: StateFlow<UpdateState> = updateState.asStateFlow()
    override val acceptedDocument: StateFlow<SourceConfigDocument> = active.asStateFlow()
    override val diagnostics: StateFlow<SourceCatalogDiagnostics> = catalogDiagnostics.asStateFlow()
    override fun activeDocument(): SourceConfigDocument = active.value

    override suspend fun refresh(): AppResult<SourceConfigDocument> = selectCatalog(fetchRemote = true)

    /**
     * Reverify and publish the local selection without requesting remote manifests or source artifacts.
     * Shares refresh serialization and cancellation reconciliation; changed selections still require
     * the store's guarded commit. Success is not a retained permission for a later download attempt.
     */
    suspend fun restoreLocalSelection(): AppResult<SourceConfigDocument> = selectCatalog(fetchRemote = false)

    private suspend fun selectCatalog(fetchRemote: Boolean): AppResult<SourceConfigDocument> =
        refreshLock.withLock {
            updateState.value = UpdateState.Refreshing
            try {
                val base = prepareBase(store.readSelection())
                publishCommitted(base.publication, base.expected)
                if (fetchRemote) {
                    val result = remote.fetchManifest(base.publication.catalog.stored?.manifest?.metadata?.checksum)
                    if (result is SourceCatalogManifestResult.Modified) acceptRemote(result.manifest)
                }
                updateState.value = UpdateState.Active(active.value.revision, checkNotNull(retained).origin)
                AppResult.Success(active.value)
            } catch (cancelled: CancellationException) {
                reconcileCancellation()
                throw cancelled
            } catch (failure: Exception) {
                val failed = pending
                val recovery = retained?.takeIf { failed != null && failed.candidate.payload != it.candidate.payload }
                if (pending != null || failure is SourceSelectionUnavailable) store.invalidateSelection()
                pending = null
                onRejected("catalog refresh failed; retaining the complete previous execution catalog")
                updateState.value = UpdateState.Failed(REFRESH_FAILED)
                if (recovery != null) recoverRetainedReadiness(recovery)
                AppResult.Failure(AppError.Unexpected(REFRESH_FAILED, failure))
            }
        }

    private suspend fun prepareBase(read: SourceSelectionRead): SelectionPreparation {
        val expected = read.expected ?: throw SourceSelectionUnavailable("generation allocator unavailable")
        val warm = retained?.takeIf { read.selection?.token == retainedReceipt?.token }
        val selected = warm?.catalog ?: selectedCatalog(read)?.takeIf { it.document.revision > bundled.document.revision }
            ?: verifiedCache() ?: bundled
        val previous = read.selection?.payload
        val history = warm?.candidate?.proofs ?: verification.historical(previous?.let(::payloadReferences).orEmpty(), bundled)
        val candidate = selectionCandidate(selected, previous, history, advancesFloor = false)
        val origin = when {
            warm != null -> warm.origin
            selected.stored == null -> UpdateState.Origin.BUNDLED
            else -> UpdateState.Origin.CACHE
        }
        return SelectionPreparation(SelectionPublication(selected, candidate, origin), expected)
    }

    private suspend fun selectedCatalog(read: SourceSelectionRead): VerifiedCatalog? {
        val identity = read.selection?.payload?.identity ?: return null
        if (identity.kind == SelectedCatalogKind.BUNDLED) return bundled.takeIf { it.identity == identity }
        val signed = store.readAcceptedManifest(identity) ?: return null
        return loadCatalog(signed)
    }

    private suspend fun verifiedCache(): VerifiedCatalog? = store.readActive()?.let(verification::stored)
        ?.takeIf { it.document.revision > bundled.document.revision }

    private suspend fun loadCatalog(signed: SignedSourceCatalogManifest): VerifiedCatalog? {
        val manifest = verification.manifest(signed) ?: return null
        val artifacts = mutableListOf<SourceRevisionArtifact>()
        for (entry in manifest.sources.filter { it.lifecycle == "active" }) {
            artifacts += store.findSource(entry.api, entry.sourceRevision, entry.checksum) ?: return null
            requireArtifactBudget(artifacts, signed.payload)
        }
        return verification.stored(StoredSourceCatalog(signed, artifacts))
    }

    private suspend fun acceptRemote(signed: SignedSourceCatalogManifest) {
        val read = store.readSelection()
        val expected = read.expected ?: throw SourceSelectionUnavailable("generation allocator unavailable")
        val current = requireNotNull(retained)
        val manifest = verification.manifest(signed) ?: return
        val floor = expected.signedFloor
        val accepted = acceptedManifest(floor)
        if (!eligible(signed, manifest, floor, accepted)) return
        val evolutionBase = if (floor?.catalogRevision == manifest.catalogRevision) manifest else accepted ?: current.catalog.manifest
        val errors = catalogEvolutionErrors(manifest, evolutionBase, current.catalog.document)
        if (errors.isNotEmpty()) { onRejected(errors.joinToString()); return }
        val artifacts = fetchRequiredSources(manifest.sources.filter { it.lifecycle == "active" }, signed.payload)
        val catalog = verification.stored(StoredSourceCatalog(signed, artifacts)) ?: return
        val candidate = selectionCandidate(catalog, current.candidate.payload, current.candidate.proofs, advancesFloor = true)
        publishCommitted(SelectionPublication(catalog, candidate, UpdateState.Origin.REMOTE), expected)
    }

    private fun eligible(
        signed: SignedSourceCatalogManifest,
        manifest: SourceCatalogManifest,
        floor: SourceCatalogAcceptanceFloor?,
        accepted: SourceCatalogManifest?,
    ): Boolean {
        if (manifest.catalogRevision <= bundled.document.revision) return false
        if (floor == null) return true
        if (manifest.catalogRevision < floor.catalogRevision) return false
        if (manifest.catalogRevision == floor.catalogRevision) return signed.metadata.checksum == floor.checksum
        return accepted != null && chainAdvances(signed, floor)
    }

    private suspend fun acceptedManifest(floor: SourceCatalogAcceptanceFloor?): SourceCatalogManifest? {
        if (floor == null) return null
        val signed = store.readAcceptedManifest() ?: return null
        if (signed.metadata.revision != floor.catalogRevision || signed.metadata.checksum != floor.checksum) return null
        return verification.manifest(signed)
    }

    private suspend fun fetchRequiredSources(entries: List<SourceCatalogEntry>, manifest: String): List<SourceRevisionArtifact> = coroutineScope {
        val artifacts = mutableListOf<SourceRevisionArtifact>()
        for (chunk in entries.chunked(MAX_PARALLEL_DOWNLOADS)) {
            val batch = chunk.map { entry -> async { fetchRequiredSource(entry) } }.awaitAll()
            requireArtifactBudget(artifacts + batch, manifest)
            artifacts += batch
        }
        artifacts
    }

    private suspend fun fetchRequiredSource(entry: SourceCatalogEntry): SourceRevisionArtifact {
        val cached = store.findSource(entry.api, entry.sourceRevision, entry.checksum)
        if (cached != null && verification.source(entry, cached) != null) return cached
        return remote.fetchSource(entry).also {
            require(verification.source(entry, it) != null) { "source revision signature verification failed" }
        }
    }

    private suspend fun publishCommitted(publication: SelectionPublication, expected: SourceSelectionExpectation) {
        pending = publication
        val existing = store.adoptSelection(publication.candidate) { publishCatalog(publication, it) }
        if (existing != null) {
            pending = null
            return
        }
        val committed = store.commitSelection(publication.candidate, expected)
        val adopted = store.adoptSelection(publication.candidate) { receipt ->
            if (receipt.token != committed.token) throw SourceSelectionUnavailable("selection changed before publication")
            publishCatalog(publication, receipt)
        }
        if (adopted == null) throw SourceSelectionUnavailable("selection changed before publication")
        pending = null
    }

    private fun publish(publication: SelectionPublication, receipt: CommittedSourceSelection) {
        publishCatalog(publication, receipt)
        updateState.value = UpdateState.Active(publication.catalog.document.revision, publication.origin)
    }

    private fun publishCatalog(publication: SelectionPublication, receipt: CommittedSourceSelection) {
        retained = publication
        retainedReceipt = receipt
        active.value = publication.catalog.document
        catalogDiagnostics.value = catalogDiagnostics(publication.catalog, publication.origin)
    }

    /** One read-only A recovery after a different pending B failed; never retry A's own failed adoption. */
    private suspend fun recoverRetainedReadiness(publication: SelectionPublication) {
        try {
            val adopted = withTimeoutOrNull(RECONCILIATION_TIMEOUT_MS) {
                val context = currentCoroutineContext()
                store.adoptSelection(publication.candidate) { receipt ->
                    context.ensureActive()
                    publishCatalog(publication, receipt) // Keep the original failure visible; the store supplies a CURRENT receipt.
                }.also { context.ensureActive() }
            }
            currentCoroutineContext().ensureActive()
            if (adopted == null) store.invalidateSelection()
        } catch (cancelled: CancellationException) {
            store.invalidateSelection()
            throw cancelled // No second adoption via cancellation reconciliation for this new recovery.
        } catch (_: Exception) {
            store.invalidateSelection()
            currentCoroutineContext().ensureActive()
        }
    }

    /** One bounded durable reconciliation, including commit-before-receipt cancellation. No old-state reset. */
    private suspend fun reconcileCancellation() {
        val candidate = pending ?: retained
        store.invalidateSelection()
        updateState.value = UpdateState.Failed("catalog identity readiness unavailable")
        withContext(NonCancellable) {
            try {
                withTimeout(RECONCILIATION_TIMEOUT_MS) {
                    if (candidate != null) store.adoptSelection(candidate.candidate) { publish(candidate, it) }
                }
            } catch (_: Exception) {
                store.invalidateSelection()
            }
        }
        pending = null
    }

    private companion object {
        const val REFRESH_FAILED = "source catalog refresh failed"
        const val MAX_PARALLEL_DOWNLOADS = 4
        const val RECONCILIATION_TIMEOUT_MS = 2_000L
    }
}

private data class SelectionPublication(
    val catalog: VerifiedCatalog,
    val candidate: VerifiedSourceSelection,
    val origin: UpdateState.Origin,
)

private data class SelectionPreparation(val publication: SelectionPublication, val expected: SourceSelectionExpectation)

private fun requireArtifactBudget(artifacts: List<SourceRevisionArtifact>, manifest: String) {
    require(artifacts.all { bounded(it.payload, SourceSelectionLimits.ARTIFACT_BYTES) })
    val bytes = manifest.encodeToByteArray().size.toLong() + artifacts.sumOf { it.payload.encodeToByteArray().size.toLong() }
    require(bytes <= SourceSelectionLimits.EVIDENCE_BYTES)
}
