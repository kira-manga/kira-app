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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import me.manga.kira.R
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.core.states.State
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.util.notification.ChapterNotificationHelper
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.sources_repositry.BaseMangaRepository
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 12.x port of upstream `LibraryRefreshWorker.kt`.
 *
 * Periodic foreground worker that walks every `SavedMangaEntity`, calls each source's
 * `fetchMangaChaptersF`, inserts any new chapters into Room, and surfaces a `ChapterNotification`
 * per new entry via [ChapterNotificationHelper].
 *
 * Deltas vs upstream:
 *  - `@HiltWorker` + `@AssistedInject` removed — Koin's `workerOf(::LibraryRefreshWorker)` injects
 *    `(Context, WorkerParameters, LibraryRepository, SharedPrefsHelper, ChapterNotificationHelper,
 *    SourcesRepository)` positionally.
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
 * Concurrency preserved verbatim: BATCH_SIZE=5, MANGA_TIMEOUT_SECONDS=30, TOTAL_TIMEOUT_MINUTES=15,
 * 1-second inter-batch delay, supervisorScope so one failed manga doesn't cancel siblings.
 */
@OptIn(ExperimentalTime::class)
class LibraryRefreshWorker(
    private val context: Context,
    params: WorkerParameters,
    private val libraryRepository: LibraryRepository,
    private val prefs: SharedPrefsHelper,
    private val chapterNotificationHelper: ChapterNotificationHelper,
    private val sourcesRepository: SourcesRepository,
) : CoroutineWorker(context, params) {

    private val log = Logger.withTag(TAG)

    private val notificationManager by lazy {
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun createRefreshChannelIfNeeded() {
        // minSdk = 26 so NotificationChannel is always available.
        val channel = NotificationChannel(
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

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_refreshing_library))
            .setContentText(context.getString(R.string.notification_starting))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, false)
            .build()

        val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, serviceType)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    override suspend fun doWork(): Result = supervisorScope {
        try {
            // On API 31+ setForeground() throws ForegroundServiceStartNotAllowedException (an
            // IllegalStateException) when the worker starts while the app is backgrounded — e.g. a
            // CONNECTED-constrained refresh deferred until connectivity returns. The refresh does
            // not require foreground promotion, so continue as ordinary background work.
            try {
                setForeground(getForegroundInfo())
            } catch (e: IllegalStateException) {
                log.w(e) { "Foreground promotion rejected; continuing refresh in background" }
            }

            val result = withTimeoutOrNull(TOTAL_TIMEOUT_MINUTES.minutes) {
                refreshAll()
            }

            if (result == null) {
                updateNotification(
                    context.getString(R.string.notification_refresh_failed, ""),
                    isComplete = true,
                    isError = true,
                )
                Result.failure()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            updateNotification(
                context.getString(R.string.notification_refresh_failed, e.message ?: ""),
                isComplete = true,
                isError = true,
            )
            Result.failure()
        } finally {
            cleanupNotification()
        }
    }

    private suspend fun refreshAll() = supervisorScope {
        try {
            val allManga = withTimeoutOrNull(30.seconds) {
                libraryRepository.getAllSavedManga().first()
            } ?: return@supervisorScope

            val total = allManga.size

            if (total == 0) {
                updateNotification(
                    context.getString(R.string.notification_no_manga_to_refresh),
                    isComplete = true,
                )
                return@supervisorScope
            }

            var completed = 0
            var failed = 0
            val batches = allManga.chunked(BATCH_SIZE)

            batches.forEachIndexed { batchIndex, batch ->
                val batchResults = batch.map { manga ->
                    async {
                        try {
                            withTimeoutOrNull(MANGA_TIMEOUT_SECONDS.seconds) {
                                refreshSingleManga(manga)
                            } ?: false
                        } catch (e: Exception) {
                            false
                        }
                    }
                }

                val results = batchResults.awaitAll()
                results.forEach { success -> if (success) completed++ else failed++ }

                val progress = ((completed + failed) * 100) / total
                val statusText = if (batchIndex < batches.size - 1) {
                    context.getString(R.string.notification_processing_batch, batchIndex + 2)
                } else {
                    context.getString(R.string.notification_finishing_up)
                }

                updateNotification(
                    text = context.getString(
                        R.string.notification_refresh_progress,
                        statusText,
                        completed,
                        total,
                        failed,
                    ),
                    progress = progress,
                )

                delay(1000)
            }

            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            prefs.putString(KEY_LAST_UPDATED, now.toString())

            updateNotification(
                context.getString(R.string.notification_refresh_completed, completed, failed),
                isComplete = true,
            )
        } catch (e: Exception) {
            updateNotification(
                context.getString(R.string.notification_refresh_failed, e.message ?: ""),
                isComplete = true,
                isError = true,
            )
        }
    }

    private suspend fun refreshSingleManga(manga: SavedMangaEntity): Boolean {
        return try {
            if (manga.id == 0L) return false
            // Strict lookup: an unknown/retired api must count as a failed refresh, NOT resolve to
            // EmptyMangaRepository — its empty-Success (imageUrl="") used to blank the saved cover
            // via the reconcile in fetchMangaUpdates (2026-07 source-lifecycle hardening).
            val repo = sourcesRepository.getOrRepoByName(manga.api) ?: run {
                log.w { "Skipping refresh for '${manga.title}': unknown source api=${manga.api}" }
                return false
            }
            fetchMangaUpdates(manga, repo)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun fetchMangaUpdates(manga: SavedMangaEntity, activeRepo: BaseMangaRepository) {
        try {
            val state = withContext(platformIoDispatcher) {
                activeRepo
                    .fetchMangaChaptersF(manga.url)
                    .timeout(20.seconds)
                    .flowOn(platformIoDispatcher)
                    .filter { it !is State.Loading }
                    .catch { e -> emit(State.Error(0, e.message ?: "Unknown error")) }
                    .first()
            }

            state.toData()?.let { mangaInfo ->
                // Never reconcile a blank cover over an existing one — a source that fails to parse
                // its details page (or a null-object repo) reports imageUrl="" and must not wipe the
                // stored cover across saved_manga/history/notifications.
                if (mangaInfo.imageUrl.isNotBlank() && mangaInfo.imageUrl != manga.imageUrl) {
                    libraryRepository.updateMangaImageUrlEverywhere(manga.id, mangaInfo.imageUrl)
                }

                val localChapters = withTimeoutOrNull(10.seconds) {
                    libraryRepository.getChaptersByMangaId(manga.id).first()
                } ?: run {
                    log.w { "Timeout getting local chapters for ${manga.title}" }
                    return
                }

                val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                // Discovery timestamp for the NEW-badge 4-day expiry (DB v10). Without it the badge
                // would be hidden immediately (fetchedAt==0 is treated as outside the window).
                val now = Clock.System.now().toEpochMilliseconds()
                val newChapters = mangaInfo.chapters
                    .filterNot { remote -> localChapters.any { it.url == remote.url } }
                    .map { remote ->
                        SavedChapterEntity(
                            mangaId = manga.id,
                            name = remote.name,
                            number = remote.number,
                            url = remote.url,
                            date = remote.date ?: today,
                            isNew = true,
                            fetchedAt = now,
                        )
                    }
                    .reversed()

                if (newChapters.isNotEmpty()) {
                    libraryRepository.insertChapterList(newChapters)
                    chapterNotificationHelper.addNewChapterNotification(manga, newChapters)
                }
            }
        } catch (e: Exception) {
            log.e(e) { "Error fetching updates for ${manga.title}" }
        }
    }

    private fun updateNotification(
        text: String,
        progress: Int = -1,
        isComplete: Boolean = false,
        isError: Boolean = false,
    ) {
        try {
            val title = if (isError) {
                context.getString(R.string.notification_library_refresh_failed)
            } else {
                context.getString(R.string.notification_refreshing_library)
            }

            val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
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
        } catch (_: Exception) {
        }
    }

    private fun cleanupNotification() {
        try {
            notificationManager.cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "LibraryRefreshWorker"
        const val BATCH_SIZE = 5
        const val KEY_LAST_UPDATED = "library_last_updated"
        const val CHANNEL_ID = "library_refresh"
        const val NOTIF_ID = 42
        const val MANGA_TIMEOUT_SECONDS = 30L
        const val TOTAL_TIMEOUT_MINUTES = 15L
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
