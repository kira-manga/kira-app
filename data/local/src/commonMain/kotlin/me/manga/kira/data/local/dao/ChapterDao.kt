package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.manga.kira.data.local.entity.SavedChapterEntity

// Phase 9.x.dao.componentprune.cumulative (Task #392): dropped 6 independently-orphan members
// surfaced by an exhaustive 3-pass reacher-chain audit (receiver-anchored `chapterDao.X(` + bare
// `\bX\b` word-boundary + `::X` method-ref) covering the entire source tree. The prior Task #388
// audit was scoped to URS-coupling only and INCORRECTLY listed these as "preserved LIVE" — this
// slice corrects via exhaustive sweep. Each dropped member had ZERO source-tree reachers under
// any receiver name (`chapterDao.`, `dao.`, method-ref) at any point in the live codebase.
// Removed (independent orphans):
//   - `toggleChapterRead(chapterId: Long)` — @Query. The LIVE caller `LibraryRepository.
//     toggleChapterRead` delegates to `libraryDeo.markChapterAndNotificationRead` (a LibraryDeo
//     @Transaction that also marks the notification row read), NOT to this DAO method. Confirmed
//     by `LibraryRepository.kt:118-119`.
//   - `deleteChaptersByIds(ids: List<Long>)` — @Query batch delete. No reacher. Deletion paths
//     reach via `deleteChapterById(chapterId)` (LIVE — `LibraryRepository.kt:93`) or
//     `removeAllChaptersForManga` on LibraryDeo (the LIVE @Transaction's own internal query).
//   - `removeAllChaptersForManga(mangaId: Long)` — @Query. The LIVE per-manga chapter wipe
//     reaches LibraryDeo's OWN `removeAllChaptersForManga` (LibraryDeo.kt:70-71) called from
//     LibraryDeo's `removeMangaWithChapters` @Transaction body — Room resolves method names
//     within the @Dao scope, so the LibraryDeo callsite never reached ChapterDao's variant.
//   - `getBookmarkedChapters(): Flow<List<SavedChapterEntity>>` — @Query Flow. No reacher
//     anywhere. The rework Bookmarks slice is documented as DEFERRED (Task #217 BLOCKED on
//     chapter-identity decision); this DAO surface was never wired.
//   - `insertChapter(chapter: SavedChapterEntity): Long` — singular `@Insert`. No reacher.
//     Chapter inserts reach via batch `insertChapters` / `insertAll` (both LIVE) or LibraryDeo's
//     own `insertChapters` (called inside LibraryDeo's `saveMangaWithChapters` @Transaction).
//   - `getChaptersByIds(ids: List<Long>): List<SavedChapterEntity>` — @Query batch read. No
//     reacher. The LIVE per-id read is `getChapterById(chapterId): Flow<...>` /
//     `getChapterByIdSuspend(chapterId)` (both LIVE).
//
// Phase 9.x.historyvm.componentprune (Task #401): dropped 1 additional coupled-dead member after
// the partner `MangaRepository.isChapterDownloaded(url)` facade retire (whose sole reacher,
// `HistoryViewModel.chapterDownloadedState(url)`, was itself retired alongside the orphan
// `HistoryUiState` data class — see `HistoryViewModel.kt` head for the originating audit).
// Removed:
//   - `isChapterDownloadedFlow(url): Flow<Boolean>` — sole reacher was
//     `MangaRepository.kt:46` (the now-retired `isChapterDownloaded(url)` facade); no
//     `chapterDao.isChapterDownloadedFlow(` reacher in any source dir post-prune.
// LIVE members preserved (verified by exhaustive reacher-chain audit):
//   - `getAllDownloadedChapters()` — `CbzMigrationWorker.kt:39`.
//   - `getChaptersByMangaId(mangaId)` — `LibraryRepository.kt:98`.
//   - `insertChaptersSafely(chapters)` @Transaction — `LibraryRepository.kt:76`.
//   - `insertChapters(chapters)` — internal LIVE via `insertChaptersSafely` body.
//   - `insertAll(chapters)` — `LibraryRepository.kt:101`.
//   - `updateChapterLocalPaths(chapterId, paths)` — `CbzMigrationWorker.kt:52`;
//     `LibraryRepository.kt:105`.
//   - `getChapterIdByUrl(url)` — `LibraryRepository.kt:112`.
//   - `markChapterDownloaded(chapterId)` — `DownloadWorkerV2.kt:274`; `LibraryRepository.kt:109`.
//   - `toggleChapterBookmark(chapterId)` — `LibraryRepository.kt:116`.
//   - `markChapterAsRead(chapterId, currentTime)` — `MangaRepository.kt:43`;
//     `LibraryRepository.kt:133`.
//   - `markChapterIsNew(chapterId)` — `LibraryRepository.kt:137`.
//   - `getChapterById(chapterId)` — `LibraryRepository.kt:68`.
//   - `getChapterByIdSuspend(chapterId)` — `DownloadsActionRepositoryImpl.kt:115`.
//   - `markChaptersNotDownloaded(ids, emptyList)` — `LibraryRepository.kt:142`.
//   - `deleteChapterById(chapterId)` — `LibraryRepository.kt:93`.
//   - `markChaptersRead(chapterIds)` — `LibraryRepository.kt:130`. Wraps batched
//     `markChaptersReadBatch` (internal LIVE).
//   - `toggleChaptersRead(chapterIds)` @Transaction — `LibraryRepository.kt:126`. Wraps batched
//     `toggleChaptersReadBatch` (internal LIVE).
//   - `toggleChaptersBookmark(chapterIds)` @Transaction — `LibraryRepository.kt:123`. Wraps
//     batched `toggleChaptersBookmarkBatch` (internal LIVE).
@Dao
interface ChapterDao {

