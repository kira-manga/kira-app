package me.manga.kira.presentation.history

import me.manga.kira.domain.model.history.HistoryEntry
import me.manga.kira.presentation.mvi.MviState

/**
 * History screen MVI state.
 *
 * Phase 7.x.history rework. Holds the reading-history snapshot rendered by the screen plus an
 * [isLoading] flag covering the gap between subscription and the first
 * `List<HistoryEntry>` emission. No `error` field — a throw from the Room `getAllHistory()`
 * upstream is rare but possible, so the VM collector now guards it with `.catch` (#17) that just
 * clears the spinner rather than surfacing an error (matches the
 * [me.manga.kira.presentation.statistics.StatisticsState] no-`error` posture); deletes are
 * suspend functions routed through `launchSafely` (#29) so a `DELETE FROM` failure is logged
 * rather than escaping `viewModelScope`.
 *
 * The state is **flow-driven**: the VM's `init {}` collector projects each upstream
 * `List<HistoryEntry>` snapshot into a fresh [items] list. Deletes propagate naturally — the
 * legacy `HistoryDao` re-emits the table on every write, so the screen is reactive without
 * needing an `OnRefresh` intent or imperative state mutation in the reducer.
 *
 * Contract §6 SRP: one rule — "what the History screen renders right now". No business logic,
 * no derivation that lives in the use case or repository.
 *
 * Contract §17: no `Any`, no `!!`. `items: List<HistoryEntry>` is read-only (the public
 * interface — the underlying list might be a `MutableList` but consumers can never call `add`
 * etc.).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster102.staleKdocSweep.cascade,
 * Task #558, 2026-05-28): the file-scope MVI-state manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-second sibling of the cluster57-101 sweep — sibling
 * of cluster102 HistoryEffect.kt plus HistoryIntent.kt):
 *  (a) "Holds the reading-history snapshot rendered by the screen plus
 *  an [isLoading] flag" — LIVE-NOT-STALE. L29-32 data class enumerates
 *  exactly two fields: `isLoading: Boolean = true` plus `items: List<
 *  HistoryEntry> = emptyList()`. Nothing else.
 *  (b) "No `error` field — the History upstream is Room's
 *  `getAllHistory()` flow which does not throw at the observe site
 *  (matches the [StatisticsState] no-`error` posture)" — LIVE-NOT-
 *  STALE. Sealed shape at L29-32 contains no error field. Statistics-
 *  State no-error posture verified via sibling :presentation State-
 *  tier survey (cluster31 Task #487).
 *  (c) "flow-driven: the VM's `init {}` collector projects each
 *  upstream `List<HistoryEntry>` snapshot into a fresh [items] list" —
 *  LIVE-NOT-STALE. HistoryViewModel.kt L64-70 realization: `observe-
 *  History().onEach { snapshot -> updateState { it.copy(isLoading =
 *  false, items = snapshot) } }.launchIn(viewModelScope)`. Init-time
 *  subscription, viewModelScope-bound cancellation, no imperative
 *  reducer mutation of `items`.
 *  (d) "Deletes propagate naturally — the legacy `HistoryDao` re-emits
 *  the table on every write, so the screen is reactive without
 *  needing an `OnRefresh` intent or imperative state mutation in the
 *  reducer" — LIVE-NOT-STALE. Reducer at HistoryViewModel.kt L74-79
 *  dispatches deletes via fire-and-forget `viewModelScope.launch`,
 *  relying on Room re-emission rather than imperative `items`
 *  mutation. No OnRefresh intent declared at HistoryIntent.kt L22-50.
 *  (e) "Contract §6 SRP: one rule — 'what the History screen renders
 *  right now'" — LIVE-NOT-STALE. The shape carries only render-time
 *  state (loading flag plus items list plus isEmpty derived); no
 *  business logic, no derivation that belongs in the use case or
 *  repository.
 *  (f) "Contract §17: no `Any`, no `!!`. `items: List<HistoryEntry>`
 *  is read-only" — LIVE-NOT-STALE. L31 field declaration is a `val
 *  List<HistoryEntry>` with no nullable variants, no `Any` casts in
 *  this file.
 *  The `isEmpty` convenience prop at L35 STANDS as LIVE-NOT-STALE on
 *  its own merits per the sealed boolean derivation `!isLoading &&
 *  items.isEmpty()`.
 *  Six LIVE-NOT-STALE classifications STAND on their own merits as
 *  a faithful History-state shape manifest. Original Phase 7.x.history-
 *  era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
data class HistoryState(
    val isLoading: Boolean = true,
    val items: List<HistoryEntry> = emptyList(),
) : MviState {

    /** Convenience: true when the snapshot is empty and we're not still loading. */
    val isEmpty: Boolean get() = !isLoading && items.isEmpty()
}
