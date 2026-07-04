package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.manga.kira.data.local.entity.HistoryItemD
import kotlinx.datetime.LocalDateTime

// Phase 9.x.historyrepository.componentprune (Task #386): dropped 3 transitively-dead @Query
// methods (`getHistoryByManga`, `getHistoryByChapter`, `deleteHistoryByManga`) coupled to the
// now-dropped facade methods on `HistoryRepository`. Reacher-chain audit verified:
//   - `historyDao.getHistoryByManga(` — only reacher was the now-dropped facade method.
//   - `historyDao.getHistoryByChapter(` — only reacher was the now-dropped facade method.
//   - `historyDao.deleteHistoryByManga(` — only reacher was the now-dropped facade method.
// `getHistoryItemByMangaUrl` (called inside the LIVE `insertOrUpdateHistory` @Transaction body)
// and `updateHistory` (called inside the same body) are LIVE-by-association and preserved.
//
// Phase 9.x.updatesourcesrepository.daoprune (Task #388): dropped 2 additional coupled-dead
// members after the `UpdateSourcesRepository` retire (Task #387). 3-pass reacher-chain audit
// confirmed zero `historyDao.X(` reachers post-URS.
// Removed:
//   - `getHistoryByApi(apiName: String): List<HistoryItemD>` — URS-only.
//   - `update(historyItem: HistoryItemD)` — URS-only. Bare `@Update` overload. Note:
//     `updateHistory` (different name) inside the LIVE `insertOrUpdateHistory` @Transaction
//     body remains LIVE-by-association.
//
// External LIVE reachers of this DAO (verified by 3-pass grep, current set):
//   - `historyDao.getAllHistory()` — HistoryRepository facade.
//   - `historyDao.updateMangaImageUrl(...)` — LibraryRepository (direct DAO reach).
//   - `historyDao.insertOrUpdateHistory(...)` — HistoryRepository facade.
//   - `historyDao.deleteHistory(...)` — HistoryRepository facade + rework `HistoryRepositoryImpl`.
//   - `historyDao.deleteAllHistory()` — HistoryRepository facade + rework `HistoryRepositoryImpl`.
//   - `historyDao.updateHistoryItem(...)` — HistoryRepository facade.
@Dao
interface HistoryDao {


    @Query("SELECT * FROM history_items ORDER BY lastReadDate DESC")
    fun getAllHistory(): Flow<List<HistoryItemD>>

    // B6 (#1): re-added for source-registry URL propagation — rewrites stored history
    // mangaUrl/chapterUrl/mangaImageUrl in place on a version bump (reuses the existing @Update
    // updateHistory). SELECT over the existing `api` column; no schema change.
    @Query("SELECT * FROM history_items WHERE api = :api")
    suspend fun getHistoryByApi(api: String): List<HistoryItemD>

    @Query("UPDATE history_items SET mangaImageUrl = :newImageUrl WHERE mangaId = :mangaId")
    suspend fun updateMangaImageUrl(mangaId: Long, newImageUrl: String)

    // url-keyed belt-and-braces twin (mirrors LibraryDeo.removeHistoryByUrl): rework-written history
    // rows carry mangaId = 0, so the mangaId-keyed update above never matches them. Keying on
    // mangaUrl propagates a rotated cover to those rows too.
    @Query("UPDATE history_items SET mangaImageUrl = :newImageUrl WHERE mangaUrl = :mangaUrl")
    suspend fun updateMangaImageUrlByUrl(mangaUrl: String, newImageUrl: String)

    @Query("SELECT * FROM history_items WHERE mangaUrl = :mangaUrl LIMIT 1")
    suspend fun getHistoryItemByMangaUrl(mangaUrl: String): HistoryItemD?

    @Transaction
    suspend fun insertOrUpdateHistory(historyItemD: HistoryItemD) {
        val existingItem = getHistoryItemByMangaUrl(historyItemD.mangaUrl)

        if (existingItem != null) {

            // Update existing entry with new chapter info and date
            val updatedItem = existingItem.copy(
                chapterUrl = historyItemD.chapterUrl,
                chapterTitle = historyItemD.chapterTitle,
                lastReadDate = historyItemD.lastReadDate,
                lastReadPage = historyItemD.lastReadPage,
                totalPages = historyItemD.totalPages
            )
            updateHistory(updatedItem)
        } else {
            insertHistory(historyItemD)
        }
    }

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertHistory(historyItemD: HistoryItemD)

    @Update
    suspend fun updateHistory(historyItemD: HistoryItemD)

    @Delete
    suspend fun deleteHistory(historyItemD: HistoryItemD)

    @Query("DELETE FROM history_items")
    suspend fun deleteAllHistory()



