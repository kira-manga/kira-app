package me.manga.yamiapk.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.storage.SharedPrefsHelper
import me.manga.yamiapk.core.util.notification.ChapterNotificationHelper
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.presentation.features.library.domain.LibraryRepository
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import androidx.work.await

@HiltWorker
class LibraryRefreshWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val libraryRepository: LibraryRepository,
    private val prefs: SharedPrefsHelper,
    private val chapterNotificationHelper: ChapterNotificationHelper,
    private val sourcesRepository: SourcesRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LibraryRefreshWorker"
        private const val BATCH_SIZE = 5
        private const val KEY_LAST_UPDATED = "library_last_updated"
        private const val CHANNEL_ID = "library_refresh"
        private const val NOTIF_ID = 42
        private const val MANGA_TIMEOUT_SECONDS = 30L
        private const val TOTAL_TIMEOUT_MINUTES = 15L
    }

    private val notificationManager by lazy {
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun createRefreshChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_library_refresh)
            val desc = context.getString(R.string.notification_channel_library_refresh_desc)
            val chan = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
                description = desc
            }
            notificationManager.createNotificationChannel(chan)
        }
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

//    override suspend fun doWork(): Result = supervisorScope {
//        return@supervisorScope try {
//            setForeground(getForegroundInfo())
//
//            val result = withTimeoutOrNull(TOTAL_TIMEOUT_MINUTES.minutes) {
//                refreshAll()
//            }
//
//            if (result == null) {
//                updateNotification(
//                    context.getString(R.string.notification_refresh_timed_out),
//                    isComplete = true,
//                    isError = true
//                )
//                Result.failure()
//            } else {
//                Result.success()
//            }
//        } catch (e: Exception) {
//            updateNotification(
//                context.getString(R.string.notification_refresh_failed, e.message ?: ""),
//                isComplete = true,
//                isError = true
//            )
//            Result.failure()
//        } finally {
//            cleanupNotification()
//        }
//    }
override suspend fun doWork(): Result = supervisorScope {
    try {
        // Start foreground service immediately
        val fg = getForegroundInfo()
        setForeground(fg)

        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MINUTES.minutes) {
            refreshAll()
        }

        if (result == null) {
            updateNotification(
                context.getString(R.string.notification_refresh_failed, ""),
                isComplete = true,
                isError = true
            )
            Result.failure()
        } else {
            Result.success()
        }

    } catch (e: Exception) {
        updateNotification(
            "Failed: ${e.message}",
            isComplete = true,
            isError = true
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
            } ?: run {
                return@supervisorScope
            }

            val total = allManga.size

            if (total == 0) {
                updateNotification(
                    context.getString(R.string.notification_no_manga_to_refresh),
                    isComplete = true
                )
                return@supervisorScope
            }

            var completed = 0
            var failed = 0

            allManga.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
                val batchResults = batch.map { manga ->
                    async {
                        try {
                            withTimeoutOrNull(MANGA_TIMEOUT_SECONDS.seconds) {
                                refreshSingleManga(manga)
                            } ?: run {
                                false
                            }
                        } catch (e: Exception) {
                            false
                        }
                    }
                }

                val results = batchResults.awaitAll()

                results.forEach { success ->
                    if (success) completed++ else failed++
                }

                val progress = ((completed + failed) * 100) / total
                val currentManga = if (batchIndex < allManga.chunked(BATCH_SIZE).size - 1) {
                    context.getString(R.string.notification_processing_batch, batchIndex + 2)
                } else {
                    context.getString(R.string.notification_finishing_up)
                }

                updateNotification(
                    text = context.getString(
                        R.string.notification_refresh_progress,
                        currentManga,
                        completed,
                        total,
                        failed
                    ),
                    progress = progress
                )

                delay(1000)
            }

            val now = LocalDateTime.now()
            prefs.putString(KEY_LAST_UPDATED, now.toString())

            val finalMessage = context.getString(
                R.string.notification_refresh_completed,
                completed,
                failed
            )
            updateNotification(finalMessage, isComplete = true)

        } catch (e: Exception) {
            updateNotification(
                context.getString(R.string.notification_refresh_failed, e.message ?: ""),
                isComplete = true,
                isError = true
            )
        }
    }

    private suspend fun refreshSingleManga(manga: SavedMangaEntity): Boolean {
        return try {
            if (manga.id == 0L) {
                return false
            }

            val repo = sourcesRepository.getRepoByName(manga.api)
            fetchMangaUpdates(manga, repo)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun fetchMangaUpdates(manga: SavedMangaEntity, activeRepo: BaseMangaRepository) {
        try {
            val state = withContext(Dispatchers.IO) {
                activeRepo
                    .fetchMangaChaptersF(manga.url)
                    .timeout(20.seconds)
                    .flowOn(Dispatchers.Default)
                    .filter { it !is State.Loading }
                    .catch { e ->
                        emit(State.Error(0, e.message ?: "Unknown error"))
                    }
                    .first()
            }

            state.toData()?.let { mangaInfo ->
                if (mangaInfo.imageUrl != manga.imageUrl) {
                    libraryRepository.updateMangaImageUrlEverywhere(manga.id, mangaInfo.imageUrl)
                }

                val localChapters = withTimeoutOrNull(10.seconds) {
                    libraryRepository.getChaptersByMangaId(manga.id).first()
                } ?: run {
                    Log.w(TAG, "Timeout getting local chapters for ${manga.title}")
                    return
                }

                val newChapters = mangaInfo.chapters
                    .filterNot { remote -> localChapters.any { it.url == remote.url } }
                    .map { remote ->
                        SavedChapterEntity(
                            mangaId = manga.id,
                            name = remote.name,
                            number = remote.number,
                            url = remote.url,
                            date = remote.date ?: LocalDate.now(),
                            isNew = true
                        )
                    }
                    .reversed()

                if (newChapters.isNotEmpty()) {
                    libraryRepository.insertChapterList(newChapters)
                    chapterNotificationHelper.addNewChapterNotification(manga, newChapters)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching updates for ${manga.title}: ${e.message}")
        }
    }

    private fun updateNotification(
        text: String,
        progress: Int = -1,
        isComplete: Boolean = false,
        isError: Boolean = false
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
        } catch (e: Exception) {
        }
    }

    private fun cleanupNotification() {
        try {
            notificationManager.cancel(NOTIF_ID)
        } catch (e: Exception) {
        }
    }
}