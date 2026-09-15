package me.manga.kira.data.repository

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.map
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.model.library.LibraryRefreshCompleted
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
class LibraryRefreshRepositoryImpl internal constructor(
    private val scheduler: BackgroundJobScheduler,
    private val refreshAllChapters: suspend () -> AppResult<LibraryRefreshCompleted>,
    private val stampLastSuccess: suspend () -> Unit,
    private val scope: CoroutineScope,
) : LibraryRefreshRepository {
    constructor(
        scheduler: BackgroundJobScheduler,
        refreshAllChapters: RefreshAllLibraryChaptersUseCase,
        libraryPrefs: LibraryPrefsRepository,
        dispatchers: DispatcherProvider,
    ) : this(
        scheduler = scheduler,
        refreshAllChapters = { refreshAllChapters() },
        stampLastSuccess = { libraryPrefs.setLastUpdated(Clock.System.now()) },
        scope = CoroutineScope(dispatchers.io + SupervisorJob()),
    )

    /** Drives the spinner on platforms that run the refresh inline (Desktop/iOS). */
    private val inlineRefreshing = MutableStateFlow(false)

    /**
     * Terminal outcome of the last inline refresh run (null until one completes).
     * SharedFlow retains the last outcome without StateFlow's equality conflation: two failed
     * attempts with the same typed error must both reach an active Library VM collector.
     */
    private val lastRefreshResult = MutableSharedFlow<AppResult<Int>?>(replay = 1).apply { tryEmit(null) }

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
        } else {
            refreshInline()
        }
    }

    private fun refreshInline() {
        // Claim before launching, so concurrent gestures cannot double-run on Desktop/iOS.
        if (!inlineRefreshing.compareAndSet(expect = false, update = true)) return
        val job =
            scope.launch {
                runCatchingCancellable {
                    val result = refreshAndStamp()
                    currentCoroutineContext().ensureActive()
                    lastRefreshResult.emit(result)
                }.onFailure { t ->
                    currentCoroutineContext().ensureActive()
                    lastRefreshResult.emit(
                        AppResult.Failure(AppError.Unexpected(message = "Library refresh failed", cause = t)),
                    )
                }
            }
        // Also clears a claim when the scope was cancelled before the launch body started.
        job.invokeOnCompletion { inlineRefreshing.value = false }
    }

    private suspend fun refreshAndStamp(): AppResult<Int> {
        val result = refreshAllChapters()
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Success && result.value.snapshotSize > 0) {
            val stampFailure = runCatchingCancellable { stampLastSuccess() }.exceptionOrNull()
            if (stampFailure != null) return AppResult.Failure(AppError.Storage.Io(stampFailure))
        }
        // A metadata write may already have committed when cancellation arrives. Never roll it
        // back or publish success from the cancelled run; there is no cross-operation transaction.
        currentCoroutineContext().ensureActive()
        return result.map { it.newChapterCount }
    }

    override fun observeIsRefreshing(): Flow<Boolean> =
        if (scheduler.dispatchesWorkerClass) {
            // #8: observe the UNIQUE-work chain by name, not a single job id — this survives the
            // ExistingWorkPolicy.REPLACE swap (new request id) that a by-id observer would race
            // against, so the spinner always tracks the live run.
            scheduler
                .observeUniqueWork(REFRESH_WORK_NAME)
                .map { it == JobState.Running }
        } else {
            inlineRefreshing.asStateFlow()
        }

    override fun observeLastRefreshResult(): Flow<AppResult<Int>?> = lastRefreshResult.asSharedFlow()

    private companion object {
        const val REFRESH_WORK_NAME = "LibraryRefresh"
        const val LIBRARY_REFRESH_WORKER_CLASS = "me.manga.kira.work.LibraryRefreshWorker"
    }
}
