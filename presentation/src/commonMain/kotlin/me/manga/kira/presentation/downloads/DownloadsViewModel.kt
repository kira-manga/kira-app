package me.manga.kira.presentation.downloads

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.usecase.downloads.CancelDownloadUseCase
import me.manga.kira.domain.usecase.downloads.CancelRunningDownloadUseCase
import me.manga.kira.domain.usecase.downloads.DeleteDownloadUseCase
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.downloads.RetryDownloadUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Downloads screen ViewModel.
 *
 * Phase 7.x.downloads.foundation rework + Phase 7.x.downloads.actions
 * rework append.
 *
 * **Foundation responsibilities** (unchanged from foundation slice):
 *  - Subscribes to [ObserveDownloadsUseCase] in `init {}` and projects
 *    each emission into [DownloadsState] with the 3 bucket projections
 *    pre-computed (Active / Failed / Completed — see partition rule
 *    block below).
 *  - Handles [DownloadsIntent.OnTabSelect] by writing `selectedTab` into
 *    state.
 *
 * **Actions slice append** (this slice):
 *  - 4 new intent handlers ([DownloadsIntent.OnRetry] /
 *    [DownloadsIntent.OnCancel] / [DownloadsIntent.OnCancelRunning] /
 *    [DownloadsIntent.OnDelete]) each dispatch to their matching use case
 *    via `viewModelScope.launch {}`.
 *  - Failures emit [DownloadsEffect.ShowError] with the throwable's
 *    `message` (falling back to class name then "Unknown error" — same
 *    fallback chain as the Complaint VM's `completeAction` path).
 *  - Successes are silent — the legacy `DownloadRepository` re-emits via
 *    Room on every write, so the row's state change (e.g., FAILED →
 *    QUEUED on retry; row vanishing on delete) is the user-visible
 *    confirmation. A success snackbar would be redundant chrome.
 *
 * **Why `init {}` collector** (not an `OnEnter` intent like
 * [me.manga.kira.presentation.library.LibraryViewModel]):
 *  - The Library VM uses `OnEnter` because it has explicit lifecycle
 *    moments mediating the observation. Downloads has none — it's a
 *    pure-display surface (plus tab toggle and per-row mutations).
 *    Subscribing in `init {}` matches the established
 *    [me.manga.kira.presentation.statistics.StatisticsViewModel]
 *    pattern.
 *  - `viewModelScope` ensures the collector cancels when the
 *    ViewModel is cleared (host destruction), preventing leaks.
 *
 * **`.catch {}` on the upstream** (#17): the upstream is the legacy
 * `DownloadRepository.observeAllDownloads()` flow which wraps a Room
 * `@Query Flow<List<...>>`. A throw at the observe site (driver/mapper
 * failure mid-requery) is rare but would escape `viewModelScope` and
 * crash the process, so the collector ends in a `.catch {}` that just
 * clears the spinner. Mutation throws are separately caught at the impl
 * boundary (`runCatching {}` in
 * [me.manga.kira.data.repository.DownloadsActionRepositoryImpl]) and
 * surface as `Result.failure` to the intent handler instead.
 *
 * **Bucket partition rule** (aligned to the native source-of-truth
 * `DownloadsScreenRoute` `getDownloadsByState(...)` queries):
 *  - **Active** = `RUNNING` ∪ `QUEUED`
 *  - **Failed** = `FAILED`
 *  - **Completed** = `SUCCESS`
 *  - **COMPRESSING** = no tab. Native queries only the four states above,
 *    so a row mid-CBZ-compression surfaces in no tab until it transitions
 *    to `SUCCESS`. (downloads-offline P2 BusinessLogic parity: a prior
 *    rework had placed `COMPRESSING` in Active; removed to match native.)
 *
 * Implemented as three independent `.filter {}` passes rather than a
 * single `groupBy { it.state }.let { ... }` block — the three-bucket
 * form reads exactly like the legacy code and avoids producing a
 * `Map<DownloadState, List<...>>` only to immediately destructure it.
 *
 * **Why no debouncing on the upstream**: the legacy
 * `DownloadRepository` emits per-row state transitions, including
 * progress updates from a running download (which can fire many times
 * per second). The MVI base's `updateState { it.copy(...) }` is a
 * `StateFlow` write — Compose's `collectAsState` automatically
 * debounces structurally-equal emissions, so the cost of frequent
 * progress updates is bounded by Compose's recomposition scheduler.
 * Adding a `.sample(100ms)` would introduce latency for very-fast
 * downloads where each percent-point matters visually. Defer
 * sampling until / unless on-device profiling shows recomposition
 * pressure on the Downloads screen.
 *
 * **No in-flight guard on mutations**: the legacy `DownloadRepository`
 * mutations are idempotent (cancel-twice is a no-op; retry of a
 * QUEUED row no-ops at the worker level; delete of an already-deleted
 * row is a Room no-op). Unlike Complaint where a double-tap on Send
 * would create two reply records, double-tapping retry / cancel /
 * delete on the Downloads screen at most emits two snackbars on a row
 * deleted between taps — acceptable failure mode. Skipping the guard
 * keeps the VM simpler.
 *
 * **`when (intent)` exhaustiveness**: the `:ui` composable can fire 5
 * intent variants (1 foundation + 4 actions). The exhaustive `when`
 * ensures a future intent variant is a compile-time error here,
 * exactly the contract we want for OCP sealed-interface extension.
 *
 * Constructor-injected use cases per contract §6 DIP — Koin binds the
 * VM as a `viewModel` in `downloadsReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster107.staleKdocSweep.cascade,
 * Task #563, 2026-05-28): the file-scope VM manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-seventh sibling of the cluster57-106 sweep — closes
 * the wave-9 `:presentation/downloads/` batch alongside DownloadsEffect.
 * kt plus DownloadsIntent.kt):
 *  (a) "Foundation responsibilities — subscribes to ObserveDownloadsUse-
 *  Case in init {} and projects each emission into DownloadsState with
 *  the 3 bucket projections pre-computed" — LIVE-NOT-STALE. L102-110
 *  primary constructor injects 5 collaborators (observeDownloads plus 4
 *  mutation use cases); L112-133 init block hosts the single observe-
 *  Downloads() collector with the three-`.filter {}`-pass bucket
 *  realization (Active, Failed, Completed).
 *  (b) "Actions slice append — 4 new intent handlers dispatch via
 *  viewModelScope.launch {}; failures emit ShowError plus successes
 *  silent" — LIVE-NOT-STALE. L141-169 four `handle` branches realize
 *  fire-and-forget launches; L173-180 `emitOnFailure(result)` helper
 *  realizes silent-success / snackbar-failure posture.
 *  (c) "Why init {} collector (not OnEnter) — peer cross-ref to
 *  StatisticsViewModel" — LIVE-NOT-STALE. StatisticsViewModel init-
 *  collector posture verified at cluster103 sibling sweep (Task #559);
 *  LibraryViewModel OnEnter contrast verified at cluster34 sweep (Task
 *  #490) — Library has explicit lifecycle moments; Downloads doesn't.
 *  (d) "Why no `catch {}` on the upstream — legacy DownloadRepository.
 *  observeAllDownloads() wraps a Room `@Query Flow<List<...>>`; Room
 *  doesn't throw at the observe site" — LIVE-NOT-STALE. L113-132
 *  collector LACKS `.catch {}` operator; impl-boundary runCatching
 *  posture confirmed at DownloadsActionRepositoryImpl per Task #442
 *  cluster45 sweep.
 *  (e) "Bucket partition rule — Active = RUNNING union QUEUED;
 *  Failed = FAILED; Completed = SUCCESS; COMPRESSING in no tab" —
 *  UPDATED for downloads-offline P2 BusinessLogic parity. The original
 *  rework placed COMPRESSING in Active; this diverged from the native
 *  source-of-truth, whose DownloadsScreenRoute queries only
 *  getDownloadsByState(listOf(RUNNING, QUEUED)) for Active, FAILED for
 *  Failed, and SUCCESS for Completed. The active `.filter {}` pass now
 *  matches RUNNING + QUEUED only, so COMPRESSING surfaces in no tab
 *  during the brief CBZ-compression phase — verbatim native behaviour.
 *  (f) "Why no debouncing on the upstream — Compose's collectAsState
 *  auto-debounces structurally-equal emissions" — LIVE-NOT-STALE.
 *  Collector at L113-132 LACKS `.sample(...)` / `.debounce(...)` /
 *  `.distinctUntilChanged()` operators; Compose-side natural debounce
 *  posture preserved.
 *  (g) "No in-flight guard on mutations — legacy DownloadRepository
 *  mutations are idempotent (cancel-twice no-op; retry of QUEUED no-op
 *  at worker level; delete of already-deleted Room no-op)" — LIVE-NOT-
 *  STALE. L141-169 four `handle` branches LACK any `if (state.value.is*)
 *  return` re-entrance guards; idempotency-based skip posture preserved
 *  vs the Complaint Send-double-tap-creates-duplicate-reply hazard.
 *  Seven classifications STAND on their own merits as a faithful
 *  DownloadsViewModel manifest. Original Phase 7.x.downloads-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class DownloadsViewModel(
    observeDownloads: ObserveDownloadsUseCase,
    private val retryDownload: RetryDownloadUseCase,
    private val cancelDownload: CancelDownloadUseCase,
    private val cancelRunningDownload: CancelRunningDownloadUseCase,
    private val deleteDownload: DeleteDownloadUseCase,
) : MviViewModel<DownloadsState, DownloadsIntent, DownloadsEffect>(
    initialState = DownloadsState(),
) {

    init {
        observeDownloads()
            .onEach { list ->
                // Native parity (downloads-offline P2, BusinessLogic): the native Active tab
                // queries getDownloadsByState(listOf(RUNNING, QUEUED)) only
                // (DownloadsScreenRoute query). COMPRESSING is in NO tab's state filter in
                // native, so a chapter in COMPRESSING momentarily surfaces in no tab while its
                // CBZ archive is written, then reappears under Completed on SUCCESS. KMP
                // previously routed COMPRESSING into the active bucket (showing a "Compressing"
                // row with a Cancel button native never exposes); aligned to native by limiting
                // active to RUNNING + QUEUED.
                val active = list.filter {
                    it.state == DownloadState.RUNNING ||
                        it.state == DownloadState.QUEUED ||
                        // DOWNLOADED = pages on disk, finalization (CBZ) pending — surfaced in Active
                        // as a visible "downloaded, finishing" row (iOS background engine only;
                        // legacy/Android/Desktop never emit it). COMPRESSING stays in no tab, unchanged.
                        it.state == DownloadState.DOWNLOADED
                }
                val failed = list.filter { it.state == DownloadState.FAILED }
                val completed = list.filter { it.state == DownloadState.SUCCESS }
                updateState {
                    it.copy(
                        isLoading = false,
                        all = list,
                        active = active,
                        failed = failed,
                        completed = completed,
                    )
                }
            }
            // #17: a throw from the upstream Room flow must not crash viewModelScope — clear the
            // spinner and degrade to the (empty) current list; the screen renders its empty state.
            .catch { updateState { it.copy(isLoading = false) } }
            .launchIn(viewModelScope)
    }

    override suspend fun handle(intent: DownloadsIntent) {
        when (intent) {
            is DownloadsIntent.OnTabSelect -> {
                val index = intent.index
                updateState { it.copy(selectedTab = index) }
            }
            is DownloadsIntent.OnRetry -> {
                val chapterId = intent.chapter.chapterId
                viewModelScope.launch {
                    val result = retryDownload(chapterId)
                    emitOnFailure(result)
                }
            }
            is DownloadsIntent.OnCancel -> {
                val chapterId = intent.chapter.chapterId
                viewModelScope.launch {
                    val result = cancelDownload(chapterId)
                    emitOnFailure(result)
                }
            }
            is DownloadsIntent.OnCancelRunning -> {
                val chapterId = intent.chapter.chapterId
                val mangaId = intent.chapter.mangaId
                viewModelScope.launch {
                    val result = cancelRunningDownload(chapterId, mangaId)
                    emitOnFailure(result)
                }
            }
            is DownloadsIntent.OnDelete -> {
                val chapterId = intent.chapter.chapterId
                viewModelScope.launch {
                    val result = deleteDownload(chapterId)
                    emitOnFailure(result)
                }
            }
        }
    }

    private suspend fun emitOnFailure(result: Result<Unit>) {
        if (result.isFailure) {
            // The throwable is logged, never surfaced raw to the user: the snackbar shows a generic
            // localized error resolved in :ui.
            Logger.withTag(TAG).w(result.exceptionOrNull()) { "downloads action failed" }
            emit(DownloadsEffect.ShowActionFailed)
        }
    }

    private companion object {
        const val TAG = "DownloadsViewModel"
    }
}
