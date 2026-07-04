package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Phase 9.x.statisticsdao.componentprune (Task #393): dropped 7 independently-orphan suspend
// `*Once` single-shot variants surfaced by an exhaustive 3-pass reacher-chain audit (receiver-
// anchored `statisticsDao.X(` / `statisticsDeo.X(` / `statsDao.X(` + bare `\bX\b` word-boundary
// + `::X` method-ref) covering the entire source tree. Each dropped member had ZERO source-tree
// reachers under any receiver name (`statisticsDao.`, `statisticsDeo.`, `statsDao.`, method-ref)
// at any point in the live codebase. The header comment at line 59 of the prior file ("suspend
// versions if you need single-shot calls") was a forward-looking hook that no consumer ever
// reached for; the rework `Phase 7.x.statistics` slice (§81) consumes the 7 Flow variants
// directly via `combine` on the legacy `StatisticsRepository` flows, so no `*Once` lift is
// pending. If a future single-shot call site emerges, it would re-introduce one variant on
// demand (YAGNI).
// Removed (independent orphans):
//   - `getTotalMangaCountOnce(): Int` — suspend. No reacher. The LIVE Flow variant
//     `getTotalMangaCount(): Flow<Int>` IS reached via `StatisticsRepository.kt:36` as
//     `inLibraryFlow`.
//   - `getTotalChaptersCountOnce(): Int` — suspend. No reacher. LIVE Flow variant
//     `getTotalChaptersCount(): Flow<Int>` reached via `StatisticsRepository.kt:37` as
//     `chaptersTotalFlow`.
//   - `getDownloadedChaptersCountOnce(): Int` — suspend. No reacher. LIVE Flow variant
//     `getDownloadedChaptersCount(): Flow<Int>` reached via `StatisticsRepository.kt:38` as
//     `chaptersDownloadedFlow`.
//   - `getReadChaptersCountOnce(): Int` — suspend. No reacher. LIVE Flow variant
//     `getReadChaptersCount(): Flow<Int>` reached via `StatisticsRepository.kt:39` as
//     `chaptersReadFlow`.
//   - `getBookmarkedChaptersCountOnce(): Int` — suspend. No reacher. LIVE Flow variant
//     `getBookmarkedChaptersCount(): Flow<Int>` reached via `StatisticsRepository.kt:40` as
//     `chaptersBookmarkedFlow`.
//   - `getCompletedMangaCountOnce(): Int` — suspend. No reacher. LIVE Flow variant
//     `getCompletedMangaCount(): Flow<Int>` reached via `StatisticsRepository.kt:41` as
//     `completedEntriesFlow`.
//   - `getStartedMangaCountOnce(): Int` — suspend. No reacher. LIVE Flow variant
//     `getStartedMangaCount(): Flow<Int>` reached via `StatisticsRepository.kt:42` as
//     `startedEntriesFlow`.
// LIVE members preserved (verified by exhaustive reacher-chain audit, each reached exactly once
// by the legacy `StatisticsRepository` facade):
//   - `getTotalMangaCount(): Flow<Int>` — `StatisticsRepository.kt:36`.
//   - `getTotalChaptersCount(): Flow<Int>` — `StatisticsRepository.kt:37`.
//   - `getDownloadedChaptersCount(): Flow<Int>` — `StatisticsRepository.kt:38`.
//   - `getReadChaptersCount(): Flow<Int>` — `StatisticsRepository.kt:39`.
//   - `getBookmarkedChaptersCount(): Flow<Int>` — `StatisticsRepository.kt:40`.
//   - `getCompletedMangaCount(): Flow<Int>` — `StatisticsRepository.kt:41`.
//   - `getStartedMangaCount(): Flow<Int>` — `StatisticsRepository.kt:42`.
@Dao
interface StatisticsDeo {

    // 1. Count total saved manga
    @Query("SELECT COUNT(*) FROM saved_manga")
    fun getTotalMangaCount(): Flow<Int>

    // 2. Count total chapters across all manga
    @Query("SELECT COUNT(*) FROM saved_chapters")
    fun getTotalChaptersCount(): Flow<Int>

    // 3. Count downloaded chapters
    @Query("SELECT COUNT(*) FROM saved_chapters WHERE isDownloaded = 1")
    fun getDownloadedChaptersCount(): Flow<Int>

    // 4. Count read chapters
    @Query("SELECT COUNT(*) FROM saved_chapters WHERE isRead = 1")
    fun getReadChaptersCount(): Flow<Int>

    // 5. Count bookmarked chapters
    @Query("SELECT COUNT(*) FROM saved_chapters WHERE isBookmarked = 1")
    fun getBookmarkedChaptersCount(): Flow<Int>

    // 6. Count completed manga (all chapters read)
    @Query(
        """
        SELECT COUNT(*)
          FROM saved_manga m
         WHERE NOT EXISTS(
           SELECT 1 FROM saved_chapters c
            WHERE c.mangaId = m.id AND c.isRead = 0
         )
        """
    )
    fun getCompletedMangaCount(): Flow<Int>

    // 7. Count started manga (at least one chapter read)
    @Query(
        """
        SELECT COUNT(*)
          FROM saved_manga m
         WHERE EXISTS(
           SELECT 1 FROM saved_chapters c
            WHERE c.mangaId = m.id AND c.isRead = 1
         )
        """
    )
    fun getStartedMangaCount(): Flow<Int>
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster185.staleKdocSweep.cascade,
 * Task #674, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-eighty-sixth sibling of the cluster57-184
 * sweep continuum — leaf 2/5 of the wave-55 commonMain :data/local
 * closing-tier 5-leaf batch; StatisticsDeo interface 2/5).
 *
 *  (a) Inline componentprune comment "Phase-9-x-statisticsdao-componentprune
 *  Task-393-dropped-7-independently-orphan-suspend-Once-single-shot-variants
 *  + getTotalMangaCountOnce + getTotalChaptersCountOnce + getDownloadedChaptersCountOnce
 *  + getReadChaptersCountOnce + getBookmarkedChaptersCountOnce +
 *  getCompletedMangaCountOnce + getStartedMangaCountOnce" — LIVE-NOT-STALE
 *  for the post-§393 StatisticsDeo surface AND FULFILLED-RETIRE for the
 *  Phase 9.x.statisticsdao.componentprune Task #393 7-orphan drop (verified:
 *  none of the 7 dropped *Once suspend variants re-appears in the @Dao
 *  interface body; the 7 LIVE Flow variants documented in the prose are
 *  bound and remain reached by the legacy `StatisticsRepository` facade
 *  at the cited line numbers — `StatisticsRepository.kt:36-42` for
 *  `inLibraryFlow` / `chaptersTotalFlow` / `chaptersDownloadedFlow` /
 *  `chaptersReadFlow` / `chaptersBookmarkedFlow` / `completedEntriesFlow`
 *  / `startedEntriesFlow` respectively). The YAGNI rationale ("If a future
 *  single-shot call site emerges, it would re-introduce one variant on
 *  demand") remains the operative posture — the rework `Phase 7.x.statistics`
 *  slice (§81) consumes the 7 Flow variants directly via `combine` and
 *  has not surfaced a single-shot need since (verified: zero `.first()` or
 *  `.firstOrNull()` collapse of these 7 Flows in source tree). The
 *  `import androidx.room.Insert` / `androidx.room.OnConflictStrategy` /
 *  any `*Once` suspend signatures do not re-appear in the import block —
 *  only `androidx.room.Dao` + `androidx.room.Query` + `kotlinx.coroutines
 *  .flow.Flow` remain (3 imports total).
 *
 *  (b) The DAO class-name typo "StatisticsDeo" (should be "StatisticsDao")
 *  AS-IS-LIVE — the typo is the LIVE binding name (`mangaDatabase.statisticsDeo()`
 *  + `factory { mangaDatabase.statisticsDeo() }` reach the typo'd binding);
 *  renaming would require a coordinated `@Database` abstract-fun rename,
 *  Koin module rename, and all repository injection-site rename. Tolerated
 *  per the broader convention of preserving legacy class names verbatim
 *  during the rework migration. No follow-up tracker — re-rename is an
 *  independent cleanup that can land after the §253 sweep completes.
 *
 *  (c) Inline numbered step-comments inside the @Query body (`// 1. Count
 *  total saved manga` through `// 7. Count started manga`) — LIVE-NOT-STALE
 *  documentation aid; each numbered comment annotates the Flow variant
 *  it precedes, and the numbering matches the 7-variant LIVE surface
 *  preserved post-§393. The two `EXISTS`-vs-`NOT EXISTS` correlated
 *  subqueries (count #6 + #7) leverage the saved_chapters.mangaId FK to
 *  saved_manga.id and are operationally identical to the legacy semantics
 *  (verified by spot-check against `StatisticsRepository.kt:41-42`).
 *
 * Verified: 7-method StatisticsDeo interface (getTotalMangaCount +
 * getTotalChaptersCount + getDownloadedChaptersCount + getReadChaptersCount
 * + getBookmarkedChaptersCount + getCompletedMangaCount + getStartedMangaCount).
 * All 7 reached exactly once by `StatisticsRepository`. Sibling: SourcesDao
 * (cluster185 prior sibling); MangaDatabase (cluster185 succeeding sibling).
 * LEAF 2/5 of the cluster185 commonMain :data/local closing-tier 5-leaf
 * batch. Two compound classifications (LIVE-NOT-STALE + FULFILLED-RETIRE
 * for Phase 9.x.statisticsdao.componentprune Task #393 + LIVE-NOT-STALE
 * documentation classifications for the inline step-comments). Original
 * Phase-9 cumulative-prune prose preserved verbatim per the audit-trail
 * -preservation convention.
 */

