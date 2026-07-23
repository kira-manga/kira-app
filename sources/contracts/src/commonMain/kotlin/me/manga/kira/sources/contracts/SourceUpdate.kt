package me.manga.kira.sources.contracts

import kotlinx.coroutines.flow.StateFlow
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * Owns the lifecycle of the complete active source catalog. Construction begins on the trusted
 * bundled tier; [refresh] may restore a complete reverified cache or atomically activate a complete
 * signed manifest after every referenced source revision is present and verified.
 *
 * A transport, signature, validation, or persistence failure keeps the complete last-known-good
 * catalog. A valid authoritative catalog may intentionally contain zero active sources after
 * lifecycle updates; that is distinct from a partial or failed synchronization.
 */
interface SourceUpdateManager {
    val state: StateFlow<UpdateState>

    /** The complete document currently in effect. Always non-null; bundled is the trusted floor. */
    fun activeDocument(): SourceConfigDocument

    /**
     * Conditionally fetch the signed manifest, download only missing immutable source revisions,
     * verify the complete candidate, and activate it atomically. A disabled remote is a safe no-op.
     */
    suspend fun refresh(): AppResult<SourceConfigDocument>
}

/** Observable status of the update manager for diagnostics and settings UI. */
sealed interface UpdateState {
    /** Complete active document resolved from bundle, cache, or remote; no refresh in flight. */
    data class Active(val revision: Long, val source: Origin) : UpdateState

    /** A refresh is in progress. */
    data object Refreshing : UpdateState

    /** Last refresh failed; [reason] is human-readable, the previous good document stays active. */
    data class Failed(val reason: String) : UpdateState

    /** Provenance of the complete active catalog revision, never an individual source revision. */
    enum class Origin { BUNDLED, CACHE, REMOTE }
}
