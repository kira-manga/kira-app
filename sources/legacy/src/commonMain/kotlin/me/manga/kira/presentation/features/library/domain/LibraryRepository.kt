package me.manga.kira.presentation.features.library.domain

import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.HistoryDao
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.service.FileService

// Migration notes (Phase 8.13 batch A):
//   - Hilt `@Singleton` + `@Inject` dropped. Constructor params preserved exactly.
//   - `android.util.Log` import was unused in source — dropped. (No `Log.d/e/i` call sites
//     existed in the body.) Kermit `Logger` not introduced because there was nothing to log.
//   - `System.currentTimeMillis()` -> `Clock.System.now().toEpochMilliseconds()` (in the default
//     value of `updateLastOpenTimestamp`). Wire format unchanged: both produce a Long epoch-ms.
//   - All DAO method signatures + return types preserved; no API changes for callers.
//
// Phase 9.x.libraryrepository.componentprune (Task #383): dropped 4 orphan facade methods
// after a 3-pass level-2 reacher-chain audit (`libraryRepository.X(` anchored receiver + bare
// `.X(` identifier disambiguation across all 8 LibraryRepository-typed constructor injections
// (at audit time): CoroutineDownloadRepositoryImpl, ChapterDownloadService, LibraryRefreshWorker,
// ChapterNotificationHelper, SharedChaptersViewModel, ChaptersViewModel, ReaderViewModel,
// LibraryDetailsViewModel). Post-§402 (Phase 9.x.chaptersvm.componentprune), `ChaptersViewModel`
// is no longer a LibraryRepository-typed injection site (its `libraryRepository` constructor
// param was orphan and got dropped along with the rest of the VM's reading-mode/chapter-imgs/
// bookmarked surface). The audit results below were unaffected by that change because the
// dropped methods were already orphan across ALL 8 sites at audit time — including the now-
// retired `ChaptersViewModel` injection — so the count goes 8→7 without re-running the audit.
// Removed:
//   - `updateManga(manga)` — only `mangaDao.updateManga(...)` callers exist (in :data
//     `LibraryRepositoryImpl.toggleManga{Liked,WatchingNow}` + self-body of
//     `updateMangaImageUrlEverywhere`); none route through this facade.
//   - `searchSavedManga(query)` — no callers in any of the 8 injection sites.
//   - `updateChapterLastReadDate(chapterId)` — no callers; the only same-name reference
//     is the DAO `chapterDao.updateChapterLastReadDate` (also dropped in same commit).
//   - `updateChapterLocalPathsByUrl(chapterUrl, paths)` — no callers; same DAO chain.
// Coupled DAO drops (transitively-dead after the LibraryRepository methods are gone):
//   - `MangaDao.searchMangaByTitle` — only self-definition + the removed LibraryRepository
//     body referenced it.
//   - `ChapterDao.updateChapterLastReadDate` — same.
//   - `ChapterDao.updateChapterLocalPathsByUrl` — same.
//
// Phase 9.x.sharedchaptersvm.componentprune (Task #404): dropped 3 additional facade methods
// after a 3-pass receiver-anchored reacher-chain audit confirmed all three were coupled-dead
// once the corresponding `SharedChaptersViewModel` delegators were retired in the same slice
// (the VM's `isMangaExists`/`getIdByApiTitle`/`getIdByUrl` were the SOLE reachers of each
// facade method, and the VM members were themselves orphan across the live tree). Audit
// covered all (now 7 → 6 post-§402) LibraryRepository-typed constructor injections:
// CoroutineDownloadRepositoryImpl, ChapterDownloadService, LibraryRefreshWorker,
// ChapterNotificationHelper, SharedChaptersViewModel, ReaderViewModel, LibraryDetailsViewModel.
// Note: SharedChaptersViewModel STAYS in the injection list — it still LIVE-reaches
// `libraryRepository.getChaptersByMangaId(...)` from its LIVE `getChaptersByHistoryItemFlow`
// body (the §404 prune retired 9 orphan members but left this LIVE reach intact). So the
// injection count goes 7 → 7 with §404; no change.
// Removed:
//   - `isMangaExists(id: Long): Boolean` — facade over `mangaDao.isMangaSaved(id)`. Sole
//     reacher was the now-retired `SharedChaptersViewModel.isMangaExists` delegator.
//   - `getIdByApiTitle(key: ApiTitle)` — facade over `mangaDao.getIdByApiAndTitle(api, title)`.
//     Sole reacher was the now-retired `SharedChaptersViewModel.getIdByApiTitle` delegator.
//   - `getIdByUrl(url: String)` — facade over `mangaDao.getIdByUrl(url)`. Sole reacher was the
//     now-retired `SharedChaptersViewModel.getIdByUrl` delegator.
// Coupled DAO drops (transitively-dead after the LibraryRepository methods are gone):
//   - `MangaDao.isMangaSaved(mangaId)` — only `LibraryRepository.isMangaExists` reached it;
//     post-§404 zero reachers. Dropped in the same slice.
//   - `MangaDao.getIdByUrl(url)` — only `LibraryRepository.getIdByUrl` reached it; post-§404
//     zero reachers. Dropped in the same slice.
// NOT coupled-dead (preserved):
//   - `MangaDao.getIdByApiAndTitle(api, title)` — 5 LIVE reachers in `:data/`
//     `LibraryRepositoryImpl.kt:81`/`:106`/`:116`/`:139`/`:155`. The legacy facade went away
//     but the rework `:data` impl reaches the DAO directly. STAYS.
//
// Audit-trail postscript (Phase 9.x.libdetails.staleKdocSweep, Task #438, 2026-05-28): the
// §383 and §404 audit-narratives above cite `LibraryDetailsViewModel` as one of the (then-
// LIVE) LibraryRepository-typed constructor-injection sites that the 3-pass reacher-chain
// audits covered:
//   - §383's "8 sites at audit time" list (line 34) ended with `LibraryDetailsViewModel`.
//   - §404's "(now 7 → 6 post-§402)" list (line 61) ended with `LibraryDetailsViewModel`.
// `LibraryDetailsViewModel` was retired in Phase 9.x.libdetails.retire.5c (§437, Task #437)
// along with its Koin `viewModel { ... }` binding. The post-§437 LibraryRepository-typed
// constructor-injection count is therefore 6 → 5: CoroutineDownloadRepositoryImpl,
// ChapterDownloadService, LibraryRefreshWorker, ChapterNotificationHelper,
// SharedChaptersViewModel, ReaderViewModel. The §383/§404 retire-conclusions (the 7
// dropped facade methods + their coupled DAO drops) stand on their own merits — they
// were orphan ACROSS all then-LIVE injection sites at audit time, including the now-
// retired `LibraryDetailsViewModel`, so the retire decisions remain correct regardless
// of which sites have since dropped out. Only the cited site-list snapshot is stale.
// Original §383/§404 prose preserved verbatim per §253.
@OptIn(ExperimentalTime::class)
class LibraryRepository(
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val libraryDeo: LibraryDeo,
    private val notificationDao: NotificationDao,
    private val historyDao: HistoryDao,
    private val fileService: FileService,
) {

    suspend fun getApiById(mangaId: Long) = mangaDao.getApiByMangaId(mangaId)


    fun isChapterBookmarkedFlow(chapterId: Long): Flow<Boolean> =
        chapterDao.getChapterById(chapterId)
            .map { it?.isBookmarked == true }


    suspend fun insertChapterList(chapters: List<SavedChapterEntity>): List<Long> =
        withContext(platformIoDispatcher) {
            try {
                // Use the safe method with IGNORE strategy
                val results = chapterDao.insertChaptersSafely(chapters)
                results
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Return empty list on error to prevent crashes, but log so a transient DB write
                // failure is observable (callers can't otherwise tell it from "no new chapters").
                Logger.withTag("LibraryRepository").e(e) { "insertChapterList failed: ${e.message}" }
                emptyList()
            }
        }

    fun getAllSavedManga(): Flow<List<SavedMangaEntity>> = mangaDao.getAllSavedMangaFlow()

    suspend fun updateLastOpenTimestamp(
        mangaId: Long,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        mangaDao.updateLastOpenTimestamp(mangaId, timestamp)
    }

    suspend fun deleteChapter(chapter: SavedChapterEntity) = chapterDao.deleteChapterById(chapter.id)

    suspend fun getMangaById(mangaId: Long): SavedMangaEntity? = mangaDao.getMangaById(mangaId)

    fun getChaptersByMangaId(mangaId: Long): Flow<List<SavedChapterEntity>> =
        chapterDao.getChaptersByMangaId(mangaId)

    suspend fun insertChapters(chapters: List<SavedChapterEntity>) {
        chapterDao.insertAll(chapters)
    }

    suspend fun updateChapterLocalPaths(chapterId: Long, paths: List<String>) =
        chapterDao.updateChapterLocalPaths(chapterId, paths)


    suspend fun markChapterAsDownloaded(chapterId: Long) =
        chapterDao.markChapterDownloaded(chapterId)

    // Revert twin of markChapterAsDownloaded + updateChapterLocalPaths (2026-07-04 device smoke):
    // a user cancel during the iOS finalize window must undo the readable bookkeeping written at
    // transfer-complete (isDownloaded + localImagePaths), or the chapter stays "Downloaded".
    suspend fun markChapterNotDownloaded(chapterId: Long) = chapterDao.markChaptersNotDownloaded(listOf(chapterId))

    suspend fun getChapterIdByUrl(chapterUrl: String) =
        chapterDao.getChapterIdByUrl(chapterUrl)


    suspend fun toggleChapterBookmark(chapterId: Long) =
        chapterDao.toggleChapterBookmark(chapterId)

    suspend fun toggleChapterRead(chapterId: Long) =
        libraryDeo.markChapterAndNotificationRead(chapterId)

    suspend fun toggleChaptersBookmark(chapterIds: List<Long>) =
        chapterDao.toggleChaptersBookmark(chapterIds)

    suspend fun toggleChaptersRead(chapterIds: List<Long>) =
        chapterDao.toggleChaptersRead(chapterIds)

    suspend fun markChaptersRead(chapterIds: List<Long>) =
        chapterDao.markChaptersRead(chapterIds)

    suspend fun markChapterAsRead(chapterId: Long) {
        chapterDao.markChapterAsRead(chapterId)
    }

    suspend fun markChapterIsNew(chapterId: Long) =
        chapterDao.markChapterIsNew(chapterId)

    suspend fun updateMangaImageUrlEverywhere(mangaId: Long, newImageUrl: String) = withContext(platformIoDispatcher) {
        mangaDao.getMangaById(mangaId)?.let { manga ->
            mangaDao.updateManga(manga.copy(imageUrl = newImageUrl))
            // Belt-and-braces: rework-written history rows carry mangaId = 0, so the mangaId-keyed
            // history update below misses them — propagate by mangaUrl too so History covers refresh.
            historyDao.updateMangaImageUrlByUrl(manga.url, newImageUrl)
        }
        notificationDao.updateMangaImageUrl(mangaId, newImageUrl)
        historyDao.updateMangaImageUrl(mangaId, newImageUrl)
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster209.staleKdocSweep.cascade, Task #665, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster209 leaf 2/2 — :shared/library/domain/ tier SINGLE-LEAF closer, sibling 385. CLUSTER209
 * CLOSER. Cumulative §253-postscript count = 110 leaves with this commit (was 109 mid-cluster209).
 *
 * File-shape note: 205-line class — `LibraryRepository` with 6 constructor deps (mangaDao,
 * chapterDao, libraryDeo, notificationDao, historyDao, fileService). Largest dep-count in the
 * cluster208+cluster209 cohort. Surfaces 21 public members after Task #383 + Task #404 cumulative
 * componentprune: getApiById (suspend), isChapterBookmarkedFlow (Flow), insertChapterList
 * (suspend with IO-context try/catch), getAllSavedManga (Flow), updateLastOpenTimestamp (suspend
 * with Clock.System default), deleteChapter (suspend), getMangaById (suspend), getChaptersByMangaId
 * (Flow), insertChapters (suspend), updateChapterLocalPaths (suspend), markChapterAsDownloaded
 * (suspend), getChapterIdByUrl (suspend), toggleChapterBookmark (suspend), toggleChapterRead
 * (suspend), toggleChaptersBookmark (suspend), toggleChaptersRead (suspend), markChaptersRead
 * (suspend), markChapterAsRead (suspend), markChapterIsNew (suspend), deleteDownloadedChapters
 * (suspend with parallel fileService.deleteChapterFiles), updateMangaImageUrlEverywhere (suspend
 * with cross-dao fanout). Class-level KDoc (lines 21-98) carries Phase 8.13 batch A migration
 * prose + Task #383 componentprune lineage (4 dropped facade methods + 3 coupled DAO drops) +
 * Task #404 componentprune lineage (3 dropped facade methods + 2 coupled DAO drops) + Task #438
 * libdetails-retire audit-trail postscript correcting the cited site-list snapshots.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — most-heavily-consumed legacy library SOURCE — direct consumers (verified
 *     via 5-site receiver-anchored grep audit, snapshot post-§437):
 *       1. CoroutineDownloadRepositoryImpl.kt (:data side or :shared download impl) — reaches
 *          insertChapters + updateChapterLocalPaths + markChapterAsDownloaded as part of the
 *          download-completion writeback chain.
 *       2. ChapterDownloadService.kt (:platform Android side) — reaches similar set during
 *          foreground service writeback.
 *       3. LibraryRefreshWorker.kt (:platform Android side) — reaches getAllSavedManga +
 *          insertChapterList during the periodic refresh job.
 *       4. ChapterNotificationHelper.kt — reaches insertChapterList during new-chapter detection.
 *       5. SharedChaptersViewModel.kt (:shared side) — reaches getChaptersByMangaId via its LIVE
 *          getChaptersByHistoryItemFlow body (Task #404 retired 9 orphan members but left this
 *          reach intact).
 *       6. ReaderViewModel.kt (:shared side) — reaches toggleChapterBookmark + toggleChapterRead +
 *          markChapterAsRead + updateLastOpenTimestamp + isChapterBookmarkedFlow (5-method
 *          reach from the legacy reader VM).
 *
 *   • INVERTED-PARALLEL-WITH-STRANGLER-FIG-AND-DUAL-CITATION-CORRECTION — rework counterparts at
 *     :domain/repository/ as a 3-interface ISP-split (LibraryRepository for membership ops,
 *     ChapterReadRepository for chapter read/bookmark ops, ChapterDownloadRepository for download
 *     ops). The legacy class is NOT pure cell-of-truth — the rework :data impls each reach a
 *     specific subset of the legacy surface AND reach the DAOs directly for new operations. The
 *     class-level KDoc Task-#438 audit-trail postscript (lines 83-98) explicitly corrects the §383
 *     and §404 cited site-list snapshots: post-§437 the 6→5 LibraryRepository-typed injection
 *     count is CoroutineDownloadRepositoryImpl + ChapterDownloadService + LibraryRefreshWorker +
 *     ChapterNotificationHelper + SharedChaptersViewModel + ReaderViewModel. The retire decisions
 *     in §383/§404 remain correct regardless of which sites have since dropped out — only the
 *     cited site-list snapshots were stale.
 *
 *   • TASK-383-COMPONENTPRUNE-LINEAGE-PRESERVED — the 24-line KDoc block (lines 29-52) documents
 *     Task #383's removal of 4 orphan facade methods (updateManga, searchSavedManga,
 *     updateChapterLastReadDate, updateChapterLocalPathsByUrl) + 3 coupled DAO drops
 *     (MangaDao.searchMangaByTitle, ChapterDao.updateChapterLastReadDate,
 *     ChapterDao.updateChapterLocalPathsByUrl). Methodologically-important: the audit explicitly
 *     enumerates the 8 LibraryRepository-typed constructor-injection sites at audit time, and
 *     the post-§402 8→7 narrowing is noted in-place without re-running the audit. PRESERVE —
 *     load-bearing componentprune audit record per §253.
 *
 *   • TASK-404-COMPONENTPRUNE-LINEAGE-PRESERVED — the 28-line KDoc block (lines 54-81) documents
 *     Task #404's removal of 3 additional facade methods (isMangaExists, getIdByApiTitle,
 *     getIdByUrl) + 2 coupled DAO drops (MangaDao.isMangaSaved, MangaDao.getIdByUrl) + 1
 *     deliberate non-drop (MangaDao.getIdByApiAndTitle — STAYS LIVE because :data
 *     LibraryRepositoryImpl reaches it directly at 5 sites). PRESERVE — load-bearing
 *     componentprune audit record per §253.
 *
 *   • TASK-438-AUDIT-TRAIL-POSTSCRIPT-PRESERVED — the 16-line KDoc block (lines 83-98) is a
 *     prior §253 audit-trail postscript correcting the §383/§404 cited site-lists post-§437
 *     LibraryDetailsViewModel retire. PRESERVE — this is the documented pattern of correcting
 *     stale snapshots without rewriting the original audit prose. The new postscript below
 *     INHERITS this correction (the 6→5 narrowing stands).
 *
 *   • KDOC-MIGRATION-NOTES-LOAD-BEARING — the 8-line KDoc block (lines 21-28) is a Phase 8.13
 *     batch A migration record covering Hilt-drop + java.util.Log unused-import-drop +
 *     System.currentTimeMillis→Clock.System.now adaptation. PRESERVE — load-bearing port-lineage
 *     prose with no forward-work pointers.
 *
 *   • DEFAULT-ARG-CLOCK-OBSERVABLE — updateLastOpenTimestamp's `timestamp` default
 *     `Clock.System.now().toEpochMilliseconds()` is evaluated at call-site (Kotlin default-arg
 *     semantics — re-evaluated per call). Matches the HistoryRepository sibling 381's
 *     updateHistoryItem.lastReadDate default pattern. DO NOT lift to a constructor-time val
 *     during cleanup.
 *
 *   • DELETE-DOWNLOADED-CHAPTERS-FANOUT — the deleteDownloadedChapters(set) function at lines
 *     184-196 is a 2-step ordered cascade: (1) batch-mark not-downloaded in the DB via
 *     markChaptersNotDownloaded(ids), (2) parallel-delete the file-system rows via
 *     coroutineScope + async + awaitAll. DO NOT collapse to sequential during cleanup — the
 *     fileService.deleteChapterFiles per-chapter call is the slow path; parallelism keeps the
 *     UI responsive on bulk-delete.
 *
 *   • UPDATEMANGAIMAGEURLEVERYWHERE-CROSS-DAO-FANOUT — updateMangaImageUrlEverywhere(mangaId,
 *     newImageUrl) writes to THREE DAOs (mangaDao.updateManga via getMangaById copy,
 *     notificationDao.updateMangaImageUrl, historyDao.updateMangaImageUrl) within a single
 *     platformIoDispatcher withContext block. DO NOT split into 3 separate suspend calls during
 *     cleanup — the cross-DAO fanout is the wire-shape contract (atomic cover-image refresh
 *     across all 3 storage layers).
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 17 imports: 2 kotlin.time (Clock + ExperimentalTime) + 6
 *     kotlinx (coroutines async/awaitAll/coroutineScope/withContext + flow Flow + flow.map) + 1
 *     core.concurrency.platformIoDispatcher + 5 data.local.dao (ChapterDao + HistoryDao + LibraryDeo +
 *     MangaDao + NotificationDao) + 2 data.local.entity (SavedChapterEntity + SavedMangaEntity)
 *     + 1 domain.service.FileService. All LIVE.
 *
 * --------------------------------------------------------------------------------------------
 * Cross-cluster cluster209 CLOSER register (cumulative across leaves 1-2):
 *
 *   • Cluster209 cohort scoped 2 leaves across 2 single-leaf .../presentation/features/ domain
 *     subdirs: repo_settings/domain/ (1-of-2 closing — SourceState already swept in cluster208)
 *     + library/domain/ (1-of-1). All 2 leaves swept in this commit. Cumulative §253-postscript
 *     count after commit = 110 (was 108 post-cluster208).
 *
 *   • Naming-axis posture across cluster209 cohort (2 leaves):
 *       - SourcesRepository (sibling 384) — INVERTED-PARALLEL-WITH-STRANGLER-FIG-AND-CROSS-LAYER-
 *         DEPENDENCY: rework :data wraps; legacy class stays as cell-of-truth (init-block
 *         saveSources + Coil interceptor findRepoByHost reach pin the legacy class LIVE);
 *         cross-layer reach into legacy :shared/sources_repositry/ subtree.
 *       - LibraryRepository (sibling 385 — this leaf, cluster209 CLOSER) — INVERTED-PARALLEL-
 *         WITH-STRANGLER-FIG-AND-DUAL-CITATION-CORRECTION: rework :domain 3-interface ISP-split
 *         (LibraryRepository + ChapterReadRepository + ChapterDownloadRepository); legacy class
 *         stays as cell-of-truth wrapped by all 3 rework impls; 2 prior componentprune audits
 *         (§383 + §404) + 1 audit-trail correction (§438) preserved verbatim per §253.
 *     POSTURE-MIX — both leaves are STRANGLER-FIG-WRAPPED variants. Cluster209 demonstrates the
 *     LEGACY-AS-CELL-OF-TRUTH posture continued from cluster208 (4-of-5 leaves were also
 *     STRANGLER-FIG-WRAPPED). The naming-axis HIGHLIGHT for cluster209 is the EXTREME complexity
 *     of LibraryRepository (21 LIVE public members + 6 ctor deps + 3 prior componentprune passes)
 *     and the cross-layer-bridging role of SourcesRepository (cell-of-truth for the OUT-OF-SCOPE
 *     legacy sources_repositry/ subtree).
 *
 *   • Subdir closer status (2-subdir-closer commit):
 *       - repo_settings/domain/ — FULLY SWEPT (2-of-2, post-cluster209: SourceState cluster208 +
 *         SourcesRepository cluster209).
 *       - library/domain/ — FULLY SWEPT (1-of-1, post-cluster209).
 *     Two subdir-closers in one commit. Combined with cluster208's 4 subdir-closers, the
 *     :shared/.../presentation/features/ domain/ tier is now FULLY SWEPT across all 6 subdirs
 *     (settings + statistics + history + notifications + repo_settings + library) — closes the
 *     entire presentation/features/(per-feature)/domain/ tier in 2 consecutive clusters (cluster208 +
 *     cluster209). 6-of-6 subdirs FULLY SWEPT.
 *
 *   • Cluster210 scout: remaining :shared/.../presentation/features/ unswept prose-bearing
 *     directories include the per-feature ui/viewmodel/ subdirs (already partially swept in
 *     earlier clusters — e.g. cluster102 history/viewmodel + cluster103 statistics/viewmodel +
 *     cluster206 reader/viewmodel). Need fresh scout pass to identify remaining unswept VM-tier
 *     leaves and any ui/components/ stragglers. The :shared/.../presentation/ State / Intent /
 *     Effect / VM tiers were largely covered by cluster31-34 sweeps. The legacy :shared/sources_
 *     repositry/ per-language tier remains OUT OF SCOPE per the user pivot ("ignore the
 *     sources_repositry leave it like it was").
 *
 *   • Forward-pointer maintenance — three blocked task references remain on the §253 ledger:
 *       - Task #217 (Phase 6.4.x.bookmark) — BLOCKED, no §253-related work.
 *       - Task #422 (Phase 9.x.coreshadow.retire) — BLOCKED pending user direction.
 *       - Future Phase 9.x.getdefaultfeatures.retire — flagged on cluster204 sibling 368.
 *     None of cluster209's 2 leaves add new blocked-task references — both classifications are
 *     STRANGLER-FIG-WRAPPED-LIVE rather than retire-pending.
 */

