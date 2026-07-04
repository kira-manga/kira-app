package me.manga.kira.presentation.features.library.data

import androidx.room.ColumnInfo

// Per-manga chapter aggregate, sourced from a saved_chapters-only GROUP BY (no JOIN).
//
// Bug 3 (Desktop bookmark not surfacing in Library): the previous single-query path used
// a `LEFT JOIN saved_chapters ... GROUP BY m.id` aggregate Flow over a `@Embedded` row
// shape. On Android that Flow re-emitted after writes; on Desktop with
// `BundledSQLiteDriver` it did not — even though Home's plain
// `SELECT api, title FROM saved_manga` Flow did. Splitting the join out and combining
// two simple Flows in Kotlin (now via the rework `:data`
// `LibraryRepositoryImpl.observeLibrary()` + `LibraryMappers.toLibraryManga`; the legacy
// `LibraryRepository.getDisplayItemsFlow` that pioneered this workaround was retired in
// Phase 9.x.mangadisplayitem.retire and its sibling `getDisplayItemsManga` + the
// aggregate row class were retired in Phase 9.x.savedmangawithmetrics.retire) keeps
// Room's per-table invalidation paths straightforward and works uniformly across targets.
data class MangaChapterMetrics(
    @ColumnInfo(name = "mangaId") val mangaId: Long,
    @ColumnInfo(name = "totalChapters") val totalChapters: Int,
    @ColumnInfo(name = "readCount") val readCount: Int,
    @ColumnInfo(name = "downloadedCount") val downloadedCount: Int,
    @ColumnInfo(name = "bookmarkedCount") val bookmarkedCount: Int,
    // Most-recent `lastReadDate` across this manga's chapters, or null when no chapter has
    // been read (Room maps SQLite NULL from `MAX(lastReadDate)` on zero matching rows). When
    // the manga has chapters but none read, `MAX(0L) = 0L` and this is non-null — callers
    // that want "never read" semantics should test for `lastReadTs == null || lastReadTs == 0L`.
    // Feeds the rework Library's LAST_READ sort (Task #317).
    @ColumnInfo(name = "lastReadTs") val lastReadTs: Long?,
)

/*
 * Audit-trail postscript (Phase 9.x.cluster211.staleKdocSweep.cascade, Task #667, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster211 leaf 2/3 — :shared/library/data/ tier SINGLE-LEAF, sibling 391. Cumulative
 * §253-postscript count = 116 leaves with this commit.
 *
 * File-shape note: 30-line file — `MangaChapterMetrics` data class with 6 fields (mangaId +
 * totalChapters + readCount + downloadedCount + bookmarkedCount + nullable lastReadTs) all
 * @ColumnInfo-annotated for Room aggregate-query projection. 13-line class-level prose
 * (lines 5-17) carrying Bug-3 desktop-bookmark-race lineage + references to two retired
 * legacy facades (getDisplayItemsFlow Phase 9.x.mangadisplayitem.retire + getDisplayItemsManga
 * Phase 9.x.savedmangawithmetrics.retire) + 4-line LAST_READ-sort field-level note
 * (lines 24-28).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — narrow-purpose Room-aggregate row class — direct reach (verified):
 *       1. :data Room DAO SavedChapterDao — projection target for the per-manga aggregate
 *          GROUP BY query (saved_chapters-only, no JOIN) that feeds rework Library metrics.
 *       2. rework :data LibraryMappers.toLibraryManga — combines MangaChapterMetrics rows
 *          with saved_manga base rows to build the rework :domain LibraryManga model.
 *
 *   • INVERTED-PARALLEL-WITH-STRANGLER-FIG — the legacy MangaChapterMetrics class survives
 *     as the Room-projection DTO; rework :domain LibraryManga is the downstream shape
 *     consumed by rework :ui. Strangler-fig in :data does the legacy → rework field-level
 *     rename mapping.
 *
 *   • BUG-3-DESKTOP-WORKAROUND-PROSE-LOAD-BEARING — the 13-line class-level prose
 *     (lines 5-17) documents why this is a separate per-manga aggregate (saved_chapters-only
 *     GROUP BY, no JOIN) instead of a single embedded LEFT-JOIN query. The Bug-3 incident
 *     (Desktop BundledSQLiteDriver did not re-emit a JOIN-aggregate Flow after writes that
 *     touched only one of the two tables) is the historical reason. PRESERVE — load-bearing
 *     incident-prose; deleting would re-invite collapse back into a JOIN-aggregate and
 *     re-introduce the Desktop bookmark-race.
 *
 *   • LAST_READ-SORT-NULL-SEMANTICS-INVARIANT — the 4-line lastReadTs field-prose
 *     (lines 24-28) documents the SQLite-MAX-on-zero-rows null vs 0L distinction. Callers
 *     wanting "never read" semantics MUST test `lastReadTs == null || lastReadTs == 0L`.
 *     Rework Library LAST_READ sort (Task #317) relies on this null-or-zero conflation —
 *     altering the SQL to `IFNULL(MAX(lastReadDate), -1)` would break the sort axis.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 import: androidx.room.ColumnInfo. LIVE.
 */
