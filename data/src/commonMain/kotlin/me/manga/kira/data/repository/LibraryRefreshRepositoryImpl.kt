package me.manga.kira.data.repository

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.usecase.library.RefreshAllLibraryChaptersUseCase
import me.manga.kira.platform.jobs.BackgroundJob
import me.manga.kira.platform.jobs.BackgroundJobScheduler
import me.manga.kira.platform.jobs.JobState
import me.manga.kira.domain.repository.LibraryPrefsRepository
import me.manga.kira.domain.repository.LibraryRefreshRepository

/**
 * Strangler-fig [LibraryRefreshRepository] implementation over the legacy `:shared`
 * [BackgroundJobScheduler] expect/actual. Same scheduling shape as the legacy
 * `RefreshViewModel.refreshLibrary` (unique tag `LibraryRefresh`, one-time request, REPLACE
 * policy via re-schedule), so both legacy and rework Library routes share the same
 * cell-of-truth job submission path.
 *
 * Cross-platform behaviour (#1, 2026-06-07): the work runs on every platform.
 *  - **Android** ([BackgroundJobScheduler.dispatchesWorkerClass] == true): enqueue the WorkManager
 *    `LibraryRefreshWorker` (full port in `:app`, with the foreground-service notification +
 *    survives backgrounding). [observeIsRefreshing] reflects the WorkManager job state.
 *  - **Desktop / iOS** (no `Worker` classpath, so the scheduler can't dispatch): run the shared
 *    [RefreshAllLibraryChaptersUseCase] in-process while the screen is open, driving the spinner via
 *    an owned [inlineRefreshing] flag. Re-entry guarded so a double pull-to-refresh doesn't double-run.
 * Android does NOT also run inline (no double dispatch); Desktop/iOS do NOT need a registered job
 * runner. Both paths converge on the same dedup + isNew/fetchedAt persist semantics as Details refresh.
 *
 * SRP (contract §6): owns ONE rule — translate the rework's [refresh] / [observeIsRefreshing]
 * surface into legacy [BackgroundJobScheduler] calls. The `JobState -> Boolean` mapping and the
 * inline-refresh spinner flag ([inlineRefreshing], for the Desktop/iOS in-process path) live here
 * because the legacy `RefreshViewModel` is a `:shared` `ViewModel` whose in-memory tracking can't
 * be shared with rework consumers. Duplicating those pieces is the price of decoupling.
 *
 * DIP (contract §6): depends on the legacy [BackgroundJobScheduler] type because the
 * scheduler is `:shared`'s `expect class`, not (yet) lifted into `:platform`. The dependency
 * is structurally at the strangler-fig boundary — `:data` reaches `:shared` for cross-cutting
 * background-work scheduling, same posture as [ReadingSessionRepositoryImpl] reaching the
 * legacy `StatisticsRepository`. Once Phase 11 ports the worker and the legacy scheduler
 * relocates, this impl swaps its dep without touching either the [LibraryRefreshRepository]
 * interface or any consumer.
 *
 * Lifecycle: `single` in Koin — the [inlineRefreshing] spinner state must be shared across all
 * callers (the use case that triggers [refresh] and the one that reads [observeIsRefreshing]). A
 * `factory` here would produce a fresh `MutableStateFlow` per resolution, breaking the
 * begin/observe pairing on the Desktop/iOS inline path.
 *
 * Threading: [BackgroundJobScheduler.scheduleOneOff] is non-suspend — on Android it calls
 * `WorkManager.enqueue` which is async-internal (returns immediately); iOS/Desktop actuals
 * return synchronously. No explicit dispatcher pinning needed.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster6.staleKdocSweep.cascade,
 * Task #462, 2026-05-28): two stale citations into the §359-retired
 * legacy `:shared/.../refresh/viewmodel/RefreshViewModel.kt` appear
 * above:
 *  - Line 16-17 (opener "Strangler-fig" paragraph): "Same scheduling
 *    shape as the legacy `RefreshViewModel.refreshLibrary` (unique tag
 *    `LibraryRefresh`, one-time request, REPLACE policy via re-
 *    schedule)".
 *  - Line 31 (SRP rationale): "the legacy `RefreshViewModel` is a
 *    `:shared` `ViewModel` consumed by the legacy library route only —
 *    its in-memory job-id tracking can't be shared with rework
 *    consumers. Duplicating those two pieces is the price of
 *    decoupling".
 * The legacy `:shared/.../refresh/viewmodel/RefreshViewModel.kt` was
 * retired in Phase 9.x.refreshvm.retire (§359 sweep, commit `c3cf354`
 * "(1/2): delete unreachable :shared RefreshViewModel"); verified by a
 * filesystem check returning zero hits for that path. The scheduling-
 * shape rationale (unique tag, one-time request, REPLACE policy) and
 * the SRP rationale for in-impl job tracking both stand on their own
 * merits — the rework `LibraryRefreshRepositoryImpl` owns the same
 * `LibraryRefresh`-tagged one-off schedule + `currentJobId`/JobState
 * mapping independent of which legacy file originally implemented the
 * equivalent. The line 31 "consumed by the legacy library route only"
 * fragment is also superseded — the legacy library route itself was
 * retired in Phase 9.x.library.retire (§347 Task #347), so the
 * decoupling rationale is now even more clearly load-bearing: the
 * rework Library route is the SOLE remaining consumer, and the
 * duplication-cost framing applies to the migration history rather
 * than current state. Original §253-era prose preserved verbatim per
 * the audit-trail-preservation convention — the citations are
 * historical record of the design lineage; the rework
 * LibraryRefreshRepositoryImpl continues to schedule + observe via
 * [BackgroundJobScheduler] through the legacy VM retire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRefreshRepositoryImpl(
    private val scheduler: BackgroundJobScheduler,
    // #1 cross-platform: the in-process refresh used where the scheduler can't dispatch a worker
    // (Desktop/iOS). On Android the WorkManager worker runs instead and this stays unused.
    private val refreshAllChapters: RefreshAllLibraryChaptersUseCase,
    // Records the refresh-completion timestamp on the inline path so the "Last updated" header is
    // correct on Desktop/iOS too (Android's LibraryRefreshWorker already writes the same cell).
    private val libraryPrefs: LibraryPrefsRepository,
    private val dispatchers: DispatcherProvider,
) : LibraryRefreshRepository {

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    /** Drives the spinner on platforms that run the refresh inline (Desktop/iOS). */
    private val inlineRefreshing = MutableStateFlow(false)

    /** Terminal outcome of the last inline refresh run (null until one completes). */
    private val lastRefreshResult = MutableStateFlow<AppResult<Int>?>(null)

    override fun refresh() {
        if (scheduler.dispatchesWorkerClass) {
            // Android: enqueue the WorkManager worker (foreground-service notification + survives
            // backgrounding). #8: enqueue as UNIQUE work (uniqueWorkName) so a rapid double
            // pull-to-refresh REPLACEs the in-flight run instead of stacking a second worker
            // (which shared NOTIF_ID 42 and could orphan the spinner).
            // #8: observeIsRefreshing now tracks the unique-work chain by name (observeUniqueWork),
            // so the returned request id is no longer retained — the enqueue runs for its side effect.
            scheduler.scheduleOneOff(
                BackgroundJob(
                    tag = REFRESH_WORK_NAME,
                    workerClass = LIBRARY_REFRESH_WORKER_CLASS,
                    uniqueWorkName = REFRESH_WORK_NAME,
                    // Native enqueued an unconstrained OneTimeWorkRequest (RefreshViewModel.refreshLibrary),
                    // so an offline pull-to-refresh ran immediately and reported per-manga failures via
                    // the worker's foreground notification. The default requiresNetwork = true added a
                    // CONNECTED constraint that left the work ENQUEUED (-> JobState.Idle -> spinner off),
                    // making the gesture look dead offline. requiresNetwork = false restores parity.
                    requiresNetwork = false,
                ),
            )
        } else if (inlineRefreshing.compareAndSet(expect = false, update = true)) {
            // Desktop/iOS: no Worker classpath — run the shared use case in-process while the screen
            // is open. The atomic compareAndSet claim guards against re-entry so two rapid refresh()
            // calls can't both pass the check before the first coroutine starts and double-run.
            scope.launch {
                try {
                    val result = refreshAllChapters()
                    // Publish the terminal outcome so the VM can surface a failure (instead of stale
                    // data presented as a successful refresh — the inline path has no notification).
                    lastRefreshResult.value = result
                    // Record the completion timestamp on success so the "Last updated" header
                    // updates on Desktop/iOS (the Android worker writes the same cell on its path).
                    if (result is AppResult.Success) {
                        libraryPrefs.setLastUpdated(Clock.System.now())
                    }
                } finally {
                    inlineRefreshing.value = false
                }
            }
        }
    }

    override fun observeIsRefreshing(): Flow<Boolean> =
        if (scheduler.dispatchesWorkerClass) {
            // #8: observe the UNIQUE-work chain by name, not a single job id — this survives the
            // ExistingWorkPolicy.REPLACE swap (new request id) that a by-id observer would race
            // against, so the spinner always tracks the live run.
            scheduler.observeUniqueWork(REFRESH_WORK_NAME)
                .map { it == JobState.Running }
        } else {
            inlineRefreshing.asStateFlow()
        }

    override fun observeLastRefreshResult(): Flow<AppResult<Int>?> = lastRefreshResult.asStateFlow()

    private companion object {
        const val REFRESH_WORK_NAME = "LibraryRefresh"
        const val LIBRARY_REFRESH_WORKER_CLASS = "me.manga.kira.work.LibraryRefreshWorker"
    }
}
