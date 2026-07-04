package me.manga.kira.core.util.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import me.manga.kira.R
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 12.x port of upstream `ChapterNotificationHelper.kt` (Hilt → Koin, `android.util.Log` → Kermit).
 *
 * Used by [LibraryRefreshWorker] to fan-out per-chapter notifications when new chapters land
 * during a background library refresh. Kept in the `app/` Android module because it's deeply tied
 * to Android `NotificationManager` + `BitmapFactory` and is only ever exercised from Android-only
 * workers — porting it to commonMain would require expect/actuals for both APIs and there is no
 * iOS/Desktop background refresh consumer yet.
 *
 * Behavior is preserved verbatim from upstream:
 *  - Channel ID `me.manga.kira.new_chapters` (IMPORTANCE_HIGH, lights+vibration enabled).
 *  - Bitmap cover load is best-effort (`runCatching`) — failed cover loads still post the notification
 *    minus the large icon.
 *  - Defensive rawId/realId reconciliation (upstream comment: insertChapterList may return -1L for
 *    pre-existing chapters; we fall back to `getChapterIdByUrl`).
 *  - Only the last 6 chapters are surfaced as system notifications even if more were inserted —
 *    upstream limit, preserved here to avoid notification spam on first-run libraries.
 *
 * Strings localize via Android `R.string.*` resources declared in this `:app` module's `res/values*`
 * (`new_chapters`, `notifications_for_new_manga_chapters`, `chapter_is_available`), mirroring the
 * upstream/native keys verbatim across the shipped locale set (including Arabic/RTL). Compose-MP's
 * runtime string accessors aren't reachable from a worker/Service context, so — exactly as native
 * does — this helper reads Android resources directly via `context.getString(...)`.
 */
