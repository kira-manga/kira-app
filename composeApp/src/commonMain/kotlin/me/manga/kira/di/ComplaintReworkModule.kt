package me.manga.kira.di

import me.manga.kira.data.repository.ComplaintActionRepositoryImpl
import me.manga.kira.data.repository.ComplaintListRepositoryImpl
import me.manga.kira.domain.repository.ComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.usecase.complaint.DeleteComplaintUseCase
import me.manga.kira.domain.usecase.complaint.EditComplaintUseCase
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import me.manga.kira.domain.usecase.complaint.ReplyToComplaintUseCase
import me.manga.kira.presentation.complaint.ComplaintViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Complaint slice.
 *
 * Phase 7.x.complaint.foundation (READ-side):
 *  - [ComplaintListRepository] (`:domain`) → [ComplaintListRepositoryImpl] (`:data`).
 *  - [ObserveUserComplaintsUseCase] (`:domain` — pass-through delegate to the repository).
 *
 * Phase 7.x.complaint.actions append (WRITE-side):
 *  - [ComplaintActionRepository] (`:domain`) → [ComplaintActionRepositoryImpl] (`:data`).
 *  - [ReplyToComplaintUseCase] / [EditComplaintUseCase] / [DeleteComplaintUseCase] — thin
 *    pass-throughs over the action repository.
 *  - [ComplaintViewModel] binding rebuilt with 4 constructor args (the foundation's
 *    `observeUserComplaints` + the 3 new mutation use cases).
 *
 * Legacy `:shared` collaborators stay bound by `SharedModule` and are resolved transitively:
 *  - [me.manga.kira.presentation.features.complaint.usecase.GetUserComplaintUseCase] — consumed
 *    by [ComplaintListRepositoryImpl] for the Firestore-bound user-scoped query.
 *  - [me.manga.kira.presentation.features.complaint.usecase.SendComplaintUseCase] /
 *    [me.manga.kira.presentation.features.complaint.usecase.UpdateComplaintUseCase] /
 *    [me.manga.kira.presentation.features.complaint.usecase.DeleteComplaintUseCase] — consumed
 *    by [ComplaintActionRepositoryImpl] for the 3 user-side mutations.
 *  - [me.manga.kira.domain.auth.UserIdProvider] — consumed by [ComplaintListRepositoryImpl].
 *
 * Cross-module dependencies resolved at composition time:
 *  - All three legacy mutation use cases are bound `single` by `SharedModule` alongside the
 *    READ-side `GetUserComplaintUseCase`. No platform-specific code in this slice.
 *
 * **Strangler-fig posture**: this slice WRITES to the same Firestore `complaints` collection that
 * the legacy `SendComplaintUseCase` / `UpdateComplaintUseCase` / `DeleteComplaintUseCase` operate
 * on — the rework `:data` impl is a thin adapter mapping the new `ComplaintSummary` domain model
 * to the legacy `Complaint` shape (and back via the foundation's READ adapter). Phase 9.x
 * route-swap retires the legacy facade later.
 *
 * **Two sibling repositories (ISP)**: the rework splits the legacy `ComplaintRepository`'s 5
 * methods into READ ([ComplaintListRepository]) and WRITE ([ComplaintActionRepository])
 * interfaces. Each consumer depends only on what it uses — the action use cases never see the
 * READ-side `observeUserComplaints` and vice versa. Contract §6 ISP discipline applies at the
 * `:domain` boundary; the impls happen to live in the same module but inject distinct legacy
 * collaborator subsets.
 *
 * SRP (contract §6): one module = one feature slice (user-side Complaint LIST + search + filter +
 * action dialogs). The OCP §6 extension hook the foundation slice's KDoc anticipated is exactly
 * this slice — new bindings appended, existing bindings unchanged in shape (only the VM's
 * constructor signature grew via additive args).
 *
 * DIP (contract §6): the two repository interfaces live in `:domain`; the two impls live in
 * `:data`. Presentation / UI see only the use cases and interfaces; legacy `:shared` types do
 * not leak into the rework presentation layer.
 *
 * Lifecycle choices:
 *  - [ComplaintListRepository] / [ComplaintActionRepository] → `single`: stateless transports
 *    whose legacy collaborators are themselves singletons.
 *  - All four use cases → `factory`: stateless thin pass-throughs, cheap to instantiate.
 *  - [ComplaintViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding (one VM instance
 *    per `NavBackStackEntry` ViewModelStoreOwner).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster20.staleKdocSweep.cascade,
 * Task #476, 2026-05-28): one category of fulfilled-deferral inversion +
 * one LIVE-strangler-fig citation appears above:
 *  - Lines 43-47 ("Strangler-fig posture: this slice WRITES to the same
 *    Firestore `complaints` collection that the legacy `SendComplaintUseCase`
 *    / `UpdateComplaintUseCase` / `DeleteComplaintUseCase` operate on —
 *    the rework `:data` impl is a thin adapter mapping the new
 *    `ComplaintSummary` domain model to the legacy `Complaint` shape (and
 *    back via the foundation's READ adapter). Phase 9.x route-swap
 *    retires the legacy facade later"). PARTIALLY-FULFILLED-INVERSION —
 *    Phase 7.x.complaint.swap (§293) re-pointed `Screen.Complaint`'s
 *    rendering adapter to the rework `ComplaintScreen` already; Phase
 *    9.x.complaint.legacyui.retire (§355) deleted the legacy `:shared`
 *    `ComplaintScreen.kt` UI; Phase 9.x.complaintvm.retire (§363)
 *    deleted the orphan legacy `ComplaintViewModel`. HOWEVER — the
 *    legacy `SendComplaintUseCase` + `UpdateComplaintUseCase` +
 *    `DeleteComplaintUseCase` trio remains LIVE in `SharedModule` as
 *    strangler-fig collaborators for the rework `:data`
 *    `ComplaintActionRepositoryImpl` (verified at line 75 below —
 *    constructor injects `send = get(), update = get(), delete = get()`
 *    where each is a legacy `:shared` use case). The "Phase 9.x
 *    route-swap retires the legacy facade later" prediction is
 *    HALF-FULFILLED — the legacy presentation-layer surface (VM + UI)
 *    is fully retired but the legacy use-case layer remains as the
 *    Firestore-bound transport backbone. Further retirement would
 *    require lifting the Firestore writes directly into the `:data`
 *    impls (out-of-scope for this campaign — same posture as the
 *    LanguageReworkModule's `SendComplaintUseCase` consumption via
 *    `FeedbackRepositoryImpl`). Mirror of §474 `WhatsNewScreenRoute.kt`
 *    half-fulfilled-retire precedent.
 *  - Lines 31-37 ("Legacy `:shared` collaborators ... GetUserComplaintUseCase
 *    ... SendComplaintUseCase / UpdateComplaintUseCase / DeleteComplaintUseCase
 *    ... UserIdProvider"). LIVE — all four legacy `:shared` collaborators
 *    STILL EXIST and are resolved transitively via `get()` at the impl
 *    constructor sites (lines 73-75 below). The strangler-fig backbone
 *    holds; only the legacy consumer-side surfaces (VM + UI) were
 *    retired across §293 + §355 + §363.
 * The ISP discipline (READ + WRITE repo split) + OCP §6 extension hook
 * (foundation slice's anticipated growth) + DIP/SRP rationale +
 * lifecycle-choices (single/factory/viewModel) sub-sections all stand
 * on their own merits past the §§293 + 355 + 363 fulfilled landings.
 * The complaintReworkModule remains LIVE as the canonical Koin module
 * for `Screen.Complaint` + `Screen.ComplaintRework` (both now converge
 * on the rework path post-§293 swap). Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the citations
 * are historical record of the design lineage including the
 * deferred-facade-retire forecast that was subsequently half-fulfilled
 * (consumer-side retired; use-case-transport retained).
 */
val complaintReworkModule: Module = module {
    single<ComplaintListRepository> { ComplaintListRepositoryImpl(legacy = get(), userIdProvider = get()) }
    single<ComplaintActionRepository> {
        // #9: getUser (legacy GetUserComplaintUseCase, already single-bound in SharedModule) lets
        // edit/reply re-fetch the full complaint and preserve all metadata.
        ComplaintActionRepositoryImpl(send = get(), update = get(), delete = get(), getUser = get())
    }

    factory { ObserveUserComplaintsUseCase(get()) }
    factory { ReplyToComplaintUseCase(get()) }
    factory { EditComplaintUseCase(get()) }
    factory { DeleteComplaintUseCase(get()) }

    viewModel {
        ComplaintViewModel(
            observeUserComplaints = get(),
            replyToComplaint = get(),
            editComplaint = get(),
            deleteComplaint = get(),
        )
    }
}
