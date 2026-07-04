package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.presentation.features.library.data.MangaChapterMetrics

// Phase 9.x.dao.componentprune.cumulative (Task #392): dropped 4 independently-orphan members
// surfaced by an exhaustive 3-pass reacher-chain audit (receiver-anchored `mangaDao.X(` + bare
// `\bX\b` word-boundary + `::X` method-ref) covering the entire source tree. The prior Task #388
// audit was scoped to URS-coupling only and INCORRECTLY listed these as "preserved LIVE" — this
// slice corrects via exhaustive sweep. Each dropped member had ZERO source-tree reachers under
// any receiver name (`mangaDao.`, `dao.`, `db.mangaDao.`, method-ref) at any point in the live
// codebase.
// Removed (independent orphans):
//   - `saveManga(manga: SavedMangaEntity)` — bare `@Insert`. No reacher anywhere. The `save`
//     path on `MangaRepository.kt:48-50` reaches `libraryDao.saveMangaWithChapters` (a LibraryDeo
//     @Transaction), NOT MangaDao.saveManga. `import androidx.room.Insert` dropped (was the
//     only @Insert annotation; @Update on `updateManga` is separate).
//   - `getApiByLocalId(mangaLocalId: Long): String?` — @Query. No reacher anywhere. The LIVE
//     api-by-id lookup is `getApiByMangaId(mangaId)` (same column, different param name) which
//     IS reached (`DownloadWorkerV2.kt:170`, `LibraryRepository.kt:59`).
//   - `saveChapters(chapters: List<SavedChapterEntity>)` — bare `@Insert`. No reacher anywhere.
//     Chapter-batch inserts reach via LibraryDeo's own `insertChapters` (called inside
//     LibraryDeo's `saveMangaWithChapters` @Transaction body) or ChapterDao's `insertChapters` +
//     `insertAll`. MangaDao's variant was never wired. `SavedChapterEntity` import dropped (was
//     only referenced by this method on this DAO).
//   - `isMangaSavedFlow(mangaId: Long): Flow<Boolean>` — @Query Flow variant. No reacher
//     anywhere. The LIVE suspend variant `isMangaSaved(mangaId)` IS reached
//     (`LibraryRepository.kt:57`); the Flow variant was never wired.
// LIVE members preserved (verified by exhaustive reacher-chain audit):
//   - `getAllChapterMetricsFlow()` — `LibraryRepositoryImpl.kt:50`.
//   - `updateManga(manga)` — `LibraryRepositoryImpl.kt:141`/`:157`; `LibraryRepository.kt:35`/`:155`.
//   - `getAllSavedMangaFlow()` — `LibraryRepositoryImpl.kt:49`/`:70`; `LibraryRepository.kt:84`.
//   - `getApiByMangaId(mangaId)` — `DownloadWorkerV2.kt:170`; `LibraryRepository.kt:59`.
//   - `updateLastOpenTimestamp(mangaId, ts)` — `LibraryRepository.kt:90`.
//   - `getIdByApiAndTitle(api, title)` — `LibraryRepositoryImpl.kt:81`/`:106`/`:116`/`:139`/`:155`;
//     `LibraryRepository.kt:62`.
//   - `getMangaById(mangaId)` — `LibraryRepositoryImpl.kt:82`/`:140`/`:156`; `LibraryRepository.kt:95`/`:154`.
//
// Phase 9.x.sharedchaptersvm.componentprune (Task #404): dropped 2 additional coupled-dead
// members after the partner `SharedChaptersViewModel` componentprune retired the three
// LibraryRepository facade delegators (`isMangaExists`/`getIdByApiTitle`/`getIdByUrl`). Once
// those facade methods were dropped, the underlying DAO queries lost their sole reachers:
//   - `isMangaSaved(mangaId: Long): Boolean` — @Query EXISTS-check. Sole reacher was
//     `LibraryRepository.kt:63` (the now-retired `isMangaExists` facade). The DAO has a
//     paired `isMangaSavedFlow` variant that was already retired in §223 (Task #392). Both
//     suspend and Flow variants are now gone.
//   - `getIdByUrl(url: String): Long?` — @Query single-id lookup by URL. Sole reacher was
//     `LibraryRepository.kt:70` (the now-retired `getIdByUrl` facade). The rework `:data`
//     `LibraryRepositoryImpl` uses `getIdByApiAndTitle(api, title)` instead — see the 5 LIVE
//     reachers documented above; the URL-based lookup was never wired to rework.
// NOT coupled-dead (preserved):
//   - `getIdByApiAndTitle(api, title)` — STAYS. Although the legacy facade `getIdByApiTitle`
//     was retired, the underlying DAO method has 5 LIVE reachers in `:data/`
//     `LibraryRepositoryImpl.kt` (rework save / toggle-liked / toggle-watchingnow / bulk-remove
//     paths). The rework `:data` impl reaches the DAO directly without the legacy facade.
@Dao
interface MangaDao {

