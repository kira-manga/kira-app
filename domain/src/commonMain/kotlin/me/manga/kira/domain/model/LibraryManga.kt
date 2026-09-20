package me.manga.kira.domain.model

import kotlin.time.Instant
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryActivity
import me.manga.kira.domain.model.library.LibraryAffinity
import me.manga.kira.domain.model.library.LibraryChapterCounts

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
    /** Local action fence; never substitute display metadata or export this numeric ID. */
    val identity: SavedWorkIdentity,
    val activity: LibraryActivity,
    val counts: LibraryChapterCounts,
    val affinity: LibraryAffinity,
) {
    init {
        require(identity.locator == WorkLocator(manga.api, manga.url))
    }

    val addedAt: Instant get() = activity.addedAt
    val lastOpenedAt: Instant get() = activity.lastOpenedAt
    val lastReadAt: Instant? get() = activity.lastReadAt
    val unreadCount: Int get() = counts.unread
    val totalChapters: Int get() = counts.total
    val downloadedCount: Int get() = counts.downloaded
    val bookmarkedCount: Int get() = counts.bookmarked
    val hasDownloads: Boolean get() = counts.downloaded > 0
    val isLiked: Boolean get() = affinity.isLiked
    val isWatchingNow: Boolean get() = affinity.isWatchingNow
}
