package me.manga.kira.domain.usecase.library

import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.flatMap
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Toggle a manga's library membership.
 *
 * Contract §6 SRP: this use case owns ONE rule — "if the manga is already in the library, remove
 * it; otherwise add it". The repository exposes add/remove primitives separately; the use case is
 * where the toggle policy lives (so add/remove can also be called individually without policy).
 *
 * Returns the new membership state (`true` = now in library, `false` = now out of library) so
 * the caller can update UI without re-querying.
 *
 * Note: the check-then-act (`get` → add/remove) is NOT transactionally atomic. Two concurrent
 * invocations (e.g. a double-tap, since `MviViewModel.submit` launches a fresh coroutine per
 * intent) can both observe the same snapshot and collapse into a single net toggle. This is
 * benign — add is an upsert and remove is idempotent, so the returned Boolean always agrees with
 * the final DB state — but a rapid double-tap may not flip membership the second time.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster127.staleKdocSweep.cascade,
 * Task #583, 2026-05-28): classified as follows after recursive symbol
 * verification (eighty-seventh sibling of the cluster57-126 sweep —
 * second file of the wave-23 `:domain/usecase/library/` 5-file
 * foundation batch alongside ObserveLibrary plus ObserveInLibrary plus
 * BulkRemoveFromLibrary plus RefreshLibrary):
 *  (a) "§6 SRP toggle-policy-lives-in-use-case + repository-exposes-
 *  add-remove-primitives-separately + returns-new-membership-state-for-
 *  UI-without-re-querying" — LIVE-NOT-STALE. LibraryViewModel.kt L46
 *  import, L90 ctor `private val toggleInLibrary: ToggleInLibraryUse-
 *  Case`, L351 realization `toggleInLibrary(manga)` inside the
 *  ToggleInLibrary intent handler. Toggle-policy single-rule preserved:
 *  L22-30 reads `repository.get(manga.api, manga.language, manga.title)`
 *  then branches on null vs non-null current state via `flatMap`,
 *  returning Boolean (true=now-in-library, false=now-out) — the
 *  membership-toggle policy lives here per the original prose's
 *  framing.
 *  (b) "Cross-feature reach — also used by Details rework parity slice
 *  1 (Phase 7.x.details.bookmark, Task #426) for the bookmark
 *  IconButton" — LIVE-FRAMING + FULFILLED-PREDICTION. DetailsView-
 *  Model uses the sibling ObserveInLibraryUseCase (87th sibling
 *  forthcoming) for the reactive heart-icon state but invokes this
 *  ToggleInLibraryUseCase for the actual toggle write — cross-package
 *  Koin single-graph resolution per the Phase 7.x.details.bookmark
 *  ADR-1 referenced in DetailsReworkModule.kt L95 ObserveInLibrary
 *  factory binding (which sits alongside the toggle binding from
 *  libraryReworkModule).
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L32 import, L124 `factory {
 *  ToggleInLibraryUseCase(get()) }` realization. Three classifications
 *  STAND on their own merits. Original Phase 6.2-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class ToggleInLibraryUseCase(
    private val repository: LibraryRepository,
) {
    /**
     * @param details the authoritative fetched details to persist on the add branch. It is optional
     * only because the removal branch does not need it. Adding without details fails closed instead
     * of creating a partial library row that loses description/author/status/rating or chapters.
     */
    suspend operator fun invoke(
        manga: Manga,
        details: MangaDetails? = null,
    ): AppResult<Boolean> {
        val existing = repository.get(manga.api, manga.language, manga.title)
        return existing.flatMap { current ->
            if (current == null) {
                val fetched = details
                    ?: return@flatMap AppResult.Failure(AppError.Validation.Required("mangaDetails"))
                repository.addToLibrary(fetched).flatMap { AppResult.Success(true) }
            } else {
                repository.removeFromLibrary(manga.api, manga.language, manga.title)
                    .flatMap { AppResult.Success(false) }
            }
        }
    }
}
