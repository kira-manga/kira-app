package me.manga.kira.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import kotlinx.coroutines.supervisorScope
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.manga.kira.R
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.util.notification.ChapterNotificationHelper
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import me.manga.kira.sources.contracts.SourceRegistry
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Phase 12.x port of upstream `LibraryRefreshWorker.kt`.
 *
 * Periodic foreground worker that walks every `SavedMangaEntity`, resolves the source from the
 * active generic catalog, inserts new chapters into Room, and surfaces a `ChapterNotification` per
 * new entry via [ChapterNotificationHelper]. Unavailable sources fail the refresh; no adapter is inferred.
 *
 * Deltas vs upstream:
 *  - `@HiltWorker` + `@AssistedInject` removed — Koin's `workerOf(::LibraryRefreshWorker)` injects
 *    `(Context, WorkerParameters, LibraryRepository, SharedPrefsHelper, ChapterNotificationHelper,
 *    SourceRegistry)` positionally.
 *  - `android.util.Log` → Kermit.
 *  - `Dispatchers.IO` → `IODispatcher`.
 *  - `java.time.LocalDate` / `LocalDateTime` → `kotlinx.datetime.LocalDate` / `LocalDateTime`. The
 *    persisted key `library_last_updated` now stores `LocalDateTime.toString()` from kotlinx-datetime
 *    instead of `java.time.LocalDateTime.toString()` — both produce ISO-8601 strings ("2026-05-24T13:45")
 *    so any downstream parser that wasn't doing weird timezone math continues to work.
 *  - Notification strings now localize via Android `R.string.notification_*` resources declared in
 *    this `:app` module's `res/values*` (mirroring native's keys verbatim across the shipped locale
 *    set, including Arabic/RTL). Compose-MP resource accessors are unreachable from a worker context,
 *    so the worker reads Android resources directly — same approach native uses.
 *  - Removed dead-code block (upstream lines 100-128 — a commented-out alternate `doWork`).
 *
 * Retains batches of five, 30-second item / 15-minute total deadlines and the 1-second batch
 * throttle. Item failures are recorded without aborting siblings; caller cancellation propagates.
 */
