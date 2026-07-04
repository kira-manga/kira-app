package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Single-purpose use case: observe whether a specific manga is currently in the user's library.
 *
 * Contract §6 SRP: one use case, one responsibility (delegating to the repository read API).
 * Mirrors [ObserveLibraryUseCase] in shape — both are thin pass-throughs because:
 * 1. Presentation layer depends on use cases, not on repositories directly (DIP).
 * 2. Future composition (e.g. cross-feature joins, side-effecting reads) lives in the use case,
 *    not in the VM.
 *
 * Identity is the composite (api, language, title) triple — same key the underlying
 * `LibraryRepository.observeIsInLibrary` and the legacy `SavedMangaEntity` primary key use, so
 * cross-screen toggles (Library, Home, Details) round-trip through the same reactive store.
 *
 * Constructor injection per contract §6 DIP. Koin binds it as a factory in `:composeApp`
 * (see `detailsReworkModule.kt`).
 *
 * Phase 7.x.details.bookmark §253 / ADR-1: introduced for the rework Details bookmark slice in
 * preference to injecting [LibraryRepository] directly into the Details VM — preserves the
 * "VMs depend on use cases only" layering symmetry every other rework VM follows.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster127.staleKdocSweep.cascade,
 * Task #583, 2026-05-28): classified as follows after recursive symbol
 * verification (eighty-eighth sibling of the cluster57-126 sweep —
 * third file of the wave-23 `:domain/usecase/library/` 5-file
 * foundation batch alongside ObserveLibrary plus ToggleInLibrary plus
 * BulkRemoveFromLibrary plus RefreshLibrary):
 *  (a) "§6 SRP single-purpose-observe-isInLibrary + (api,language,title)-
 *  triple-identity + same-key-as-SavedMangaEntity-PK + cross-screen-
 *  Library-Home-Details-round-trip" — LIVE-NOT-STALE. DetailsView-
 *  Model.kt L13 import, L82 ctor `private val observeInLibrary:
 *  ObserveInLibraryUseCase`, L203 realization `libraryMembershipJob =
 *  observeInLibrary(...)` inside the subscription to active-manga
 *  identity; DetailsViewModel L189 KDoc explicitly references
 *  "Subscribe to [ObserveInLibraryUseCase] keyed on the active manga
 *  identity" — the identity-keyed subscription invariant is preserved
 *  verbatim. DetailsViewModel L312 references the flow-re-emission-
 *  drives-isInLibrary policy.
 *  (b) "Phase 7.x.details.bookmark §253 / ADR-1 introduced-for-rework-
 *  Details-bookmark-slice + preference-over-LibraryRepository-direct-
 *  injection + VMs-depend-on-use-cases-only-layering-symmetry" — LIVE-
 *  NOT-STALE + FULFILLED-PREDICTION. The Phase 7.x.details.bookmark
 *  slice (Task #426, completed) is the canonical caller; ADR-1
 *  rationale stands — DetailsReworkModule.kt L95 `factory { Observe-
 *  InLibraryUseCase(get()) }` confirms the cross-module Koin binding
 *  (LibraryRepository single from libraryReworkModule resolves to the
 *  DetailsReworkModule factory's get<LibraryRepository>() lookup).
 *  (c) "§6 DIP + Koin factory binding in :composeApp detailsRework-
 *  Module + cross-feature use of LibraryRepository single from
 *  libraryReworkModule" — LIVE-NOT-STALE. The intra-cluster127 sibling
 *  cross-ref to ObserveLibraryUseCase (86th) and ToggleInLibraryUseCase
 *  (87th) confirms the layering-symmetry framing — three siblings in
 *  this 5-file foundation batch all follow the same "use case wraps
 *  repository, Koin factory in :composeApp" pattern. Three
 *  classifications STAND on their own merits. Original Phase 7.x.
 *  details.bookmark-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
class ObserveInLibraryUseCase(
    private val repository: LibraryRepository,
) {
    operator fun invoke(api: String, language: String, title: String): Flow<Boolean> =
        repository.observeIsInLibrary(api = api, language = language, title = title)
}
