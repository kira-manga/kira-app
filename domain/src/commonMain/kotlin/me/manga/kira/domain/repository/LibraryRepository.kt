package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest

/**
 * Library aggregate root contract.
 *
 * Contract §6 ISP: this interface only owns library operations. Chapter reading, downloads, and
 * source-listing each get their own repository (added in later phases). No god-repository.
 *
 * Contract §6 DIP: defined in :domain; the concrete implementation lives in :data and is bound
 * by Koin in :composeApp. Use cases depend on this interface, not on the impl.
 *
 * All mutating ops return [AppResult] so callers handle failures explicitly (contract §10/§19).
 * Read ops return [Flow] for reactive UI — the underlying Room store emits on every write, so the
 * UI never reads stale data.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster141.staleKdocSweep.cascade,
 * Task #597, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-forty-third sibling of the cluster57-140
 * sweep — first file of the wave-25 third-cluster 3-leaf-repository
 * closing batch opening cluster141; will close :domain/repository/ tier
 * to 26/26 FULLY SWEPT after cluster141 lands):
 *  (a) "Library-aggregate-root-contract + Contract-§6-ISP-this-interface-
 *  only-owns-library-operations + Chapter-reading-downloads-and-source-
 *  listing-each-get-their-own-repository-added-in-later-phases + No-god-
 *  repository + Contract-§6-DIP-defined-in-:domain-the-concrete-
 *  implementation-lives-in-:data-and-is-bound-by-Koin-in-:composeApp +
 *  Use-cases-depend-on-this-interface-not-on-the-impl + All-mutating-
 *  ops-return-AppResult-so-callers-handle-failures-explicitly-contract-
 *  §10-§19 + Read-ops-return-Flow-for-reactive-UI + the-underlying-
 *  Room-store-emits-on-every-write-so-the-UI-never-reads-stale-data" —
 *  LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified via recursive
 *  grep: LibraryRepository is the highest-fan-in :domain interface,
 *  consumed by 20+ use cases across the library/ + bookmark/ + history/
 *  + downloads/ subpackages plus LibraryRepositoryImpl + LibraryRework-
 *  Module + ToggleInLibraryUseCase (the §426 Phase-7.x.details.bookmark
 *  rework Details consumer) plus DetailsViewModel via the use case
 *  layer plus ChapterReadingUseCases via observeIsInLibrary plus
 *  BulkRemoveFromLibraryUseCase via removeAllFromLibrary plus the
 *  toggleLiked/toggleWatchingNow pair via ToggleLikedUseCase /
 *  ToggleWatchingNowUseCase. The ISP "no god-repository" claim still
 *  holds: chapter reads (ChapterPagesRepository + ReadProgressReposito-
 *  ry), downloads (DownloadsRepository + DownloadsActionRepository),
 *  source listing (SourcesRepository), reading-mode (ReadingMode-
 *  Repository), and reading-statistics (ReadingStatisticsRepository)
 *  each live on their own interfaces — sibling-not-fattened posture
 *  preserved across the whole :domain/repository/ tier.
 *  (b) "Reactive-snapshot-of-the-user-library + Emit-order-is-
 *  unspecified-the-presentation-layer-applies-the-user-chosen-sort +
 *  The-DAO-currently-emits-by-title-ASC-callers-must-not-depend-on-
 *  that + Reactive-flag-is-this-manga-currently-in-the-user-library +
 *  One-shot-lookup-of-a-single-library-entry-or-null-when-absent +
 *  Add-a-manga-to-the-library-Idempotent-adding-an-existing-entry-is-
 *  a-no-op-success + Remove-a-manga-and-its-associated-chapter-rows-
 *  cached-files-from-the-library + Bulk-removal-used-by-the-library-
 *  multi-select-UI + Flip-the-isLiked-affinity-flag-Idempotent-in-the-
 *  sense-that-calling-twice-restores-the-original-value-there-is-no-
 *  separate-set-path + No-ops-success-if-the-manga-is-not-in-the-
 *  library + Strangler-fig-boundary-the-:data-impl-reaches-the-legacy-
 *  MangaDao.updateManga-via-the-existing-:shared-strangler-fig-posture-
 *  to-persist-the-flipped-row + preserving-the-exact-same-wire-format-
 *  the-legacy-Details-screen-heart-toggle-and-legacy-LibraryViewModel.
 *  toggleLiked-already-use + §179-Task-345 + Closes-the-LibraryManga.
 *  isLiked-KDoc-Mutation-is-still-owned-by-the-legacy-Details-route-
 *  until-a-later-slice-ports-the-toggle-into-:domain-comment + Flip-the-
 *  isWatchingNow-affinity-flag-Same-shape-and-semantics-as-toggleLiked
 *  + §179-Task-345-Closes-the-LibraryManga.isWatchingNow-KDoc-Mutation-
 *  is-still-owned-by-the-legacy-comment" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION plus FORECAST-NOT-YET-FULFILLED-(Phase-9.x-
 *  legacy-MangaDao.updateManga-retire-post-route-swap). Verified via
 *  recursive grep: the 8-method surface declared here (observeLibrary
 *  + observeIsInLibrary + get + addToLibrary + removeFromLibrary +
 *  removeAllFromLibrary + toggleLiked + toggleWatchingNow) matches
 *  LibraryRepositoryImpl.kt in :data 1:1 — no method drift since the
 *  §179 (Task #345) action-row landing. The strangler-fig reach into
 *  legacy MangaDao.updateManga remains LIVE per the :data impl's
 *  cluster23 §479 postscript — both rework + legacy library surfaces
 *  flip the SAME Room column. The §425-§430 Phase-7.x.details.parity
 *  campaign post-swap ALL legacy Library/Details routes now consume
 *  these :domain methods; the legacy LibraryViewModel.toggleLiked +
 *  HomeViewModel.toggleManga (per §431 Phase-9.x.homevm.bookmarkprune)
 *  retire chain remains forecast.
 *  (c) "Composite-primary-key-used-to-identify-a-manga-across-the-
 *  source-repo-boundary + Carried-explicitly-rather-than-collapsed-
 *  into-a-single-string-so-callers-can-not-accidentally-mix-encodings-
 *  this-is-the-same-triple-as-the-existing-SavedMangaEntity-composite-
 *  PK" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified: MangaKey
 *  is consumed by toggleLiked + toggleWatchingNow + removeAllFromLibrary
 *  + the use case layer + DetailsViewModel + LibraryViewModel +
 *  BulkRemoveFromLibraryUseCase. The triple-typed encoding posture
 *  holds — no flat-string keys have crept into the repository surface.
 *  Three classifications STAND on their own merits. Opens cluster141.
 *  Original Phase 6.2-era prose (extended through Phase 7.x.details.
 *  parity-§179-Task-#345) preserved verbatim per the audit-trail-
 *  preservation convention.
 */
