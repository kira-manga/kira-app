package me.manga.kira.sources.contracts

import kotlinx.coroutines.flow.StateFlow
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * Owns the lifecycle of "which config document is active right now". At construction the active
 * document resolves to the bundled asset only; any cached or remote document is folded in solely by
 * [refresh] (the cache read is suspend and cannot run in the constructor). In Stage-0 remote refresh
 * is DISABLED by default and [refresh] has no production caller, so the manager only ever surfaces
 * the bundled document — this interface describes the full shape without turning the dynamic path on.
 *
 * Resolution precedence is always: verified+valid remote/cached document with the highest [revision]
 * wins; on any failure (no network, bad signature, failed validation) the manager falls back to the
 * last good document and ultimately to the always-present bundled asset. The active document is never
 * empty.
 */
interface SourceUpdateManager {
    val state: StateFlow<UpdateState>

    /** The document currently in effect. Always non-null after construction (bundled is the floor). */
    fun activeDocument(): SourceConfigDocument

    /**
     * Attempt to fetch + verify + validate a newer document and, if it wins on precedence, make it
     * active and cache it. Safe to call when remote is disabled — it then resolves to a no-op success.
     */
    suspend fun refresh(): AppResult<SourceConfigDocument>
}

/** Observable status of the update manager, for diagnostics/settings UI (not consumed in Stage-0). */
sealed interface UpdateState {
    /** Active document resolved from bundled/cache; no refresh in flight. */
    data class Active(val revision: Long, val source: Origin) : UpdateState

    /** A refresh is in progress. */
    data object Refreshing : UpdateState

    /** Last refresh failed; [reason] is human-readable, the previous good document stays active. */
    data class Failed(val reason: String) : UpdateState

    /**
     * The highest-precedence document **accepted** in the last refresh (bundled < cache < remote) —
     * NOT a per-source provenance guarantee. When a higher-priority bundled source overrides a remote
     * one ([me.manga.kira.sources.config] merge), origin may still report `REMOTE` even though the
     * winning source came from bundled. Per-source provenance is a Stage-1 concern; in Stage-0 this
     * value is diagnostic only and not consumed.
     */
    enum class Origin { BUNDLED, CACHE, REMOTE }
}
