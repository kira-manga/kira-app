package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.manga.kira.data.local.entity.ChapterNotification

// Phase 9.x.notificationdao.componentprune (Task #385): dropped 10 orphan members + 4
// transitively-dead @Transaction body callees after a 3-pass reacher-chain audit (anchored
// receiver `notificationDao.X(` + `dao.X(` for the `NotificationRepository.dao` field +
// bare identifier disambiguation across 27 distinct DAO members). The 3 external reachers
// kept (post Phase 9.x.updatesourcesrepository.daoprune) are
// `notificationDao.insertNotificationsList` (ChapterNotificationHelper),
// `notificationDao.addLocalImagePathByChapterId` (ChapterDownloadService × 6), and
// `notificationDao.updateMangaImageUrl` (LibraryRepository); plus the 4 `dao.X` self-body
// reachers in `NotificationRepository` (`dao.getAllNotifications`, `dao.markAllAsRead`,
// `dao.deleteAllNotifications`, `dao.deleteNotification`) — count was 5 prior to Phase
// 9.x.notificationrepository.componentprune (Task #396) which dropped the coupled-dead
// `dao.insertNotification` consumer (see entry below). The
// `addLocalImagePathByChapterId` @Transaction body keeps `getNotificationByChapterId` +
// `updateNotification` LIVE-by-association.
//
// Phase 9.x.updatesourcesrepository.daoprune (Task #388): dropped 2 additional coupled-dead
// members after the `UpdateSourcesRepository` retire (Task #387). 3-pass reacher-chain audit
// confirmed zero `notificationDao.X(` reachers post-URS for either.
// Removed:
//   - `getNotificationsByApi(apiName: String): List<ChapterNotification>` — URS-only.
//   - `update(notification: ChapterNotification)` — URS-only. Bare `@Update` overload. Note:
//     `updateNotification` (different name) inside the LIVE `addLocalImagePathByChapterId`
//     @Transaction body remains LIVE-by-association.
//
// Phase 9.x.notificationrepository.componentprune (Task #396): dropped 1 additional
// coupled-dead DAO member after the partner `NotificationRepository.restore(notification)`
// orphan retire in the same slice. 3-pass reacher-chain audit (`notificationDao.X(` +
// `dao.X(` + `\bX\b` + `::X`) confirmed the receiver's only call site was
// `NotificationRepository.restore`, which itself had zero runtime reachers (all external
// mentions are KDoc comments on `:domain/repository/UpdatesRepository.kt:18/19/25`
// explicitly documenting the deliberate omission). With `restore()` gone, the singular
// insert is unreached.
// Removed:
//   - `insertNotification(notification: ChapterNotification): Long` — singular @Insert. The
//     plural-batch `insertNotificationsList(notifications: List<ChapterNotification>):
//     List<Long>` (reached by `ChapterNotificationHelper.kt:110`) stays LIVE — the batch
//     overload covers every remaining notification-creation path.
// Direct orphans dropped:
//   - `getUnreadNotifications(): Flow<List<ChapterNotification>>` — no reacher.
//   - `markAsRead(notificationId: Long)` — all `.markAsRead(` callers route through
//     `NotificationRepository.markAsRead` → `libraryDeo.markChapterAndNotificationRead`,
//     NOT this DAO method.
//   - `countAll(): Int` — no reacher.
//   - `getLatest(limit: Int): List<ChapterNotification>` — no reacher.
//   - `markAsDownloaded(notificationId: Long)` — no reacher.
//   - `updateLocalImagePaths(notificationId, paths)` — no reacher.
//   - `insertNotifications(notifications: List<ChapterNotification>): Unit` overload — only
//     `insertNotificationsList(...): List<Long>` (different return type) is reached.
//   - `addLocalImagePath(notificationId, newPath)` @Transaction — no reacher.
//   - `addLocalImagePathForChapter(chapterUrl, newPaths)` @Transaction — no reacher.
//   - `markChapterAndNotificationRead(chapterId)` @Transaction — duplicate of the LIVE
//     `LibraryDeo.markChapterAndNotificationRead`; all 2 external callers
//     (`NotificationRepository.markAsRead` + `LibraryRepository`) use the `libraryDeo.` receiver.
// Transitively-dead @Transaction body callees (only reached from now-dropped transactions):
//   - `getNotificationById(id)` — only called inside dropped `addLocalImagePath`.
//   - `findOneByChapterUrl(chapterUrl)` — only called inside dropped `addLocalImagePathForChapter`.
//   - `markChapterAsReadInternal(chapterId)` — only called inside dropped
//     `markChapterAndNotificationRead`.
//   - `markNotificationReadInternal(chapterId)` — only called inside dropped
//     `markChapterAndNotificationRead`.
@Dao
interface NotificationDao {