@OptIn(ExperimentalTime::class)
@Suppress("LongParameterList")
class LibraryRefreshWorker(
    private val context: Context,
    params: WorkerParameters,
    private val libraryRepository: LibraryRepository,
    private val prefs: SharedPrefsHelper,
    private val chapterNotificationHelper: ChapterNotificationHelper,
    private val sourceRegistry: SourceRegistry,
) : CoroutineWorker(context, params) {
    private val log = Logger.withTag(TAG)
    internal val refreshWork by lazy { LibraryRefreshWork(WorkPort(), ::showProgress) }
    private val notificationManager by lazy {
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun createRefreshChannelIfNeeded() {
        // minSdk = 26 so NotificationChannel is always available.
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_library_refresh),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_library_refresh_desc)
            }
        notificationManager.createNotificationChannel(channel)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createRefreshChannelIfNeeded()

        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_refreshing_library))
                .setContentText(context.getString(R.string.notification_starting))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOnlyAlertOnce(true)
                .setProgress(100, 0, false)
                .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    override suspend fun doWork(): Result =
        supervisorScope {
            try {
                // Foreground promotion and its notification service/channel are optional to
                // Updates persistence, including API 31+ service restrictions and SecurityException.
                try {
                    setForeground(getForegroundInfo())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    log.w { "refresh_foreground_unavailable" }
                }
                refreshWork.run()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                runCatchingCancellable {
                    updateNotification(
                        context.getString(R.string.notification_refresh_failed, e.message ?: ""),
                        isComplete = true,
                        isError = true,
                    )
                }
                Result.failure()
            } finally {
                cleanupNotification()
            }
        }

    private inner class WorkPort : LibraryRefreshWorkPort {
        override fun library() = libraryRepository.getAllSavedManga()

        override fun source(api: String) = sourceRegistry.get(api)

        override fun chapters(mangaId: Long) = libraryRepository.getChaptersByMangaId(mangaId)

        override suspend fun updateCover(
            mangaId: Long,
            coverUrl: String,
        ) = libraryRepository.updateMangaImageUrlEverywhere(mangaId, coverUrl)

        override suspend fun persistNotifications(
            manga: SavedMangaEntity,
            chapters: List<SavedChapterEntity>,
        ) = chapterNotificationHelper.persistNewChapterNotifications(manga, chapters)

        override suspend fun displayNotifications(notifications: List<ChapterNotification>) =
            chapterNotificationHelper.displayNotifications(notifications)

        override suspend fun stampLastSuccess() {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            prefs.putString(KEY_LAST_UPDATED, now.toString())
        }
    }

    private fun showProgress(progress: LibraryRefreshWorkProgress) {
        val total = progress.snapshotSize ?: 0
        if (progress.stop != null) {
            val text =
                when {
                    !progress.isComplete -> context.getString(R.string.notification_refresh_failed, "")
                    total == 0 -> context.getString(R.string.notification_no_manga_to_refresh)
                    else -> context.getString(R.string.notification_refresh_completed, progress.succeeded, 0)
                }
            updateNotification(text, isComplete = true, isError = !progress.isComplete)
        } else if (total > 0) {
            showBatchProgress(progress, total)
        }
    }

    private fun showBatchProgress(
        progress: LibraryRefreshWorkProgress,
        total: Int,
    ) {
        val status =
            if (progress.attempted < total) {
                context.getString(
                    R.string.notification_processing_batch,
                    progress.attempted / LibraryRefreshWork.BATCH_SIZE + 1,
                )
            } else {
                context.getString(R.string.notification_finishing_up)
            }
        updateNotification(
            context.getString(
                R.string.notification_refresh_progress,
                status,
                progress.succeeded,
                total,
                progress.failed + progress.timedOut,
            ),
            progress = progress.attempted * 100 / total,
        )
    }

    private fun updateNotification(
        text: String,
        progress: Int = -1,
        isComplete: Boolean = false,
        isError: Boolean = false,
    ) {
        try {
            val title =
                if (isError) {
                    context.getString(R.string.notification_library_refresh_failed)
                } else {
                    context.getString(R.string.notification_refreshing_library)
                }

            val builder =
                NotificationCompat
                    .Builder(applicationContext, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setOnlyAlertOnce(true)

            if (!isComplete && progress >= 0) {
                builder.setProgress(100, progress, false)
            } else if (isComplete) {
                builder.setProgress(0, 0, false)
            }

            notificationManager.notify(NOTIF_ID, builder.build())
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        }
    }

    private fun cleanupNotification() {
        try {
            notificationManager.cancel(NOTIF_ID)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "LibraryRefreshWorker"
        const val KEY_LAST_UPDATED = "library_last_updated"
        const val CHANNEL_ID = "library_refresh"
        const val NOTIF_ID = 42
    }
}

/*
 * §253 audit-trail postscript — cluster284 §253 sweep (2026-05-29)
 *
 * Classification: LIVE-HOST CoroutineWorker — a foreground WorkManager worker (overrides
 * getForegroundInfo) registered through Koin's WorkManager DSL; the central per-chapter-update job.
 *
 * LIVE evidence:
 *  - Registered via workerOf(::LibraryRefreshWorker) in app/.../di/AppKoinModule.kt:36.
 *  - The KoinWorkerFactory that satisfies that binding is installed by workManagerFactory() at
 *      MyApp.kt:77; MyApp is the manifest Application (AndroidManifest.xml:30 android:name=".MyApp")
 *      and implements Configuration.Provider (MyApp.kt:54) so the factory is in place pre-default-init.
 *  - Its six ctor params (lines 69-76) resolve from Koin: LibraryRepository, SharedPrefsHelper,
 *      ChapterNotificationHelper (itself bound at AppKoinModule.kt:33), SourcesRepository.
 *  - The manifest also declares the foreground service host it needs:
 *      androidx.work.impl.foreground.SystemForegroundService with foregroundServiceType="dataSync"
 *      (AndroidManifest.xml:70-74) plus FOREGROUND_SERVICE_DATA_SYNC permission (manifest line 12) —
 *      consistent with ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC used at line 107.
 *  - ARCHITECTURE.md:25894-25900 + 30569-30576 confirm this exact FQN is the live Android refresh
 *      entry, mirroring legacy posture verbatim.
 *
 * Status: LIVE-HOST (legacy-logic-bearing Phase 12.x port — substantial batched-refresh + foreground
 * notification logic resides here, not delegated into rework :composeApp/:shared).
 *
 * Delta-axes vs rework graph:
 *  1. Android component lifecycle — foreground worker: getForegroundInfo (line 96) creates the
 *     "library_refresh" IMPORTANCE_LOW channel and posts NOTIF_ID 42 progress notification; SDK-Q
 *     guard (line 108) chooses the typed ForegroundInfo overload.
 *  2. Koin startKoin wiring — workerOf replaces upstream @HiltWorker + @AssistedInject (KDoc line 51);
 *     params resolved positionally by KoinWorkerFactory.
 *  3. WorkManager integration — supervisorScope doWork (line 115) with TOTAL_TIMEOUT_MINUTES=15 outer
 *     timeout, BATCH_SIZE=5 chunked fan-out, 1s inter-batch delay, MANGA_TIMEOUT_SECONDS=30 per-manga.
 *  4. Source/data coupling — drives sourcesRepository.getRepoByName + BaseMangaRepository.
 *     fetchMangaChaptersF (line 215) State flow, then libraryRepository inserts + chapterNotification
 *     Helper.addNewChapterNotification (line 253) for each new chapter.
 *  5. kotlinx-datetime delta — java.time replaced by kotlinx.datetime; KEY_LAST_UPDATED persists
 *     LocalDateTime.toString() ISO-8601 (line 186) — KDoc line 56-59 documents wire-shape parity.
 *  6. Localization — notification strings localize via Android R.string.notification_* resources in
 *     this :app module's res/values* (native-parity fix; mirrors native keys across the shipped
 *     locales). Compose-MP accessors remain unreachable from worker context, so Android resources are
 *     read directly, same as ChapterNotificationHelper.
 *
 * Nested-comment hazard check: this block contains no slash-star, no star-slash, no slash-star-star
 * sequence; the comment is balanced and compiles cleanly.
 */
