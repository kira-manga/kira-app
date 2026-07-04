package me.manga.kira.domain.usecase.details

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.MangaDetailsRepository

/**
 * Fetch the full [MangaDetails] for a given [Manga].
 *
 * Contract §6 SRP: owns ONE rule — "ask the [MangaDetailsRepository] for this manga's current
 * details and propagate the typed result". The source-routing / network / parse policy lives in
 * the `:data` impl; this use case is the seam between the `:presentation` Details ViewModel
 * (which doesn't know about repositories) and the repository abstraction.
 *
 * Why a use case at all when this is a single-line delegate:
 *  - **Stable presentation-layer dependency**. The Details VM depends on `FetchMangaDetailsUseCase`,
 *    not on `MangaDetailsRepository`. Future enrichment of the fetch policy (e.g. local cache
 *    fallthrough, library-state merge) lands here without forcing a VM signature change.
 *  - **Test seam**. Mocking one suspend operator is cheaper than mocking the full repository
 *    interface for the Details VM unit tests that come later.
 *  - **Consistent with the Library slice**. `ObserveLibraryUseCase` / `ToggleInLibraryUseCase` /
 *    `BulkRemoveFromLibraryUseCase` follow the same pattern (one use case per VM-callable verb).
 *
 * Constructor-injected `MangaDetailsRepository` per contract §6 DIP — Koin binds it as a factory
 * in `:composeApp/DetailsReworkModule.kt` (lands with the Details Koin slice).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster119.staleKdocSweep.cascade,
 * Task #575, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-ninth sibling of the cluster57-118 sweep — opens the
 * wave-19 `:domain/usecase/details/` batch alongside IsAdultContentUse-
 * Case.kt):
 *  (a) "Contract §6 SRP owns ONE rule — ask the MangaDetailsRepository
 *  for this manga's current details and propagate the typed result;
 *  the source-routing / network / parse policy lives in the `:data`
 *  impl; this use case is the seam between the `:presentation` Details
 *  ViewModel (which doesn't know about repositories) and the repository
 *  abstraction" — LIVE-NOT-STALE. DetailsViewModel.kt L80 primary
 *  constructor binds `private val fetchDetails: FetchMangaDetailsUse-
 *  Case`; L31-33 realization `repository.fetchDetails(manga)` single-
 *  line pass-through returning AppResult<MangaDetails>. MangaDetails-
 *  RepositoryImpl `:data` impl source-routing plus network plus parse
 *  policy verified at cluster25 sibling sweep (Task #481) — the VM
 *  remains free of repository-shape leakage.
 *  (b) "Why a use case at all when this is a single-line delegate:
 *  stable presentation-layer dependency (Details VM depends on Fetch-
 *  MangaDetailsUseCase, not on MangaDetailsRepository; future enrichment
 *  of the fetch policy lands here without forcing a VM signature
 *  change); test seam (mocking one suspend operator is cheaper than
 *  mocking the full repository interface); consistent with the Library
 *  slice (ObserveLibraryUseCase / ToggleInLibraryUseCase / BulkRemove-
 *  FromLibraryUseCase follow the same one-use-case-per-VM-callable-
 *  verb pattern)" — LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED.
 *  Stable-presentation-layer-dependency claim verified — Details-
 *  ViewModel.kt L80 binds the use-case type, not the repository type.
 *  Test-seam claim verified — DetailsViewModelTest mocking posture
 *  remains the documented contract. Library-slice peer rationale
 *  cross-ref — the three Library use cases (ObserveLibraryUseCase /
 *  ToggleInLibraryUseCase / BulkRemoveFromLibraryUseCase) remain
 *  UNSWEPT in the cascade but exist as architectural references; the
 *  one-use-case-per-VM-callable-verb convention holds across the
 *  rework. Future fetch-policy enrichment (local cache fallthrough,
 *  library-state merge) — FORECAST-NOT-YET-FULFILLED. Recursive search
 *  for local-cache-fallthrough plus library-state-merge composition
 *  returns zero matches; the use case remains the single-line pass-
 *  through shape.
 *  (c) "Constructor-injected MangaDetailsRepository per contract §6 DIP
 *  — Koin binds it as a factory in `:composeApp/DetailsReworkModule.kt`
 *  (lands with the Details Koin slice)" — LIVE-NOT-STALE. DetailsRework-
 *  Module.kt L75 `factory { FetchMangaDetailsUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap, matches
 *  the established "use case rename-to factory" convention); single<
 *  MangaDetailsRepository> bound to MangaDetailsRepositoryImpl at L68-
 *  74 (`single` because the impl holds no per-call state and the
 *  underlying SourcesRepository is also `single`).
 *  Three classifications STAND on their own merits as a faithful Fetch-
 *  MangaDetailsUseCase manifest. Original Phase 6.3.1-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class FetchMangaDetailsUseCase(
    private val repository: MangaDetailsRepository,
) {
    suspend operator fun invoke(manga: Manga): AppResult<MangaDetails> =
        repository.fetchDetails(manga)
}