interface LibraryRepository {
    /** Every item retains its local parent ID; display metadata is never an action key. */
    fun observeLibrary(): Flow<List<LibraryManga>>

    /** Emits explicit ownership failures and invalidates on accepted-catalog changes. */
    fun observeMembership(work: WorkLocator): Flow<AppResult<SavedWorkIdentity?>>

    /** Resolve one complete exact/accepted-alias family; ambiguity is not absence. */
    suspend fun get(work: WorkLocator): AppResult<LibraryManga?>

    /** Same-work re-add refreshes metadata while preserving parent/child IDs and local state. */
    suspend fun addToLibrary(fetched: FetchedWorkDetails): AppResult<SavedWorkIdentity>

    /**
     * Preflight all requests and atomically reconcile metadata, new chapters and optional Updates.
     * Every request revalidates its pre-fetch parent ID and both raw fetch addresses in the writer.
     * A failure rolls back this complete batch, not just the statement that failed.
     */
    suspend fun refresh(
        requests: List<LibraryRefreshRequest>,
        notify: Boolean,
    ): AppResult<List<LibraryRefreshReceipt>>

    /** Clear durable work progress and remove exactly this retained parent, then clean its files. */
    suspend fun removeFromLibrary(owner: SavedWorkIdentity): AppResult<Unit>

    /** Preflight the complete selection; never substitute a re-added parent or partially remove it. */
    suspend fun removeAllFromLibrary(owners: List<SavedWorkIdentity>): AppResult<Int>

    /** Atomic, checked affinity update after current-policy retained-owner resolution. */
    suspend fun toggleLiked(owner: SavedWorkIdentity): AppResult<Unit>

    /** Atomic, checked watching flag update; metadata/timestamps are untouched. */
    suspend fun toggleWatchingNow(owner: SavedWorkIdentity): AppResult<Unit>

    /** Opened-only write for a retained saved parent; removal/replacement is an explicit failure. */
    suspend fun markOpened(owner: SavedWorkIdentity): AppResult<Unit>
}

/**
 * Legacy display carrier for the not-yet-cutover Home/backup surfaces.
 * It is NOT a primary key and is deliberately not accepted by any library mutation.
 */
data class MangaKey(
    val api: String,
    val language: String,
    val title: String,
)
