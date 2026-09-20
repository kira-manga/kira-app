package me.manga.kira.core.util.notification

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.manga.kira.MyApp
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.library.LibraryChapterNotification
import me.manga.kira.locale.RealApplicationLocaleTest
import me.manga.kira.locale.assertOnlyChannelLabelsChanged
import me.manga.kira.locale.seedUserChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.annotation.experimental.LazyApplication.LazyLoad.ON

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], application = MyApp::class, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
@LazyApplication(ON)
class ChapterNotificationResourceLocaleTest : RealApplicationLocaleTest() {
    private var database: MangaDatabase? = null

    @After
    fun closeResolvedDatabase() {
        database?.close()
    }

    @Test
    fun actualBuildSnapshotsTheNewSelectionAfterItsCoverAwait() = runBlocking {
        val helper = actualHelper()
        selectLanguage("fr")
        val enteredCoverLoader = CompletableDeferred<Unit>()
        val cover = CompletableDeferred<Bitmap?>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            helper.buildChapterNotification(chapter()) {
                enteredCoverLoader.complete(Unit)
                cover.await()
            }
        }
        try {
            assertTrue(enteredCoverLoader.isCompleted)
            assertFalse(pending.isCompleted)
            selectLanguage("ar")
            cover.complete(null)
            assertText(withTimeout(30_000) { pending.await() }, "الفصل 7 متاح")
            assertChannelLabels("فصل جديد", "الإشعارات بالفصول الجديدة للمانجا")
        } finally {
            pending.cancel()
        }
    }

    @Test
    fun sameActualKoinHelperUsesLatestLanguageOnNextBuildWithOrWithoutACover() = runBlocking {
        val helper = actualHelper()
        selectLanguage("fr")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val first = helper.buildChapterNotification(chapter()) { bitmap }
        assertText(first, "Chapitre 7 disponible !")
        assertNotNull(first.getLargeIcon())
        assertChannelLabels("Nouveaux chapitres", "Notifications des nouveaux chapitres")
        selectLanguage("ar")
        val next = helper.buildChapterNotification(chapter()) { null }
        assertText(next, "الفصل 7 متاح")
        assertNull(next.getLargeIcon())
        assertChannelLabels("فصل جديد", "الإشعارات بالفصول الجديدة للمانجا")
        assertText(first, "Chapitre 7 disponible !")
    }

    @Test
    fun blockedSameIdChannelKeepsItsUserStateWhenTheHelperRelabelsIt() = runBlocking {
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        val before = manager.seedUserChannel(CHANNEL_ID, NotificationManager.IMPORTANCE_NONE)
        val helper = actualHelper()
        selectLanguage("fr")
        helper.buildChapterNotification(chapter()) { null }
        manager.assertOnlyChannelLabelsChanged(before, "Nouveaux chapitres", "Notifications des nouveaux chapitres")
        selectLanguage("ar")
        helper.buildChapterNotification(chapter()) { null }
        manager.assertOnlyChannelLabelsChanged(before, "فصل جديد", "الإشعارات بالفصول الجديدة للمانجا")
    }

    private fun actualHelper(): ChapterNotificationHelper {
        app // Start the actual Application/DI path, rather than providing a test Koin module.
        val koin = GlobalContext.get()
        database = koin.get<MangaDatabase>()
        return koin.get<ChapterNotificationHelper>()
    }

    private fun assertText(notification: Notification, body: String) {
        assertEquals(CHANNEL_ID, notification.channelId)
        assertEquals("Fixture manga", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(body, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    private fun assertChannelLabels(name: String, description: String) {
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        val channel = checkNotNull(manager.getNotificationChannel(CHANNEL_ID))
        assertEquals(name, channel.name.toString())
        assertEquals(description, channel.description)
    }

    private fun chapter() = LibraryChapterNotification(
        notificationId = 31,
        chapterId = 23,
        manga = Manga(
            api = "fixture",
            language = "en",
            title = "Fixture manga",
            url = "https://example.invalid/manga",
            coverUrl = "https://example.invalid/unused-cover.png",
            rating = null,
            genres = emptyList(),
        ),
        chapter = Chapter(
            number = "7", name = "", url = "https://example.invalid/chapter/7",
            date = null, isDownloaded = false, isBookmarked = false,
        ),
    )

    private companion object {
        const val CHANNEL_ID = "me.manga.kira.new_chapters"
    }
}
