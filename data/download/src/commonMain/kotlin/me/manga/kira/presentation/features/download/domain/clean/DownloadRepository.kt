package me.manga.kira.presentation.features.download.domain.clean

import kotlinx.coroutines.flow.Flow
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity

// Migration notes (Phase 8.13 batch C):
//   - Pure interface, no annotations to strip (source had none).
//   - The Android impl (WorkManager-backed) stays in androidMain because PagingSource + WorkManager
//     are Android-only. See DownloadRepositoryImpl in androidMain (Phase 8.13 batch C).
//
// Phase 9.x.downloadrepository.componentprune (Task #398): dropped 4 independently-orphan
// members surfaced by an exhaustive 3-pass reacher-chain audit (receiver-anchored
// `downloadRepository.X(` / `repository.X(` / `repo.X(` + bare `\bX\b` word-boundary + `::X`
// method-ref) chained through `DownloadViewModelv2` (the sole legacy VM consumer wired at
// `App.kt:283-284` and threaded into `LibraryMangaScreenRoute`) to confirm zero source-tree
// reachers anywhere. The slice also dropped the coupled-dead VM members + 2 DAO callees in the
// same commit (see `DownloadViewModelv2.kt`, `DownloadRepositoryImpl.kt`,
// `CoroutineDownloadRepositoryImpl.kt`, and `ChapterDownloadDao.kt` audit headers).
// Removed (independent orphans):
//   - `queuedCount(): Flow<Int>` — sole VM reach `DownloadViewModelv2.queuedCount` had zero
//     external consumers. The queued-count UI badge that was wired in the legacy
//     `:shared/.../download/ui` screen consumed it via the now-retired legacy DownloadsScreen
//     (Phase 9.x.downloads.legacyui.retire, Task #352). The rework `:ui DownloadsScreen` does
//     not surface a queued-count badge.
//   - `observeAllDownloadsPaged(): Flow<PagingData<ChapterDownloadEntity>>` — sole VM reach
//     `DownloadViewModelv2.downloadsPaged` had zero external consumers. The rework's
//     `DownloadsRepository.observeAll()` (`:domain`) returns `Flow<List<DownloadedChapter>>`
//     directly — the paging variant was explicitly deferred (see `DownloadsRepository.kt:44-51`
//     KDoc: "Paging deferred ... legacy `:ui` post-Phase 8.13 batch C migration does NOT consume
//     the paged variant").
//   - `observeDownloadsByStatePaged(states): Flow<PagingData<ChapterDownloadEntity>>` — sole VM
//     reach `DownloadViewModelv2.getDownloadsByState` had zero external consumers. Same paged-
//     deferral rationale as the all-downloads paged variant.
//   - `clearFailedAndQueued()` — sole VM reach `DownloadViewModelv2.clearDownloads` had zero
//     external consumers. The rework `DownloadsActionRepository` exposes per-row mutations
//     (cancel/retry/delete) but no bulk clear-failed-and-queued action — the rework UI surfaces
//     per-row dismiss instead. KDoc reference in `DownloadsActionRepository.kt:128` is a
//     stale historical mention (will be addressed in a follow-up staleKdocSweep slice).
//
// Phase 9.x.downloadrepository.componentprune.cascade.interface (Task #440, 2026-05-28): after
// `DownloadViewModelv2` itself was retired (Phase 9.x.downloadvmv2.retire, Task #439), the
// 7 members that were preserved as LIVE in §398 on the basis of "VM X → LibraryMangaScreenRoute"
// re-audited: only 5 retain reachers (via the rework `:data` strangler-fig in
// `DownloadsRepositoryImpl.kt` + `DownloadsActionRepositoryImpl.kt`). The other 6 became
// cascade-orphan and are dropped here. Audit-trail preserved per §253: the §398 verdicts above
// remain unchanged; this postscript documents the post-VM-retire revision.
// Removed (cascade-orphan after Task #439):
//   - `observeRunningChapter()` — §398 reach chain was VM `runningChapter` →
//     `LibraryMangaScreenRoute`; VM deleted in §439, `LibraryMangaScreenRoute` deleted in §435.
//     Rework `:data DownloadsRepositoryImpl` reaches only `observeAllDownloads()`.
//   - `isDownloading()` — §398 reach chain was VM `isDownloading` → `LibraryMangaScreenRoute`;
//     both ends retired. Rework `:ui DownloadsScreen` does not surface a global running flag.
//   - `queuedChapterIds()` — §398 reach chain was VM `queuedChapterIds` → `LibraryMangaScreenRoute`;
//     both ends retired. Rework Library card binds its own download-status flow per row.
//   - `networkStatus()` — §398 reach chain was VM `networkAvailable` → `LibraryMangaScreenRoute`;
//     both ends retired. The platform `ConnectivityObserver` is still LIVE — observed directly
//     by other rework VMs (e.g. `ReaderViewModel`) — but the legacy `DownloadRepository`
//     re-export becomes a coupled-dead re-export and is dropped.
//   - `enqueueChaptersDownload(...)` — §398 reach chain was VM `downloadChapters` →
//     `LibraryMangaScreenRoute`; both ends retired. The rework `DownloadsActionRepository`
//     enqueues one chapter per `enqueueDownload(chapterId, ...)` call — the bulk path was the
//     legacy "download N selected" UI affordance that is not present in the rework surface.
//   - `cancelAllDownloads()` — §398 reach chain was VM `cancelDownloads` → `LibraryMangaScreenRoute`;
//     both ends retired. The rework `DownloadsActionRepository` exposes per-row `cancelDownload`
//     + `cancelRunningDownload`; bulk-cancel is not surfaced in the rework UI.
// LIVE members preserved (verified by exhaustive reacher-chain audit through the rework
// `:data` strangler-fig classes `DownloadsRepositoryImpl` + `DownloadsActionRepositoryImpl`):
//   - `observeAllDownloads()` — `DownloadsRepositoryImpl.kt:77` (.map -> domain projection).
//   - `enqueueChapterDownload(...)` — `DownloadsActionRepositoryImpl.kt:117, 127`
//     (enqueue + retry both single-chapter).
//   - `deleteDownload(chapterId)` — `DownloadsActionRepositoryImpl.kt:142`.
//   - `onCancel(chapterId)` — `DownloadsActionRepositoryImpl.kt:135`.
//   - `cancelARunningChapter(chapterId, mangaId)` — `DownloadsActionRepositoryImpl.kt:139`.
//
// `ConnectivityObserver.Status` import dropped — only `networkStatus` used it.
interface DownloadRepository {

    fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>>
    suspend fun enqueueChapterDownload(chapter: SavedChapterEntity, title: String, mangaApi: String)
    suspend fun deleteDownload(chapterId: Long)

    suspend fun onCancel(chapterId: Long)

    suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long)

    // Re-added (DOWNLOAD "cancel-all marks rows failed" backlog item, 2026-06-01): cancel the
    // whole download queue. Backs the notification "Cancel all" action wired in the :app
    // DownloadCancelReceiver. Matches the native source-of-truth semantics: flip every
    // RUNNING / QUEUED / COMPRESSING row to FAILED via the DAO, then cancel the unique
    // WorkManager job on Android (the nonAndroid coroutine impl cancels its active job and
    // drains the queue instead). Originally pruned by Task #440 slice A; restored because the
    // receiver needs the DB "mark failed" half, not just the WorkManager cancel.
    suspend fun cancelAllDownloads()

    // Startup reconciliation of interrupted downloads (restart-freeze fix, 2026-06-02). Reset every
    // row left RUNNING / COMPRESSING by a previous process back to QUEUED (DAO reEnqueueInterrupted),
    // then re-trigger the engine so those rows actually drain: Android re-posts the unique
    // WorkManager job (KEEP, no duplicate); the iOS/Desktop coroutine impl sends a wake-up to its
    // in-process worker loop. Without this an interrupted download stays stuck "downloading" forever
    // because the worker only pulls QUEUED rows (getNextQueuedChapter). Idempotent — safe to call
    // once per launch. Invoked from the App.kt startup seam via the rework
    // DownloadsActionRepository.reconcileInterrupted() so all platforms reconcile identically.
    suspend fun reconcileInterruptedDownloads()
}

