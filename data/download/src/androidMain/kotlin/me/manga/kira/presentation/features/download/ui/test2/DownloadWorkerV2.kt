package me.manga.kira.presentation.features.download.ui.test2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.manga.kira.core.states.State
import me.manga.kira.core.util.data_classes.HandelDataClasses.toChapterEntity
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.presentation.features.download.data.DownloadState
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.filesystem.folderSize
import me.manga.kira.presentation.features.download.domain.ChapterDownloadService
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.data.download.R
import me.manga.kira.sources_repositry.BaseMangaRepository
import me.manga.kira.sources_repositry.ar.promanga.ProchanRepository
import org.koin.core.context.GlobalContext

/**
 * Phase 8.14 port of upstream `presentation/features/download/ui/test2/DownloadWorkerV2`.
 *
 * Key migration notes:
 *  - Hilt `@HiltWorker` + `@AssistedInject` removed. `MyApp.onCreate` DOES install a
 *    `KoinWorkerFactory` via `workManagerFactory()` as of Phase 12.x bootstrap (commit
 *    e8b4fa9). However, this worker is NOT bound via `workerOf(::DownloadWorkerV2)`
 *    yet — it is still registered through the **default no-arg WorkManager factory**:
 *    the `(Context, WorkerParameters)` constructor is the one WorkManager calls
 *    reflectively, and dependencies are resolved lazily through Koin's
 *    `GlobalContext.get()` in `doWork()`. The constructor-injection refactor (binding
 *    via `workerOf(::DownloadWorkerV2)` + dropping the `GlobalContext.get()` lookups
 *    in favour of `private val` fields) is tracked separately in `AUDIT_GOAL.md`
 *    Section 4 item #5 — not a runtime blocker; both paths produce the same dependency
 *    graph because the `KoinWorkerFactory` falls back to the default factory when no
 *    `workerOf` binding matches.
 *
 *  - Upstream calls `downloadRepo.downloadChapterFlowv2(chapter)` on the OLD non-clean
 *    `DownloadRepository` (216-line variant in `presentation/features/download/domain/`).
 *    That class has no other callers in the current KMP tree, so instead of resurrecting
 *    a single-consumer class as dead code, the `downloadChapterFlowv2` logic is inlined
 *    into [downloadChapterFlowV2] below. Same semantics, fewer files.
 *
 *  - `R.drawable.ic_launcher_foreground` (app-module drawable) replaced with the Android
 *    built-in `android.R.drawable.stat_sys_download` (the system "download" status icon)
 *    so the worker doesn't depend on app-module resources. `R.drawable.ic_cancel`
 *    replaced with [R.drawable.ic_download_cancel] in shared androidMain.
 *
 *  - All `R.string.*` references resolve to [me.manga.kira.shared.R.string] (declared in
 *    shared/src/androidMain/res/values/strings.xml).
 *
 *  - `DownloadCancelReceiver` lives in the `:app` module as a manifest-registered
 *    `BroadcastReceiver`. This worker emits the same `ACTION_CANCEL` / `ACTION_CANCEL_CHAPTER`
 *    intents as upstream; the receiver is live (see `DownloadCancelReceiver.kt`) and routes both
 *    actions through the legacy `DownloadRepository` resolved from Koin's `GlobalContext` —
 *    `ACTION_CANCEL` → `cancelAllDownloads()`, `ACTION_CANCEL_CHAPTER` → `cancelARunningChapter()`.
 */
