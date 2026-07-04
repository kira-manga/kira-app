package me.manga.kira.presentation.features.download.domain.clean

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.util.data_classes.HandelDataClasses.toChapterDownloadEntity
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.presentation.features.download.domain.ChapterDownloadService
import me.manga.kira.presentation.features.download.ui.test2.DownloadWorkerV2

/**
 * Phase 8.14 port of upstream `presentation/features/download/domain/clean/DownloadRepositoryImpl`.
 *
 * Changes vs. upstream:
 *  - Hilt `@Inject` + `@ApplicationContext` annotations stripped (Koin handles wiring).
 *  - `getWorkInfosForUniqueWorkLiveData(...).map(...).asFlow()` replaced with the native
 *    Flow API `getWorkInfosForUniqueWorkFlow(...)` (WorkManager 2.10+). No
 *    `androidx.lifecycle.asFlow` import needed.
 *  - `R.string.cancelled_by_user` lookup uses `context.getString(...)`, identical to source.
 *
 * The `Application` constructor parameter from upstream is dropped — only `Context` is
 * needed (for `getString`); `WorkManager` is provided directly.
 *
 * Phase 9.x.downloadrepository.componentprune (Task #398): dropped 4 `override` impls
 * (`queuedCount`, `observeAllDownloadsPaged`, `observeDownloadsByStatePaged`,
 * `clearFailedAndQueued`) — interface methods retired in the same slice; see
 * `DownloadRepository.kt` audit header. `androidx.paging.PagingData` and `DownloadingState`
 * imports dropped — only the retired impls used them.
 *
 * Phase 9.x.downloadrepository.componentprune.cascade.interface (Task #440 slice A,
 * 2026-05-28): dropped 6 `override` impls (`observeRunningChapter`, `isDownloading`,
 * `queuedChapterIds`, `networkStatus`, `enqueueChaptersDownload`, `cancelAllDownloads`) —
 * interface methods retired in the same slice; see `DownloadRepository.kt` audit-trail
 * postscript. `WorkInfo`, `ConnectivityObserver.Status`, `kotlinx.coroutines.flow.map`, and
 * `toChapterDownloadEntities` imports dropped — only the retired impls used them.
 *
 * Phase 9.x.downloadrepository.componentprune.cascade.ctordep (Task #440 slice B,
 * 2026-05-28): dropped the `connectivityObserver: ConnectivityObserver` ctor parameter held
 * coupled-dead in slice A (its sole caller was `networkStatus`, retired in slice A). Matching
 * Koin ctor-arg drop in `PlatformModule.android.kt` lands in the same commit alongside the
 * cross-platform `CoroutineDownloadRepositoryImpl` ctor + `PlatformModule.ios.kt` +
 * `PlatformModule.desktop.kt` ctor-arg drops. `ConnectivityObserver` import dropped — no
 * remaining usage. `@Suppress("UNUSED_PARAMETER")` removed.
 */