class ChapterNotificationHelper(
    private val context: Context,
    private val notificationDao: NotificationDao,
    private val libraryRepository: LibraryRepository,
) {
    private val log = Logger.withTag(TAG)
    private val notificationManager = context.getSystemService<NotificationManager>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        // minSdk = 26 so NotificationChannel is unconditionally available.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.new_chapters),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notifications_for_new_manga_chapters)
            enableLights(true)
            enableVibration(true)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    fun addNewChapterNotification(manga: SavedMangaEntity, chapters: List<SavedChapterEntity>) {
        if (chapters.isEmpty()) {
            log.w { "addNewChapterNotification called with empty chapters for mangaId=${manga.id}" }
            return
        }

        coroutineScope.launch {
            try {
                val rawIds = libraryRepository.insertChapterList(chapters)

                val realIds = chapters.mapIndexed { idx, chapter ->
                    val raw = rawIds.getOrNull(idx)
                    if (raw == null || raw == -1L) {
                        libraryRepository.getChapterIdByUrl(chapter.url) ?: -1L
                    } else raw
                }

                val notifications = chapters.mapIndexedNotNull { idx, chapter ->
                    val chapterId = realIds.getOrNull(idx) ?: -1L
                    if (chapterId <= 0L) null
                    else ChapterNotification(
                        mangaId = manga.id,
                        mangaTitle = manga.title,
                        mangaImageUrl = manga.imageUrl,
                        chapterId = chapterId,
                        chapterNumber = chapter.number,
                        chapterUrl = chapter.url,
                        mangaUrl = manga.url,
                        api = manga.api,
                        language = manga.language,
                    )
                }

                if (notifications.isEmpty()) {
                    log.w { "No notifications to insert for mangaId=${manga.id}" }
                    return@launch
                }

                val notifRowIds = notificationDao.insertNotificationsList(notifications)
                val pairs = notifications.zip(notifRowIds)
                if (pairs.isEmpty()) {
                    log.w { "insertNotificationsList returned no IDs for mangaId=${manga.id}" }
                    return@launch
                }

                pairs.takeLast(6).asReversed().forEach { (notif, rowId) ->
                    showChapterNotification(notif.copy(id = rowId))
                }
            } catch (t: Throwable) {
                log.e(t) { "Failed to add chapter notification for mangaId=${manga.id}" }
            }
        }
    }

    private fun showChapterNotification(notification: ChapterNotification) {
        coroutineScope.launch {
            try {
                val bitmap = async(Dispatchers.IO) {
                    runCatching {
                        val connection = URL(notification.mangaImageUrl).openConnection().apply {
                            connectTimeout = COVER_FETCH_TIMEOUT_MILLIS
                            readTimeout = COVER_FETCH_TIMEOUT_MILLIS
                        }
                        try {
                            connection.getInputStream().use { BitmapFactory.decodeStream(it) }
                        } finally {
                            (connection as? HttpURLConnection)?.disconnect()
                        }
                    }.getOrNull()
                }.await()

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(notification.mangaTitle)
                    .setContentText(
                        context.getString(R.string.chapter_is_available, notification.chapterNumber),
                    )
                    .setAutoCancel(true)

                bitmap?.let { builder.setLargeIcon(it) }

                notificationManager?.notify(notification.id.toInt(), builder.build())
            } catch (t: Throwable) {
                log.e(t) { "Failed to show chapter notification for chapterId=${notification.chapterId}" }
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "me.manga.kira.new_chapters"
        const val TAG = "ChapterNotifHelper"
        const val COVER_FETCH_TIMEOUT_MILLIS = 10_000
    }
}

/*
 * §253 audit-trail postscript — cluster284 §253 sweep (2026-05-29)
 *
 * Classification: LIVE-HOST helper — Android-only NotificationManager fan-out collaborator, NOT a
 * registered Android component itself (no manifest entry; it is a plain Koin single).
 *
 * LIVE evidence:
 *  - Registered as a Koin single in app/.../di/AppKoinModule.kt:33 —
 *      single { ChapterNotificationHelper(androidContext(), get(), get()) }
 *    so the two trailing get() lookups resolve NotificationDao + LibraryRepository from the graph.
 *  - Sole consumer is LibraryRefreshWorker (constructor param chapterNotificationHelper,
 *    LibraryRefreshWorker.kt:74; invoked at LibraryRefreshWorker.kt:253 inside fetchMangaUpdates).
 *  - appKoinModule is layered into the live Koin graph at MyApp.kt:78 (modules(appKoinModule))
 *    inside the initKoin(allReworkModules()) block — this is the running Application onCreate path.
 *  - Class KDoc (lines 22-44) confirms it is a deliberate app-module resident: deeply tied to
 *    NotificationManager plus BitmapFactory, no iOS/Desktop background-refresh consumer exists.
 *
 * Status: LIVE-HOST (legacy-logic-bearing — Phase 12.x straight port from Hilt-era upstream, not a
 * thin delegate into the rework :composeApp/:shared graph; carries channel + bitmap + dedup logic).
 *
 * Delta-axes vs rework graph:
 *  1. Android component lifecycle — owns NotificationChannel "me.manga.kira.new_chapters"
 *     (IMPORTANCE_HIGH) created eagerly in init block (line 54-56); minSdk 26 means no SDK guard.
 *  2. Koin startKoin wiring — bound in app-scoped appKoinModule (cannot live in PlatformModule.
 *     android.kt: the app -> composeApp -> shared graph never resolves app-module classes backward).
 *  3. WorkManager integration — exercised only from the WorkManager-driven LibraryRefreshWorker; the
 *     own internal CoroutineScope(Dispatchers.IO) (line 52) is independent of any worker scope.
 *  4. Localization — channel name/description + content text localize via Android R.string.* in this
 *     :app module's res/values* (native-parity fix; keys mirror native verbatim). Compose-MP accessors
 *     remain unreachable from worker/Service context, so Android resources are read directly.
 *  5. Repository reconciliation — defensive rawId/realId fallback via libraryRepository.
 *     getChapterIdByUrl (line 85) preserved verbatim from upstream insert-returns--1L behavior.
 *
 * Nested-comment hazard check: this block contains no slash-star, no star-slash, no slash-star-star
 * sequence; the comment is balanced and compiles cleanly.
 */
