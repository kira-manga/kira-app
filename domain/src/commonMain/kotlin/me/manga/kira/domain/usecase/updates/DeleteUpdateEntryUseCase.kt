package me.manga.kira.domain.usecase.updates

import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.domain.repository.UpdatesRepository

/**
 * Delete a single chapter-update entry.
 *
 * Phase 7.x.updates rework. The rework `UpdatesViewModel` injects this use case and invokes it
 * from `viewModelScope.launch` when the user taps the per-row "Delete" button. Fire-and-forget:
 * the upstream [ObserveUpdatesUseCase] flow re-emits with the entry removed once the Room
 * transaction commits.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [UpdatesRepository.deleteEntry]". The Room
 * `@Delete` plumbing lives in the legacy DAO; the rework `:data` impl maps the [UpdateEntry]
 * back to the legacy `ChapterNotification` entity.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [me.manga.kira.domain.usecase.history.DeleteHistoryEntryUseCase] — the VM depends on a
 * stable use case interface, not on a repository method (DIP); future composition (e.g., emit
 * an analytics event on delete, or trigger an undo-snackbar restore queue) lives here, not in
 * the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `updatesReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster110.staleKdocSweep.cascade,
 * Task #566, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fiftieth sibling of the cluster57-109 sweep — wave-10
 * `:domain/usecase/updates/` batch alongside ObserveUpdatesUseCase.kt
 * plus MarkAllUpdatesAsReadUseCase.kt plus DeleteAllUpdatesUseCase.kt;
 * MarkUpdateAsReadUseCase.kt already postscripted at cluster16 Task
 * #472):
 *  (a) "Phase 7.x.updates rework — rework UpdatesViewModel injects this
 *  use case plus invokes it from viewModelScope.launch when the user
 *  taps the per-row Delete button" — MIXED LIVE-PLUS-SUPERSEDED-WIRE.
 *  The original framing referenced a direct per-row Delete tap dispatch-
 *  ing OnDeleteEntry; the §298 undosnackbar architectural decision
 *  (cluster108 sibling sweep, Task #564, verified the wiring) interposed
 *  a two-step OnRequestDelete rename-to OnConfirmDelete handshake. The
 *  realization today: UpdatesScreen.kt L307 per-row Delete-button on-
 *  Click dispatches `UpdatesIntent.OnRequestDelete(entry)`, which the VM
 *  routes to a pendingDeleteIds state-mutation arm (no use-case call);
 *  the use case is REACHED via `UpdatesIntent.OnConfirmDelete(entry)`
 *  handler at UpdatesViewModel.kt L184-187 once the undo-snackbar
 *  window expires. UpdatesViewModel.kt L153 `OnDeleteEntry rename-to
 *  viewModelScope.launch { deleteUpdateEntry(intent.entry) }` LIVE-wired
 *  but unreached from rework `:ui` today; deliberately preserved per the
 *  §298 architectural decision so the original per-row direct-delete
 *  arrival path remains available to alternative `:ui` callers. Fire-
 *  and-forget posture preserved verbatim — both arrival paths terminate
 *  in the same one-line use case invoke().
 *  (b) "Fire-and-forget — upstream ObserveUpdatesUseCase flow re-emits
 *  with the entry removed once the Room transaction commits" — LIVE-NOT-
 *  STALE. UpdatesRepositoryImpl.kt delegates to legacy Notification-
 *  Repository.deleteNotification() → DAO `@Delete` realization; Room
 *  re-emit posture verified at cluster26 sibling sweep (Task #482).
 *  (c) "Contract §6 SRP owns ONE rule — delegate to UpdatesRepository.
 *  deleteEntry; the Room `@Delete` plumbing lives in the legacy DAO;
 *  the rework `:data` impl maps the UpdateEntry back to the legacy
 *  ChapterNotification entity" — LIVE-NOT-STALE. L30 realization
 *  `repository.deleteEntry(entry)` single-line pass-through; the
 *  UpdateEntry rename-to ChapterNotification entity-shape conversion
 *  realized in `:data` UpdatesRepositoryImpl.kt verified at cluster26
 *  sibling sweep.
 *  (d) "Future composition — emit an analytics event on delete, or
 *  trigger an undo-snackbar restore queue" — MIXED FORECAST-PARTIALLY-
 *  FULFILLED. Analytics-emission forecast: FORECAST-NOT-YET-FULFILLED;
 *  recursive search for analytics-event emission on this use case
 *  returns zero matches. Undo-snackbar-restore-queue forecast: FULFILLED-
 *  WITH-ALTERNATIVE-MECHANISM at §298 (cluster108 sibling sweep, Task
 *  #564) — the rework lifted the undo posture into a presentation-state
 *  pendingDeleteIds path (UpdatesViewModel.kt undo-snackbar lifecycle)
 *  rather than a use-case-internal restore queue. The use case remains
 *  a single-line pass-through; the §298 mechanism realizes the undo
 *  product behaviour at the `:presentation` layer instead. Forecast
 *  posture preserved verbatim — the original prose's "lives here, not
 *  in the VM" stipulation was not the realization that landed; the
 *  decision is documented at §298 not retroactively re-written here.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `updatesReworkModule`" — LIVE-NOT-STALE. UpdatesRework-
 *  Module.kt L60 `factory { DeleteUpdateEntryUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to
 *  construct, never shared).
 *  Five classifications STAND on their own merits as a faithful Delete-
 *  UpdateEntryUseCase manifest. Original Phase 7.x.updates-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class DeleteUpdateEntryUseCase(
    private val repository: UpdatesRepository,
) {
    suspend operator fun invoke(entry: UpdateEntry) = repository.deleteEntry(entry)
}