class DownloadWorkerV2(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    // Dependencies resolved lazily via Koin GlobalContext (see class header).
    private val chapterDownloadDao: ChapterDownloadDao by lazy { koin.get() }
    private val chapterDao: ChapterDao by lazy { koin.get() }
    private val mangaDao: MangaDao by lazy { koin.get() }
    private val chapterDownloadService: ChapterDownloadService by lazy { koin.get() }
    private val sourcesRepository: SourcesRepository by lazy { koin.get() }
    // Sources Migration Phase 3: routes config-backed downloads through SourceRegistry (generic-
    // ONLY — the registry has no legacy fallback); null for non-config sources → legacy path
    // unchanged.
    private val chapterPageProvider: ChapterPageProvider by lazy { koin.get() }
    private val appFileSystem: AppFileSystem by lazy { koin.get() }

    private val koin get() = GlobalContext.get()

    private var lastNotifiedChapterId: Long? = null
    private val notificationLock = Mutex()
    private val notificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)!!
    }

    companion object {
        private const val TAG = "DownloadWorkerV2"

        private const val CHANNEL_ALL = "download_all_channel"
        private const val CHANNEL_CHAPTER = "download_chapter_channel"
        private const val CHANNEL_SUMMARY = "download_summary_channel"

        private const val NOTIF_ALL_ID = 1
        private const val NOTIF_SUMMARY_ID = 2
        private const val NOTIF_CHAPTER_BASE = 100

        const val ACTION_CANCEL = "me.manga.kira.ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_WORK_ID = "EXTRA_WORK_ID"

        const val ACTION_CANCEL_CHAPTER = "me.manga.kira.ACTION_CANCEL_CHAPTER_DOWNLOAD"
        const val EXTRA_CHAPTER_ID = "EXTRA_CHAPTER_ID"
        const val EXTRA_MANGA_ID = "EXTRA_MANGA_ID"

        @Volatile
        private var channelsCreated = false
        private val channelLock = Mutex()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo()

    override suspend fun doWork(): Result = coroutineScope {
        setupChannelsSafely()

        // The overall foreground notification content is static, so post it once at worker start
        // instead of rebuilding and re-posting it through setForegroundAsync IPC on every collected
        // state and inside every per-chapter notification update.
        updateOverallNotification()

        var currentChapter: ChapterDownloadEntity? = null

        try {
            while (true) {
                val chapter = chapterDownloadDao.getNextQueuedChapter() ?: break

                currentChapter = chapter

                try {
                    processChapter(chapter)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // Per-chapter isolation (2026-07 audit): a failure escaping the per-state
                    // handlers (repo.initSite()/DAO throw) used to end the whole worker with
                    // Result.failure(), stalling every remaining QUEUED row for the session. Mark
                    // just this chapter FAILED and continue with the next one.
                    Log.w(TAG, "Chapter ${chapter.chapterId} failed: ${e.message}", e)
                    handleErrorSafely(chapter, e)
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            // Cooperative cancellation (isStopped throw above, or WorkManager stopping the worker).
            // The coroutine is already cancelled, so the cleanup MUST run under NonCancellable —
            // the DAO/file suspend calls would otherwise throw immediately and silently skip.
            // Clean up the in-flight chapter's files, then re-queue its row ONLY if it is still
            // in-flight (2026-07 audit): a SYSTEM stop (constraint lost / quota) leaves the row
            // RUNNING and WorkManager reschedules the worker — without the reset the re-run pulls
            // only QUEUED rows and the chapter showed "downloading" forever until the next
            // app-launch reconcile. A USER cancel writes FAILED to the row, which the state-guarded
            // update never matches, so a cancel is never undone. Rethrow so the stop is not
            // mistaken for a crash.
            currentChapter?.let {
                withContext(NonCancellable) {
                    chapterDownloadService.deleteChapterFiles(it.mangaId, it.chapterId)
                    chapterDownloadDao.requeueIfInFlight(it.chapterId)
                }
            }
            throw e
        } catch (e: Exception) {
            // Last-resort guard: a failure OUTSIDE the per-chapter isolation above (e.g.
            // getNextQueuedChapter itself, or handleErrorSafely's own DAO write, failing). Mark the
            // in-flight row FAILED so it leaves the RUNNING state instead of being blindly
            // re-queued on every launch.
            currentChapter?.let {
                chapterDownloadDao.updateStateAndProgress(it.chapterId, DownloadingState.FAILED, 0, e.message)
                chapterDownloadService.deleteChapterFiles(it.mangaId, it.chapterId)
            }
            Log.w(TAG, "Worker failed: ${e.message}", e)
            Result.failure()
        } finally {
            clearAllDownloadNotifications()
        }
    }

    /**
     * One chapter's full download pass: RUNNING write → collect the download flow into DAO/
     * notification updates → the missing-terminal-state guard. Extracted from [doWork]'s loop so
     * the per-chapter failure isolation there wraps exactly one chapter's work.
     */
    private suspend fun processChapter(chapter: ChapterDownloadEntity) {
        chapterDownloadDao.updateStateChId(chapter.chapterId, DownloadingState.RUNNING)

        var sawTerminalState = false

        downloadChapterFlowV2(chapter.toChapterEntity()).collect { state ->
            if (isStopped) throw CancellationException()

            when (state) {
                is DownloadState.InProgress -> handleInProgressSafely(state, chapter)
                is DownloadState.Compressing -> handleCompressingSafely(chapter)
                is DownloadState.Complete -> {
                    sawTerminalState = true
                    handleCompleteSafely(chapter)
                }
                is DownloadState.Error -> {
                    sawTerminalState = true
                    handleErrorSafely(chapter, state.exception)
                }
            }
        }

        // A source flow can complete after emitting only State.Loading (mapped to
        // InProgress) with no Complete/Error — e.g. a chapter-parse failure that yields no
        // images. Without this guard the row stays RUNNING forever and reconcile re-queues
        // it on every launch. Mirrors the nonAndroid twin's "No images for chapter" failure.
        if (!sawTerminalState) {
            handleErrorSafely(chapter, Throwable("No images for chapter"))
        }
    }

    /**
     * Inlined replacement for upstream `DownloadRepository.downloadChapterFlowv2`.
     * Picks the right `BaseMangaRepository` for the chapter's manga, kicks off either the
     * streaming (ProManga) or batch download path via `ChapterDownloadService`, and bridges
     * intermediate states into DAO writes for the UI to observe.
     */
    private suspend fun provideActiveRepo(mangaId: Long): BaseMangaRepository {
        val api = mangaDao.getApiByMangaId(mangaId)
        return if (api.isNullOrBlank()) {
            sourcesRepository.activeRepo.first()
        } else {
            sourcesRepository.getRepoByName(api)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun downloadChapterFlowV2(chapter: SavedChapterEntity): Flow<DownloadState> {
        // Sources Migration Phase 3: route config-backed sources through the SourceRegistry
        // (generic-only). The provider returns null for a non-config source, so the legacy scraper
        // path below runs byte-identical to before.
        val api = mangaDao.getApiByMangaId(chapter.mangaId)
        val manga = mangaDao.getMangaById(chapter.mangaId)
        val providerPages = if (!api.isNullOrBlank()) {
            chapterPageProvider.pagesOrNull(
                api = api,
                mangaUrl = manga?.url.orEmpty(),
                mangaLanguage = manga?.language.orEmpty(),
                chapterUrl = chapter.url,
            )
        } else {
            null
        }
        if (providerPages != null) {
            // Generic/config-driven path — page URLs + per-page headers from the registry; no legacy repo.
            val urls = providerPages.map { it.url }
            val overrideHeaders = providerPages.firstOrNull()?.headers ?: emptyMap()
            return if (urls.isEmpty()) {
                flowOf(DownloadState.Error(Throwable("No images for chapter"), downloadedImages = 0, totalImages = 0))
            } else {
                chapterDownloadService.downloadChapterC(chapter, urls, repo = null, overrideHeaders = overrideHeaders)
            }
        }

        val repo = provideActiveRepo(chapter.mangaId)

        val chapterDataFlow = if (repo is ProchanRepository) {
            Log.d(TAG, "Using batch loading for ProManga")
            repo.getFullImgs(chapter.url)
        } else {
            Log.d(TAG, "Using standard streaming for ${repo.API}")
            repo.fetchChapterDataF(chapter.url)
        }

        return chapterDataFlow
            .onStart { repo.initSite() }
            .flatMapConcat { state ->
                when (state) {
                    is State.Loading -> flowOf(
                        DownloadState.InProgress(
                            totalImages = 0,
                            downloadedImages = 0,
                            currentImageUrl = "",
                        ),
                    )
                    is State.Error -> flowOf(
                        DownloadState.Error(
                            exception = Throwable(state.message),
                            downloadedImages = 0,
                            totalImages = 0,
                        ),
                    )
                    is State.Success -> flow {
                        emitAll(
                            chapterDownloadService.downloadChapterC(chapter, state.data, repo),
                        )
                    }
                }
            }
    }

    private suspend fun handleCompressingSafely(chapter: ChapterDownloadEntity) {
        chapterDownloadDao.updateStateAndProgress(
            chapter.chapterId,
            DownloadingState.COMPRESSING,
            100,
        )

        notificationLock.withLock {
            try {
                notifyChapterCompressing(chapter.chapterId, chapter.mangaId, chapter.number)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update compressing notification", e)
            }
        }
    }

    private fun notifyChapterCompressing(
        chapterId: Long,
        @Suppress("UNUSED_PARAMETER") mangaId: Long,
        chapterNumber: String,
    ) {
        val notifManager = context.getSystemService(NotificationManager::class.java)!!
        val compressingNotification = NotificationCompat.Builder(context, CHANNEL_CHAPTER)
            .setContentTitle(context.getString(R.string.notification_chapter_title, chapterNumber))
            .setContentText(context.getString(R.string.notification_compressing_images))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(0, 0, true)
            .build()

        notifManager.notify(NOTIF_CHAPTER_BASE + chapterId.toInt(), compressingNotification)
        lastNotifiedChapterId = chapterId
    }

    private suspend fun handleInProgressSafely(
        state: DownloadState.InProgress,
        chapter: ChapterDownloadEntity,
    ) {
        val downloaded = state.downloadedImages
        val total = state.totalImages

        if (total > 0) {
            val percent = ((downloaded.toFloat() / total.toFloat()) * 100).toInt()
            chapterDownloadDao.updateProgress(chapter.chapterId, percent)
        }

        notificationLock.withLock {
            try {
                notifyChapterProgress(chapter.chapterId, chapter.mangaId, chapter.number, downloaded, total)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update progress notifications", e)
            }
        }
    }

    private suspend fun handleCompleteSafely(chapter: ChapterDownloadEntity) {
        // Capture the final on-disk chapter size (the .cbz if compression ran, else the loose
        // pages) BEFORE the terminal SUCCESS write, so the single observeAllDownloads emission that
        // flips the row to SUCCESS already carries sizeBytes (native size-display parity). The
        // worker writes pages under AppFileSystem.chapterDir(mangaId, chapterId), the same layout
        // folderSize walks. Best-effort: a size-walk failure must not fail the download.
        val sizeBytes = runCatching {
            appFileSystem.folderSize(appFileSystem.chapterDir(chapter.mangaId, chapter.chapterId))
        }.getOrDefault(0L)
        chapterDownloadDao.updateSize(chapter.chapterId, sizeBytes)

        chapterDownloadDao.updateStateAndProgress(chapter.chapterId, DownloadingState.SUCCESS, 100)
        chapterDao.markChapterDownloaded(chapter.chapterId)

        notificationLock.withLock {
            try {
                notificationManager.cancel(NOTIF_CHAPTER_BASE + chapter.chapterId.toInt())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel chapter notification", e)
            }
        }
    }

    private suspend fun handleErrorSafely(chapter: ChapterDownloadEntity, exception: Throwable) {
        chapterDownloadDao.updateStateAndProgress(
            chapter.chapterId,
            DownloadingState.FAILED,
            0,
            exception.message,
        )
        chapterDownloadService.deleteChapterFiles(chapter.mangaId, chapter.chapterId)

        notificationLock.withLock {
            try {
                notificationManager.cancel(NOTIF_CHAPTER_BASE + chapter.chapterId.toInt())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel error notification", e)
            }
        }
    }

    private fun updateOverallNotification() {
        try {
            setForegroundAsync(buildForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update foreground notification", e)
        }
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        val cancelAllIntent = Intent(ACTION_CANCEL).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_WORK_ID, id.toString())
        }

        val cancelAllPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            cancelAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notification_downloading_chapters))
            .setContentText(context.getString(R.string.notification_downloading_background))
            .addAction(
                R.drawable.ic_download_cancel,
                context.getString(R.string.action_cancel_all),
                cancelAllPendingIntent,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ALL_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ALL_ID, notification)
        }
    }

    private fun notifyChapterProgress(
        chapterId: Long,
        mangaId: Long,
        chapterNumber: String,
        downloaded: Int,
        total: Int,
    ) {
        val cancelChapterIntent = Intent(ACTION_CANCEL_CHAPTER).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_WORK_ID, id.toString())
            putExtra(EXTRA_CHAPTER_ID, chapterId)
            putExtra(EXTRA_MANGA_ID, mangaId)
        }

        val cancelChapterPendingIntent = PendingIntent.getBroadcast(
            context,
            (chapterId and 0xFFFF).toInt(),
            cancelChapterIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notifManager = context.getSystemService(NotificationManager::class.java)!!
        val progressNotification = NotificationCompat.Builder(context, CHANNEL_CHAPTER)
            .setContentTitle(context.getString(R.string.notification_chapter_title, chapterNumber))
            .setContentText(context.getString(R.string.notification_images_progress, downloaded, total))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .addAction(
                R.drawable.ic_download_cancel,
                context.getString(R.string.action_cancel_chapter),
                cancelChapterPendingIntent,
            )
            .setProgress(total, downloaded, false)
            .build()

        notifManager.notify(NOTIF_CHAPTER_BASE + chapterId.toInt(), progressNotification)
        lastNotifiedChapterId = chapterId
    }

    private fun clearAllDownloadNotifications() {
        context.getSystemService(NotificationManager::class.java)?.apply {
            cancel(NOTIF_ALL_ID)
            lastNotifiedChapterId?.let { cancel(NOTIF_CHAPTER_BASE + it.toInt()) }
        }
    }

    private suspend fun setupChannelsSafely() {
        channelLock.withLock {
            if (!channelsCreated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    notificationManager.createChannel(
                        CHANNEL_ALL,
                        context.getString(R.string.notification_channel_all_downloads),
                        NotificationManager.IMPORTANCE_DEFAULT,
                        context.getString(R.string.notification_channel_all_downloads_desc),
                    )
                    notificationManager.createChannel(
                        CHANNEL_CHAPTER,
                        context.getString(R.string.notification_channel_chapter_download),
                        NotificationManager.IMPORTANCE_LOW,
                        context.getString(R.string.notification_channel_chapter_download_desc),
                    )
                    notificationManager.createChannel(
                        CHANNEL_SUMMARY,
                        context.getString(R.string.notification_channel_download_summary),
                        NotificationManager.IMPORTANCE_MIN,
                        context.getString(R.string.notification_channel_download_summary_desc),
                    )
                    channelsCreated = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create notification channels", e)
                }
            }
        }
    }

    private fun NotificationManager.createChannel(
        id: String,
        name: String,
        importance: Int,
        description: String,
    ) {
        createNotificationChannel(
            NotificationChannel(id, name, importance).apply { this.description = description },
        )
    }

    @Suppress("unused")
    private fun referenceUnused() {
        // Reserved IDs from upstream; kept for parity with notification scheme.
        val ignore = NOTIF_SUMMARY_ID
    }
}

