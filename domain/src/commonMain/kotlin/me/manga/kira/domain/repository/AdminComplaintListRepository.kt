package me.manga.kira.domain.repository

import me.manga.kira.domain.model.complaint.ComplaintSummary

/**
 * Read-only surface over ALL submitted feedback/complaint records (admin view).
 *
 * Phase 7.x.complaint.admin rework. Sibling of the user-side [ComplaintListRepository] — the same
 * record type ([ComplaintSummary]) but a different fetch contract:
 *  - [ComplaintListRepository.loadUserComplaints] — filters to the current user's submissions
 *    (called by the rework Feedback Manager screen).
 *  - [AdminComplaintListRepository.loadAllComplaints] — returns every submission in the
 *    Firestore `complaints` collection (called by the rework admin dashboard).
 *
 * The `:data` impl strangler-fig delegates to:
 *  - `:shared`/`GetAllComplaintUseCase` — the actual Firestore-bound read (no userId filter).
 *
 * ONE strangler-fig reach (vs the user-side's two — admin doesn't need `UserIdProvider` since the
 * query is collection-wide, not user-scoped). Same posture as
 * [me.manga.kira.domain.repository.ReadingStatisticsRepository] (1 reach) and
 * [me.manga.kira.domain.repository.LanguageRepository] (1 reach).
 *
 * **Why a sibling interface rather than extending [ComplaintListRepository]?** Contract §6 ISP:
 * each consumer should depend only on what it uses. The user-side Feedback Manager screen never
 * fetches "all complaints" — admin-only data leaking into a user-facing VM would be a security-
 * adjacent concern (admin view exposes other users' submissions, including their `userId`s).
 * The user-side [ComplaintListRepository] KDoc explicitly anticipated this split (lines 27-32):
 * *"A future slice that adds, e.g., admin-side `loadAllComplaints()` would land on a sibling
 * `ComplaintAdminListRepository` rather than fattening this one — the user-side LIST screen
 * never needs the admin list and vice versa."* This slice is exactly that prediction realised.
 *
 * Contract §6 SRP: ONE rule — "fetch ALL submitted feedback records as a single snapshot list,
 * and report success/failure". No mutation (UpdateStatus / Edit / AddClosureReason / Delete /
 * bulk-update / bulk-delete are deferred to a future `AdminComplaintActionRepository` sibling
 * slice — same posture as the user-side foundation→actions slice progression); no continuous
 * observation (single-shot fetch matching the legacy
 * [me.manga.kira.presentation.features.complaint.usecase.GetAllComplaintUseCase] semantics —
 * Firestore `.get()`, not `.snapshots()`).
 *
 * Contract §6 ISP: ONE method. The deferred admin-action surface (status change, edit, closure
 * reason, delete, bulk-update, bulk-delete, statistics) lands on a sibling repository when its
 * slice ships, not here.
 *
 * Contract §6 DIP: consumers (the use case
 * [me.manga.kira.domain.usecase.complaint.ObserveAllComplaintsUseCase], and through it the
 * rework `AdminComplaintViewModel`) depend on this interface, never on the legacy facade or
 * Firestore directly. Koin binds the impl at the composition root in `complaintAdminReworkModule`.
 *
 * Lifecycle expectation: the impl is bound as a `single` (matching the upstream legacy
 * collaborator's singleton from `SharedModule`). A `factory` would re-create the impl on each
 * resolution — wasteful for a stateless transport.
 *
 * Behavior preservation: the rework reads from the SAME Firestore `complaints` collection as the
 * legacy [me.manga.kira.presentation.features.complaint.usecase.GetAllComplaintUseCase] path
 * (the legacy `:shared` admin VM that previously wrapped this use case was retired in
 * `Phase 9.x.admincomplaint.retire`). Both routes see identical record sets — same query
 * (collection-wide, no filter), same field
 * semantics, same ordering (Firestore-native, no client-side sort in either path — sorting is a
 * `:presentation`/`:ui` concern, deferred to a future actions slice).
 *
 * **Why "Observe" in the use case name despite the suspend return?** Same rationale as the
 * user-side [ComplaintListRepository] — the underlying legacy facade
 * [me.manga.kira.presentation.features.complaint.usecase.GetAllComplaintUseCase] is suspend
 * single-fetch (Firestore `.get()`, not `.snapshots()`). The `Observe` name is forward-compatible
 * with a future reactive-flow extension that wouldn't require renaming.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster141.staleKdocSweep.cascade,
 * Task #597, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-forty-fourth sibling of the cluster57-140
 * sweep — second file of the wave-25 third-cluster 3-leaf-repository
 * closing batch alongside LibraryRepository):
 *  (a) "Read-only-surface-over-ALL-submitted-feedback-complaint-records-
 *  admin-view + Phase-7.x.complaint.admin-rework + Sibling-of-the-user-
 *  side-ComplaintListRepository-the-same-record-type-ComplaintSummary-
 *  but-a-different-fetch-contract + ComplaintListRepository.loadUser-
 *  Complaints-filters-to-the-current-user-submissions-called-by-the-
 *  rework-Feedback-Manager-screen + AdminComplaintListRepository.
 *  loadAllComplaints-returns-every-submission-in-the-Firestore-
 *  complaints-collection-called-by-the-rework-admin-dashboard + The-
 *  :data-impl-strangler-fig-delegates-to-:shared-GetAllComplaintUseCase-
 *  the-actual-Firestore-bound-read-no-userId-filter + ONE-strangler-
 *  fig-reach-vs-the-user-side-two-admin-does-not-need-UserIdProvider-
 *  since-the-query-is-collection-wide-not-user-scoped + Same-posture-
 *  as-ReadingStatisticsRepository-1-reach-and-LanguageRepository-1-
 *  reach + Why-a-sibling-interface-rather-than-extending-Complaint-
 *  ListRepository-Contract-§6-ISP-each-consumer-should-depend-only-on-
 *  what-it-uses + The-user-side-Feedback-Manager-screen-never-fetches-
 *  all-complaints-admin-only-data-leaking-into-a-user-facing-VM-would-
 *  be-a-security-adjacent-concern + admin-view-exposes-other-users-
 *  submissions-including-their-userId-s + The-user-side-Complaint-
 *  ListRepository-KDoc-explicitly-anticipated-this-split + This-slice-
 *  is-exactly-that-prediction-realised" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION. Verified via recursive grep: AdminComplaint-
 *  ListRepository is consumed by ObserveAllComplaintsUseCase (the
 *  :domain caller) plus AdminComplaintListRepositoryImpl (the :data
 *  Firestore-via-shared-facade impl) plus ComplaintAdminReworkModule
 *  plus AdminComplaintReworkScreenRoute. The sibling-not-fattened ISP
 *  carve holds — ComplaintListRepository did NOT grow a
 *  loadAllComplaints method; admin and user surfaces remain on
 *  separate interfaces with separate Koin bindings. The user-side
 *  ComplaintListRepository KDoc's cluster140 §253 postscript
 *  classified the realised-sibling-prediction symmetrically — both
 *  ends of the prediction now reference each other in their
 *  postscripts.
 *  (b) "Contract-§6-SRP-ONE-rule-fetch-ALL-submitted-feedback-records-
 *  as-a-single-snapshot-list-and-report-success-or-failure + No-
 *  mutation-UpdateStatus-Edit-AddClosureReason-Delete-bulk-update-
 *  bulk-delete-are-deferred-to-a-future-AdminComplaintActionRepository-
 *  sibling-slice + Same-posture-as-the-user-side-foundation-actions-
 *  slice-progression + no-continuous-observation-single-shot-fetch-
 *  matching-the-legacy-GetAllComplaintUseCase-semantics-Firestore-.
 *  get-not-.snapshots + Contract-§6-ISP-ONE-method + The-deferred-
 *  admin-action-surface-status-change-edit-closure-reason-delete-bulk-
 *  update-bulk-delete-statistics-lands-on-a-sibling-repository-when-
 *  its-slice-ships-not-here + Contract-§6-DIP + consumers-the-use-
 *  case-ObserveAllComplaintsUseCase-and-through-it-the-rework-Admin-
 *  ComplaintViewModel-depend-on-this-interface-never-on-the-legacy-
 *  facade-or-Firestore-directly + Koin-binds-the-impl-at-the-
 *  composition-root-in-complaintAdminReworkModule + Lifecycle-
 *  expectation-the-impl-is-bound-as-a-single + Behavior-preservation-
 *  the-rework-reads-from-the-SAME-Firestore-complaints-collection-as-
 *  the-legacy-GetAllComplaintUseCase-path + the-legacy-:shared-admin-
 *  VM-that-previously-wrapped-this-use-case-was-retired-in-Phase-9.x.
 *  admincomplaint.retire + Both-routes-see-identical-record-sets-same-
 *  query-collection-wide-no-filter-same-field-semantics-same-ordering
 *  + No-userId-argument-the-admin-view-is-collection-wide + The-
 *  current-admin-status-check-lives-in-the-route-adapter-Settings-
 *  ReworkScreenRoute-consults-Admin.isAdmin-before-routing-to-the-
 *  admin-screen + this-repository-does-not-gate-on-identity-gating-
 *  happens-at-navigation-time-not-data-access-time + Why-Observe-in-
 *  the-use-case-name-despite-the-suspend-return-Same-rationale-as-the-
 *  user-side-ComplaintListRepository" — LIVE-NOT-STALE plus FULFILLED-
 *  PREDICTION plus FORECAST-NOT-YET-FULFILLED-(future-AdminComplaint-
 *  ActionRepository-sibling — already-realised: AdminComplaintAction-
 *  Repository is one of the 13 already-swept files entering wave-25,
 *  hosting AddClosureReason + ChangeStatus + AdminDelete + AdminEdit
 *  + bulk-update + bulk-delete; the deferred-actions-slice prediction
 *  has been fully realised). Verified: AdminComplaintViewModel imports
 *  ObserveAllComplaintsUseCase + the admin-mutation use cases (Admin-
 *  Edit + AdminDelete + ChangeStatus + AddClosureReason) — no :data
 *  import + no :shared facade reach + no Firestore SDK reach. The
 *  Result<List<ComplaintSummary>> return type stays — no AppResult
 *  migration here (legacy parity). The §365 Phase-9.x.admincomplaint.
 *  swap landed; §366 Phase-9.x.admincomplaint.retire landed; the
 *  cross-route record-set parity claim ("identical record sets") is
 *  no longer dual-route (legacy retired) — but the Firestore-as-cell-
 *  of-truth posture holds.
 *  Two classifications STAND on their own merits. Original Phase 7.x.
 *  complaint.admin-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
interface AdminComplaintListRepository {

    /**
     * Fetch ALL submitted feedback/complaint records (admin view).
     *
     * The impl calls the legacy `GetAllComplaintUseCase()` to get `List<Complaint>`, and maps
     * each entry to a [ComplaintSummary] via the same enum/field mapping the user-side
     * [me.manga.kira.data.repository.ComplaintListRepositoryImpl] uses (kept private + file-local
     * in each impl — the mappers are 1:1 mirror-image enum projections, and lifting them to a
     * shared helper would expand the `:data` API surface for no caller-visible benefit; same
     * locality reasoning as the user-side impl's KDoc).
     *
     * Concurrency: `suspend` because the legacy `GetAllComplaintUseCase.invoke` is suspend
     * (Firestore network round-trip). Caller dispatches on `viewModelScope`.
     *
     * Result semantics:
     *  - [Result.success] — Firestore read returned (list may be empty if no complaints exist).
     *  - [Result.failure] — any failure: network, Firestore permission denial, deserialization,
     *    etc. The caller surfaces a single error state regardless of cause.
     *
     * **No userId argument**: the admin view is collection-wide. The current admin-status check
     * lives in the route adapter (`SettingsReworkScreenRoute` consults `Admin.isAdmin` before
     * routing to the admin screen); this repository doesn't gate on identity — gating happens at
     * navigation time, not data-access time.
     *
     * @return [Result.success] wrapping the list of summaries; [Result.failure] on any throw.
     */
    suspend fun loadAllComplaints(): Result<List<ComplaintSummary>>
}