/*
 * Audit-trail postscript (Phase 9.x.cluster211.staleKdocSweep.cascade, Task #667, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster211 leaf 3/3 — :shared/download/domain/clean/ tier SINGLE-LEAF, sibling 392 CLUSTER211
 * CLOSER. Cumulative §253-postscript count = 117 leaves with this commit.
 *
 * File-shape note: 86-line interface — `DownloadRepository` with 5 LIVE members post-Task-#440
 * cascade-prune (observeAllDownloads + enqueueChapterDownload + deleteDownload + onCancel +
 * cancelARunningChapter). 69-line class-level prose carrying two stacked componentprune
 * audit headers: Phase 9.x.downloadrepository.componentprune (Task #398) + Phase
 * 9.x.downloadrepository.componentprune.cascade.interface (Task #440).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — narrow-purpose download-control INTERFACE — direct reach via rework
 *     :data strangler-fig (verified at Task #440 audit):
 *       1. DownloadsRepositoryImpl (:data/repository/) — observeAllDownloads().map projection
 *          to rework :domain DownloadedChapter list.
 *       2. DownloadsActionRepositoryImpl (:data/repository/) — calls enqueueChapterDownload
 *          (single-chapter enqueue + retry path), deleteDownload, onCancel, and
 *          cancelARunningChapter for per-row mutations on the rework Downloads screen.
 *
 *   • INVERTED-PARALLEL-WITH-STRANGLER-FIG-AND-CASCADE-PRUNE — rework :ui DownloadsScreen
 *     consumes a domain-shaped DownloadedChapter via the rework :data strangler-fig. The
 *     legacy DownloadRepository interface survives narrowed-down to 5 LIVE members; the
 *     historical 15-member surface (queuedCount + observeAllDownloadsPaged +
 *     observeDownloadsByStatePaged + clearFailedAndQueued + observeRunningChapter +
 *     isDownloading + queuedChapterIds + networkStatus + enqueueChaptersDownload +
 *     cancelAllDownloads) was pruned across Task #398 (4 independent orphans) + Task #440
 *     (6 cascade-orphans after the legacy DownloadViewModelv2 retire in Task #439). Net
 *     post-prune: 5 of 15 LIVE = 33 percent surface-retention.
 *
 *   • TASK-398-AND-440-COMPONENTPRUNE-LINEAGE-PRESERVED — the 69-line stacked componentprune
 *     prose (lines 7-76) documents both pruning passes' verdicts: §398 (queued-count UI
 *     badge + 2 paged-deferral variants + bulk-clear) and §440 (5 cascade-orphan members
 *     post-VM-retire). PRESERVE — load-bearing componentprune audit per §253; future
 *     re-reachers can grep through the 10-symbol orphan-name manifest to confirm zero
 *     re-introduction.
 *
 *   • LIVE-MEMBERS-MANIFEST-LOAD-BEARING — the 5-name LIVE-members manifest (lines 68-74)
 *     pairs each surviving interface member with its exact line+column reacher in the
 *     rework :data strangler-fig classes. PRESERVE — this is the receipt-of-audit for the
 *     post-prune surface; deleting would force future audits to re-derive the reach chains
 *     from scratch.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 3 imports: kotlinx.coroutines.flow.Flow + 2 :data Room
 *     entities (ChapterDownloadEntity + SavedChapterEntity). All LIVE post-prune.
 *
 * Cross-cluster cluster211 CLOSER register:
 *
 *   • Tier-completion declaration: this 3-leaf batch closes the remaining unswept
 *     :shared/.../presentation/features/(per-feature)/ ad-hoc-tier leaves outside the
 *     {domain,ui/viewmodel}/ subdir-tier swept across cluster208+cluster209+cluster210.
 *     Specifically: :shared/repo_settings/data/ + :shared/library/data/ + :shared/download/
 *     domain/clean/ now all 100% §253-postscript-covered.
 *
 *   • Combined :shared/.../presentation/features/(per-feature)/ tier-totals across the
 *     5-cluster cumulative sweep: cluster207 (data/-tier 5-leaf) + cluster208 (domain/-tier
 *     5-leaf) + cluster209 (domain/-tier closing 2-leaf) + cluster210 (ui/viewmodel/-tier
 *     4-leaf) + cluster211 (ad-hoc 3-leaf) = 19 §253-postscript-bearing leaves spanning the
 *     entire :shared/.../features/(per-feature)/ subtree below the previously-retired UI
 *     screens/components tier.
 *
 *   • POSTURE-MIX across cluster211 3-leaf batch: 2 STRANGLER-FIG (Source.kt for SourceState
 *     serialization wire-format + MangaChapterMetrics.kt for Room aggregate projection) +
 *     1 STRANGLER-FIG-WITH-CASCADE-PRUNE (this file). All 3 leaves are :data-layer-reached
 *     LIVE-NOT-STALE narrowed-down ports of the original legacy surface.
 *
 *   • Wave-65 componentprune-lineage-retention across cluster211 = 1-of-3 (this file
 *     carries 2 stacked componentprune headers spanning Task #398 + Task #440). Source.kt
 *     carries a single Migration-note line. MangaChapterMetrics.kt carries Bug-3-incident
 *     prose. The cluster211 batch demonstrates 3 dominant audit-prose retention postures:
 *     (a) componentprune-multi-pass (DownloadRepository), (b) migration-1-1-port (Source),
 *     (c) incident-workaround (MangaChapterMetrics).
 *
 *   • Blocked-task forward-pointers carried unchanged: Task #217 (Phase 6.4.x.bookmark) +
 *     Task #422 (Phase 9.x.coreshadow.retire). Neither blocker resolved by this slice; both
 *     remain pending-with-user-decision.
 */
