package me.manga.kira.domain.usecase.history

import me.manga.kira.domain.model.history.HistoryEntry
import me.manga.kira.domain.repository.HistoryRepository

/**
 * Delete a single reading-history entry.
 *
 * Phase 7.x.history rework. The rework `HistoryViewModel` injects this use case and invokes it
 * from `viewModelScope.launch` when the user taps the per-row delete button. Fire-and-forget:
 * the upstream [me.manga.kira.domain.usecase.history.ObserveHistoryUseCase] flow re-emits
 * with the entry removed once the Room transaction commits.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [HistoryRepository.deleteEntry]". The Room
 * `@Delete` plumbing lives in the legacy DAO; the rework `:data` impl maps the [HistoryEntry]
 * back to the legacy `HistoryItemD` entity.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [me.manga.kira.domain.usecase.library.BulkRemoveFromLibraryUseCase] — the VM depends on a
 * stable use case interface, not on a repository method (DIP); future composition (e.g., emit
 * an analytics event on delete, or trigger a cleanup of orphan downloaded image files) lives
 * here, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `historyReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster112.staleKdocSweep.cascade,
 * Task #568, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-second sibling of the cluster57-111 sweep — wave-12
 * `:domain/usecase/history/` batch alongside ObserveHistoryUseCase.kt
 * plus DeleteAllHistoryUseCase.kt):
 *  (a) "Phase 7.x.history rework — rework HistoryViewModel injects this
 *  use case plus invokes it from viewModelScope.launch when the user
 *  taps the per-row delete button" — LIVE-NOT-STALE. HistoryViewModel.kt
 *  L124-126 `HistoryIntent.OnDeleteEntry rename-to viewModelScope.launch
 *  { deleteHistoryEntry(intent.entry) }` realization confirms the fire-
 *  and-forget direct-dispatch posture; cluster102 sibling sweep (Task
 *  #558) verified the OnDeleteEntry handler classification. Note: unlike
 *  the cluster110 DeleteUpdateEntryUseCase counterpart, History has NO
 *  §298 undosnackbar drift — the History `:ui` dispatches OnDeleteEntry
 *  directly per-row without an OnRequestDelete plus OnConfirmDelete
 *  handshake (HistoryScreen.kt verified by recursive search; no
 *  pendingDeleteIds state-mutation arm exists on the History VM).
 *  (b) "Fire-and-forget — upstream ObserveHistoryUseCase flow re-emits
 *  with the entry removed once the Room transaction commits" — LIVE-NOT-
 *  STALE. HistoryRepositoryImpl.kt delegates to legacy HistoryRepository.
 *  deleteHistory() → DAO `@Delete` realization; Room re-emit posture
 *  verified at cluster25 sibling sweep (Task #481).
 *  (c) "Contract §6 SRP owns ONE rule — delegate to HistoryRepository.
 *  deleteEntry; the Room `@Delete` plumbing lives in the legacy DAO;
 *  the rework `:data` impl maps the HistoryEntry back to the legacy
 *  HistoryItemD entity" — LIVE-NOT-STALE. L30 realization `repository.
 *  deleteEntry(entry)` single-line pass-through; the HistoryEntry
 *  rename-to HistoryItemD entity-shape conversion realized in `:data`
 *  HistoryRepositoryImpl.kt verified at cluster25 sibling sweep.
 *  (d) "Future composition — emit an analytics event on delete, or
 *  trigger a cleanup of orphan downloaded image files" — FORECAST-NOT-
 *  YET-FULFILLED. Recursive search for analytics-event emission or
 *  orphan-image cleanup orchestration on this use case returns zero
 *  matches; the use case remains a single-line pass-through. Forecast
 *  posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `historyReworkModule`" — LIVE-NOT-STALE. HistoryRework-
 *  Module.kt L102 `factory { DeleteHistoryEntryUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to
 *  construct, never shared).
 *  Five classifications STAND on their own merits as a faithful Delete-
 *  HistoryEntryUseCase manifest. Original Phase 7.x.history-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class DeleteHistoryEntryUseCase(
    private val repository: HistoryRepository,
) {
    suspend operator fun invoke(entry: HistoryEntry) = repository.deleteEntry(entry)
}
