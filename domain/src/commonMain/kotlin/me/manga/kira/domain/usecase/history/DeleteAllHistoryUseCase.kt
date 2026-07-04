package me.manga.kira.domain.usecase.history

import me.manga.kira.domain.repository.HistoryRepository

/**
 * Delete every reading-history entry.
 *
 * Phase 7.x.history rework. The rework `HistoryViewModel` injects this use case and invokes it
 * from `viewModelScope.launch` when the user taps "Clear all" in the top bar. Fire-and-forget:
 * the upstream [me.manga.kira.domain.usecase.history.ObserveHistoryUseCase] flow re-emits an
 * empty list once the Room `DELETE FROM history_items` transaction commits.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [HistoryRepository.deleteAll]". The bulk-delete
 * SQL lives in the legacy DAO (`@Query("DELETE FROM history_items")`); the rework `:data` impl
 * is a single-line forward.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [DeleteHistoryEntryUseCase] — the VM depends on a stable use case interface, not on a
 * repository method (DIP); future composition (e.g., a "you are about to delete N entries"
 * confirmation pre-flight, or an analytics event) lives here, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `historyReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster112.staleKdocSweep.cascade,
 * Task #568, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-second sibling of the cluster57-111 sweep — closes
 * the wave-12 `:domain/usecase/history/` batch alongside ObserveHistory-
 * UseCase.kt plus DeleteHistoryEntryUseCase.kt):
 *  (a) "Phase 7.x.history rework — rework HistoryViewModel injects this
 *  use case plus invokes it from viewModelScope.launch when the user
 *  taps Clear all in the top bar" — LIVE-NOT-STALE. HistoryViewModel.kt
 *  L127-129 `HistoryIntent.OnDeleteAll rename-to viewModelScope.launch
 *  { deleteAllHistory() }` realization confirms the fire-and-forget
 *  posture; HistoryScreen.kt L169 top-bar Clear-all dispatch realized
 *  without a confirmation AlertDialog gate (verified by recursive
 *  search for AlertDialog declarations preceding the OnDeleteAll
 *  dispatch site — no matches).
 *  (b) "Fire-and-forget — upstream ObserveHistoryUseCase flow re-emits
 *  an empty list once the Room DELETE FROM history_items transaction
 *  commits" — LIVE-NOT-STALE. HistoryRepositoryImpl.kt delegates to
 *  legacy HistoryRepository.deleteAllHistory() → DAO `@Query("DELETE
 *  FROM history_items")` realization; Room re-emit posture verified at
 *  cluster25 sibling sweep (Task #481).
 *  (c) "Contract §6 SRP owns ONE rule — delegate to HistoryRepository.
 *  deleteAll; the bulk-delete SQL lives in the legacy DAO; the rework
 *  `:data` impl is a single-line forward" — LIVE-NOT-STALE. L28
 *  realization `repository.deleteAll()` single-line pass-through.
 *  (d) "Future composition — a `you are about to delete N entries`
 *  confirmation pre-flight, or an analytics event" — FORECAST-NOT-YET-
 *  FULFILLED. Recursive search for confirmation-dialog gating or
 *  analytics-event emission preceding this use case returns zero
 *  matches; the use case remains a single-line pass-through. Per (a),
 *  HistoryScreen.kt L169 dispatches OnDeleteAll directly without any
 *  pre-flight gate. Forecast posture preserved verbatim. Peer cross-
 *  ref to cluster110 sibling sweep DeleteAllUpdatesUseCase classific-
 *  ation (d) — same forecast posture on the partnered Updates use case.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `historyReworkModule`" — LIVE-NOT-STALE. HistoryRework-
 *  Module.kt L103 `factory { DeleteAllHistoryUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to
 *  construct, never shared).
 *  Five classifications STAND on their own merits as a faithful Delete-
 *  AllHistoryUseCase manifest. Original Phase 7.x.history-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class DeleteAllHistoryUseCase(
    private val repository: HistoryRepository,
) {
    suspend operator fun invoke() = repository.deleteAll()
}
