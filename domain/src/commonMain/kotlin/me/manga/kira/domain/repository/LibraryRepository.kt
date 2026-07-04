package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga

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

    /**
     * Reactive snapshot of the user's library. Emit order is unspecified — the presentation
     * layer applies the user-chosen sort. The DAO currently emits by title ASC; callers must
     * not depend on that.
     */
    fun observeLibrary(): Flow<List<LibraryManga>>

    /** Reactive flag: is this manga currently in the user's library? */
    fun observeIsInLibrary(api: String, language: String, title: String): Flow<Boolean>

    /** One-shot lookup of a single library entry, or null when absent. */
    suspend fun get(api: String, language: String, title: String): AppResult<LibraryManga?>

    /**
     * Add a manga to the library together with its full chapter list. Idempotent — adding an
     * existing entry is a no-op success, and only chapter URLs not already persisted are inserted.
     *
     * Native parity: the source-of-truth app persists the manga row AND its chapters atomically at
     * add-time (`saveMangaWithChapters`) so an in-library manga can render its chapter list straight
     * from Room without a network re-fetch on open. [chapters] is the list the caller already holds
     * (the fetched [me.manga.kira.domain.model.MangaDetails.chapters]); callers that add without
     * a chapter context (e.g. Home/Library quick-toggle) pass `emptyList()` — the manga is added and
     * its chapters fill in on the first Details open.
     */
    suspend fun addToLibrary(manga: Manga, chapters: List<Chapter>): AppResult<Unit>

    /**
     * Persist chapters discovered by a Details/library refresh that are not yet saved for this
     * (in-library) manga, flagging each as NEW (native parity: `LibraryDetailsViewModel.refreshChapters`
     * + `LibraryRefreshWorker` insert with `isNew = true`). Diffs [fetched] against the saved chapter
     * URLs and inserts only the genuinely-new ones (idempotent: re-running inserts nothing). Returns
     * the count of newly-persisted chapters. No-op (returns 0) when the manga isn't in the library.
     *
     * This is what makes new chapters survive leaving and reopening the Details screen (they are
     * written to Room, not just held in ViewModel state) and what feeds the red NEW badge.
     */
    suspend fun persistNewChapters(
        api: String,
        language: String,
        title: String,
        fetched: List<Chapter>,
    ): AppResult<Int>

    /**
     * Like [persistNewChapters] but ALSO writes a `notifications` row for each newly-persisted chapter
     * so it appears in the Notifications/Updates screen (native parity: the Android `LibraryRefreshWorker`
     * calls `addNewChapterNotification` after the insert). Used ONLY by the library refresh-all path
     * (incl. the Desktop/iOS inline refresh) — NOT by the Details pull-to-refresh, which must stay
     * notification-free to match native. De-dup is intrinsic: only genuinely-new chapters are notified.
     * Returns the count newly persisted; 0 when the manga isn't in the library.
     */
    suspend fun persistNewChaptersAndNotify(manga: Manga, fetched: List<Chapter>): AppResult<Int>

    /**
     * Reconcile the saved cover URL for an in-library manga when a refresh discovers it changed.
     * No-op (success) when the manga isn't in the library or [newCoverUrl] already matches the saved
     * row. Mirrors the native `LibraryRefreshWorker`'s `updateMangaImageUrlEverywhere`: rewrites the
     * cover in `saved_manga`, `history` and `notifications` so a rotated CDN URL doesn't leave a
     * permanently-stale cover on Desktop/iOS (which have no WorkManager worker and run only the
     * cross-platform inline refresh). Manga sites rotate cover/CDN URLs constantly, so without this
     * a cover that rots after add is never repaired on those platforms.
     */
    suspend fun updateCoverIfChanged(
        api: String,
        language: String,
        title: String,
        newCoverUrl: String,
    ): AppResult<Unit>

    /** Remove a manga (and its associated chapter rows / cached files) from the library. */
    suspend fun removeFromLibrary(api: String, language: String, title: String): AppResult<Unit>

    /**
     * Bulk removal — used by the library multi-select UI. Returns the count of rows ACTUALLY
     * purged (#21): keys with no saved_manga row are skipped, so this can be < `keys.size`. The
     * success toast shows this true count instead of the selected count.
     */
    suspend fun removeAllFromLibrary(keys: List<MangaKey>): AppResult<Int>

    /**
     * Flip the `isLiked` affinity flag for the manga identified by [key]. Idempotent in the
     * sense that calling twice restores the original value — there is no separate "set" path.
     *
     * No-ops (success) if the manga is not in the library; the action-row only renders for
     * in-library cards so the absent-key case is defensive rather than expected.
     *
     * Strangler-fig boundary: the `:data` impl reaches the legacy `MangaDao.updateManga`
     * (via the existing `:shared` strangler-fig posture) to persist the flipped row,
     * preserving the exact same wire format the legacy Details-screen heart toggle and
     * legacy `LibraryViewModel.toggleLiked` already use. Same posture as the existing
     * `addToLibrary` / `removeFromLibrary` methods on this interface — Phase 9.x retires
     * the legacy DAO reach, not this slice.
     *
     * §179 (Task #345). Closes the `LibraryManga.isLiked` KDoc's "Mutation is still owned by
     * the legacy Details route until a later slice ports the toggle into `:domain`" comment.
     */
    suspend fun toggleLiked(key: MangaKey): AppResult<Unit>

    /**
     * Flip the `isWatchingNow` affinity flag for the manga identified by [key]. Same shape
     * and semantics as [toggleLiked] — see that method's KDoc for the strangler-fig boundary
     * narrative.
     *
     * §179 (Task #345). Closes the `LibraryManga.isWatchingNow` KDoc's "Mutation is still
     * owned by the legacy" comment.
     */
    suspend fun toggleWatchingNow(key: MangaKey): AppResult<Unit>

    /**
     * Record that the manga identified by ([api], [language], [title]) was just opened, bumping its
     * `lastOpenTimestamp` to now. No-op (success) when the manga isn't in the library. Feeds the
     * LAST_READ sort (native parity: native bumps `lastOpenTimestamp` on each Details open so
     * LAST_READ orders by recency of opening).
     */
    suspend fun markOpened(api: String, language: String, title: String): AppResult<Unit>
}

/**
 * Composite primary key used to identify a manga across the source/repo boundary.
 *
 * Carried explicitly rather than collapsed into a single string so callers can't accidentally
 * mix encodings — this is the same triple as the existing `SavedMangaEntity` composite PK.
 */
data class MangaKey(
    val api: String,
    val language: String,
    val title: String,
)