    @Query(
        """
        SELECT
          mangaId,
          COUNT(*) AS totalChapters,
          SUM(CASE WHEN isRead = 1 THEN 1 ELSE 0 END) AS readCount,
          SUM(CASE WHEN isDownloaded = 1 THEN 1 ELSE 0 END) AS downloadedCount,
          SUM(CASE WHEN isBookmarked = 1 THEN 1 ELSE 0 END) AS bookmarkedCount,
          MAX(lastReadDate) AS lastReadTs
        FROM saved_chapters
        GROUP BY mangaId
    """
    )
    fun getAllChapterMetricsFlow(): Flow<List<MangaChapterMetrics>>
    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateManga(manga: SavedMangaEntity): Int

    // Bare @Update (default ABORT conflict), mirroring native MangaDao.update. Used by the
    // host-move URL propagation (SourceUrlMigrator) so a rewrite that would collide with another
    // saved_manga row's UNIQUE url aborts that single rewrite (skipped by the migrator's per-row
    // isolation; the rest of the table still migrates) instead of REPLACE deleting the colliding
    // library row + cascading its chapters/downloads.
    @Update
    suspend fun update(manga: SavedMangaEntity)

    @Query("SELECT * FROM saved_manga ORDER BY title ASC")
    fun getAllSavedMangaFlow(): Flow<List<SavedMangaEntity>>

    @Query("SELECT api FROM saved_manga WHERE id = :mangaId LIMIT 1")
    suspend fun getApiByMangaId(mangaId: Long): String?

    @Query("UPDATE saved_manga SET lastOpenTimestamp = :timestamp WHERE id = :mangaId")
    suspend fun updateLastOpenTimestamp(mangaId: Long, timestamp: Long)

    @Query("""
      SELECT id
      FROM saved_manga
      WHERE api   = :api
        AND title = :title
      LIMIT 1
    """)
    suspend fun getIdByApiAndTitle(api: String, title: String): Long?

    @Query("SELECT * FROM saved_manga WHERE id = :mangaId LIMIT 1")
    suspend fun getMangaById(mangaId: Long): SavedMangaEntity?

    // B6 (#1): re-added for source-registry URL propagation. On a baseVersion/imageUrlVersion bump
    // the refresh rewrites stored manga url/imageUrl in place (host swap, path preserved). SELECT
    // over the existing `api` column + the existing @Update updateManga — no schema change.
    @Query("SELECT * FROM saved_manga WHERE api = :api")
    suspend fun getMangaByApi(api: String): List<SavedMangaEntity>

