package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult

/**
 * Triggers and observes the user-initiated "refresh library" background work.
 *
 * Mirrors the legacy `RefreshViewModel.refreshLibrary` + `isWorkRunning` surface in :shared,
 * lifted to a `:domain` interface so the rework `LibraryViewModel` can depend on it without
 * reaching into `:shared` directly. Implementation lives in `:data` and reaches the legacy
 * [me.manga.kira.core.jobs.BackgroundJobScheduler] (the cell-of-truth) — same strangler-fig
 * posture documented in §147.
 *
 * Refresh runs on every platform (#1, 2026-06-07). On Android the `:data` impl enqueues the ported
 * `LibraryRefreshWorker` (app/.../work/LibraryRefreshWorker.kt) as UNIQUE WorkManager work (REPLACE
 * policy) and tracks it via `observeUniqueWork(...) == Running`. On iOS/Desktop — which have no
 * Worker classpath — the impl runs the shared `RefreshAllLibraryChaptersUseCase` in-process while
 * the screen is open and drives the spinner via an `inlineRefreshing` StateFlow. Either way
 * [observeIsRefreshing] reflects the live run.
 *
 * SRP (contract §6): one rule — gate the user-side refresh trigger + spinner-state observable.
 * No business logic about WHAT to refresh lives here (that's worker-internal once it lands).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster7.staleKdocSweep.cascade,
 * Task #463, 2026-05-28): three stale citations into the §359-retired
 * legacy `:shared/.../refresh/viewmodel/RefreshViewModel.kt` appear
 * above:
 *  - Line 8 (interface opener): "Mirrors the legacy
 *    `RefreshViewModel.refreshLibrary` + `isWorkRunning` surface in
 *    :shared".
 *  - Lines 14-15 ([refresh]/[observeIsRefreshing] worker-port forecast):
 *    "legacy `RefreshViewModel.kt:50-50` flags Phase 11 for the worker".
 *  - Line 29 ([refresh] semantics description): "Matches legacy
 *    `RefreshViewModel.refreshLibrary` semantics (REPLACE policy: a
 *    second call while the first is in-flight cancels the prior schedule
 *    and starts a new one)".
 * The legacy `:shared/.../refresh/viewmodel/RefreshViewModel.kt` was
 * retired in Phase 9.x.refreshvm.retire (§359 sweep, commit `c3cf354`
 * "(1/2): delete unreachable :shared RefreshViewModel"); verified by a
 * filesystem check returning zero hits for that path. The interface-
 * surface design (`refresh()` + `observeIsRefreshing(): Flow<Boolean>`)
 * + REPLACE-policy semantics + Phase-11 worker-port forecast all stand
 * on their own merits — the `:data` impl
 * [me.manga.kira.data.repository.LibraryRefreshRepositoryImpl]
 * continues to schedule + observe via [me.manga.kira.core.jobs.
 * BackgroundJobScheduler] (the cell-of-truth) which REMAINS LIVE post-
 * §359 retire; only the legacy VM that previously orchestrated the
 * `LibraryRefresh`-tagged job was the unreachable orphan. The rework
 * LibraryViewModel + LibraryScreenRoute pair (§313 / §347) is the SOLE
 * remaining consumer post-§347 legacy-library-route retire — the
 * decoupling rationale documented above is doubly load-bearing now.
 * Original §253-era prose preserved verbatim per the audit-trail-
 * preservation convention — the citations are historical record of the
 * design lineage; the rework LibraryRefreshRepository contract continues
 * to surface the documented refresh trigger + spinner observable past
 * the §359 retire.
 */
interface LibraryRefreshRepository {

    /**
     * Enqueue a one-shot library-refresh background job. Fire-and-forget — observe
     * [observeIsRefreshing] to track the spinner state. Matches legacy `RefreshViewModel.
     * refreshLibrary` semantics (REPLACE policy: a second call while the first is in-flight
     * cancels the prior schedule and starts a new one).
     */
    fun refresh()

    /**
     * `true` while a previously-[refresh]ed job is in the Running state, `false` otherwise
     * (Idle / Succeeded / Failed / no job ever scheduled). Drives the pull-to-refresh
     * spinner in the rework Library UI.
     *
     * Cold-and-shareable. Multiple subscribers see the same underlying scheduler-state
     * flow; closing one subscription does not cancel the underlying job tracking.
     */
    fun observeIsRefreshing(): Flow<Boolean>

    /**
     * Terminal outcome of the most recent INLINE refresh run (Desktop/iOS, where the work runs
     * in-process). `null` before any run has completed; otherwise the last run's
     * [AppResult] (`Success(newChapterCount)` or `Failure(error)`). Lets the rework Library VM
     * surface a refresh failure as an error effect instead of silently presenting stale data as a
     * successful refresh. On Android the refresh runs in a WorkManager worker that reports its own
     * failures via notification, so this flow stays `null` there (the boolean [observeIsRefreshing]
     * is the only signal that path emits).
     */
    fun observeLastRefreshResult(): Flow<AppResult<Int>?>
}
