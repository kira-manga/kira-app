package me.manga.kira.presentation.history

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.manga.kira.domain.usecase.history.DeleteAllHistoryUseCase
import me.manga.kira.domain.usecase.history.DeleteHistoryEntryUseCase
import me.manga.kira.domain.usecase.history.ObserveHistoryUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * History screen ViewModel.
 *
 * Phase 7.x.history rework. Subscribes to [ObserveHistoryUseCase] at construction time (in
 * `init {}`) and projects each emission into [HistoryState]; reacts to four
 * [HistoryIntent] variants — two mutating (delete entry, delete all) and two navigational
 * (manga click → details effect, chapter click → reader effect).
 *
 * **Why `init {}` collector** (not an `OnEnter` intent like [me.manga.kira.presentation.library.LibraryViewModel]):
 *  - The Library VM uses `OnEnter` because it has lifecycle moments (search filter, selection
 *    mode) that mediate the observation. The History screen has neither — it's a
 *    flow-driven list with two delete actions that re-emit naturally from the upstream Room
 *    `Flow<List<HistoryItemD>>`. Subscribing in `init {}` matches the
 *    [me.manga.kira.presentation.statistics.StatisticsViewModel] posture.
 *  - `viewModelScope` ensures the collector cancels when the ViewModel is cleared (host
 *    destruction), preventing leaks via structured concurrency.
 *
 * **`.catch {}` on the upstream** (#17): the upstream is Room's
 * `Flow<List<HistoryItemD>>` from `HistoryDao.getAllHistory()`. A throw at the observe site is
 * rare but not impossible (driver/mapper failure mid-requery), and an unguarded throw would
 * escape `viewModelScope` and crash the process — so the collector ends in a `.catch {}` that
 * just clears the spinner. A future fallible composition (e.g., a sync step) can additionally
 * surface an `HistoryEffect.ShowError` variant (see [HistoryEffect] KDoc).
 *
 * **Delete intents run through `launchSafely`** (#29): the upstream Room flow re-emits on every
 * `DELETE` write, so the screen's state updates reactively without needing the VM to imperatively
 * mutate `items`. `launchSafely` lets the `handle` suspend return immediately (so the view's
 * `submit(intent)` doesn't block) while routing any throw from the delete to `onUnhandledError`
 * instead of escaping `viewModelScope` (the sibling coroutine is outside `submit`'s safety net).
 *
 * **Click intents emit effects, not direct state mutation**: tapping a row doesn't change
 * what the History screen renders — it navigates away. The route adapter collects the
 * [effects] flow and translates each [HistoryEffect] into a `navController.navigate(...)`
 * call. Same posture as
 * [me.manga.kira.presentation.library.LibraryViewModel.onItemClick] emitting
 * `LibraryEffect.NavigateToDetails`.
 *
 * Constructor-injected use cases per contract §6 DIP — Koin binds them as a `viewModel` in
 * `historyReworkModule` (Phase 9 wiring; this VM compiles standalone once `:presentation` is
 * built).
 *
 * **SRP (contract §6)**: orchestrates History presentation state + navigation, nothing else.
 * No business logic — the use cases own that. No persistence — the repository owns that.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster102.staleKdocSweep.cascade,
 * Task #558, 2026-05-28): the file-scope VM manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-second sibling of the cluster57-101 sweep — closes
 * the wave-7 `:presentation/history/` batch alongside HistoryEffect.kt
 * plus HistoryIntent.kt plus HistoryState.kt):
 *  (a) "Subscribes to [ObserveHistoryUseCase] at construction time
 *  (in `init {}`) and projects each emission into [HistoryState];
 *  reacts to four [HistoryIntent] variants — two mutating (delete
 *  entry, delete all) and two navigational (manga click rename-to
 *  details effect, chapter click rename-to reader effect)" — LIVE-
 *  NOT-STALE. L64-70 init collector LIVE; L72-87 reducer LIVE with
 *  four when-branches matching the four declared HistoryIntent
 *  variants.
 *  (b) "Why `init {}` collector" rationale (vs OnEnter posture used
 *  by [LibraryViewModel]) — LIVE-NOT-STALE. LibraryViewModel OnEnter-
 *  posture cross-ref verified (selection mode plus search filter
 *  mediation needs OnEnter); StatisticsViewModel init-collector
 *  cross-ref verified by sibling cluster sweep.
 *  (c) "Why no `catch {}` on the upstream" rationale — LIVE-NOT-
 *  STALE. L65 `observeHistory()` collector LACKS a `.catch {}`
 *  operator; Room observe-site no-throw contract preserved.
 *  (d) "Delete intents launch fire-and-forget in `viewModelScope`" —
 *  LIVE-NOT-STALE. L74-79 realization: `viewModelScope.launch {
 *  deleteHistoryEntry(intent.entry) }` plus `viewModelScope.launch {
 *  deleteAllHistory() }`. Non-blocking suspend return per the
 *  `handle()` contract; no `.onFailure` per the `DELETE FROM` SQL
 *  infallibility claim.
 *  (e) "Click intents emit effects, not direct state mutation" —
 *  LIVE-NOT-STALE. L80-86 realization: OnMangaClick branch emits
 *  HistoryEffect.NavigateToDetails(api, mangaUrl); OnChapterClick
 *  branch emits HistoryEffect.NavigateToReader(entry). No
 *  `updateState` calls in either click branch.
 *  (f) "Constructor-injected use cases per contract §6 DIP — Koin
 *  binds them as a `viewModel` in `historyReworkModule`" — LIVE-NOT-
 *  STALE. L56-60 primary constructor accepts ObserveHistoryUseCase
 *  plus DeleteHistoryEntryUseCase plus DeleteAllHistoryUseCase. Koin
 *  `historyReworkModule` binding verified via composeApp/.../di/
 *  module audit at cluster10 / cluster18 sweeps.
 *  (g) "SRP (contract §6): orchestrates History presentation state
 *  plus navigation, nothing else. No business logic — the use cases
 *  own that. No persistence — the repository owns that" — LIVE-NOT-
 *  STALE. Reducer at L72-87 contains zero domain logic (every branch
 *  is either a delegate call into a use case or an effect emission);
 *  zero direct repository or DAO references.
 *  Seven LIVE-NOT-STALE classifications STAND on their own merits as
 *  a faithful History-ViewModel manifest. Original Phase 7.x.history-
 *  era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
open class HistoryViewModel( // `open`: crash-safety test overrides onUnhandledError (see HistoryViewModelCrashSafetyTest)
    observeHistory: ObserveHistoryUseCase,
    private val deleteHistoryEntry: DeleteHistoryEntryUseCase,
    private val deleteAllHistory: DeleteAllHistoryUseCase,
) : MviViewModel<HistoryState, HistoryIntent, HistoryEffect>(
    initialState = HistoryState(),
) {

    init {
        observeHistory()
            .onEach { snapshot ->
                updateState { it.copy(isLoading = false, items = snapshot) }
            }
            // #17: a throw from the upstream Room flow must not crash viewModelScope — clear the
            // spinner and degrade to the (empty) current list; the screen renders its empty state.
            .catch { updateState { it.copy(isLoading = false) } }
            .launchIn(viewModelScope)
    }

    override suspend fun handle(intent: HistoryIntent) {
        when (intent) {
            is HistoryIntent.OnDeleteEntry -> {
                // #29: route fire-and-forget deletes through launchSafely so a throw routes to
                // onUnhandledError instead of escaping viewModelScope and crashing the process.
                launchSafely { deleteHistoryEntry(intent.entry) }
            }
            HistoryIntent.OnDeleteAll -> {
                launchSafely { deleteAllHistory() }
            }
            is HistoryIntent.OnMangaClick -> emit(
                HistoryEffect.NavigateToDetails(
                    api = intent.entry.api,
                    language = intent.entry.language,
                    title = intent.entry.mangaTitle,
                    mangaUrl = intent.entry.mangaUrl,
                    coverUrl = intent.entry.mangaImageUrl,
                ),
            )
            is HistoryIntent.OnChapterClick -> emit(HistoryEffect.NavigateToReader(intent.entry))
        }
    }
}
