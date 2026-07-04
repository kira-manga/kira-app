package me.manga.kira.presentation.updates

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.manga.kira.core.error.AppError
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.updates.DeleteAllUpdatesUseCase
import me.manga.kira.domain.usecase.updates.DeleteUpdateEntryUseCase
import me.manga.kira.domain.usecase.updates.MarkAllUpdatesAsReadUseCase
import me.manga.kira.domain.usecase.updates.MarkUpdateAsReadUseCase
import me.manga.kira.domain.usecase.updates.ObserveUpdatesUseCase
import me.manga.kira.domain.usecase.updates.RestoreUpdateEntryUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Updates screen ViewModel.
 *
 * Phase 7.x.updates rework. Subscribes to [ObserveUpdatesUseCase] at construction time (in
 * `init {}`) and projects each emission into [UpdatesState]; reacts to six [UpdatesIntent]
 * variants — four mutating (mark-read per-entry, mark-all-read, delete-entry, delete-all) and
 * two navigational (manga click → details effect, chapter click → reader effect).
 *
 * **Why `init {}` collector** (not an `OnEnter` intent like
 * [me.manga.kira.presentation.library.LibraryViewModel]):
 *  - The Library VM uses `OnEnter` because it has lifecycle moments (search filter, selection
 *    mode) that mediate the observation. The Updates screen has neither — it's a flow-driven
 *    list with mutate-and-re-emit actions from the upstream Room
 *    `Flow<List<ChapterNotification>>`. Subscribing in `init {}` matches the
 *    [me.manga.kira.presentation.history.HistoryViewModel] posture.
 *  - `viewModelScope` ensures the collector cancels when the ViewModel is cleared (host
 *    destruction), preventing leaks via structured concurrency.
 *
 * **Why no `catch {}` on the upstream**: the upstream is Room's
 * `Flow<List<ChapterNotification>>` from `NotificationDao.getAllNotifications()` (via the
 * legacy facade's `getGroupedNotifications`). Room's observe-site does not throw — it emits
 * the current rows and re-emits on every tracked write. If a future refactor introduces a
 * fallible upstream (e.g., a sync step layered onto the flow), add `.catch {}` here and an
 * `UpdatesEffect.ShowError` variant (see [UpdatesEffect] KDoc).
 *
 * **Mutating intents launch fire-and-forget in `viewModelScope`**: the upstream Room flow
 * re-emits on every `UPDATE`/`DELETE` write, so the screen's state updates reactively without
 * needing the VM to imperatively mutate `items`. The `launch {}` lets the `handle` suspend
 * return immediately (so the view's `submit(intent)` doesn't block); the mutation itself
 * completes on the use case's coroutine. `UPDATE notifications SET isRead = 1` /
 * `DELETE FROM notifications` SQL are structurally infallible — no `.onFailure` is needed
 * (cf. [UpdatesEffect] KDoc on the lack of `ShowError`).
 *
 * **Click intents emit effects, not direct state mutation**: tapping a row doesn't change
 * what the Updates screen renders — it navigates away. The route adapter collects the
 * [effects] flow and translates each [UpdatesEffect] into a `navController.navigate(...)`
 * call. Same posture as [HistoryViewModel].
 *
 * Constructor-injected use cases per contract §6 DIP — Koin binds them as a `viewModel` in
 * `updatesReworkModule`.
 *
 * **SRP (contract §6)**: orchestrates Updates presentation state + navigation, nothing else.
 * No business logic — the use cases own that. No persistence — the repository owns that. No
 * date-grouping — the `:ui` composable owns that (the rework deliberately converges History
 * and Updates on a single date-label idiom in `:ui` rather than splitting the regroup logic
 * across `:data`, `:domain`, or `:presentation`).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster108.staleKdocSweep.cascade,
 * Task #564, 2026-05-28): the file-scope VM manifest above is classified
 * as follows after recursive symbol verification across the KMP graph
 * (forty-eighth sibling of the cluster57-107 sweep — closes the wave-9
 * `:presentation/updates/` batch alongside UpdatesEffect.kt plus Updates-
 * State.kt):
 *  (a) "Foundation slice landed six intent variants — four mutating
 *  (mark-read per-entry, mark-all-read, delete-entry, delete-all) and
 *  two navigational (manga click rename-to details effect, chapter
 *  click rename-to reader effect)" — STALE-SUPERSEDED for the count,
 *  LIVE for the conceptual classification. L81-125 handle now realizes
 *  TEN branches: foundation 6 (OnMarkAsRead, OnMarkAllAsRead, OnDelete-
 *  Entry, OnDeleteAll, OnMangaClick, OnChapterClick) plus Phase 7.x.
 *  updates.downloadbutton.wire 1 (OnDownloadClick at L102-112) plus
 *  Phase 7.x.updates.undosnackbar 3 (OnRequestDelete at L113-116 plus
 *  OnUndoDelete at L117-119 plus OnConfirmDelete at L120-123). Foundation
 *  prose preserved verbatim per audit-trail convention; the count drift
 *  from six to ten is registered here (not by rewriting the original).
 *  (b) "Init {} collector — peer cross-ref to HistoryViewModel posture
 *  (not LibraryViewModel OnEnter)" — LIVE-NOT-STALE. L73-79 init block
 *  hosts the single observeUpdates() collector; HistoryViewModel init-
 *  collector posture verified at cluster104 sweep (Task #560); Library-
 *  ViewModel OnEnter contrast verified at cluster34 sweep (Task #490)
 *  — Library has explicit lifecycle moments; Updates doesn't.
 *  (c) "No `catch {}` on the upstream — Room NotificationDao.getAll-
 *  Notifications() flow doesn't throw at the observe site" — LIVE-NOT-
 *  STALE. L73-79 collector LACKS `.catch {}` operator; same impl-
 *  boundary posture as the History/Statistics siblings.
 *  (d) "Mutating intents launch fire-and-forget in viewModelScope" —
 *  LIVE-NOT-STALE for the foundation 4 (OnMarkAsRead at L83-85 plus
 *  OnMarkAllAsRead at L86-88 plus OnDeleteEntry at L89-91 plus OnDelete-
 *  All at L92-94) — each wraps the use case call in viewModelScope.
 *  launch{}; no `.onFailure` chain attached. SUPERSEDED-BUT-COMPATIBLE
 *  for the Phase 7.x.updates.downloadbutton.wire OnDownloadClick at
 *  L102-112 which DOES attach `.onFailure { throwable -> emit(Updates-
 *  Effect.ShowError(...)) }` — first fallible-mutation site on this VM.
 *  (e) "Click intents emit effects not direct state mutation — route
 *  adapter collects effects flow plus translates each UpdatesEffect into
 *  navController.navigate(...)" — LIVE-NOT-STALE. L95-100 OnMangaClick
 *  emits NavigateToDetails(api, mangaUrl); L101 OnChapterClick emits
 *  NavigateToReader(entry). Same posture as HistoryViewModel verified
 *  at cluster104 sweep (Task #560). PARITY-FIX (Updates feed audit):
 *  OnChapterClick now additionally launches markUpdateAsRead(entry) when
 *  !entry.isRead BEFORE emitting NavigateToReader, mirroring native's
 *  onNotificationClick = { markAsRead(chapterId); navigate } (native
 *  UpdatesScreen.kt:217-220). The !isRead guard is load-bearing: the DAO
 *  query is a toggle (isRead = NOT isRead), so re-marking a read row
 *  would flip it back to unread. Unlike OnMangaClick this branch is no
 *  longer a pure effect-emit.
 *  (f) "Phase 7.x.updates.undosnackbar soft-delete trio (OnRequestDelete
 *  stages plus emits ShowUndoSnackbar; OnUndoDelete unstages; OnConfirm-
 *  Delete unstages plus calls deleteUpdateEntry use case)" — LIVE-NOT-
 *  STALE. L113-123 three-branch realization: OnRequestDelete adds id to
 *  pendingDeleteIds plus emits ShowUndoSnackbar(entry); OnUndoDelete
 *  removes id; OnConfirmDelete removes id AND launches deleteUpdate-
 *  Entry(entry) in viewModelScope. Order in OnConfirmDelete (unstage-
 *  first then delete) ensures the screen's visibleItems re-includes the
 *  entry before the Room re-emit fires.
 *  (g) "Phase 7.x.updates.downloadbutton.wire OnDownloadClick onFailure-
 *  emit-ShowError" — LIVE-NOT-STALE. L102-112 realization: launches
 *  enqueueDownload(chapterId, mangaTitle, api) and chains `.onFailure {
 *  throwable -> emit(UpdatesEffect.ShowError(throwable.message ?:
 *  "Download failed")) }`. The message fallback chain is the throwable's
 *  message field falling back to the literal "Download failed" (NOT the
 *  e::class.simpleName fallback used by SettingsViewModel / Sources-
 *  ViewModel) — minor posture divergence noted but documented as
 *  intentional (a single-string snackbar from a single fallible call).
 *  Seven classifications STAND on their own merits as a faithful
 *  UpdatesViewModel manifest. Original Phase 7.x.updates-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
open class UpdatesViewModel( // `open`: crash-safety test overrides onUnhandledError (see UpdatesViewModelCrashSafetyTest)
    private val observeUpdates: ObserveUpdatesUseCase,
    private val markUpdateAsRead: MarkUpdateAsReadUseCase,
    private val markAllUpdatesAsRead: MarkAllUpdatesAsReadUseCase,
    private val deleteUpdateEntry: DeleteUpdateEntryUseCase,
    private val restoreUpdateEntry: RestoreUpdateEntryUseCase,
    private val deleteAllUpdates: DeleteAllUpdatesUseCase,
    private val enqueueDownload: EnqueueDownloadUseCase,
    observeDownloads: ObserveDownloadsUseCase,
) : MviViewModel<UpdatesState, UpdatesIntent, UpdatesEffect>(
    initialState = UpdatesState(),
) {


    /**
     * Tracks the [observeUpdates] collector so [UpdatesIntent.OnRetry] can cancel-before-relaunch
     * it (the cancel-before-relaunch tracked-Job pattern used by the reader VM). The `.catch {}`
     * below TERMINATES the flow on an upstream throw, so a retry cannot resume the dead collector —
     * it must launch a fresh one, and the stale job must be cancelled first so two collectors never
     * race writes into `items`.
     */
    private var observeJob: Job? = null

    init {
        subscribeToUpdates()

        // GAP-UPD-03: project the live download queue/history into state so each row can
        // reflect queued / running / done progress (native parity with the legacy row's
        // queuedChapterIds + runningChapter derivation). Second independent collector over the
        // Room downloads flow; both cancel with viewModelScope on host destruction.
        observeDownloads()
            .onEach { downloads ->
                updateState { it.copy(downloads = downloads) }
            }
            .launchIn(viewModelScope)
    }

    override suspend fun handle(intent: UpdatesIntent) {
        when (intent) {
            is UpdatesIntent.OnMarkAsRead -> {
                // #29: launchSafely so a throw routes to onUnhandledError, not viewModelScope crash.
                launchSafely { markUpdateAsRead(intent.entry) }
            }
            UpdatesIntent.OnMarkAllAsRead -> {
                launchSafely { markAllUpdatesAsRead() }
            }
            is UpdatesIntent.OnDeleteEntry -> {
                launchSafely { deleteUpdateEntry(intent.entry) }
            }
            UpdatesIntent.OnDeleteAll -> {
                launchSafely { deleteAllUpdates() }
            }
            UpdatesIntent.OnRetry -> {
                // The `.catch {}` in subscribeToUpdates() ended the collector on the last upstream
                // throw, so a re-emit can't clear loadError — re-subscribe with a fresh collector.
                // Reset to loading first so the error surface gives way to the spinner immediately.
                updateState { it.copy(isLoading = true, loadError = null) }
                subscribeToUpdates()
            }
            is UpdatesIntent.OnMangaClick -> emit(
                UpdatesEffect.NavigateToDetails(
                    api = intent.entry.api,
                    language = intent.entry.language,
                    title = intent.entry.mangaTitle,
                    mangaUrl = intent.entry.mangaUrl,
                    coverUrl = intent.entry.mangaImageUrl,
                ),
            )
            is UpdatesIntent.OnChapterClick -> {
                // Native parity (UpdatesScreen.kt:217-220): tapping the row body marks the
                // chapter read BEFORE navigating to the reader. Guarded by !isRead because the
                // underlying DAO query is a toggle (UPDATE notifications SET isRead = NOT isRead)
                // — re-marking an already-read entry would flip it back to unread.
                if (!intent.entry.isRead) {
                    launchSafely { markUpdateAsRead(intent.entry) }
                }
                emit(UpdatesEffect.NavigateToReader(intent.entry))
            }
            is UpdatesIntent.OnDownloadClick -> {
                viewModelScope.launch {
                    enqueueDownload(
                        chapterId = intent.entry.chapterId,
                        mangaTitle = intent.entry.mangaTitle,
                        api = intent.entry.api,
                    ).onFailure { throwable ->
                        Logger.withTag(TAG).w(throwable) { "enqueue download failed" }
                        emit(UpdatesEffect.ShowDownloadEnqueueFailed)
                    }
                }
            }
            is UpdatesIntent.OnRequestDelete -> {
                // Native parity (deleteWithUndo): delete IMMEDIATELY, then offer Undo. This makes
                // the snackbar's Undo authoritative and removes the data-loss race the old fixed
                // auto-finalize timer had — an accessibility-extended snackbar (TalkBack raises the
                // dismiss timeout well past any fixed window) could previously keep "Undo" visible
                // AFTER the timer had already permanently deleted the row with no restore path.
                // Navigating away simply leaves the (already-applied) delete in place, which is the
                // user's intent. The optimistic pendingDeleteIds hide avoids any flash before the
                // observeUpdates flow re-emits without the row.
                val entry = intent.entry
                updateState { it.copy(pendingDeleteIds = it.pendingDeleteIds + entry.id) }
                emit(UpdatesEffect.ShowUndoSnackbar(entry))
                launchSafely { deleteUpdateEntry(entry) }
            }
            is UpdatesIntent.OnUndoDelete -> {
                // Re-insert the row (preserves id/date/position) and reveal it again. Await the
                // restore and surface a failure: this is the worst-shaped silent write to swallow
                // because the user explicitly tapped Undo to recover the row the app just deleted —
                // if the re-insert throws (Room write failure, disk full) the pendingDeleteIds hide
                // is already lifted and the observeUpdates flow never brings the row back, so without
                // feedback the undo reads as successful while the entry is permanently lost.
                val entry = intent.entry
                updateState { it.copy(pendingDeleteIds = it.pendingDeleteIds - entry.id) }
                runCatchingCancellable { restoreUpdateEntry(entry) }
                    .onFailure { throwable ->
                        Logger.withTag(TAG).w(throwable) { "restore update entry failed" }
                        emit(UpdatesEffect.ShowUndoFailed)
                    }
            }
            is UpdatesIntent.OnConfirmDelete -> {
                // Snackbar dismissed without Undo: nothing to delete (already done on request);
                // just drop the optimistic-hide id so the set doesn't linger.
                updateState { it.copy(pendingDeleteIds = it.pendingDeleteIds - intent.entry.id) }
            }
        }
    }

    /**
     * (Re)subscribe the [observeUpdates] collector. Cancels any prior [observeJob] first
     * (cancel-before-relaunch) so [UpdatesIntent.OnRetry] can recover from the terminal `.catch {}`
     * without leaving two collectors racing writes into `items`. Called once from `init {}` and
     * again on every retry.
     */
    private fun subscribeToUpdates() {
        observeJob?.cancel()
        observeJob = observeUpdates()
            .onEach { snapshot ->
                updateState { it.copy(isLoading = false, items = snapshot, loadError = null) }
            }
            // Parity fix (updates-refresh "error message rendering"): native's
            // NotificationsViewModel (NotificationsViewModel.kt:40-49) guards the grouped-
            // notifications flow with `.catch {}`, projecting the throwable into `errorMessage`
            // and clearing the list so the screen can render an inline error instead of crashing
            // the collector. Mirrors that here — Room's observe site rarely throws, but a mapping
            // failure in `toDomain` (or any future fallible upstream) would otherwise propagate
            // uncaught. `catch` only fires on UPSTREAM failures; downstream collector exceptions
            // are not swallowed. NOTE: `catch` ends the flow — recovery is via OnRetry, which
            // relaunches this collector (a re-emit alone cannot revive a caught flow).
            .catch { throwable ->
                updateState {
                    it.copy(
                        isLoading = false,
                        items = emptyList(),
                        loadError = AppError.Unexpected(message = throwable.message ?: "failed to load updates", cause = throwable),
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private companion object {
        const val TAG = "UpdatesViewModel"
    }
}
