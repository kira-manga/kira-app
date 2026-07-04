package me.manga.kira.domain.usecase.complaint

import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.repository.ComplaintListRepository

/**
 * Use case: fetch the current user's submitted feedback/complaint records.
 *
 * Phase 7.x.complaint.foundation rework. Thin pass-through over
 * [ComplaintListRepository.loadUserComplaints] — same posture as
 * [me.manga.kira.domain.usecase.feedback.SendLanguageRequestUseCase] (no orchestration,
 * no validation, no derivation in the use case layer; the repository owns the strangler-fig
 * boundary and the data shape).
 *
 * **Why a use case at all if it's a pure pass-through?** The architectural contract requires
 * `:presentation` to depend on use cases, not directly on repositories — the use case layer is
 * the §6 ISP boundary that lets a single `:presentation` consumer pick exactly the operations it
 * needs. If the slice later gains a `LoadAdminComplaintsUseCase` or `SearchComplaintsUseCase`,
 * they slot in as siblings without forcing the `:presentation` VM to import the full repository
 * interface.
 *
 * **Why "Observe" despite the suspend return?** See the rationale on
 * [ComplaintListRepository]'s KDoc: the underlying legacy facade is suspend single-fetch; the
 * name is forward-compatible with a future `Flow`-based reactive path that would extend the
 * repository without renaming.
 *
 * Contract §6 SRP: one rule — "expose the user's complaint LIST as a one-shot suspend fetch".
 * No filtering, sorting, or transformation; those live in the `:presentation` VM or the `:data`
 * impl as appropriate.
 *
 * Contract §6 DIP: depends on the [ComplaintListRepository] interface, not on its `:data`
 * impl. Koin binds the impl via `complaintReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster124.staleKdocSweep.cascade,
 * Task #580, 2026-05-28): classified as follows after recursive symbol
 * verification (seventy-sixth sibling of the cluster57-123 sweep —
 * third file of the wave-21 `:domain/usecase/complaint/` user-side 4-
 * file follow-up batch alongside Delete plus Edit plus Reply; this file
 * is the architectural-symmetry peer to intra-cluster123 sibling
 * ObserveAllComplaintsUseCase (sibling 73rd) — the cluster123 73rd
 * sibling postscript classification (b) explicitly cites THIS file's
 * sibling existence as the FULFILLED-PREDICTION evidence for the "if a
 * future actions slice adds UpdateComplaintStatusUseCase, EditComplaint-
 * UseCase, DeleteComplaintUseCase, etc., they slot in as siblings"
 * architectural-symmetry forecast):
 *  (a) "Phase 7.x.complaint.foundation rework — thin pass-through over
 *  ComplaintListRepository.loadUserComplaints; same posture as Send-
 *  LanguageRequestUseCase (no orchestration, no validation, no
 *  derivation in the use case layer; the repository owns the strangler-
 *  fig boundary and the data shape)" — LIVE-NOT-STALE. ComplaintView-
 *  Model.kt L9 import, L116 ctor `private val observeUserComplaints:
 *  ObserveUserComplaintsUseCase`, L254 realization `val result =
 *  observeUserComplaints()` inside the list-load refresh path. L37-38
 *  single-line pass-through `repository.loadUserComplaints()`. Peer
 *  cross-ref to SendLanguageRequestUseCase posture verified at
 *  cluster120 sibling sweep (Task #576, :domain/usecase/feedback/) —
 *  both use cases share the no-orchestration-no-validation-no-derivation
 *  single-line-repository-delegate posture; SendLanguageRequestUseCase
 *  itself was swept in cluster120 with the identical structural-
 *  symmetry framing.
 *  (b) "Why a use case at all if it's a pure pass-through — the
 *  architectural contract requires `:presentation` to depend on use
 *  cases, not directly on repositories; the use case layer is the §6
 *  ISP boundary that lets a single `:presentation` consumer pick
 *  exactly the operations it needs; if the slice later gains a Load-
 *  AdminComplaintsUseCase or SearchComplaintsUseCase, they slot in as
 *  siblings without forcing the `:presentation` VM to import the full
 *  repository interface" — LIVE-NOT-STALE plus FULFILLED-PREDICTION-
 *  WITH-RENAMING. The forecasted "LoadAdminComplaintsUseCase" sibling
 *  has fulfilled with a slight renaming — it is now named ObserveAll-
 *  ComplaintsUseCase (intra-cluster123 73rd sibling, swept in
 *  cluster123 Task #579) and depends on AdminComplaintListRepository
 *  (the admin-side sibling-repo split documented at cluster #468
 *  sibling sweep complaintactionrepo.staleKdocSweep). The "Search-
 *  ComplaintsUseCase" forecast remains FORECAST-NOT-YET-FULFILLED —
 *  recursive search returns zero matches for a search-shaped use case
 *  in the complaint package; admin-side filter/sort logic stays in
 *  AdminComplaintViewModel state-derivation rather than a dedicated
 *  search use case.
 *  (c) "Why Observe despite the suspend return — see the rationale on
 *  ComplaintListRepository's KDoc; the underlying legacy facade is
 *  suspend single-fetch; the name is forward-compatible with a future
 *  Flow-based reactive path that would extend the repository without
 *  renaming" — LIVE-FRAMING plus FORECAST-NOT-YET-FULFILLED. Suspend-
 *  single-fetch-not-Flow posture verified at cluster #468 sibling
 *  sweep (complaintactionrepo.staleKdocSweep) — the ComplaintList-
 *  Repository.loadUserComplaints signature remains `suspend fun load-
 *  UserComplaints(): Result<List<ComplaintSummary>>`, not a `Flow`.
 *  Flow-extension forecast — FORECAST-NOT-YET-FULFILLED. Recursive
 *  search for Flow-shaped user-complaint-observe returns zero matches;
 *  the suspend-fetch single-shot path is unchanged. The intra-cluster
 *  123 sibling ObserveAllComplaintsUseCase (sibling 73rd) shares this
 *  identical LIVE-FRAMING-plus-Flow-FORECAST-NOT-YET-FULFILLED posture
 *  on the admin side — two siblings, two pending forecasts, both
 *  architecturally aligned.
 *  (d) §6 SRP + §6 DIP + Koin factory lifecycle — LIVE-NOT-STALE.
 *  ComplaintReworkModule.kt L127 `factory { ObserveUserComplaintsUse-
 *  Case(get()) }` realization; L9 import binds `:domain`-layer
 *  interface, not `:data`-layer impl. AdminComplaintViewModel.kt L22
 *  KDoc reference to ObserveUserComplaintsUseCase as architectural-
 *  symmetry peer is upheld — the admin VM cites THIS use case as the
 *  user-side counterpart to ObserveAllComplaintsUseCase, mirroring the
 *  bidirectional cross-reference between the two sibling postscripts.
 *  Four classifications STAND on their own merits. Original Phase
 *  7.x.complaint.foundation-era prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
class ObserveUserComplaintsUseCase(
    private val repository: ComplaintListRepository,
) {
    suspend operator fun invoke(): Result<List<ComplaintSummary>> =
        repository.loadUserComplaints()
}