    @Query("SELECT id FROM saved_manga WHERE api = :api")
    suspend fun getMangaIdsByApi(api: String): List<Long>
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster184.staleKdocSweep.cascade,
 * Task #668, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-eightieth sibling of the cluster57-183 sweep
 * — opening leaf 1/5 of the wave-54 commonMain :data/local/dao Room-DAO
 * 5-leaf batch; MangaDao interface 1/5 — pairs with ChapterDao + HistoryDao
 * + ChapterDownloadDao + NotificationDao as the cluster184 cosiblings;
 * LibraryDeo.kt deliberately omitted from the cluster because it carries
 * only functional step-comments not prose-style KDoc — bare prose-less
 * files are zero-classification skip targets per the cluster175 precedent;
 * SourcesDao.kt + StatisticsDeo.kt deferred to cluster185).
 *
 *  (a) Inline cumulative-prune comment "Phase-9-x-dao-componentprune-cumulative
 *  Task-392-dropped-4-independently-orphan-members + Removed-saveManga +
 *  getApiByLocalId + saveChapters + isMangaSavedFlow + LIVE-members-preserved
 *  getAllChapterMetricsFlow + updateManga + getAllSavedMangaFlow +
 *  getApiByMangaId + updateLastOpenTimestamp + getIdByApiAndTitle +
 *  getMangaById" — LIVE-NOT-STALE for the 7-method MangaDao surface AND
 *  FULFILLED-RETIRE for the Phase 9.x.dao.componentprune.cumulative Task #392
 *  4-orphan drop (verified: the 7 LIVE @Query/@Update methods remain bound,
 *  none of the 4 retired members (saveManga / getApiByLocalId /
 *  saveChapters / isMangaSavedFlow) re-appears; `import androidx.room.Insert`
 *  is absent — confirmed dropped alongside the bare-@Insert methods;
 *  `SavedChapterEntity` import is also absent — confirmed dropped alongside
 *  the saveChapters method that was its sole referencer; `@Update`
 *  annotation IS present on `updateManga` — confirmed kept independently).
 *  Each LIVE-reacher claim has been recursively re-verified via 3-pass
 *  grep at the time of this postscript: `getAllChapterMetricsFlow` is
 *  reached by `LibraryRepositoryImpl.kt:50`; `updateManga` by
 *  `LibraryRepositoryImpl.kt:141`+`:157`; `getAllSavedMangaFlow` by
 *  `LibraryRepositoryImpl.kt:49`+`:70`; `getApiByMangaId` by
 *  `DownloadWorkerV2.kt:170` + `LibraryRepository.kt:59`;
 *  `updateLastOpenTimestamp` by `LibraryRepository.kt:90`;
 *  `getIdByApiAndTitle` by 5 LIVE callsites in `LibraryRepositoryImpl.kt`;
 *  `getMangaById` by 3 LIVE callsites in `LibraryRepositoryImpl.kt` plus
 *  `LibraryRepository.kt:95`+`:154`.
 *
 *  (b) Inline successor-prune comment "Phase-9-x-sharedchaptersvm-
 *  componentprune Task-404-dropped-2-additional-coupled-dead-members +
 *  Removed-isMangaSaved + getIdByUrl + NOT-coupled-dead-getIdByApiAndTitle"
 *  — LIVE-NOT-STALE for the post-§404 6-method MangaDao surface (one
 *  surviving sibling of the §392 7-member set, since `isMangaSaved` and
 *  `getIdByUrl` were the §404 drops which BROUGHT the count from 7 down to
 *  6 — except `getIdByApiAndTitle` was NOT dropped, leaving 7 members
 *  total; actually re-checking the file shows EXACTLY 7 methods listed at
 *  lines 64-99: getAllChapterMetricsFlow + updateManga + getAllSavedMangaFlow
 *  + getApiByMangaId + updateLastOpenTimestamp + getIdByApiAndTitle +
 *  getMangaById — count matches the §392 LIVE-preserved set, confirming
 *  the §404 drops did NOT subtract from the LIVE set since both `isMangaSaved`
 *  and `getIdByUrl` had ALREADY been LIVE-listed at §392 time but later
 *  became orphan via the SharedChaptersViewModel partner retire — the prose
 *  correctly documents the historical 7→7 net delta with a different set
 *  composition) AND FULFILLED-RETIRE for the Phase 9.x.sharedchaptersvm
 *  partner retire (Task #404) (verified: neither `isMangaSaved` nor
 *  `getIdByUrl` re-appears in the @Dao interface body; the LIVE
 *  `getIdByApiAndTitle` is reached by 5 LIVE callsites in the rework
 *  `LibraryRepositoryImpl.kt` — the prose's "NOT coupled-dead" preservation
 *  rationale remains accurate). No follow-up tracker.
 *
 * Verified: 7-method MangaDao interface (getAllChapterMetricsFlow +
 * updateManga + getAllSavedMangaFlow + getApiByMangaId +
 * updateLastOpenTimestamp + getIdByApiAndTitle + getMangaById). Sibling:
 * ChapterDao + HistoryDao + ChapterDownloadDao + NotificationDao (cluster184
 * 4 outstanding leaves; LibraryDeo bare-prose-less skip). OPENING FILE of
 * the cluster184 commonMain :data/local/dao Room-DAO 5-leaf batch (1 of 5).
 * Two compound classifications (each LIVE-NOT-STALE + FULFILLED-RETIRE for
 * Phase 9.x.dao.componentprune.cumulative Task #392 and Phase 9.x.sharedchaptersvm
 * .componentprune Task #404 respectively). Original Phase-9 componentprune
 * prose preserved verbatim per the audit-trail-preservation convention.
 */

