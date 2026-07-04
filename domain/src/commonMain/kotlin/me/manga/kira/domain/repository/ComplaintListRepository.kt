package me.manga.kira.domain.repository

import me.manga.kira.domain.model.complaint.ComplaintSummary

/**
 * Read-only surface over the user's submitted feedback/complaint records. Returns the LIST that
 * the rework Feedback Manager screen renders.
 *
 * Phase 7.x.complaint.foundation rework. The `:data` impl strangler-fig delegates to:
 *  - `:shared`/`GetUserComplaintUseCase` — the actual Firestore-bound read
 *  - `:shared`/`UserIdProvider` — to resolve the current user's id
 *
 * Same strangler-fig posture as [FeedbackRepository] (3 reaches) and [LanguageRepository]
 * (1 reach). Two reaches in this slice — `GetUserComplaintUseCase` + `UserIdProvider`.
 *
 * **Why a sibling interface rather than extending [FeedbackRepository]?** The [FeedbackRepository]
 * KDoc explicitly anticipates this exact split (lines 27-32 of [FeedbackRepository]): *"If a
 * future complaint surface needs a generic `sendComplaint(type, subject, body)` shape, that's a
 * sibling repository (e.g., `ComplaintRepository`), not a fatter interface."* The same reasoning
 * applies to the read-side: the language picker doesn't care about reading complaint lists, and
 * the complaint list doesn't care about issuing language requests. Two interfaces, two
 * responsibilities. ISP preserved.
 *
 * Contract §6 SRP: ONE rule — "fetch the current user's submitted feedback records as a single
 * snapshot list, and report success/failure". No mutation (Send/Edit/Delete are deferred to
 * follow-on slices and would go on a `ComplaintMutationRepository` or extend the existing
 * `FeedbackRepository`); no continuous observation (this is a fetch-once + retry posture
 * matching the legacy `loadForUser()`).
 *
 * Contract §6 ISP: ONE method. A future slice that adds, e.g., admin-side `loadAllComplaints()`
 * would land on a sibling `ComplaintAdminListRepository` rather than fattening this one — the
 * user-side LIST screen never needs the admin list and vice versa.
 *
 * Contract §6 DIP: the consumer (the use case
 * [me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase], and through it the
 * rework `ComplaintViewModel`) depends on this interface, never on the legacy facade or
 * Firestore directly. Koin binds the impl at the composition root in
 * `complaintReworkModule`.
 *
 * Lifecycle expectation: the impl is bound as a `single` (matching the upstream legacy
 * collaborators' singletons from `SharedModule` / `PlatformModule.*`). A `factory` would
 * re-create the impl on each resolution — wasteful for a stateless transport.
 *
 * Behavior preservation: the rework reads from the SAME Firestore collection as the legacy
 * `ComplaintViewModel.loadForUser` path. Both routes see identical record sets — same
 * `userId` filter, same field semantics, same ordering (Firestore-native, no client-side sort
 * in either path).
 *
 * **Why "Observe" in the use case name despite the suspend return?** The legacy
 * `GetUserComplaintUseCase` is suspend (single-fetch). Wrapping it in a `Flow<...>` would be a
 * fake-reactive design — the underlying Firestore call is `.get()`, not `.snapshots()`. If a
 * future slice swaps the data source to a real `.snapshots()` flow, the `Observe` name still
 * fits and the interface can grow a `fun observeUserComplaints(): Flow<List<ComplaintSummary>>`
 * sibling without renaming. For now, the suspend single-fetch matches the legacy behaviour 1:1.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster140.staleKdocSweep.cascade,
 * Task #596, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-forty-second sibling of the cluster57-139
 * sweep — fifth and closing file of the wave-25 second-cluster 5-leaf-
 * repository batch alongside PageProgressRepository plus ReadingStatistics-
 * Repository plus AboutRepository plus WhatsNewRepository; closes
 * cluster140):
 *  (a) "Read-only-surface-over-the-user-submitted-feedback-complaint-
 *  records + Phase-7.x.complaint.foundation-rework + The-:data-impl-
 *  strangler-fig-delegates-to-:shared-GetUserComplaintUseCase-the-
 *  actual-Firestore-bound-read-plus-:shared-UserIdProvider-to-resolve-
 *  the-current-user-id + Same-strangler-fig-posture-as-FeedbackRepository-
 *  3-reaches-and-LanguageRepository-1-reach + Two-reaches-in-this-slice
 *  + Why-a-sibling-interface-rather-than-extending-FeedbackRepository +
 *  The-FeedbackRepository-KDoc-explicitly-anticipates-this-exact-split
 *  + The-same-reasoning-applies-to-the-read-side + The-language-picker-
 *  does-not-care-about-reading-complaint-lists-and-the-complaint-list-
 *  does-not-care-about-issuing-language-requests + Two-interfaces-two-
 *  responsibilities-ISP-preserved + Contract-§6-SRP-ONE-rule-fetch-the-
 *  current-user-submitted-feedback-records-as-a-single-snapshot-list-
 *  and-report-success-or-failure + No-mutation-Send-Edit-Delete-are-
 *  deferred-to-follow-on-slices + no-continuous-observation-this-is-a-
 *  fetch-once-plus-retry-posture-matching-the-legacy-loadForUser" —
 *  LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified via recursive
 *  grep: ComplaintListRepository is consumed by ObserveUserComplaints-
 *  UseCase (the :domain caller) plus ComplaintListRepositoryImpl (the
 *  :data Firestore-via-shared-facade impl) plus ComplaintReworkModule
 *  plus ComplaintReworkScreenRoute. The sibling-not-fattened posture
 *  with FeedbackRepository holds — FeedbackRepository has NOT grown a
 *  loadUserComplaints method; the two interfaces remain orthogonal.
 *  The mutation surface (delete/edit/reply) lives on the orthogonal
 *  ComplaintActionRepository per its cluster's prior §253 postscript —
 *  the foundational ISP split anticipated here continues holding.
 *  (b) "Contract-§6-ISP-ONE-method + A-future-slice-that-adds-e.g.-
 *  admin-side-loadAllComplaints-would-land-on-a-sibling-Complaint-
 *  AdminListRepository-rather-than-fattening-this-one + The-user-side-
 *  LIST-screen-never-needs-the-admin-list-and-vice-versa + Contract-§6-
 *  DIP + the-consumer-the-use-case-ObserveUserComplaintsUseCase-and-
 *  through-it-the-rework-ComplaintViewModel-depends-on-this-interface-
 *  never-on-the-legacy-facade-or-Firestore-directly + Koin-binds-the-
 *  impl-at-the-composition-root-in-complaintReworkModule + Lifecycle-
 *  expectation-the-impl-is-bound-as-a-single + Behavior-preservation-
 *  the-rework-reads-from-the-SAME-Firestore-collection-as-the-legacy-
 *  ComplaintViewModel.loadForUser-path + Both-routes-see-identical-
 *  record-sets-same-userId-filter-same-field-semantics-same-ordering +
 *  Why-Observe-in-the-use-case-name-despite-the-suspend-return + The-
 *  legacy-GetUserComplaintUseCase-is-suspend-single-fetch + Wrapping-
 *  it-in-a-Flow-would-be-a-fake-reactive-design + the-underlying-
 *  Firestore-call-is-.get-not-.snapshots + If-a-future-slice-swaps-the-
 *  data-source-to-a-real-.snapshots-flow-the-Observe-name-still-fits-
 *  and-the-interface-can-grow-a-fun-observeUserComplaints-Flow-List-
 *  ComplaintSummary-sibling-without-renaming + Result-semantics-
 *  Result.success-Firestore-read-returned-Result.failure-any-failure-
 *  network-Firestore-deserialization" — LIVE-NOT-STALE plus FULFILLED-
 *  PREDICTION plus FORECAST-NOT-YET-FULFILLED-(future-AdminComplaint-
 *  ListRepository-sibling — already-LIVE-per-cluster57-138-sweep-
 *  showing-AdminComplaintListRepository-as-a-swept-leaf-:domain/
 *  repository-tier-entry; classification holds the original prediction-
 *  text but the AdminComplaintListRepository sibling already exists as
 *  a separate interface). Verified: ComplaintViewModel imports only
 *  ObserveUserComplaintsUseCase + the mutation use cases (Reply +
 *  Delete + Edit) — no :data import + no :shared facade reach. The
 *  Result<List<ComplaintSummary>> return type stays — no AppResult
 *  migration here (legacy parity). The Observe-named-but-suspend-
 *  returning use case name remains accurate.
 *  Two classifications STAND on their own merits. Closes cluster140.
 *  Original Phase 7.x.complaint.foundation-era prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
interface ComplaintListRepository {

    /**
     * Fetch the current user's submitted feedback/complaint records.
     *
     * The impl resolves the user id via `UserIdProvider`, calls the legacy
     * `GetUserComplaintUseCase(userId)` to get `List<Complaint>`, and maps each entry to a
     * [ComplaintSummary]. The `metadata` field on the legacy `Complaint` is intentionally not
     * carried into the summary (see [ComplaintSummary] KDoc).
     *
     * Concurrency: `suspend` because the legacy `GetUserComplaintUseCase.invoke` is suspend
     * (Firestore network round-trip). Caller dispatches on `viewModelScope`.
     *
     * Result semantics:
     *  - [Result.success] — Firestore read returned (list may be empty if the user has no
     *    submissions).
     *  - [Result.failure] — any failure: network, Firestore, deserialization, etc. The caller
     *    surfaces a single error state regardless of cause.
     *
     * @return [Result.success] wrapping the list of summaries; [Result.failure] on any throw.
     */
    suspend fun loadUserComplaints(): Result<List<ComplaintSummary>>
}
