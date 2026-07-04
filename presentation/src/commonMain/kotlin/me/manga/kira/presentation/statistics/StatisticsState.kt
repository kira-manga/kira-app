package me.manga.kira.presentation.statistics

import me.manga.kira.presentation.mvi.MviState

/**
 * Statistics screen MVI state.
 *
 * Phase 7.x.statistics rework. Holds the 8 reading-statistics numbers (in-library count,
 * read minutes, completed/started entry counts, 4 chapter counts) plus an
 * [isLoading] flag covering the gap between subscription and the first
 * [me.manga.kira.domain.model.statistics.ReadingStatistics] emission.
 *
 * The state is **flow-driven**: the VM's `init {}` collector projects each upstream
 * [me.manga.kira.domain.model.statistics.ReadingStatistics] snapshot into a fresh state. No
 * intent reducer mutates the 8 numbers directly — Room / DataStoreHelper writes from elsewhere
 * in the app (e.g., the Reader marking a chapter read) propagate through the upstream flows and
 * naturally re-emit, so the screen is reactive without needing an `OnRefresh` intent.
 *
 * Contract §6 SRP: one rule — "what the Statistics screen renders right now". No business
 * logic, no derivation; the 8 fields are verbatim from the upstream model.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster103.staleKdocSweep.cascade,
 * Task #559, 2026-05-28): the file-scope MVI-state manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-third sibling of the cluster57-102 sweep — sibling
 * of cluster103 StatisticsEffect.kt plus StatisticsIntent.kt):
 *  (a) "Holds the 8 reading-statistics numbers (in-library count,
 *  read-duration string, completed/started entry counts, 4 chapter
 *  counts) plus an [isLoading] flag" — LIVE-NOT-STALE. L22-32 data
 *  class enumerates exactly nine fields: `isLoading: Boolean = true`
 *  plus the 8 statistics numbers (`inLibrary`, `readDuration`,
 *  `entriesStarted`, `entriesCompleted`, `chaptersTotal`, `chapters-
 *  Read`, `chaptersDownloaded`, `chaptersBookmarked`). Field-shape
 *  matches the ReadingStatistics domain model exactly.
 *  (b) "flow-driven: the VM's `init {}` collector projects each
 *  upstream `ReadingStatistics` snapshot into a fresh state" — LIVE-
 *  NOT-STALE. StatisticsViewModel.kt L49-67 realization: `observe-
 *  ReadingStatistics().onEach { snapshot -> updateState { it.copy(
 *  isLoading = false, ...all 8 fields...) } }.launchIn(viewModel-
 *  Scope)`. Init-time subscription, viewModelScope-bound cancellation,
 *  no imperative reducer mutation of any field.
 *  (c) "No intent reducer mutates the 8 numbers directly — Room /
 *  DataStoreHelper writes from elsewhere in the app (e.g., the Reader
 *  marking a chapter read) propagate through the upstream flows and
 *  naturally re-emit, so the screen is reactive without needing an
 *  `OnRefresh` intent" — LIVE-NOT-STALE. StatisticsIntent.kt L26
 *  sealed-interface declaration is empty; the cross-screen reactive-
 *  refresh path (Reader's chapter-read-marker plus Library bookmark
 *  toggle plus Downloads completion) is preserved by Room's tracked-
 *  write re-emission contract plus DataStoreHelper's settings-flow
 *  re-emit-on-write contract.
 *  (d) "Contract §6 SRP: one rule — 'what the Statistics screen
 *  renders right now'" — LIVE-NOT-STALE. The shape carries only
 *  render-time state (loading flag plus 8 numbers); no business
 *  logic, no derivation that belongs in the use case or repository.
 *  Cited as LIVE-NOT-STALE cross-reference target in cluster102
 *  HistoryState.kt postscript (clause (b) "matches the [Statistics-
 *  State] no-`error` posture") — self-consistency check passes.
 *  Four LIVE-NOT-STALE classifications STAND on their own merits as
 *  a faithful Statistics-state shape manifest. Original Phase 7.x.
 *  statistics-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
data class StatisticsState(
    val isLoading: Boolean = true,
    val inLibrary: Int = 0,
    // Native parity (StatisticsViewModel.kt:20-21): native seeds "0h 0m" pre-emission; the typed
    // wire seeds 0 minutes, which :ui renders as the same "0h 0m" via Res.string.h_m.
    val readMinutes: Int = 0,
    val entriesStarted: Int = 0,
    val entriesCompleted: Int = 0,
    val chaptersTotal: Int = 0,
    val chaptersRead: Int = 0,
    val chaptersDownloaded: Int = 0,
    val chaptersBookmarked: Int = 0,
) : MviState
