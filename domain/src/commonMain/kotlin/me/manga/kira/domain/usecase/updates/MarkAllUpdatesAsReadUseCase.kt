package me.manga.kira.domain.usecase.updates

import me.manga.kira.domain.repository.UpdatesRepository

/**
 * Mark every chapter-update entry as read.
 *
 * Phase 7.x.updates rework. The rework `UpdatesViewModel` injects this use case and invokes it
 * from `viewModelScope.launch` when the user taps "Mark all read" in the top bar. Fire-and-
 * forget: the upstream
 * [me.manga.kira.domain.usecase.updates.ObserveUpdatesUseCase] flow re-emits with every
 * entry's `isRead = true` once the Room bulk-update transaction commits — every per-row "Mark
 * read" button vanishes simultaneously.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [UpdatesRepository.markAllAsRead]". The bulk-
 * update SQL lives in the legacy DAO (`@Query("UPDATE notifications SET isRead = 1")`); the
 * rework `:data` impl is a single-line forward.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [MarkUpdateAsReadUseCase] — the VM depends on a stable use case interface, not on a
 * repository method (DIP); future composition (e.g., propagate the bulk read-state to a sync
 * server, or emit an analytics event) lives here, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `updatesReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster110.staleKdocSweep.cascade,
 * Task #566, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fiftieth sibling of the cluster57-109 sweep — wave-10
 * `:domain/usecase/updates/` batch alongside ObserveUpdatesUseCase.kt
 * plus DeleteUpdateEntryUseCase.kt plus DeleteAllUpdatesUseCase.kt;
 * MarkUpdateAsReadUseCase.kt already postscripted at cluster16 Task
 * #472):
 *  (a) "Phase 7.x.updates rework — rework UpdatesViewModel injects this
 *  use case plus invokes it from viewModelScope.launch when the user
 *  taps Mark all read in the top bar" — LIVE-NOT-STALE. UpdatesView-
 *  Model.kt L150-152 `UpdatesIntent.OnMarkAllAsRead rename-to view-
 *  ModelScope.launch { markAllUpdatesAsRead() }` realization confirms
 *  the fire-and-forget posture; cluster108 sibling sweep (Task #564)
 *  verified the mutating-intent classification.
 *  (b) "Fire-and-forget — upstream ObserveUpdatesUseCase flow re-emits
 *  with every entry's isRead = true once the Room bulk-update trans-
 *  action commits; every per-row Mark read button vanishes simultan-
 *  eously" — LIVE-NOT-STALE. UpdatesRepositoryImpl.kt delegates to
 *  legacy NotificationRepository.markAllAsRead() → DAO `@Query("UPDATE
 *  notifications SET isRead = 1")` realization; Room re-emit posture
 *  verified at cluster26 sibling sweep (Task #482).
 *  (c) "Contract §6 SRP owns ONE rule — delegate to UpdatesRepository.
 *  markAllAsRead; the bulk-update SQL lives in the legacy DAO; the
 *  rework `:data` impl is a single-line forward" — LIVE-NOT-STALE.
 *  L30 realization `repository.markAllAsRead()` single-line pass-
 *  through.
 *  (d) "Future composition — propagate the bulk read-state to a sync
 *  server, or emit an analytics event" — FORECAST-NOT-YET-FULFILLED.
 *  Recursive search for sync-server propagation / analytics emission
 *  on this use case returns zero matches; the use case remains a
 *  single-line pass-through. Forecast posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `updatesReworkModule`" — LIVE-NOT-STALE. UpdatesRework-
 *  Module.kt L59 `factory { MarkAllUpdatesAsReadUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to
 *  construct, never shared).
 *  Five classifications STAND on their own merits as a faithful Mark-
 *  AllUpdatesAsReadUseCase manifest. Original Phase 7.x.updates-era
 *  prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
class MarkAllUpdatesAsReadUseCase(
    private val repository: UpdatesRepository,
) {
    suspend operator fun invoke() = repository.markAllAsRead()
}
