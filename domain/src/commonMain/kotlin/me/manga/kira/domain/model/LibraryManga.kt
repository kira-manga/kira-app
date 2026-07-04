package me.manga.kira.domain.model

import kotlin.time.Instant

/**
 * A [Manga] paired with the user's library-specific metadata.
 *
 * Kept separate from [Manga] so domain code can pass plain Manga instances around without
 * leaking the user's library state (SOLID-SRP: Manga = source-shape, LibraryManga = the
 * source-shape PLUS user-shape).
 *
 * [addedAt] mirrors `SavedMangaEntity.dateAdded` in the existing schema (baseline §8).
 * [unreadCount] is a denormalized aggregate of `ChapterEntity.isRead == false` for this manga;
 * the repository populates it on read so the UI doesn't recompute on every recomposition.
 * [totalChapters] / [lastReadAt] feed the Library grid's TOTAL_CHAPTERS / LAST_READ sort modes
 * — denormalized from the chapter-aggregates query for the same SRP reason.
 * [bookmarkedCount] feeds the rework Library's BOOKMARKED filter axis (Task #321) — denormalized
 * from `SavedChapterEntity.bookmarked == true` per-manga, sourced from the already-existing
 * `MangaChapterMetrics.bookmarkedCount` Room aggregate column (no schema migration required —
 * the column was already populated by [me.manga.kira.presentation.features.library.data.MangaChapterMetrics]).
 * [downloadedCount] feeds the rework Library's per-card "↓ N" caption (§150 rung 17, Task #343)
 * parallel to the bookmark caption — denormalized from `SavedChapterEntity.isDownloaded == true`
 * per-manga, sourced from the already-existing `MangaChapterMetrics.downloadedCount` Room
 * aggregate column (no schema migration required — the same column that has fed [hasDownloads]
 * since §148). [hasDownloads] is kept as `downloadedCount > 0` for the DOWNLOADED filter
 * predicate; the raw count surfaces the richer "how many" information on the card caption.
 * [isLiked] / [isWatchingNow] feed the rework Library's category-tabs axis
 * (`LibraryCategory.LIKED` / `LibraryCategory.WATCHING_NOW`, Task #324) — pass-through of the
 * long-standing `SavedMangaEntity.isLiked` / `isWatchingNow` columns (no schema migration
 * required; the columns have been populated by the legacy Details-screen heart icon and the
 * "watching now" mark since the original APK).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster7.staleKdocSweep.cascade,
 * Task #463, 2026-05-28): a stale citation into the §380-retired legacy
 * `MangaDisplayItem` appears above:
 *  - Line 47 ([lastReadAt] field rationale): "Mirrors the legacy
 *    `MangaDisplayItem.lastReadTs` posture; feeds the LAST_READ sort".
 * The legacy `MangaDisplayItem` + its `lastReadTs` field were retired in
 * Phase 9.x.mangadisplayitem.retire (§380 sweep, commit `121ae82` "(1/2):
 * delete orphan MangaDisplayItem.kt + dead getDisplayItemsFlow"); verified
 * by a filesystem check returning zero hits for that path. The LAST_READ
 * sort rationale (denormalised `MAX(SavedChapterEntity.lastReadDate)`
 * pulled into the LibraryManga aggregate) stands on its own merits — the
 * rework Library route's TOTAL_CHAPTERS + LAST_READ sort axis was
 * authored in §317 (`Phase 7.x.library.sort.tierb`) and remains LIVE,
 * documented inline above; the field's posture is independent of the
 * legacy display-item shape that originally surfaced an equivalent
 * timestamp. Original §253-era prose preserved verbatim per the audit-
 * trail-preservation convention — the citation is historical record of
 * the design lineage; the rework LibraryManga.lastReadAt continues to
 * feed the LAST_READ sort past the §380 retire.
 */
data class LibraryManga(
    val manga: Manga,
    /** When the user added this manga to their library. */
    val addedAt: Instant,
    /** Number of chapters not yet read. Denormalized for UI. */
    val unreadCount: Int,
    /** True when at least one chapter is fully downloaded locally. */
    val hasDownloads: Boolean,
    /** Total chapters known for this manga. Denormalized for the TOTAL_CHAPTERS sort. */
    val totalChapters: Int,
    /**
     * Timestamp of the most-recently read chapter (`MAX(SavedChapterEntity.lastReadDate)`),
     * or `null` if no chapter has ever been read.
     *
     * Mirrors the legacy `MangaDisplayItem.lastReadTs` posture.
     */
    val lastReadAt: Instant?,
    /**
     * Timestamp the manga was last OPENED (`SavedMangaEntity.lastOpenTimestamp`) — set on save and
     * bumped each time its Details screen is opened. Feeds the LAST_READ sort (native parity: native
     * sorts LAST_READ by `manga.lastOpenTimestamp`, the last-open time, not by chapter read dates).
     * Always present (the entity defaults it to "now" at save time).
     */
    val lastOpenedAt: Instant,
    /**
     * Count of chapters the user has bookmarked for this manga. Denormalized from
     * `SavedChapterEntity.bookmarked == true` per-manga. Feeds the rework Library's BOOKMARKED
     * filter axis (predicate: `bookmarkedCount > 0`). `0` for manga with no bookmarks or no
     * saved chapters.
     */
    val bookmarkedCount: Int,
    /**
     * Count of chapters fully downloaded locally for this manga. Denormalized from
     * `SavedChapterEntity.isDownloaded == true` per-manga (the same `MangaChapterMetrics.
     * downloadedCount` Room column that [hasDownloads] is derived from). Feeds the rework
     * Library's per-card "↓ N" caption (§150 rung 17, Task #343) parallel to the [bookmarkedCount]
     * caption. `0` for manga with no downloads or no saved chapters.
     */
    val downloadedCount: Int,
    /**
     * True when the user has hearted this manga (legacy "like" flag). Pass-through of
     * `SavedMangaEntity.isLiked`. Feeds the rework Library's `LibraryCategory.LIKED` tab
     * (predicate: `isLiked`). Mutation (heart toggle) is still owned by the legacy Details
     * route until a later slice ports the toggle into `:domain`.
     */
    val isLiked: Boolean,
    /**
     * True when the user has marked this manga as "watching now". Pass-through of
     * `SavedMangaEntity.isWatchingNow`. Feeds the rework Library's
     * `LibraryCategory.WATCHING_NOW` tab (predicate: `isWatchingNow`). Mutation is still owned
     * by the legacy "watching now" mark until a later slice ports it into `:domain`.
     */
    val isWatchingNow: Boolean,
)
