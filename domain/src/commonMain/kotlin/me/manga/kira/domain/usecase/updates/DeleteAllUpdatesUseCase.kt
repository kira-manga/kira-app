package me.manga.kira.domain.usecase.updates

import me.manga.kira.domain.repository.UpdatesRepository

/**
 * Delete every chapter-update entry.
 *
 * Phase 7.x.updates rework. The rework `UpdatesViewModel` injects this use case and invokes it
 * from `viewModelScope.launch` when the user taps "Clear all" in the top bar. Fire-and-forget:
 * the upstream [ObserveUpdatesUseCase] flow re-emits an empty list once the Room
 * `DELETE FROM notifications` transaction commits.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [UpdatesRepository.deleteAll]". The bulk-delete
 * SQL lives in the legacy DAO (`@Query("DELETE FROM notifications")`); the rework `:data` impl
 * is a single-line forward.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [DeleteUpdateEntryUseCase] — the VM depends on a stable use case interface, not on a
 * repository method (DIP); future composition (e.g., a "you are about to delete N entries"
 * confirmation pre-flight, or an analytics event) lives here, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `updatesReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster110.staleKdocSweep.cascade,
 * Task #566, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fiftieth sibling of the cluster57-109 sweep — closes the
 * wave-10 `:domain/usecase/updates/` batch alongside ObserveUpdates-
 * UseCase.kt plus MarkAllUpdatesAsReadUseCase.kt plus DeleteUpdateEntry-
 * UseCase.kt; MarkUpdateAsReadUseCase.kt already postscripted at
 * cluster16 Task #472):
 *  (a) "Phase 7.x.updates rework — rework UpdatesViewModel injects this
 *  use case plus invokes it from viewModelScope.launch when the user
 *  taps Clear all in the top bar" — LIVE-NOT-STALE. UpdatesViewModel.kt
 *  L156-158 `UpdatesIntent.OnDeleteAll rename-to viewModelScope.launch
 *  { deleteAllUpdates() }` realization confirms the fire-and-forget
 *  posture; UpdatesScreen.kt L242 top-bar Clear-all dispatch realized
 *  without a confirmation AlertDialog gate (verified by recursive
 *  search for AlertDialog declarations preceding the OnDeleteAll
 *  dispatch site — no matches).
 *  (b) "Fire-and-forget — upstream ObserveUpdatesUseCase flow re-emits
 *  an empty list once the Room DELETE FROM notifications transaction
 *  commits" — LIVE-NOT-STALE. UpdatesRepositoryImpl.kt delegates to
 *  legacy NotificationRepository.deleteAllNotifications() → DAO
 *  `@Query("DELETE FROM notifications")` realization; Room re-emit
 *  posture verified at cluster26 sibling sweep (Task #482).
 *  (c) "Contract §6 SRP owns ONE rule — delegate to UpdatesRepository.
 *  deleteAll; the bulk-delete SQL lives in the legacy DAO; the rework
 *  `:data` impl is a single-line forward" — LIVE-NOT-STALE. L28
 *  realization `repository.deleteAll()` single-line pass-through.
 *  (d) "Future composition — a `you are about to delete N entries`
 *  confirmation pre-flight, or an analytics event" — FORECAST-NOT-YET-
 *  FULFILLED. Recursive search for confirmation-dialog gating or
 *  analytics-event emission preceding this use case returns zero
 *  matches; the use case remains a single-line pass-through. Per (a),
 *  UpdatesScreen.kt L242 dispatches OnDeleteAll directly without any
 *  pre-flight gate. Forecast posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `updatesReworkModule`" — LIVE-NOT-STALE. UpdatesRework-
 *  Module.kt L61 `factory { DeleteAllUpdatesUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to
 *  construct, never shared).
 *  Five classifications STAND on their own merits as a faithful Delete-
 *  AllUpdatesUseCase manifest. Original Phase 7.x.updates-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class DeleteAllUpdatesUseCase(
    private val repository: UpdatesRepository,
) {
    suspend operator fun invoke() = repository.deleteAll()
}
