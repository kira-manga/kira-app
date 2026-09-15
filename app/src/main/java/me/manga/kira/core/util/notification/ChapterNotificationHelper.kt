package me.manga.kira.core.util.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.R
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity

/**
 * Worker-owned Updates persistence followed by optional Android display. No independent scope:
 * a cancelled worker may leave committed Updates, but cannot schedule later cover work/posts.
 * The shared Room discovery transaction owns chapter/Updates atomicity and cross-worker dedup.
 */
class ChapterNotificationHelper(
    private val context: Context,
    private val libraryDeo: LibraryDeo,
    private val covers: NotificationCovers,
) {
    /** Persist all intended rows before making any notification-service or cover decision. */
    suspend fun persistNewChapterNotifications(
        manga: SavedMangaEntity,
        chapters: List<SavedChapterEntity>,
    ): List<ChapterNotification> = libraryDeo.persistChapterDiscoveries(
        api = manga.api,
        mangaUrl = manga.url,
        chapters = chapters,
        expectedMangaId = manga.id,
    )

    /** Best effort only, joined by the worker outside its per-manga persistence timeout. */
    suspend fun displayNotifications(notifications: List<ChapterNotification>) {
        val owner = currentCoroutineContext()
        owner.ensureActive()
        if (notifications.isEmpty()) return
        try {
            val selected = notifications.takeLast(DISPLAY_LIMIT).asReversed()
            covers.withCover(selected.first().mangaImageUrl, ::canPost) { bitmap ->
                selected.forEach { notification ->
                    owner.ensureActive()
                    if (canPost()) post(notification, bitmap)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            owner.ensureActive()
            Logger.withTag(TAG).w { "notification_display_unavailable" }
        }
    }

    private fun canPost(): Boolean {
        val manager = context.getSystemService<NotificationManager>() ?: return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            hasPostPermission() &&
            channelAvailable(manager)
    }

    private fun hasPostPermission(): Boolean {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun channelAvailable(manager: NotificationManager): Boolean {
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: createChannel(manager)
        val groupBlocked =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                channel.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true
        return channel.importance != NotificationManager.IMPORTANCE_NONE && !groupBlocked
    }

    private fun createChannel(manager: NotificationManager): NotificationChannel {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.new_chapters),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notifications_for_new_manga_chapters)
                enableLights(true)
                enableVibration(true)
            }
        manager.createNotificationChannel(channel)
        return checkNotNull(manager.getNotificationChannel(CHANNEL_ID))
    }

    private fun post(
        notification: ChapterNotification,
        bitmap: Bitmap?,
    ) {
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(notification.mangaTitle)
                .setContentText(context.getString(R.string.chapter_is_available, notification.chapterNumber))
                .setAutoCancel(true)
        if (bitmap != null) builder.setLargeIcon(bitmap)
        context.getSystemService<NotificationManager>()?.notify(notification.id.toInt(), builder.build())
    }

    private companion object {
        const val CHANNEL_ID = "me.manga.kira.new_chapters"
        const val TAG = "ChapterNotifHelper"
        const val DISPLAY_LIMIT = 6
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
