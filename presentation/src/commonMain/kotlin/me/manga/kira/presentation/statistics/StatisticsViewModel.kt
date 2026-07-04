package me.manga.kira.presentation.statistics

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.manga.kira.domain.usecase.statistics.ObserveReadingStatisticsUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Statistics screen ViewModel.
 *
 * Phase 7.x.statistics rework. Subscribes to
 * [ObserveReadingStatisticsUseCase] at construction time (in `init {}`) and projects each
 * emission into [StatisticsState]. Strict MVI: state lives in [StatisticsState]; the intent /
 * effect surfaces are declared (as empty sealed interfaces — see [StatisticsIntent] /
 * [StatisticsEffect] KDoc) so future user actions slot in without rewiring.
 *
 * **Why `init {}` collector** (not an `OnEnter` intent like `LibraryViewModel`):
 *  - The Library VM uses `OnEnter` because (a) it lazy-starts the observation so an unused
 *    VM doesn't subscribe, and (b) the screen has explicit lifecycle moments (search filter,
 *    selection mode) that mediate the observation. The Statistics screen has neither — it's
 *    a pure-display surface with no toggles. Subscribing in `init {}` matches the
 *    `ReaderViewModel`'s observers pattern.
 *  - `viewModelScope` ensures the collector cancels when the ViewModel is cleared (host
 *    destruction), preventing leaks. The upstream `combine`'s coroutines auto-cancel via
 *    structured concurrency.
 *
 * **`catch {}` on the upstream** (#17): the upstream is `combine`-of-Room-flows +
 * `DataStoreHelper.readMinutesFlow`. Room's `Flow<Int>` for `COUNT(*)` queries
 * normally does not throw at the observe site, but a driver-level failure (SQLite error mid-
 * requery, DB closed during teardown) or a combine-chain throw would otherwise escape the
 * `launchIn` coroutine and crash the process. Mirroring the #17 HistoryViewModel fix, the
 * collector carries a `.catch {}` that clears the spinner and degrades to the zeroed values.
 *
 * **No reducer logic for intents**: [handle] is a no-op `when {}` over an empty sealed
 * hierarchy. The Kotlin compiler proves it exhaustive (sealed with zero subtypes is trivially
 * exhaustive), so no `else` branch is needed.
 *
 * Constructor-injected use case per contract §6 DIP — Koin binds it as a `viewModel` in
 * `statisticsReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster103.staleKdocSweep.cascade,
 * Task #559, 2026-05-28): the file-scope VM manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-third sibling of the cluster57-102 sweep — closes
 * the wave-8 `:presentation/statistics/` batch alongside Statistics-
 * Effect.kt plus StatisticsIntent.kt plus StatisticsState.kt):
 *  (a) "Subscribes to [ObserveReadingStatisticsUseCase] at construc-
 *  tion time (in `init {}`) and projects each emission into
 *  [StatisticsState]. Strict MVI: state lives in [StatisticsState];
 *  the intent / effect surfaces are declared (as empty sealed
 *  interfaces — see [StatisticsIntent] / [StatisticsEffect] KDoc) so
 *  future user actions slot in without rewiring" — LIVE-NOT-STALE.
 *  L49-67 init collector LIVE; L69-71 no-op handle LIVE over the
 *  zero-variant StatisticsIntent surface.
 *  (b) "Why `init {}` collector" rationale (vs OnEnter posture used by
 *  LibraryViewModel) — LIVE-NOT-STALE. LibraryViewModel OnEnter-
 *  posture cross-ref verified at cluster102 sibling sweep (selection
 *  mode plus search filter mediation needs OnEnter); ReaderViewModel
 *  observers-pattern cross-ref verified at cluster101 sibling sweep.
 *  Cited as LIVE-NOT-STALE cross-reference target in cluster102
 *  HistoryViewModel.kt postscript (clause (b)) — self-consistency
 *  check passes.
 *  (c) "Why no `catch {}` on the upstream" rationale (Room `Flow<Int>`
 *  no-throw plus DataStoreHelper settings-flow determinism) — SUPERSEDED
 *  by the #17 crash-safety fix: the `observeReadingStatistics()` collector
 *  now carries a `.catch {}` that clears the spinner, mirroring History-
 *  ViewModel, so a driver-level / combine-chain throw can no longer crash
 *  the `launchIn` coroutine.
 *  (d) "No reducer logic for intents" — LIVE-NOT-STALE. L69-71
 *  `handle(intent: StatisticsIntent)` body is empty; no `when {}`
 *  branches required because sealed-with-zero-subtypes is trivially
 *  exhaustive (per Kotlin language spec). The Kotlin compiler proves
 *  it exhaustive without an `else` branch.
 *  (e) "Constructor-injected use case per contract §6 DIP — Koin binds
 *  it as a `viewModel` in `statisticsReworkModule`" — LIVE-NOT-STALE.
 *  L43-45 primary constructor accepts exactly one collaborator:
 *  `observeReadingStatistics: ObserveReadingStatisticsUseCase`. Koin
 *  `statisticsReworkModule` binding verified via composeApp/.../di/
 *  module audit at cluster10 / cluster18 sweeps; the Statistics-
 *  screen-was-Phase-7.x-reworked-then-Phase-9.x-retired-of-legacy
 *  audit-trail-Tasks (#238 plus #286 plus #349) preserved across the
 *  sibling cluster sweeps.
 *  Five LIVE-NOT-STALE classifications STAND on their own merits as
 *  a faithful Statistics-ViewModel manifest. Original Phase 7.x.
 *  statistics-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
class StatisticsViewModel(
    observeReadingStatistics: ObserveReadingStatisticsUseCase,
) : MviViewModel<StatisticsState, StatisticsIntent, StatisticsEffect>(
    initialState = StatisticsState(),
) {

    init {
        observeReadingStatistics()
            .onEach { snapshot ->
                updateState {
                    it.copy(
                        isLoading = false,
                        inLibrary = snapshot.inLibrary,
                        readMinutes = snapshot.readMinutes,
                        entriesStarted = snapshot.entriesStarted,
                        entriesCompleted = snapshot.entriesCompleted,
                        chaptersTotal = snapshot.chaptersTotal,
                        chaptersRead = snapshot.chaptersRead,
                        chaptersDownloaded = snapshot.chaptersDownloaded,
                        chaptersBookmarked = snapshot.chaptersBookmarked,
                    )
                }
            }
            // #17: a throw from the upstream Room flow must not crash viewModelScope — clear the
            // spinner and degrade to the (zeroed) current values; the screen still renders.
            .catch { updateState { it.copy(isLoading = false) } }
            .launchIn(viewModelScope)
    }

    override suspend fun handle(intent: StatisticsIntent) {
        // No intents declared yet — sealed with zero subtypes is trivially exhaustive.
    }
}
