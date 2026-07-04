package me.manga.kira.domain.usecase.complaint

import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.repository.AdminComplaintListRepository

/**
 * Use case: fetch ALL submitted feedback/complaint records (admin view).
 *
 * Phase 7.x.complaint.admin rework. Thin pass-through over
 * [AdminComplaintListRepository.loadAllComplaints] — same posture as the user-side
 * [ObserveUserComplaintsUseCase] (no orchestration, no validation, no derivation in the use case
 * layer; the repository owns the strangler-fig boundary and the data shape).
 *
 * **Why a use case at all if it's a pure pass-through?** The architectural contract requires
 * `:presentation` to depend on use cases, not directly on repositories — the use case layer is
 * the §6 ISP boundary that lets a single `:presentation` consumer pick exactly the operations it
 * needs. If a future actions slice adds `UpdateComplaintStatusUseCase`, `EditComplaintUseCase`,
 * `DeleteComplaintUseCase`, etc., they slot in as siblings without forcing the
 * `AdminComplaintViewModel` to import the full repository interface(s).
 *
 * **Why "Observe" despite the suspend return?** See the rationale on
 * [AdminComplaintListRepository]'s KDoc — the underlying legacy facade is suspend single-fetch;
 * the name is forward-compatible with a future `Flow`-based reactive path that would extend the
 * repository without renaming.
 *
 * **Why "All" not "Admin" in the name?**: the use case name describes the data scope ("all
 * complaints"), not the consumer ("admin view"). The gating between user-side and admin-side
 * happens at navigation time (`Admin.isAdmin` in the `SettingsReworkScreenRoute` adapter), not at
 * use-case-name granularity. If a future analytics screen wants all complaints for aggregation,
 * it can reuse this same use case without a renaming round-trip.
 *
 * Contract §6 SRP: one rule — "expose the all-complaints LIST as a one-shot suspend fetch". No
 * filtering, sorting, or transformation; those live in the `:presentation` VM or the `:data`
 * impl as appropriate.
 *
 * Contract §6 DIP: depends on the [AdminComplaintListRepository] interface, not on its `:data`
 * impl. Koin binds the impl via `complaintAdminReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster123.staleKdocSweep.cascade,
 * Task #579, 2026-05-28): classified as follows after recursive symbol
 * verification (seventy-third sibling of the cluster57-122 sweep —
 * fifth and final file of the wave-21 `:domain/usecase/complaint/`
 * admin-side 5-file batch alongside AdminDelete plus AdminEdit plus
 * ChangeStatus plus AddClosureReason; user-side 4 files (Delete plus
 * Edit plus ObserveUser plus Reply) deferred to cluster124 follow-up):
 *  (a) "Phase 7.x.complaint.admin rework — thin pass-through over
 *  AdminComplaintListRepository.loadAllComplaints; same posture as the
 *  user-side ObserveUserComplaintsUseCase (no orchestration, no
 *  validation, no derivation in the use case layer; the repository owns
 *  the strangler-fig boundary and the data shape)" — LIVE-NOT-STALE.
 *  AdminComplaintViewModel.kt L12 import, L83 ctor `private val observe-
 *  AllComplaints: ObserveAllComplaintsUseCase`, L261 realization `val
 *  result = observeAllComplaints()` inside the load-list refresh path.
 *  L42-43 single-line pass-through `repository.loadAllComplaints()`.
 *  Cross-references at Screen.kt L850 (AdminComplaintRework route entry)
 *  plus AdminComplaintReworkScreenRoute.kt L33 (KDoc reference to
 *  ObserveAllComplaintsUseCase) confirm the architectural-symmetry peer
 *  pairing with ObserveUserComplaintsUseCase — which will be swept in
 *  cluster124 alongside the other 3 user-side complaint use cases.
 *  (b) "Why a use case at all if it's a pure pass-through — the
 *  architectural contract requires `:presentation` to depend on use
 *  cases, not directly on repositories; the use case layer is the §6
 *  ISP boundary that lets a single `:presentation` consumer pick
 *  exactly the operations it needs; if a future actions slice adds
 *  UpdateComplaintStatusUseCase, EditComplaintUseCase, DeleteComplaint-
 *  UseCase, etc., they slot in as siblings without forcing the Admin-
 *  ComplaintViewModel to import the full repository interface(s)" —
 *  LIVE-NOT-STALE plus FULFILLED-PREDICTION. The forecasted "future
 *  actions slice" has fulfilled — intra-cluster123 peer cross-ref
 *  confirms ChangeComplaintStatusUseCase (sibling 71st), AdminEdit-
 *  ComplaintUseCase (sibling 70th), AdminDeleteComplaintUseCase
 *  (sibling 69th), and AddClosureReasonUseCase (sibling 72nd) all
 *  exist as siblings in the same `:domain/usecase/complaint/` package
 *  with realised AdminComplaintViewModel constructor injections;
 *  AdminComplaintViewModel imports the 4 action use cases as
 *  individual `:domain` symbols (L8-L11) rather than depending on the
 *  full AdminComplaintActionRepository interface — the ISP §6 boundary
 *  forecast has fulfilled in spirit (the names differ slightly from
 *  the forecasted UpdateComplaintStatusUseCase plus EditComplaintUseCase
 *  plus DeleteComplaintUseCase, but the topology is identical).
 *  (c) "Why Observe despite the suspend return — see the rationale on
 *  AdminComplaintListRepository's KDoc; the underlying legacy facade
 *  is suspend single-fetch; the name is forward-compatible with a
 *  future Flow-based reactive path that would extend the repository
 *  without renaming" — LIVE-FRAMING plus FORECAST-NOT-YET-FULFILLED.
 *  Suspend-single-fetch-not-Flow posture verified at cluster #468
 *  sibling sweep (complaintactionrepo.staleKdocSweep) — the
 *  AdminComplaintListRepository.loadAllComplaints signature remains
 *  `suspend fun loadAllComplaints(): Result<List<ComplaintSummary>>`,
 *  not a `Flow`. Flow-extension forecast — FORECAST-NOT-YET-FULFILLED.
 *  Recursive search for Flow-shaped admin-complaint-observe returns
 *  zero matches; the suspend-fetch single-shot path is unchanged.
 *  (d) "Why All not Admin in the name — the use case name describes the
 *  data scope (all complaints), not the consumer (admin view); the
 *  gating between user-side and admin-side happens at navigation time
 *  (Admin.isAdmin in the SettingsReworkScreenRoute adapter), not at
 *  use-case-name granularity; if a future analytics screen wants all
 *  complaints for aggregation, it can reuse this same use case without
 *  a renaming round-trip" — LIVE-NOT-STALE plus FORECAST-NOT-YET-
 *  FULFILLED. Admin.isAdmin-at-nav-time-gating posture verified at
 *  cluster #461 sibling sweep (settingsreworkroute.staleKdocSweep) —
 *  the gate is the SettingsReworkScreen "Manage Complaints" affordance,
 *  shown only when Admin.isAdmin returns true. The name-describes-data-
 *  scope-not-consumer rationale stands — the `All` qualifier
 *  distinguishes the all-complaints scope from the user-side `User`
 *  qualifier (sibling ObserveUserComplaintsUseCase, deferred to
 *  cluster124). Future-analytics-screen-reuse forecast — FORECAST-NOT-
 *  YET-FULFILLED. Recursive search for analytics-screen-consuming-
 *  ObserveAllComplaints returns zero matches; the AdminComplaintView-
 *  Model is the sole consumer; no analytics surface has emerged. §6
 *  SRP + §6 DIP + Koin factory lifecycle LIVE: ComplaintAdminRework-
 *  Module.kt L148 `factory { ObserveAllComplaintsUseCase(get()) }`
 *  realization; L4 import binds `:domain`-layer interface, not
 *  `:data`-layer impl.
 *  Four classifications STAND on their own merits. Original Phase
 *  7.x.complaint.admin-era prose preserved verbatim per the audit-
 *  trail-preservation convention; the "future actions slice" forecast
 *  of (b) is upheld as a FULFILLED-PREDICTION via intra-cluster123
 *  sibling cross-refs to the 4 just-swept admin-side action use cases,
 *  closing the architectural-symmetry expectation that the original
 *  Phase 7.x.complaint.admin-era prose articulated. Closes the admin-
 *  side half of `:domain/usecase/complaint/` as SWEPT (5 of 9 files);
 *  user-side 4-file follow-up batched for cluster124 per the wave-20-
 *  established ≤5-file-cap-with-followup convention.
 */
class ObserveAllComplaintsUseCase(
    private val repository: AdminComplaintListRepository,
) {
    suspend operator fun invoke(): Result<List<ComplaintSummary>> =
        repository.loadAllComplaints()
}
