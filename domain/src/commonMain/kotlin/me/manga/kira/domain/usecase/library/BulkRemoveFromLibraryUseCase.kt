package me.manga.kira.domain.usecase.library

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.repository.MangaKey

/**
 * Bulk-remove a set of manga from the user's library in one call.
 *
 * Contract §6 SRP: this use case owns ONE rule — "remove a batch of library entries and tell the
 * caller how many were targeted so the UI can render a localized 'removed N items' message".
 * The per-item removal policy lives in [me.manga.kira.domain.repository.LibraryRepository.removeAllFromLibrary] /
 * the data layer; bulk-vs-single is an orchestration concern that belongs in a use case, not in
 * the ViewModel and not in the repository.
 *
 * Behavior:
 *  - Empty [keys] → returns `AppResult.Success(0)` without touching the repository. Avoids a
 *    pointless DAO round-trip when the multi-select list happens to be cleared between the
 *    user's tap and the dispatch.
 *  - Non-empty [keys] → delegates to `repository.removeAllFromLibrary(keys)` and forwards its
 *    result verbatim: `AppResult.Success(actualPurgedCount)` (not-found keys are skipped, so the
 *    toast reflects what was really removed) or the underlying [AppResult.Failure].
 *
 * Constructor-injected `LibraryRepository` per contract §6 DIP — Koin binds it as a factory in
 * `:composeApp` (cheap to instantiate, no per-call state).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster127.staleKdocSweep.cascade,
 * Task #583, 2026-05-28): classified as follows after recursive symbol
 * verification (eighty-ninth sibling of the cluster57-126 sweep —
 * fourth file of the wave-23 `:domain/usecase/library/` 5-file
 * foundation batch alongside ObserveLibrary plus ToggleInLibrary plus
 * ObserveInLibrary plus RefreshLibrary):
 *  (a) "§6 SRP bulk-remove-batch + tell-caller-how-many-targeted-for-
 *  localized-removed-N-items-message + bulk-vs-single-orchestration-
 *  belongs-in-use-case-not-VM-not-repository" — LIVE-NOT-STALE.
 *  LibraryViewModel.kt L25 import, L91 ctor `private val bulkRemove-
 *  FromLibrary: BulkRemoveFromLibraryUseCase`, two realizations:
 *  L345 `bulkRemoveFromLibrary(keys)` inside the multi-select-bulk-
 *  remove intent handler AND L600 `bulkRemoveFromLibrary(listOf(key))`
 *  for the single-tap-remove path (the use case's L31-34 empty-list-
 *  short-circuit doubles as the entry point for both intent shapes
 *  per the orchestration framing).
 *  (b) "Empty-keys → AppResult.Success(0) without-touching-repository
 *  + avoids-pointless-DAO-round-trip-when-multi-select-list-cleared-
 *  between-tap-and-dispatch" — LIVE-NOT-STALE. L32 `if (keys.isEmpty())
 *  return AppResult.Success(0)` early-return preserved verbatim; the
 *  race-condition framing (multi-select list clear-between-tap-and-
 *  dispatch) stands.
 *  (c) "§6 DIP + Koin factory binding in :composeApp (cheap-no-per-
 *  call-state)" — LIVE-NOT-STALE. LibraryReworkModule.kt L11 import,
 *  L125 `factory { BulkRemoveFromLibraryUseCase(get()) }` realization;
 *  the LibraryReworkModule L148 KDoc references "`BulkRemoveFromLibrary-
 *  UseCase` factory above — no new binding there" reinforcing the
 *  factory-singleton-binding posture. Three classifications STAND on
 *  their own merits. Original Phase 6.2.x-era prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
class BulkRemoveFromLibraryUseCase(
    private val repository: LibraryRepository,
) {
    suspend operator fun invoke(keys: List<MangaKey>): AppResult<Int> {
        if (keys.isEmpty()) return AppResult.Success(0)
        // #21: forward the ACTUAL purged-row count from the repo (skips not-found keys) instead of
        // the selected keys.size, so the success toast reflects what was really removed.
        return repository.removeAllFromLibrary(keys)
    }
}