    @Query("SELECT * FROM saved_chapters WHERE isDownloaded = 1")
    suspend fun getAllDownloadedChapters(): List<SavedChapterEntity>

    @Query("SELECT * FROM saved_chapters WHERE mangaId = :mangaId ORDER BY id ASC")
    fun getChaptersByMangaId(mangaId: Long): Flow<List<SavedChapterEntity>>

    // B6 (#1): non-Flow suspend read for source-registry URL propagation (the Flow variant above
    // is unusable in the one-shot propagation pass). Chapters carry no `api` column, so they are
    // reached per-manga via MangaDao.getMangaIdsByApi -> this, exactly like native. No schema change.
    @Query("SELECT * FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun getChaptersByMangaIdR(mangaId: Long): List<SavedChapterEntity>

    // B6 (#1): bare @Update to rewrite a chapter's url in place on a baseUrl bump.
    @Update
    suspend fun updateChapter(chapter: SavedChapterEntity)


    @Transaction
    suspend fun insertChaptersSafely(chapters: List<SavedChapterEntity>): List<Long> {
        return insertChapters(chapters)
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChapters(chapters: List<SavedChapterEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chapters: List<SavedChapterEntity>)


    @Query("UPDATE saved_chapters SET localImagePaths = :paths WHERE id = :chapterId")
    suspend fun updateChapterLocalPaths(chapterId: Long, paths: List<String>)

    @Query("SELECT id FROM saved_chapters WHERE url = :url LIMIT 1")
    suspend fun getChapterIdByUrl(url: String): Long?

    // Bulk url -> id resolution for the multi-select mark-read path. Chunked by the caller-facing
    // wrapper below to stay under SQLite's host-variable limit (999); urls with no in-library row
    // are simply absent from the result (no row -> skipped), same effect as N getChapterIdByUrl
    // calls but in one round-trip per chunk.
    @Query("SELECT id FROM saved_chapters WHERE url IN (:urls)")
    suspend fun getChapterIdsByUrlsBatch(urls: List<String>): List<Long>

    suspend fun getChapterIdsByUrls(urls: List<String>): List<Long> =
        urls.chunked(500).flatMap { batch -> getChapterIdsByUrlsBatch(batch) }

    // Bulk url -> id MAP resolution for the download-all path (which has no mangaId scope, just a
    // manga's chapter urls). One round-trip per 500-url chunk replaces N getChapterIdByUrl calls;
    // urls with no in-library row are simply absent from the map (no row -> skipped).
    @Query("SELECT id, url FROM saved_chapters WHERE url IN (:urls)")
    suspend fun getChapterIdUrlPairsBatch(urls: List<String>): List<ChapterIdUrl>

    suspend fun getChapterIdMapByUrls(urls: List<String>): Map<String, Long> =
        urls.chunked(500)
            .flatMap { batch -> getChapterIdUrlPairsBatch(batch) }
            .associate { it.url to it.id }

    // Scoped (id, url) resolution for the refresh notify-pass: unlike getChapterIdByUrl (url-only,
    // LIMIT 1), this is anchored on mangaId so a chapter url legally reused under a DIFFERENT manga
    // can't win and attach a notification to the wrong manga's row. Chunked by the wrapper below to
    // stay under SQLite's host-variable limit.
    @Query("SELECT id, url FROM saved_chapters WHERE mangaId = :mangaId AND url IN (:urls)")
    suspend fun getChapterIdUrlPairsForMangaBatch(mangaId: Long, urls: List<String>): List<ChapterIdUrl>

    suspend fun getChapterIdsByUrlForManga(mangaId: Long, urls: List<String>): Map<String, Long> =
        urls.chunked(500)
            .flatMap { batch -> getChapterIdUrlPairsForMangaBatch(mangaId, batch) }
            .associate { it.url to it.id }

    @Query("UPDATE saved_chapters SET isDownloaded = 1 WHERE id = :chapterId")
    suspend fun markChapterDownloaded(chapterId: Long)

    @Query("UPDATE saved_chapters SET isBookmarked = NOT isBookmarked WHERE id = :chapterId")
    suspend fun toggleChapterBookmark(chapterId: Long)

    @Query("UPDATE saved_chapters SET isRead = 1, lastReadDate = :currentTime WHERE id = :chapterId")
    suspend fun markChapterAsRead(chapterId: Long, currentTime: Long = kotlin.time.Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE saved_chapters SET isNew = 0 WHERE id = :chapterId")
    suspend fun markChapterIsNew(chapterId: Long)


    @Query("SELECT * FROM saved_chapters WHERE id = :chapterId LIMIT 1")
    fun getChapterById(chapterId: Long): Flow<SavedChapterEntity?>

    // url-keyed bookmark stream: unlike the id-keyed getChapterById flow, this stays bound to a
    // chapter that is not yet in-library — Room re-emits on any saved_chapters write, so the row
    // appearing later (manga saved mid-session) delivers the real bookmark state. Absent row → null.
    @Query("SELECT * FROM saved_chapters WHERE url = :url LIMIT 1")
    fun getChapterByUrl(url: String): Flow<SavedChapterEntity?>

    @Query("SELECT * FROM saved_chapters WHERE id = :chapterId LIMIT 1")
    suspend fun getChapterByIdSuspend(chapterId: Long): SavedChapterEntity?


    @Query("""
      UPDATE saved_chapters
        SET isDownloaded = 0,
            localImagePaths = :emptyList
        WHERE id IN (:ids)
    """)
    suspend fun markChaptersNotDownloaded(ids: List<Long>, emptyList: List<String> = emptyList())


    @Query("DELETE FROM saved_chapters WHERE id = :chapterId")
    suspend fun deleteChapterById(chapterId: Long)


    suspend fun markChaptersRead(chapterIds: List<Long>) {
        chapterIds.chunked(500).forEach { batch ->
            markChaptersReadBatch(batch)
        }
    }

    @Transaction
    suspend fun toggleChaptersRead(chapterIds: List<Long>) {
        chapterIds.chunked(500).forEach { batch ->
            toggleChaptersReadBatch(batch)
        }
    }

    @Transaction
    suspend fun toggleChaptersBookmark(chapterIds: List<Long>) {
        chapterIds.chunked(500).forEach { batch ->
            toggleChaptersBookmarkBatch(batch)
        }
    }


    @Query("UPDATE saved_chapters SET isRead = 1 WHERE id IN (:chapterIds)")
    suspend fun markChaptersReadBatch(chapterIds: List<Long>)

    @Query("UPDATE saved_chapters SET isRead = NOT isRead WHERE id IN (:chapterIds)")
    suspend fun toggleChaptersReadBatch(chapterIds: List<Long>)

    @Query("UPDATE saved_chapters SET isBookmarked = NOT isBookmarked WHERE id IN (:chapterIds)")
    suspend fun toggleChaptersBookmarkBatch(chapterIds: List<Long>)

}

/** Row projection (id + url) for the bulk chapter id-resolution queries on [ChapterDao]. */
data class ChapterIdUrl(
    val id: Long,
    val url: String,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster184.staleKdocSweep.cascade,
 * Task #669, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-eighty-first sibling of the cluster57-183
 * sweep — leaf 2/5 of the wave-54 commonMain :data/local/dao Room-DAO
 * 5-leaf batch; ChapterDao interface 2/5).
 *
 *  (a) Inline cumulative-prune comment "Phase-9-x-dao-componentprune-cumulative
 *  Task-392-dropped-6-independently-orphan-members + Removed-toggleChapterRead
 *  + deleteChaptersByIds + removeAllChaptersForManga + getBookmarkedChapters
 *  + insertChapter + getChaptersByIds" — LIVE-NOT-STALE for the ChapterDao
 *  surface AND FULFILLED-RETIRE for the Phase 9.x.dao.componentprune.cumulative
 *  Task #392 6-orphan drop (verified: none of the 6 retired members
 *  re-appears in the @Dao interface body; the 18-method LIVE surface
 *  documented in the prose matches the methods actually present at
 *  lines 73-159: getAllDownloadedChapters + getChaptersByMangaId +
 *  insertChaptersSafely @Transaction + insertChapters + insertAll +
 *  updateChapterLocalPaths + getChapterIdByUrl + markChapterDownloaded +
 *  toggleChapterBookmark + markChapterAsRead + markChapterIsNew +
 *  getChapterById + getChapterByIdSuspend + markChaptersNotDownloaded +
 *  deleteChapterById + markChaptersRead [chunked-500 wrapper] +
 *  toggleChaptersRead @Transaction [chunked-500 wrapper] +
 *  toggleChaptersBookmark @Transaction [chunked-500 wrapper] +
 *  markChaptersReadBatch + toggleChaptersReadBatch + toggleChaptersBookmarkBatch).
 *  The chunked-500 batching wrappers IS load-bearing: SQLite's parameterized
 *  query host-variable limit (default SQLITE_MAX_VARIABLE_NUMBER = 999) caps
 *  the WHERE id IN (:chapterIds) argument list, so the public-facing
 *  markChaptersRead / toggleChaptersRead / toggleChaptersBookmark methods
 *  partition into 500-sized chunks before delegating to the *Batch helpers;
 *  the @Transaction wrappers on toggleChaptersRead and toggleChaptersBookmark
 *  ensure cross-chunk atomicity. The `kotlin.time.Clock.System.now()
 *  .toEpochMilliseconds()` default value on `markChapterAsRead` IS load-bearing
 *  for the Phase-6 java.lang.System.currentTimeMillis() → kotlin.time.Clock
 *  KMP-portable migration documented in cluster183's Converters.kt postscript.
 *
 *  (b) Inline successor-prune comment "Phase-9-x-historyvm-componentprune
 *  Task-401-dropped-1-additional-coupled-dead-member-isChapterDownloadedFlow"
 *  — LIVE-NOT-STALE for the post-§401 18-method ChapterDao surface AND
 *  FULFILLED-RETIRE for the Phase 9.x.historyvm.componentprune Task #401
 *  partner retire (verified: `isChapterDownloadedFlow` does not appear in
 *  the @Dao interface body; the `MangaRepository.isChapterDownloaded(url)`
 *  facade that was its sole reacher is documented retired in the
 *  MangaRepository componentprune chain — see §384 task lineage). No
 *  follow-up tracker.
 *
 * Verified: 21-symbol ChapterDao interface (18 public methods + 3 private
 * *Batch helpers for chunked transactions). Sibling: MangaDao (cluster184
 * opening sibling); HistoryDao + ChapterDownloadDao + NotificationDao
 * (cluster184 succeeding siblings). LEAF 2/5 of the cluster184 commonMain
 * :data/local/dao Room-DAO 5-leaf batch. Two compound classifications
 * (each LIVE-NOT-STALE + FULFILLED-RETIRE for Phase 9.x.dao.componentprune
 * .cumulative Task #392 and Phase 9.x.historyvm.componentprune Task #401
 * respectively). Original Phase-9 componentprune prose preserved verbatim
 * per the audit-trail-preservation convention.
 *
 * CORRECTION (B6 #1, 2026-06-12): the "18-method LIVE surface" / "21-symbol interface (18 public +
 * 3 private *Batch helpers)" counts above are STALE. B6 added getChaptersByMangaIdR (lines 84-85)
 * and updateChapter (lines 88-89), so the live non-batch surface is 20 methods (23 members total
 * with the 3 *Batch helpers). The *Batch helpers (markChaptersReadBatch / toggleChaptersReadBatch /
 * toggleChaptersBookmarkBatch, lines 163-170) are public interface members — Room @Query methods
 * cannot be private — not "private" as the prose states. Retained as lineage per the
 * audit-trail-preservation convention.
 */

