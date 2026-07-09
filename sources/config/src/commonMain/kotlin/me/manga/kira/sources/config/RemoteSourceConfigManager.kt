package me.manga.kira.sources.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.ConfigSignatureVerifier
import me.manga.kira.sources.contracts.ConfigStore
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * Resolves and maintains the active config document.
 *
 * Resolution precedence (lowest → highest): **bundled** (the always-present floor, trusted because it
 * shipped in the signed app binary) → **cache** (the last document we accepted; trusted-on-write, so
 * no re-verification needed) → **remote** (only if a [RemoteConfigSource] is wired AND its signature
 * verifies AND it validates). Documents are combined per-source by [ConfigMerger], and any document
 * that fails to parse/verify/validate is simply dropped — the previous good document stays active and
 * the active document is never empty.
 *
 * **Stage-0 posture:** construct WITHOUT a [RemoteConfigSource] ⇒ remote is disabled, the manager only
 * ever surfaces bundled+cache, and [refresh] performs no network I/O. The dynamic path exists in code
 * (and is tested with fakes) but is off until a source is explicitly piloted.
 */
class RemoteSourceConfigManager(
    private val store: ConfigStore,
    private val verifier: ConfigSignatureVerifier,
    private val validator: SourceConfigValidator,
    private val remote: RemoteConfigSource? = null,
    /**
     * Invoked when a document is dropped (parse failure or validation errors) with the tier it came
     * from (`bundled`/`cache`/`remote`) and the per-stanza reasons. Rejection is otherwise SILENT —
     * the previous good document stays active, and a rejected BUNDLED document degrades to [EMPTY]
     * (every generic source lost at once, since validation is all-or-nothing). The composition root
     * wires this to a logger so that catastrophic-but-quiet state is diagnosable in the field.
     */
    private val onDocumentRejected: (origin: String, reasons: List<String>) -> Unit = { _, _ -> },
) : SourceUpdateManager {

    private val bundledDocument: SourceConfigDocument = resolveBundled()

    private val active = MutableStateFlow(bundledDocument)
    private val updateState = MutableStateFlow<UpdateState>(
        UpdateState.Active(bundledDocument.revision, UpdateState.Origin.BUNDLED),
    )

    // Serializes refresh() so concurrent callers can't race on the cache/remote reads or leave a torn
    // active/state pair. Refresh is infrequent (startup + manual), so a single mutex is ample.
    private val refreshLock = Mutex()

    override val state: StateFlow<UpdateState> = updateState.asStateFlow()

    override fun activeDocument(): SourceConfigDocument = active.value

    override suspend fun refresh(): AppResult<SourceConfigDocument> = refreshLock.withLock {
        val previous = updateState.value
        updateState.value = UpdateState.Refreshing
        try {
            val documents = mutableListOf(bundledDocument)
            var origin = UpdateState.Origin.BUNDLED
            // Anti-rollback floor: a cached/remote document is only folded in when its revision is at
            // least the bundled document's. This enforces the "highest revision wins" contract — a
            // stale-but-valid cache can never override a newer bundled config (e.g. a domain fix that
            // shipped in an app update), and a replayed older signed remote document is rejected.
            var acceptedRevision = bundledDocument.revision

            // CACHE — trusted-on-write, only needs to parse + validate.
            store.readCached()?.let { raw ->
                acceptedOrNull(raw, origin = "cache")?.takeIf { it.revision >= acceptedRevision }?.let {
                    documents += it
                    origin = UpdateState.Origin.CACHE
                    acceptedRevision = it.revision
                }
            }

            // REMOTE — only when wired; must verify signature before it is parsed or trusted.
            remote?.fetch()?.let { payload ->
                if (verifier.verify(payload.payload.encodeToByteArray(), payload.signatureBase64)) {
                    acceptedOrNull(payload.payload, origin = "remote")?.takeIf { it.revision >= acceptedRevision }?.let {
                        store.writeCached(payload.payload) // cache only what we verified and accepted
                        documents += it
                        origin = UpdateState.Origin.REMOTE
                        acceptedRevision = it.revision
                    }
                }
            }

            val effective = ConfigMerger.merge(documents)
            active.value = effective
            updateState.value = UpdateState.Active(effective.revision, origin)
            AppResult.Success(effective)
        } catch (c: CancellationException) {
            // Don't leave the observable state wedged at Refreshing when a refresh is cancelled.
            updateState.value = previous
            throw c
        } catch (t: Throwable) {
            // Keep the last good active document; report the failure.
            updateState.value = UpdateState.Failed(t.message ?: "config refresh failed")
            AppResult.Failure(AppError.Unexpected(t.message ?: "config refresh failed", t))
        }
    }

    /**
     * Parse + validate a raw document; null if it is malformed or fails validation. Every drop is
     * reported through [onDocumentRejected] with its [origin] — validation is all-or-nothing, so a
     * single bad stanza rejects the whole document and the reasons list is the only diagnostic.
     */
    private fun acceptedOrNull(raw: String, origin: String): SourceConfigDocument? {
        val parsed = when (val result = SourceConfigParser.parse(raw)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                onDocumentRejected(origin, listOf("document does not parse: ${result.error}"))
                return null
            }
        }
        val validation = validator.validate(parsed)
        if (!validation.isValid) {
            onDocumentRejected(origin, validation.errors)
            return null
        }
        return parsed
    }

    private fun resolveBundled(): SourceConfigDocument {
        val raw = store.readBundled() ?: return EMPTY
        return acceptedOrNull(raw, origin = "bundled") ?: EMPTY
    }

    private companion object {
        /** Revision -1 guarantees any real document outranks the empty floor in [ConfigMerger]. */
        val EMPTY = SourceConfigDocument(schemaVersion = 1, revision = -1)
    }
}