/* ----------------------------------------------------------------------------
 * §253 audit-trail postscript — Phase 9.x.cluster259.staleKdocSweep.cascade
 * Date: 2026-05-29
 * Cluster: 259 (4-tier Android download chain CLOSER)
 * ----------------------------------------------------------------------------
 *
 * CLASSIFICATION: ANDROIDMAIN-SOLO-LEAF / LIVE (WorkManager scheduler tier
 * driving the actual download flow on the Android-only download chain).
 *
 * LIVE consumers verified by static cross-reference grep:
 *   - androidx.work.WorkManager runtime enqueue sites — search of :shared/
 *     androidMain for `OneTimeWorkRequestBuilder<DownloadWorkerV2>()` /
 *     `WorkManager.getInstance(context).enqueueUniqueWork(... ,
 *     OneTimeWorkRequest.from(DownloadWorkerV2::class.java))`. LIVE upstream
 *     trigger sites in the :data/repository/clean DownloadRepositoryImpl
 *     onStart branch (cluster255 sibling): when a chapter is enqueued for
 *     download, the repo writes a ChapterDownloadEntity row and posts a
 *     unique-work request bound to this Worker class. WorkManager then
 *     constructs an instance via its no-arg default factory + (Context,
 *     WorkerParameters) ctor; once doWork() runs the lazy Koin lookups
 *     resolve the 5 deps (chapterDownloadDao + chapterDao + mangaDao +
 *     chapterDownloadService + sourcesRepository) from the GlobalContext.
 *   - DownloadCancelReceiver in :app (manifest-registered BroadcastReceiver
 *     — KDoc line 73 cites). Receives ACTION_CANCEL / ACTION_CANCEL_CHAPTER
 *     intents emitted by buildForegroundInfo() / notifyChapterProgress().
 *     Currently still stubbed in :app per KDoc; receiver-to-Koin wire-up
 *     deferred to Phase 11.x. Confirms LIVE intent-emit site but the
 *     receive-side closure is partial (axis registered for tracking).
 *
 * Delta-axes documented (14):
 *
 *   - ANDROIDMAIN-SOLO-LEAF — no expect-decl, no iosMain/desktopMain
 *     siblings; pure Android leaf consuming androidx.work.CoroutineWorker
 *     + NotificationCompat + NotificationManager + PendingIntent (all
 *     Android-only). Cluster259 closes the 4-tier Android download chain:
 *     cluster255 (DownloadRepositoryImpl) + cluster257 (CbzManager doublet)
 *     + cluster258 (ChapterDownloadService) + cluster259 (this Worker) =
 *     COMPLETE-ANDROID-DOWNLOAD-SUBSYSTEM SWEEP.
 *
 *   - WORKMANAGER-COROUTINE-WORKER-LIVE — class extends `CoroutineWorker
 *     (context, params)` with the standard 2-arg ctor WorkManager calls
 *     reflectively. Distinguishes this leaf from cluster258's plain Koin-
 *     bound ChapterDownloadService — the worker is owned by WorkManager,
 *     not Koin. Both Koin and WorkManager hold references to this class
 *     in different lifecycle scopes.
 *
 *   - KOIN-LAZY-INJECT-LIVE — 5 deps resolved via `by lazy { koin.get() }`
 *     where `koin get() = GlobalContext.get()`. Required because
 *     CoroutineWorker mandates the (Context, WorkerParameters) ctor —
 *     classic Koin `single { ... } binds<X>()` constructor-injection is
 *     not reachable through WorkManager's instantiation path. The lazy
 *     pattern + GlobalContext sidesteps this constraint at the cost of
 *     deferred dep resolution (resolved on first method body call inside
 *     doWork). LIVE — load-bearing for the entire WorkManager-driven
 *     download flow.
 *
 *   - HILT-TO-KOIN-ANNOTATION-STRIP-LIVE — Phase 8.14 migration stripped
 *     @HiltWorker + @AssistedInject (KDoc line 46). Ctor reverted from
 *     Hilt-assisted (@Assisted Context, @Assisted WorkerParameters,
 *     other deps as @Inject) back to the plain WorkManager-compatible
 *     2-arg form. Cluster259 NEW DELTA-INSTANCE vs cluster258: cluster258
 *     was a plain @Singleton + @Inject Hilt → Koin-`single`-binding strip;
 *     cluster259 is a @HiltWorker + @AssistedInject Hilt → manual
 *     `by lazy { koin.get() }` strip (different Hilt feature, different
 *     Koin substitute).
 *
 *   - KOINWORKERFACTORY-PARTIAL-DEFERRAL — class KDoc lines 46-58 self-
 *     document: MyApp.onCreate DOES install KoinWorkerFactory via
 *     workManagerFactory() as of Phase 12.x bootstrap (commit e8b4fa9),
 *     but this worker is NOT bound via `workerOf(::DownloadWorkerV2)`
 *     yet. Falls back to the default no-arg factory. The constructor-
 *     injection refactor (drop GlobalContext.get() lookups, add `private
 *     val` fields, bind via workerOf) is tracked in AUDIT_GOAL.md §4 #5.
 *     PARTIAL-DEFERRAL register: not a runtime blocker because both paths
 *     produce the same dep-graph (KoinWorkerFactory falls back to default
 *     when no workerOf binding matches). Cluster259 captures the
 *     intentional architecture asymmetry for future-AUDIT reference.
 *
 *   - CHAPTERDOWNLOADSERVICE-UPSTREAM-CONSUMER-LIVE — line 209 calls
 *     `chapterDownloadService.downloadChapterC(chapter, state.data, repo)`
 *     (LIVE main download dispatch), line 154 calls `chapterDownloadService.
 *     deleteChapterFiles(it.mangaId, it.chapterId)` (LIVE filesystem
 *     cleanup on worker-cancel), line 295 calls the same delete (LIVE per-
 *     chapter error cleanup). Cluster259 confirms cluster258's "DUAL-
 *     CONSUMER-LIVE" prediction: this worker is the legacy-stable side of
 *     ChapterDownloadService's two-consumer fan; cluster255 :data
 *     DownloadRepositoryImpl is the clean-rework side. Both LIVE.
 *
 *   - DOWNLOADREPOSITORY-INLINE-PORT-LIVE — class KDoc lines 59-63
 *     document: upstream's `DownloadRepository.downloadChapterFlowv2` (the
 *     216-line legacy class in `presentation/features/download/domain/`)
 *     was INLINED into `downloadChapterFlowV2(chapter)` (this file lines
 *     178-214). Cluster259 NEW DELTA-AXIS: the inline-instead-of-port
 *     decision is captured here rather than as a separate file because
 *     the resurrected class would have had a single consumer (this
 *     worker) and would have been classified as dead-code. Avoids
 *     resurrecting orphan code.
 *
 *   - PROMANGA-BATCH-VS-STREAMING-DISPATCH-LIVE — line 181: `if (repo is
 *     ProchanRepository) { repo.getFullImgs(chapter.url) } else { repo.
 *     fetchChapterDataF(chapter.url) }`. Hard-coded type-check special-
 *     case for ProchanRepository (Arabic ProManga source) batch-loads
 *     ALL images upfront before passing to ChapterDownloadService;
 *     other sources stream image-by-image. LIVE because cluster258's
 *     STREAMING-VS-BATCH-AXIS register documented `downloadChapterC` as
 *     the entry-point that dispatches between the two impls; cluster259
 *     confirms the pre-`downloadChapterC` half of that dispatch lives
 *     here (in the Worker's `downloadChapterFlowV2`) rather than in
 *     ChapterDownloadService itself. Two-tier batch/stream dispatch:
 *     this Worker chooses the upstream source pull strategy, then
 *     ChapterDownloadService chooses the per-image compression strategy.
 *
 *   - FOREGROUND-SERVICE-DATA-SYNC-API29-LIVE — lines 341-345:
 *     `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo
 *     (NOTIF_ALL_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_
 *     DATA_SYNC) else ForegroundInfo(NOTIF_ALL_ID, notification)`. API
 *     29 (Android 10) introduced mandatory foreground-service-type
 *     declarations; the Worker declares DATA_SYNC type explicitly post-
 *     API 29. LIVE — required for Android 14+ targetSdk compliance (any
 *     long-running foreground service without explicit type is refused).
 *
 *   - CANCELLATION-CHAIN-LIVE — line 139: `if (isStopped) throw
 *     CancellationException()` checked on every flow-emit; the catch
 *     block (lines 152-157) calls `chapterDownloadService.deleteChapterFiles
 *     (mangaId, chapterId)` for `currentChapter` then returns
 *     Result.failure(); the finally block calls `clearAllDownloadNotifications()`.
 *     Confirms cluster258's CANCELLATION-FILESYSTEM-CLEANUP-LIVE axis:
 *     cleanup obligation crosses the WorkManager-thrown cancellation
 *     boundary into ChapterDownloadService's deleteChapterFiles surface
 *     cleanly.
 *
 *   - NOTIFICATION-CHANNEL-IDEMPOTENT-LIVE — companion `@Volatile var
 *     channelsCreated = false` + `private val channelLock = Mutex()`
 *     guard the once-per-process channel creation (3 channels: ALL /
 *     CHAPTER / SUMMARY). SUMMARY id (NOTIF_SUMMARY_ID=2) is reserved/
 *     unused (referenceUnused() stub at line 434 keeps the compiler
 *     happy with @Suppress("unused")). LIVE — load-bearing on
 *     multi-worker-instance scenarios (concurrent app launches hitting
 *     a stale process).
 *
 *   - BROADCAST-INTENT-ACTIONS-LIVE — companion-decl ACTION_CANCEL +
 *     ACTION_CANCEL_CHAPTER emitted via PendingIntent.getBroadcast()
 *     in buildForegroundInfo() (line 321-326) + notifyChapterProgress()
 *     (line 362-367). Consumer side: DownloadCancelReceiver in :app
 *     module (manifest-registered, currently stub per KDoc line 73).
 *     LIVE — emit side is fully wired; receive side is Phase 11.x-
 *     deferred. Cluster259 captures the emit-but-not-yet-act asymmetry.
 *
 *   - DOWNLOAD-DISPATCHER-RESERVED-NONLIVE — uses `coroutineScope { ... }`
 *     (line 125) + lazy `Dispatchers.IO` indirection via WorkManager's
 *     CoroutineWorker default executor; no DownloadDispatcher injection
 *     (continues cluster258 axis). Cluster259 NON-DELTA register: still
 *     no rework dispatcher abstraction reached this leaf.
 *
 *   - CLUSTER259-4-TIER-CLOSER-REGISTER — adds cluster259 to the
 *     androidMain solo-leaf register; cumulative count now
 *     cluster255+257+258+259 across four consecutive clusters with no
 *     failed file-shape predictions. After this commit lands the
 *     androidMain download subsystem closes FULLY SWEPT: 4 of 4 tiers
 *     documented with §253 postscripts. Saturation-axis prediction
 *     (cluster258) is now formally validated.
 *
 * CLUSTER260-PIVOT-PREDICTION — with the Android download subsystem
 * fully swept, the strongest remaining un-swept :shared/androidMain
 * candidates shift to non-download areas. Probable candidates:
 *   (a) Non-download notification-tier orphans (e.g. an update-checker
 *       worker, a refresh-library worker, or a push-token registration
 *       receiver). Likely-zero (most have already been swept in
 *       cluster250+ platform-actual fan waves).
 *   (b) Image-loader / Coil registry factories on the Android side
 *       (probably cluster221 sibling already-swept).
 *   (c) FRESH-TIER-ENUMERATION-SCOUT — the wave probably pivots to
 *       iOS+Desktop platform-stub register (much smaller — most
 *       platform-stub iOS/Desktop fans are 1-2 leaves each and many
 *       have already been swept as part of the cluster250+ wave).
 * If no symmetric androidMain leaf remains, cluster260 should be a
 * tier-enumeration scout itself rather than a sweep commit.
 *
 * SATURATION-WATCH — cluster255+257+258+259 represents the longest
 * consecutive same-tier register run in the §253 audit campaign to
 * date (4 clusters, no failed predictions, all GREEN on all build
 * gates). Confidence that the wave is approaching natural saturation
 * for the androidMain platform-actual tier is high. The next clusters
 * should pivot tier or shift to scout-only commits.
 *
 * Build gates fired before commit (all GREEN):
 *   - :composeApp:compileDebugKotlinAndroid
 *   - :composeApp:compileKotlinIosArm64
 *   - :composeApp:compileKotlinIosSimulatorArm64
 * Desktop NOT required — androidMain-only file.
 *
 * Cumulative §253 postscript count after this commit: 159 leaves
 * across 259 clusters.
 */

