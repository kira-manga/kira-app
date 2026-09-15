package me.manga.kira.presentation.features.download.ui.test2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import kotlinx.coroutines.runBlocking
import me.manga.kira.core.storage.StorageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual worker/resource tests through the locale-owner SPI, not an actual Android host proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], application = DownloadLocaleTestApplication::class, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadWorkerResourceLocaleTest {
    private lateinit var app: DownloadLocaleTestApplication
    private lateinit var worker: DownloadWorkerV2
    private lateinit var manager: NotificationManager

    @Before
    fun constructActualWorkerWithoutRunningWork() {
        app = RuntimeEnvironment.getApplication() as DownloadLocaleTestApplication
        manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        worker = actualDownloadWorker(app)
        selectLanguage("")
    }

    @Test
    fun foregroundBeforeDoWorkAndItsNextBuildTranslateTitleBodyActionAndAllChannels() = runBlocking {
        assertSame(app, worker.applicationContext)
        assertTrue(manager.notificationChannels.isEmpty())
        selectLanguage("fr")
        val first = worker.getForegroundInfo()
        assertEquals(1, first.notificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, first.foregroundServiceType)
        assertText(
            first.notification, "Téléchargement des chapitres", "Téléchargement de tous les chapitres en arrière-plan",
        )
        assertEquals("Tout annuler", first.notification.actions.single().title.toString())
        assertChannels(FRENCH_CHANNELS)
        selectLanguage("ar")
        val next = worker.getForegroundInfo()
        assertText(next.notification, "جاري تنزيل الفصول", "جاري تنزيل جميع الفصول في الخلفية")
        assertEquals("إلغاء الكل", next.notification.actions.single().title.toString())
        assertChannels(ARABIC_CHANNELS)
        val cancel = next.notification.actions.single().actionIntent
        assertEquals(first.notification.actions.single().actionIntent, cancel)
        assertCancellationIntent(cancel, DownloadWorkerV2.ACTION_CANCEL, 0)
    }

    @Test
    fun sameWorkerProgressBuildRefreshesTheWholeNoticeAndKeepsCancellationIdentity() {
        selectLanguage("fr")
        val first = worker.buildChapterProgressNotification(7, 11, "7", 2, 9)
        assertText(first, "Chapitre 7", "2 / 9 images")
        assertEquals("Annuler le chapitre", first.actions.single().title.toString())
        assertChannels(FRENCH_CHANNELS)
        selectLanguage("ar")
        val next = worker.buildChapterProgressNotification(7, 11, "7", 2, 9)
        assertText(next, "الفصل 7", "٢ / ٩ صور")
        assertEquals("إلغاء الفصل", next.actions.single().title.toString())
        assertEquals(2, next.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(9, next.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertChannels(ARABIC_CHANNELS)
        val cancel = next.actions.single().actionIntent
        assertEquals(first.actions.single().actionIntent, cancel)
        assertCancellationIntent(cancel, DownloadWorkerV2.ACTION_CANCEL_CHAPTER, 7)
        val intent = shadowOf(cancel).savedIntent
        assertEquals(7L, intent.getLongExtra(DownloadWorkerV2.EXTRA_CHAPTER_ID, -1))
        assertEquals(11L, intent.getLongExtra(DownloadWorkerV2.EXTRA_MANGA_ID, -1))
    }

    @Test
    fun sameWorkerCompressingBuildRefreshesTitleBodyAndChannelsWithoutDoWorkSetup() {
        selectLanguage("fr")
        val first = worker.buildChapterCompressingNotification("7")
        assertText(first, "Chapitre 7", "Compression des images…")
        assertChannels(FRENCH_CHANNELS)
        selectLanguage("ar")
        val next = worker.buildChapterCompressingNotification("7")
        assertText(next, "الفصل 7", "جاري ضغط الصور…")
        assertTrue(next.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertChannels(ARABIC_CHANNELS)
        assertText(first, "Chapitre 7", "Compression des images…")
    }

    @Test
    fun repeatedBuildsOnlyRelabelSameIdChannelsIncludingUserBlockedSummary() = runBlocking {
        val before = mapOf(
            CHANNEL_ALL to manager.seedUserChannel(CHANNEL_ALL, NotificationManager.IMPORTANCE_HIGH),
            CHANNEL_CHAPTER to manager.seedUserChannel(CHANNEL_CHAPTER, NotificationManager.IMPORTANCE_HIGH),
            CHANNEL_SUMMARY to manager.seedUserChannel(CHANNEL_SUMMARY, NotificationManager.IMPORTANCE_NONE),
        )
        selectLanguage("fr")
        worker.getForegroundInfo()
        assertChannels(FRENCH_CHANNELS, before)
        selectLanguage("ar")
        worker.buildChapterCompressingNotification("7")
        assertChannels(ARABIC_CHANNELS, before)
    }

    private fun selectLanguage(tag: String) {
        app.androidLocaleState.settings.putString(StorageKeys.SELECTED_LANGUAGE, tag)
    }

    private fun assertText(notification: Notification, title: String, body: String) {
        assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(body, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    private fun assertCancellationIntent(pending: PendingIntent, action: String, requestCode: Int) {
        val shadow = shadowOf(pending)
        val intent = shadow.savedIntent
        assertEquals(action, intent.action)
        assertEquals(app.packageName, intent.`package`)
        assertEquals(worker.id.toString(), intent.getStringExtra(DownloadWorkerV2.EXTRA_WORK_ID))
        assertEquals(requestCode, shadow.requestCode)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, shadow.flags)
    }

    private fun assertChannels(
        labels: List<ChannelLabels>,
        previous: Map<String, NotificationChannel> = emptyMap(),
    ) {
        for ((id, name, description) in labels) {
            val channel = checkNotNull(manager.getNotificationChannel(id))
            assertEquals(name, channel.name.toString())
            assertEquals(description, channel.description)
            previous[id]?.let { before ->
                before.name = name
                before.description = description
                assertEquals(before, channel)
            }
        }
        assertEquals(labels.map { it.id }.toSet(), manager.notificationChannels.map { it.id }.toSet())
    }

    private data class ChannelLabels(val id: String, val name: String, val description: String)

    private companion object {
        const val CHANNEL_ALL = "download_all_channel"
        const val CHANNEL_CHAPTER = "download_chapter_channel"
        const val CHANNEL_SUMMARY = "download_summary_channel"
        val FRENCH_CHANNELS = listOf(
            ChannelLabels(CHANNEL_ALL, "Tous les téléchargements", "Progression globale des téléchargements"),
            ChannelLabels(CHANNEL_CHAPTER, "Téléchargement de chapitre", "Progression d’un seul chapitre"),
            ChannelLabels(CHANNEL_SUMMARY, "Résumé des téléchargements", "Notifications de fin"),
        )
        val ARABIC_CHANNELS = listOf(
            ChannelLabels(CHANNEL_ALL, "جميع التنزيلات", "تقدم التنزيل الإجمالي"),
            ChannelLabels(CHANNEL_CHAPTER, "تنزيل فصل", "تقدم تنزيل فصل واحد"),
            ChannelLabels(CHANNEL_SUMMARY, "ملخص التنزيل", "إشعارات الإكمال"),
        )
    }
}