    @Query("UPDATE notifications SET mangaImageUrl = :newImageUrl WHERE mangaId = :mangaId")
    suspend fun updateMangaImageUrl(mangaId: Long, newImageUrl: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationsList(notifications: List<ChapterNotification>): List<Long>

    @Update
    suspend fun updateNotification(notification: ChapterNotification)

    @Query("SELECT * FROM notifications ORDER BY notificationDate DESC")
    fun getAllNotifications(): Flow<List<ChapterNotification>>

    // B6 (#1): re-added for source-registry URL propagation — rewrites stored notification
    // mangaUrl/chapterUrl/mangaImageUrl in place on a version bump (reuses the existing @Update
    // updateNotification). SELECT over the existing `api` column; no schema change.
    @Query("SELECT * FROM notifications WHERE api = :api")
    suspend fun getNotificationsByApi(api: String): List<ChapterNotification>

    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllAsRead()

    @Delete
    suspend fun deleteNotification(notification: ChapterNotification)

    @Query("DELETE FROM notifications")
    suspend fun deleteAllNotifications()

    @Query("SELECT * FROM notifications WHERE chapterId = :chapterId LIMIT 1")
    suspend fun getNotificationByChapterId(chapterId: Long): ChapterNotification?

    // `downloaded` (default true = the classic set-on-complete callers, unchanged) also serves the
    // REVERT direction (2026-07-04 device smoke): a user cancel during the iOS finalize window
    // must undo the notification row's downloaded bookkeeping too — pass
    // (chapterId, emptyList(), downloaded = false) — or the Updates screen keeps offering the
    // cancelled chapter as downloaded. No-op when no row exists.
    @Transaction
    suspend fun addLocalImagePathByChapterId(
        chapterId: Long,
        newPaths: List<String>,
        downloaded: Boolean = true,
    ) {
        val notif = getNotificationByChapterId(chapterId) ?: return
        val updated = notif.copy(
            localImagePaths = newPaths,
            isDownloaded    = downloaded
        )
        updateNotification(updated)
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster184.staleKdocSweep.cascade,
 * Task #672, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-eighty-fourth sibling of the cluster57-183
 * sweep — closing leaf 5/5 of the wave-54 commonMain :data/local/dao
 * Room-DAO 5-leaf batch; NotificationDao interface 5/5).
 *
 *  (a) Inline cumulative-prune comment "Phase-9-x-notificationdao-componentprune
 *  Task-385-dropped-10-orphan-members-plus-4-transitively-dead-Transaction-body
 *  -callees" — LIVE-NOT-STALE for the post-§385 NotificationDao surface AND
 *  FULFILLED-RETIRE for the Phase 9.x.notificationdao.componentprune Task #385
 *  10+4 orphan drop (verified: none of the 10 direct orphans
 *  (`getUnreadNotifications` / `markAsRead` / `countAll` / `getLatest` /
 *  `markAsDownloaded` / `updateLocalImagePaths` / `insertNotifications`
 *  overload / `addLocalImagePath` @Transaction / `addLocalImagePathForChapter`
 *  @Transaction / `markChapterAndNotificationRead` @Transaction) re-appears
 *  in the @Dao interface body; none of the 4 transitively-dead body callees
 *  (`getNotificationById` / `findOneByChapterUrl` / `markChapterAsReadInternal`
 *  / `markNotificationReadInternal`) re-appears either; the 3 external LIVE
 *  reachers preserved (`insertNotificationsList` reached by
 *  `ChapterNotificationHelper.kt:110`; `addLocalImagePathByChapterId` reached
 *  by `ChapterDownloadService.kt` ×6; `updateMangaImageUrl` reached by
 *  `LibraryRepository`) are all bound in the @Dao interface body; the 4
 *  `dao.X` self-body reachers (`getAllNotifications` / `markAllAsRead` /
 *  `deleteAllNotifications` / `deleteNotification`) reached by
 *  `NotificationRepository` facade methods are also all bound).
 *
 *  (b) Inline coupled-dead-prune comment "Phase-9-x-updatesourcesrepository
 *  -daoprune Task-388-dropped-2-additional-coupled-dead-members +
 *  getNotificationsByApi + update" — LIVE-NOT-STALE for the post-§388
 *  NotificationDao surface AND FULFILLED-RETIRE for the Phase 9.x.
 *  updatesourcesrepository.daoprune Task #388 partner retire (verified:
 *  `getNotificationsByApi(apiName)` does not appear; bare-name
 *  `@Update update(notification)` overload does not appear — only the
 *  LIVE `updateNotification(notification)` variant with the
 *  `Notification`-suffix naming remains, which IS called inside the LIVE
 *  `addLocalImagePathByChapterId` @Transaction body at line 110).
 *
 *  (c) Inline coupled-dead-prune comment "Phase-9-x-notificationrepository
 *  -componentprune Task-396-dropped-1-additional-coupled-dead-DAO-member +
 *  insertNotification" — LIVE-NOT-STALE for the post-§396 NotificationDao
 *  surface AND FULFILLED-RETIRE for the Phase 9.x.notificationrepository
 *  .componentprune Task #396 partner retire (verified: singular
 *  `insertNotification(notification): Long` does not appear in the @Dao
 *  interface body; the plural-batch
 *  `insertNotificationsList(notifications: List<ChapterNotification>):
 *  List<Long>` remains LIVE — reached by `ChapterNotificationHelper.kt:110`
 *  per the §385 prose). The `NotificationRepository.restore(notification)`
 *  partner orphan retire (also §396) which was the singular-insert's sole
 *  reacher is documented retired in Task #396 — the `:domain/repository/
 *  UpdatesRepository.kt:18/19/25` KDoc comments cited in the §385 prose
 *  documenting the deliberate omission of a restore-from-undo flow remain
 *  in place and IS load-bearing (Updates feature explicitly elects NOT to
 *  expose a notification-restore action, consistent with the rework
 *  Updates snackbar's no-restore semantic).
 *
 * The `addLocalImagePathByChapterId` @Transaction body keeping
 * `getNotificationByChapterId` + `updateNotification` LIVE-by-association
 * IS load-bearing — both are otherwise unreached externally but the
 * upsert-by-chapterId pattern (fetch existing notification, copy with
 * new localImagePaths + isDownloaded=true, persist via updateNotification)
 * requires both helpers inside the @Transaction wrapper for cross-write
 * atomicity. The ChapterDownloadService.kt ×6 reach pattern is the
 * post-download notification refresh hook that flips a notification row
 * from "new" to "downloaded" once the worker completes its image fetches.
 *
 *  CLOSING-LEAF SUMMARY: the cluster184 :data/local/dao Room-DAO 5-leaf
 *  batch collectively documents the cumulative Phase 9.x dao-componentprune
 *  chain that pruned the post-strangler-fig DAO surface across all 5 LIVE
 *  DAOs — MangaDao did §392 (4 orphans) + §404 (2 coupled-dead); ChapterDao
 *  did §392 (6 orphans) + §401 (1 coupled-dead isChapterDownloadedFlow);
 *  HistoryDao did §386 (3 transitively-dead) + §388 (2 coupled-dead);
 *  ChapterDownloadDao did §394 (4 orphans) + §398 (2 coupled-dead) + §441
 *  cascade (2 cascade-orphans); NotificationDao did §385 (10 orphans + 4
 *  transitively-dead) + §388 (2 coupled-dead) + §396 (1 coupled-dead).
 *  The common architectural invariant across all 5 DAOs is that Room's
 *  @Dao annotation processor preserves the LIVE method surface by reading
 *  only the methods present in the @Dao interface body at compile time —
 *  pruning unreached methods produces zero behaviour delta but eliminates
 *  the dead-symbol surface area, which was the central motivation for
 *  the cumulative chain documented across cluster184. LibraryDeo.kt was
 *  deliberately skipped from cluster184 (bare prose-less, only carries
 *  functional step-comments inside @Transaction bodies — zero-classification
 *  per the cluster175 precedent); SourcesDao.kt and StatisticsDeo.kt are
 *  deferred to cluster185 next. No follow-up tracker.
 *
 * Verified: 9-symbol NotificationDao interface (updateMangaImageUrl +
 * insertNotificationsList + updateNotification + getAllNotifications +
 * markAllAsRead + deleteNotification + deleteAllNotifications +
 * getNotificationByChapterId + addLocalImagePathByChapterId @Transaction).
 * Sibling: MangaDao + ChapterDao + HistoryDao + ChapterDownloadDao
 * (cluster184 prior siblings). CLOSING FILE of the cluster184 commonMain
 * :data/local/dao Room-DAO 5-leaf batch (5 of 5). Three compound
 * classifications (each LIVE-NOT-STALE + FULFILLED-RETIRE for
 * Phase 9.x.notificationdao.componentprune Task #385, Phase 9.x.
 * updatesourcesrepository.daoprune Task #388, and Phase 9.x.
 * notificationrepository.componentprune Task #396 respectively). Original
 * Phase-9 componentprune prose preserved verbatim per the audit-trail
 * -preservation convention.
 *
 * CORRECTION (2026-06-12): section (b)'s "getNotificationsByApi does not appear" and the "9-symbol
 * NotificationDao interface" count are STALE — B6 (#1) re-added getNotificationsByApi (lines 91-92)
 * for source-registry URL propagation (now via SourceCatalogSyncRepositoryImpl/SourceUrlMigrator;
 * the endpoint refresh was retired in SourceRegistry retirement Phase 6), so the live
 * surface is 10 members. Retained as lineage per the audit-trail-preservation convention.
 */