class DownloadRepositoryImpl(
    private val workManager: WorkManager,
    private val dao: ChapterDownloadDao,
    private val chapterDownloadService: ChapterDownloadService,
) : DownloadRepository {

    private companion object {
        private const val WORK_NAME = "mangaDownloadv2"
    }

    override fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>> = dao.observeAllDownloads()

    private fun enqueueRequest(policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) =
        OneTimeWorkRequestBuilder<DownloadWorkerV2>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
            .also { workManager.enqueueUniqueWork(WORK_NAME, policy, it) }

    override suspend fun enqueueChapterDownload(
        chapter: SavedChapterEntity,
        title: String,
        mangaApi: String,
    ) {
        // Dedup against an already-active row (2026-07 audit — mirrors the nonAndroid coroutine
        // sibling): the DAO inserts with OnConflictStrategy.REPLACE on the unique chapterId index,
        // so an unconditional re-insert of a chapter currently QUEUED / RUNNING / COMPRESSING
        // rewrites its row to QUEUED/progress=0 mid-download. No-op in that case; only absent rows
        // and terminal SUCCESS / FAILED rows proceed (the retry path).
        val existing = dao.getDownloadByChapter(chapter.id)?.state
        if (DownloadRecovery.isActiveDownloadState(existing)) {
            return
        }
        val id = dao.insert(chapter.toChapterDownloadEntity(apiName = mangaApi, title = title))
        // Source guards `?.let { enqueueRequest() }` against the entity not being inserted (Long?).
        // The KMP DAO returns a non-nullable Long, so we always enqueue on success.
        // APPEND_OR_REPLACE (not the KEEP default): a prior unique-work run that already passed its
        // final getNextQueuedChapter()==null can be terminal-but-not-yet-cleared when this insert
        // lands; with KEEP this enqueue would be DROPPED and the new row would sit QUEUED with no
        // worker. Same race reconcileInterruptedDownloads() guards against.
        if (id >= 0L) enqueueRequest(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    override suspend fun deleteDownload(chapterId: Long) {
        dao.deleteByChapterId(chapterId)
    }

    override suspend fun onCancel(chapterId: Long) {
        // Sentinel, not the localized string (2026-07 audit): :ui renders its own localized
        // "cancelled by user" label only when errorMsg matches
        // DownloadedChapter.CANCELLED_BY_USER_SENTINEL — a resolved string was frozen at
        // write-time locale and never matched, so the row showed raw text instead.
        dao.updateFailure(chapterId, DownloadedChapter.CANCELLED_BY_USER_SENTINEL)
    }

    override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) {
        workManager.cancelUniqueWork(WORK_NAME)
        chapterDownloadService.deleteChapterFiles(mangaId, chapterId)
        onCancel(chapterId)
        enqueueRequest()
    }

    // Re-added (DOWNLOAD "cancel-all marks rows failed" backlog item, 2026-06-01). Mirrors the
    // native DownloadRepositoryImpl.cancelAllDownloads() verbatim: mark every in-flight row
    // FAILED in the DB, then cancel the unique WorkManager job.
    override suspend fun cancelAllDownloads() {
        dao.markAllRunningOrQueuedAsFailed()
        workManager.cancelUniqueWork(WORK_NAME)
    }

    // Restart-freeze fix (2026-06-02). Reset rows orphaned in RUNNING / COMPRESSING by a previous
    // process back to QUEUED, then re-post the unique download work so WorkManager drains the
    // now-QUEUED rows. Uses APPEND_OR_REPLACE (NOT KEEP): WorkManager persists work specs across
    // process death, so after a kill the "mangaDownloadv2" unique work can still be ENQUEUED/RUNNING
    // from its auto-rescheduled prior run. With KEEP this reconcile enqueue would be DROPPED, and if
    // that prior run already passed its final getNextQueuedChapter()==null it finishes without ever
    // seeing the row we just re-QUEUED — re-freezing the exact download BUG 1 fixes. APPEND_OR_REPLACE
    // guarantees a drain pass runs AFTER any current work (appended), and REPLACEs a terminal/failed
    // chain, without cancelling a legitimately in-flight download (which REPLACE would).
    override suspend fun reconcileInterruptedDownloads() {
        dao.reEnqueueInterrupted()
        enqueueRequest(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster255.staleKdocSweep.cascade, Task #712, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster255 leaf 1/2 OPENER — :shared/androidMain/presentation/features/download/domain/
 * clean/ legacy-tier 2-actual structural-divergence fan opens. Sibling 424 of the cluster57+
 * §253 wave. Cumulative §253-postscript count = 154 leaves with this commit.
 *
 * Cluster254-CLOSER stale prediction acknowledgement: cluster254's CLOSER postscript predicted
 * ScreenshotProvider (cluster220) OR AnalyticsClient (cluster223) as cluster255 candidates,
 * both already pre-swept. The actual cluster255 target — DownloadRepository 2-actual
 * structural-divergence fan via the nonAndroidMain source-set — was not listed because the
 * cluster254 scouting heuristic enumerated only :shared/(android/ios/desktop)Main/core/[sub-
 * tier] 3-actual fans, missing the :shared/(androidMain,nonAndroidMain)/presentation/features/
 * download/domain/clean/ 2-actual structural-divergence shape. Per audit-trail-preservation
 * convention, cluster254's stale prediction is NOT amended — the correction lives here.
 *
 * File-shape note: 98-line file — `DownloadRepositoryImpl` concrete class (NOT actual —
 * implements commonMain `DownloadRepository` INTERFACE, not expect-class) with 4 ctor-args
 * (context + workManager + dao + chapterDownloadService) + 4 fun overrides (observeAllDownloads
 * + enqueueChapterDownload + deleteDownload + onCancel + cancelARunningChapter) + 1 private
 * enqueueRequest helper + companion (WORK_NAME) + 33-line class-level KDoc prose containing
 * 4 historical entries (Phase 8.14 port + Phase 9.x.downloadrepository.componentprune Task
 * #398 + Phase 9.x.downloadrepository.componentprune.cascade.interface Task #440 slice A +
 * Phase 9.x.downloadrepository.componentprune.cascade.ctordep Task #440 slice B).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE + FULFILLED-CONTRACT — concrete impl of commonMain `DownloadRepository`
 *     interface. Twin-fulfilled by `CoroutineDownloadRepositoryImpl` (sibling 425, this
 *     cluster, nonAndroidMain leaf — iOS+Desktop shared). Wired via :shared
 *     PlatformModule.android.kt Koin binding. Reachable via `DownloadsRepositoryImpl` (NOTE:
 *     different file — :data/repository/DownloadsRepositoryImpl.kt is the rework-tier
 *     :data adapter; this :shared legacy-tier impl is the strangler-fig backing store for
 *     the rework adapter's strangler-fig reach into :shared).
 *
 *   • STRUCTURAL-DIVERGENCE-2-ACTUAL-FAN — distinct from the 3-actual platform-fan shape
 *     swept by clusters 214-253. The DownloadRepository interface is fulfilled by 2 concrete
 *     classes (NOT expect/actual) split across :shared/androidMain (this file, WorkManager-
 *     backed) + :shared/nonAndroidMain (CoroutineDownloadRepositoryImpl, ktor+okio coroutine-
 *     queue-backed). The nonAndroidMain source set is the iOS+Desktop sibling-shared compile
 *     target — the first appearance of this source-set pattern in the §253 wave. Twin-actual
 *     fan is structurally novel: most platform-actual fans in this project are 3-actual
 *     (android+ios+desktop) but here the iOS+Desktop pair shares a single implementation via
 *     nonAndroidMain because WorkManager has no JVM/Native equivalent.
 *
 *   • KDOC-DESIGN-RATIONALE-LOAD-BEARING — 33-line KDoc prose documents:
 *     (a) the Phase 8.14 upstream-port-of-record ("Phase 8.14 port of upstream presentation/
 *     features/download/domain/clean/DownloadRepositoryImpl");
 *     (b) the 3 upstream-divergences ("Hilt @Inject + @ApplicationContext annotations
 *     stripped (Koin handles wiring)" + "getWorkInfosForUniqueWorkLiveData(...).map(...)
 *     .asFlow() replaced with the native Flow API getWorkInfosForUniqueWorkFlow(...)
 *     (WorkManager 2.10+)" + "R.string.cancelled_by_user lookup uses context.getString(...),
 *     identical to source");
 *     (c) the constructor-shape divergence rationale ("The Application constructor parameter
 *     from upstream is dropped — only Context is needed (for getString); WorkManager is
 *     provided directly");
 *     (d) Phase 9.x.downloadrepository.componentprune Task #398 4-override-drop history
 *     (queuedCount + observeAllDownloadsPaged + observeDownloadsByStatePaged +
 *     clearFailedAndQueued + 2 imports dropped);
 *     (e) Phase 9.x.downloadrepository.componentprune.cascade.interface Task #440 slice A
 *     6-override-drop history (observeRunningChapter + isDownloading + queuedChapterIds +
 *     networkStatus + enqueueChaptersDownload + cancelAllDownloads + 4 imports dropped);
 *     (f) Phase 9.x.downloadrepository.componentprune.cascade.ctordep Task #440 slice B
 *     ctor-arg-drop history (connectivityObserver: ConnectivityObserver dropped + Koin ctor-
 *     arg drop in PlatformModule.android.kt + ConnectivityObserver import dropped). PRESERVE
 *     — design-intent doc + 4-historical-audit-entry chain; load-bearing for any future audit
 *     wishing to reconstruct the cumulative trim history of the DownloadRepository interface
 *     surface area.
 *
 *   • WORKMANAGER-PUSH-API-CONTRAST-WITH-SIBLING-LIVE — Android leaf uses WorkManager
 *     OneTimeWorkRequest + ExistingWorkPolicy.KEEP + NetworkType.CONNECTED constraint +
 *     enqueueUniqueWork(WORK_NAME="mangaDownloadv2") for queue management + DownloadWorkerV2
 *     handles the actual download work in a separate worker class. Contrast with sibling
 *     425 (nonAndroidMain) which uses a coroutine-channel-based worker loop in-process
 *     (Channel<Unit>(UNLIMITED) wake-ups + Mutex-guarded activeJob + workerLoop coroutine
 *     launched on applicationScope) because no WorkManager equivalent exists on JVM/Native.
 *     Both contracts deliver the same 5-fun observable+mutating surface but via radically
 *     different scheduling primitives. LIVE — load-bearing for the Android-side queue-
 *     scheduling-during-Doze-mode-survival behavior.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 7 imports: android.content.Context + androidx.work
 *     (Constraints + ExistingWorkPolicy + NetworkType + OneTimeWorkRequestBuilder +
 *     WorkManager) + kotlinx.coroutines.flow.Flow + 5 me.manga.kira.* (HandelDataClasses
 *     .toChapterDownloadEntity + ChapterDownloadDao + ChapterDownloadEntity + SavedChapter
 *     Entity + ChapterDownloadService + DownloadWorkerV2). LIVE — Android-platform-only SPI;
 *     androidx.work is the load-bearing scheduling primitive; no Hilt annotations
 *     (Koin-migration verified by §253 postscript design-rationale audit).
 *
 *   • CLUSTER255 OPENER REGISTER — 2-leaf :shared (legacy-tier) 2-actual structural-
 *     divergence fan-out for the commonMain `DownloadRepository` interface OPENS. CLUSTER255
 *     CLOSER at sibling 425 (CoroutineDownloadRepositoryImpl.kt, nonAndroidMain). Posture-
 *     mix register: 2 LIVE-NOT-STALE + FULFILLED-CONTRACT (both actuals deliver the same
 *     interface contract — 5 fun overrides observeAllDownloads + enqueueChapterDownload +
 *     deleteDownload + onCancel + cancelARunningChapter — via 2 different platform-native
 *     mechanisms: Android WorkManager + DownloadWorkerV2 worker-class push-API,
 *     iOS+Desktop-shared coroutine-channel-based in-process worker loop).
 *
 *   • CLUSTER256 PIVOT PREDICTION — remaining un-swept :shared platform-actual candidates
 *     limited to: (a) HighQualitySkiaImageDecoder.kt (nonAndroidMain image-decoder, no
 *     Android counterpart — Coil ImageDecoderRegistry registers it on iOS+Desktop only since
 *     Android uses BitmapFactory directly); (b) CbzManager.kt + OptimizedCbzManager.kt
 *     (androidMain-only utility helpers, NOT expect/actual fans). The :shared platform-
 *     actual subtree §253 sweep is approaching saturation: cluster256 (HighQualitySkia
 *     ImageDecoder.kt nonAndroidMain solo-leaf) would close the nonAndroidMain source-set
 *     coverage; cluster257+ would scout the androidMain-only utility solo-leaves
 *     (CbzManager + OptimizedCbzManager) which are NOT structurally fan-shaped.
 */

