package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Single-purpose use case: observe the user's library.
 *
 * Contract §6 SRP: one use case, one responsibility (delegating to the repository read API).
 * The use case exists even when it's a thin pass-through because:
 * 1. Presentation layer depends on use cases, not on repositories directly (DIP).
 * 2. Future composition (filter/sort/cross-feature joins) lives in the use case, not in the VM.
 *
 * Constructor injection per contract §6 DIP. Koin binds it as a factory in :composeApp.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster127.staleKdocSweep.cascade,
 * Task #583, 2026-05-28): classified as follows after recursive symbol
 * verification (eighty-sixth sibling of the cluster57-126 sweep — first
 * and opening file of the wave-23 `:domain/usecase/library/` 5-file
 * foundation batch alongside ToggleInLibrary plus ObserveInLibrary plus
 * BulkRemoveFromLibrary plus RefreshLibrary; opens the 5-cluster
 * library/ sweep cycle (cluster127-131, 25 files total)):
 *  (a) "Single-purpose use case observe-library + delegating-to-
 *  repository-read-API + presentation-depends-on-use-cases-not-
 *  repositories (DIP) + future-composition-filter-sort-cross-feature-
 *  joins-lives-in-use-case" — LIVE-NOT-STALE + FULFILLED-PREDICTION.
 *  LibraryViewModel.kt L34 import, L89 ctor `private val observe-
 *  Library: ObserveLibraryUseCase`, L286 realization `observeJob =
 *  observeLibrary()` inside startObserving(); the future-composition
 *  forecast HAS LANDED: VM L554 KDoc references "the `observeLibrary()`
 *  flow in [startObserving] re-emits" — sort/filter/category/density/
 *  display-toggle composition all sit downstream of this Flow per the
 *  observed evolution from Phase 7.x.library.sort through Phase 7.x.
 *  library.display.* (Tasks #316-345).
 *  (b) "§6 DIP + Koin factory binding in :composeApp" — LIVE-NOT-STALE.
 *  LibraryReworkModule.kt L20 import, L123 `factory { ObserveLibrary-
 *  UseCase(get()) }` realization confirms the factory-binding
 *  prediction. Three classifications STAND on their own merits.
 *  Original Phase 6.2-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class ObserveLibraryUseCase(
    private val repository: LibraryRepository,
) {
    operator fun invoke(): Flow<List<LibraryManga>> = repository.observeLibrary()
}
