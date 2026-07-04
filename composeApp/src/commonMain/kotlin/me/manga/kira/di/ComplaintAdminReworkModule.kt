package me.manga.kira.di

import me.manga.kira.data.repository.AdminComplaintActionRepositoryImpl
import me.manga.kira.data.repository.AdminComplaintListRepositoryImpl
import me.manga.kira.domain.repository.AdminComplaintActionRepository
import me.manga.kira.domain.repository.AdminComplaintListRepository
import me.manga.kira.domain.usecase.complaint.AddClosureReasonUseCase
import me.manga.kira.domain.usecase.complaint.AdminDeleteComplaintUseCase
import me.manga.kira.domain.usecase.complaint.AdminEditComplaintUseCase
import me.manga.kira.domain.usecase.complaint.ChangeComplaintStatusUseCase
import me.manga.kira.domain.usecase.complaint.ObserveAllComplaintsUseCase
import me.manga.kira.presentation.complaint.admin.AdminComplaintViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework admin Complaint dashboard slice.
 *
 * Phase 7.x.complaint.admin foundation (READ-side):
 *  - [AdminComplaintListRepository] (`:domain`) → [AdminComplaintListRepositoryImpl] (`:data`).
 *  - [ObserveAllComplaintsUseCase] (`:domain` — pass-through delegate to the repository).
 *  - [AdminComplaintViewModel] (`:presentation`) — one constructor dep (the use case).
 *
 * Legacy `:shared` collaborator resolved transitively via `SharedModule`:
 *  - [me.manga.kira.presentation.features.complaint.usecase.GetAllComplaintUseCase] —
 *    consumed by [AdminComplaintListRepositoryImpl] for the Firestore-bound collection-wide
 *    query (legacy `getAllComplaints()` reaches the `complaints` collection without a userId
 *    filter — admin views EVERY user's submissions).
 *
 * **Sibling vs extension (ISP §6)**: [AdminComplaintListRepository] is a SIBLING of
 * [me.manga.kira.domain.repository.ComplaintListRepository], not an extension — predicted
 * verbatim in the user-side `ComplaintListRepository` KDoc lines 27-32. The user-side interface's
 * `loadComplaints()` takes no `userId` arg (the impl pulls it from `UserIdProvider`), so making
 * the admin "all complaints" load a second method on the same interface would force every
 * non-admin consumer to depend on a method they can't use without elevated privileges. Two
 * sibling interfaces with one method each is the ISP-clean split.
 *
 * **Strangler-fig posture**: same as the user-side foundation
 * ([me.manga.kira.di.complaintReworkModule]) — the `:data` impl reaches into the legacy
 * `:shared` `GetAllComplaintUseCase` (constructor-injected) until Phase 9.x route-swap retires
 * the legacy facade. ONE `:data` → `:shared` reach, documented in
 * [AdminComplaintListRepositoryImpl]'s KDoc.
 *
 * **Cross-module dependencies resolved at composition time**: the legacy
 * `GetAllComplaintUseCase` is bound `single` by `SharedModule` alongside the user-side legacy
 * `GetUserComplaintUseCase`. No platform-specific code in this slice.
 *
 * **Why a separate module vs appending to `complaintReworkModule`**: same posture as the
 * `aboutReworkModule` / `whatsNewReworkModule` split — each slice owns its own DI module file
 * for SRP (contract §6: one module = one feature slice). Appending here would couple the
 * admin-foundation slice's lifecycle to the user-side action slice's iteration cadence. The
 * `:composeApp/.../di/ReworkModules.kt` aggregator lists both modules side-by-side.
 *
 * SRP (contract §6): one module = one feature slice (admin Complaint dashboard READ-side LIST +
 * search + 2-axis filter). The OCP §6 extension hook this slice's KDoc anticipates is a future
 * `Phase 7.x.complaint.admin.actions` slice that will append bindings for status-change / edit /
 * closure-reason / delete / bulk-update / bulk-delete repositories + use cases + an extended
 * VM constructor signature — same shape as the user-side foundation → actions slice progression.
 *
 * DIP (contract §6): the repository interface lives in `:domain`; the impl lives in `:data`.
 * Presentation / UI see only the use case and interface; legacy `:shared` types do not leak
 * into the rework presentation layer.
 *
 * Lifecycle choices:
 *  - [AdminComplaintListRepository] → `single`: stateless transport whose legacy collaborator is
 *    itself a singleton.
 *  - [ObserveAllComplaintsUseCase] → `factory`: stateless thin pass-through, cheap to instantiate.
 *  - [AdminComplaintViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding (one VM
 *    instance per `NavBackStackEntry` ViewModelStoreOwner).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster21.staleKdocSweep.cascade,
 * Task #477, 2026-05-28): two categories of fulfilled-prediction +
 * fulfilled-extension-hook citations appear above:
 *  - Lines 39-43 ("Strangler-fig posture: same as the user-side
 *    foundation ([me.manga.kira.di.complaintReworkModule]) — the
 *    `:data` impl reaches into the legacy `:shared` `GetAllComplaintUseCase`
 *    (constructor-injected) until Phase 9.x route-swap retires the
 *    legacy facade. ONE `:data` → `:shared` reach, documented in
 *    [AdminComplaintListRepositoryImpl]'s KDoc"). PARTIALLY-FULFILLED-
 *    INVERSION — Phase 9.x.admincomplaint.swap (§365) re-pointed
 *    `Screen.ComplaintAdmin`'s rendering adapter to the rework
 *    `AdminComplaintScreen` already; Phase 9.x.admincomplaint.retire
 *    (§366) deleted the orphan legacy admin VM + screen + 2 helpers +
 *    `SharedModule` `viewModel { AdminComplaintViewModel(...) }` binding.
 *    HOWEVER — the legacy `:shared` `GetAllComplaintUseCase` +
 *    `UpdateComplaintUseCase` + `DeleteComplaintUseCase` + `UserIdProvider`
 *    trio remain LIVE in `SharedModule` as strangler-fig collaborators
 *    for the rework `:data` `AdminComplaintActionRepositoryImpl`
 *    (verified at lines 81-87 below — constructor injects `legacyGetAll
 *    = get(), legacyUpdate = get(), legacyDelete = get(),
 *    legacyUserIdProvider = get()`). "Phase 9.x route-swap retires the
 *    legacy facade" prediction is HALF-FULFILLED: consumer-side
 *    (VM + UI) retired across §366; use-case-transport retained as
 *    Firestore-bound transport backbone. The "ONE `:data` → `:shared`
 *    reach" count is now FOUR (1 in foundation slice + 3 added in
 *    actions slice for the WRITE-side sibling repo at lines 81-87
 *    below). Mirror of user-side §474 + cluster20 §476
 *    half-fulfilled-retire precedent.
 *  - Lines 55-59 ("The OCP §6 extension hook this slice's KDoc
 *    anticipates is a future `Phase 7.x.complaint.admin.actions` slice
 *    that will append bindings for status-change / edit / closure-reason
 *    / delete / bulk-update / bulk-delete repositories + use cases +
 *    an extended VM constructor signature — same shape as the user-side
 *    foundation → actions slice progression"). FULFILLED — Phase
 *    7.x.complaint.admin.actions (§259) shipped the
 *    [AdminComplaintActionRepository] + the 4 mutation use cases
 *    ([ChangeComplaintStatusUseCase] + [AddClosureReasonUseCase] +
 *    [AdminDeleteComplaintUseCase] + [AdminEditComplaintUseCase]
 *    visible at lines 91-94 below) + the extended VM constructor
 *    signature (5 ctor args at lines 96-103 below). Phase
 *    7.x.complaint.admin.bulk (§265) did NOT materialise as separate
 *    `bulk-update` / `bulk-delete` use cases — the bulk surface
 *    consumes the existing single-action use cases in a fan-out loop
 *    instead (SRP refinement: bulk is a UI concern, not a domain
 *    operation). Refined for SRP. Mirror of §463 + §471
 *    consolidated-effect precedent.
 * The ISP sibling-vs-extension rationale + SRP module-separation +
 * DIP/SRP rationale + lifecycle-choices (single/factory/viewModel)
 * sub-sections all stand on their own merits past the §§259 + 265 +
 * 365 + 366 fulfilled landings. The complaintAdminReworkModule remains
 * LIVE as the canonical Koin module for `Screen.ComplaintAdmin` +
 * `Screen.ComplaintAdminRework` (both now converge on the rework path
 * post-§365 swap). Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citations are historical
 * record of the design lineage including the deferred-actions-slice
 * extension-hook and the deferred-facade-retire forecast that were
 * subsequently fulfilled across §259 + §265 + §365 + §366.
 */
val complaintAdminReworkModule: Module = module {
    single<AdminComplaintListRepository> { AdminComplaintListRepositoryImpl(legacy = get()) }

    // Phase 7.x.complaint.admin.actions — WRITE-side sibling of AdminComplaintListRepository.
    // Legacy deps resolved transitively via SharedModule:
    //   GetAllComplaintUseCase     — for metadata re-fetch before status-change / closure-reason
    //   UpdateComplaintUseCase     — for changeStatus / addClosureReason
    //   DeleteComplaintUseCase     — for deleteComplaint
    //   UserIdProvider             — for closure-reason `reasonAddedBy` metadata field
    single<AdminComplaintActionRepository> {
        AdminComplaintActionRepositoryImpl(
            legacyGetAll = get(),
            legacyUpdate = get(),
            legacyDelete = get(),
            legacyUserIdProvider = get(),
        )
    }

    factory { ObserveAllComplaintsUseCase(get()) }
    factory { ChangeComplaintStatusUseCase(get()) }
    factory { AddClosureReasonUseCase(get()) }
    factory { AdminDeleteComplaintUseCase(get()) }
    factory { AdminEditComplaintUseCase(get()) }

    viewModel {
        AdminComplaintViewModel(
            observeAllComplaints = get(),
            changeStatus = get(),
            addClosureReason = get(),
            adminDeleteComplaint = get(),
            adminEditComplaint = get(),
        )
    }
}