    @Query("""
        UPDATE history_items
           SET chapterUrl       = :chapterUrl,
               chapterTitle     = :chapterTitle,
               isDownloaded     = :isDownloaded,
               localImagePaths  = :localImagePaths,
               lastReadDate     = :lastReadDate,
               lastReadPage     = :lastReadPage,
               totalPages       = :totalPages
         WHERE id = :id
    """)
    suspend fun updateHistoryItem(
        id: Long,
        chapterUrl: String,
        chapterTitle: String,
        isDownloaded: Boolean,
        localImagePaths: List<String> = listOf(),
        lastReadDate: LocalDateTime,
        lastReadPage: Int,
        totalPages: Int
    )

}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster184.staleKdocSweep.cascade,
 * Task #670, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-eighty-second sibling of the cluster57-183
 * sweep — middle leaf 3/5 of the wave-54 commonMain :data/local/dao
 * Room-DAO 5-leaf batch; HistoryDao interface 3/5).
 *
 *  (a) Inline cumulative-prune comment "Phase-9-x-historyrepository-componentprune
 *  Task-386-dropped-3-transitively-dead-Query-methods + getHistoryByManga +
 *  getHistoryByChapter + deleteHistoryByManga" — LIVE-NOT-STALE for the
 *  HistoryDao surface AND FULFILLED-RETIRE for the Phase 9.x.historyrepository
 *  .componentprune Task #386 3-orphan drop (verified: none of the 3 retired
 *  members re-appears in the @Dao interface body; the 7 external LIVE
 *  reachers documented in the prose are bound and remain reached by
 *  HistoryRepository facade methods (getAllHistory + getLatestHistoryIdByManga
 *  + insertOrUpdateHistory + deleteHistory + deleteAllHistory +
 *  updateHistoryItem) plus the LibraryRepository direct-DAO reach for
 *  updateMangaImageUrl; the `getHistoryItemByMangaUrl` and `updateHistory`
 *  methods are LIVE-by-association — both called inside the
 *  `insertOrUpdateHistory` @Transaction body at lines 64+76 which uses the
 *  upsert pattern: fetch-existing-by-mangaUrl, then either update or
 *  insert). The `@Transaction` annotation on `insertOrUpdateHistory` IS
 *  load-bearing for cross-write atomicity between the existing-row check
 *  (`getHistoryItemByMangaUrl`) and the conditional update-or-insert
 *  (`updateHistory` / `insertHistory`) — without it the
 *  check-then-act gap could create a duplicate row under concurrent writes.
 *
 *  (b) Inline successor-prune comment "Phase-9-x-updatesourcesrepository-
 *  daoprune Task-388-dropped-2-additional-coupled-dead-members +
 *  getHistoryByApi + update" — LIVE-NOT-STALE for the post-§388 HistoryDao
 *  surface AND FULFILLED-RETIRE for the Phase 9.x.updatesourcesrepository
 *  .daoprune Task #388 partner retire (verified: `getHistoryByApi` does
 *  not appear in the @Dao interface body; bare `@Update update(historyItem)`
 *  overload does not appear — only the LIVE `updateHistory(historyItemD)`
 *  variant with the `History`-suffix naming remains, which IS called
 *  inside the LIVE `insertOrUpdateHistory` @Transaction body; the
 *  `UpdateSourcesRepository` class retire (Task #387) cited as the
 *  upstream blocker is documented retired in Task #387). The
 *  `updateHistory`-vs-`update` disambiguation IS load-bearing because Room's
 *  @Update annotation resolves by name, and both methods would have had
 *  identical SQL semantics had `update` remained — but `update` was the
 *  URS-coupled variant while `updateHistory` is the LIVE-by-association
 *  variant called from inside the @Transaction body. No follow-up tracker.
 *
 * Verified: 9-method HistoryDao interface (getAllHistory + updateMangaImageUrl
 * + getHistoryItemByMangaUrl + getLatestHistoryIdByManga + insertOrUpdateHistory
 * @Transaction + insertHistory + updateHistory + deleteHistory +
 * deleteAllHistory + updateHistoryItem). Sibling: MangaDao + ChapterDao
 * (cluster184 prior siblings); ChapterDownloadDao + NotificationDao
 * (cluster184 succeeding siblings). MIDDLE LEAF 3/5 of the cluster184
 * commonMain :data/local/dao Room-DAO 5-leaf batch. Two compound
 * classifications (each LIVE-NOT-STALE + FULFILLED-RETIRE for
 * Phase 9.x.historyrepository.componentprune Task #386 and Phase
 * 9.x.updatesourcesrepository.daoprune Task #388 respectively). Original
 * Phase-9 componentprune prose preserved verbatim per the audit-trail
 * -preservation convention.
 *
 * CORRECTION (2026-06-12): section (b)'s "getHistoryByApi does not appear in the @Dao interface body"
 * is STALE — B6 (#1) re-added getHistoryByApi (lines 50-51) for source-registry URL propagation
 * (now via SourceCatalogSyncRepositoryImpl/SourceUrlMigrator — the endpoint refresh was retired in
 * SourceRegistry retirement Phase 6). Separately, getLatestHistoryIdByManga was removed
 * this sweep (zero production callers; only a TODO() test-fake override referenced it), so the
 * "External LIVE reachers" entry for it was dropped and section (a)'s list no longer applies. The
 * live surface is 11 members. Retained as lineage per the audit-trail-preservation convention.
 */
