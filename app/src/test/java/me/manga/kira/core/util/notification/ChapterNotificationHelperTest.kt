package me.manga.kira.core.util.notification

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import me.manga.kira.R
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.di.appKoinModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [NotificationPostingShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChapterNotificationHelperTest {
    private lateinit var context: Application
    private lateinit var room: NotificationRoomFixture
    private lateinit var manager: NotificationManager
    private lateinit var posting: NotificationPostingShadow

    @Before
    fun open() {
        context = RuntimeEnvironment.getApplication()
        room = NotificationRoomFixture(context)
        manager = context.getSystemService(NotificationManager::class.java)
        posting = Shadow.extract(manager)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @After
    fun close() = room.close()

    @Test
    fun persistsAllNineIncludingIgnoreRowsThenPostsNewestSixReversedWithOneCover() =
        runBlocking {
            val manga = room.manga()
            val chapters = room.chapters(manga, 9)
            // Real IGNORE/-1 results must reconcile, not be mistaken for missing insert results.
            room.db.chapterDao().insertChapters(chapters.take(3))
            val cover = NotificationNativeCoverWitness()
            val helper = room.helper(cover.loader)
            val committed = helper.persistNewChapterNotifications(manga, chapters)
            assertEquals(9, committed.size)
            assertEquals(setOf(manga.title), committed.map { it.mangaTitle }.toSet())
            assertEquals(setOf(manga.imageUrl), committed.map { it.mangaImageUrl }.toSet())
            room.assertStoredWithRealChapterIds(committed)
            assertTrue(posting.posted.isEmpty())
            cover.assertIdle()

            helper.displayNotifications(committed)

            val expected =
                (9 downTo 4).map { number -> committed.single { it.chapterNumber == number.toString() } }
            assertEquals(expected.map { it.id.toInt() }, posting.posted.map { it.first })
            cover.assertSingleDecode()
            posting.posted.zip(expected).forEach { (record, row) -> assertNotificationContent(record.second, row) }
            assertEquals(committed.sortedBy { it.id }, room.updates())
        }

    @Test
    fun sharedChapterUrlIgnoreFallbackKeepsCapturedOwnerAcrossDelayedDisplay() =
        runBlocking {
            NotificationOwnerWitness(room).assertDelayedDisplay(posting, ::assertNotificationContent)
        }

    @Test
    fun appPermissionAndChannelDenialKeepEveryUpdateWithoutCoverOrPost() =
        runBlocking {
            val cover = NotificationNativeCoverWitness()
            val helper = room.helper(cover.loader)
            for (denial in listOf("app", "permission", "channel")) {
                denyNotifications(denial)
                try {
                    val manga = room.manga(denial)
                    val rows = helper.persistNewChapterNotifications(manga, room.chapters(manga, 8))
                    helper.displayNotifications(rows)
                    assertEquals(8, room.updates().count { it.mangaId == manga.id })
                    cover.assertIdle()
                    assertTrue(posting.posted.isEmpty())
                } finally {
                    shadowOf(manager).setNotificationsEnabled(true)
                    shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

    @Test
    fun swallowedRoomInsertErrorIsNotAnIgnoreFallbackOrCoverRequest() =
        runBlocking {
            val manga = room.manga()
            val chapter = room.chapters(manga, 1).single()
            room.db.chapterDao().insertChapters(listOf(chapter))
            val attempts = AtomicInteger()
            val chapters =
                room.chapterInserts {
                    attempts.incrementAndGet()
                    room.db.chapterDao().insertChaptersSafely(it)
                }
            val covers = CountingCovers()
            val helper = room.helper(covers, room.repository(chapters))
            // A real Room FK failure becomes [] in the repository. A URL-only fallback could
            // find the seeded chapter and incorrectly turn the storage failure into a banner.
            val error = persistenceFailure(helper, manga, listOf(chapter.copy(mangaId = manga.id + 1_000)))
            assertTrue(error is IllegalStateException)
            assertEquals("chapter_insert_result_count", error?.message)
            assertEquals(1, attempts.get())
            assertEquals(1, room.db.chapterDao().getChaptersByMangaIdR(manga.id).size)
            assertTrue(room.updates().isEmpty())
            assertEquals(0, covers.calls)
            assertTrue(posting.posted.isEmpty())
        }

    @Test
    fun incompleteChapterInsertResultsFailBeforeNotificationsAndCover() =
        runBlocking {
            val manga = room.manga()
            val chapters = room.chapterInserts { room.db.chapterDao().insertChaptersSafely(it).dropLast(1) }
            val covers = CountingCovers()
            val helper = room.helper(covers, room.repository(chapters))

            val error = persistenceFailure(helper, manga, room.chapters(manga, 2))

            assertEquals("chapter_insert_result_count", error?.message)
            assertEquals(2, room.db.chapterDao().getChaptersByMangaIdR(manga.id).size)
            assertTrue(room.updates().isEmpty())
            assertEquals(0, covers.calls)
            assertTrue(posting.posted.isEmpty())
        }

    @Test
    fun notificationWriteFailureNeverStartsCoverOrRetriesStorage() =
        runBlocking {
            val attempts = AtomicInteger()
            val notifications =
                room.notificationInserts {
                    attempts.incrementAndGet()
                    throw SQLiteException("fixture_notification_write")
                }
            val covers = CountingCovers()
            val helper = room.helper(covers, notifications = notifications)
            val manga = room.manga()

            val error = persistenceFailure(helper, manga, room.chapters(manga, 2))

            assertTrue(error is SQLiteException)
            assertEquals(1, attempts.get())
            assertEquals(2, room.db.chapterDao().getChaptersByMangaIdR(manga.id).size)
            assertTrue(room.updates().isEmpty())
            assertEquals(0, covers.calls)
            assertTrue(posting.posted.isEmpty())
        }

    @Test
    fun incompleteOrNonpositiveNotificationIdsCannotDisplayCommittedRows() =
        runBlocking {
            for (invalidCount in listOf(true, false)) {
                val attempts = AtomicInteger()
                val notifications =
                    room.notificationInserts {
                        attempts.incrementAndGet()
                        val ids = room.db.notificationDao().insertNotificationsList(it)
                        if (invalidCount) ids.dropLast(1) else listOf(-1L, ids.last())
                    }
                val covers = CountingCovers()
                val helper = room.helper(covers, notifications = notifications)
                val manga = room.manga("invalid-$invalidCount")

                val error = persistenceFailure(helper, manga, room.chapters(manga, 2))

                assertEquals("notification_insert_result_ids", error?.message)
                assertEquals(1, attempts.get())
                assertEquals(2, room.updates().count { it.mangaId == manga.id })
                assertEquals(0, covers.calls)
                assertTrue(posting.posted.isEmpty())
            }
        }

    @Test
    fun notificationServiceFailureDoesNotBlockConstructionOrPersistence() =
        runBlocking {
            val brokenContext = MissingNotificationService(context)
            val cover = NotificationNativeCoverWitness()
            val helper = room.helper(cover.loader, context = brokenContext)
            val manga = room.manga()
            val rows = helper.persistNewChapterNotifications(manga, room.chapters(manga, 8))
            assertEquals(0, brokenContext.lookups)

            helper.displayNotifications(rows)

            assertTrue(brokenContext.lookups > 0)
            assertEquals(8, room.updates().size)
            cover.assertIdle()
            assertTrue(posting.posted.isEmpty())
        }

    @Test
    fun coldHostKoinInstantiationPostsTextWithoutComposingUi() =
        runBlocking {
            val repository = room.repository()
            val dependencies =
                module {
                    single<NotificationDao> { room.db.notificationDao() }
                    single { repository }
                }
            val app =
                koinApplication {
                    androidContext(context)
                    modules(appKoinModule, dependencies)
                }
            try {
                assertTrue(app.koin.get<NotificationCovers>() is NotificationCoverLoader)
                val helper = app.koin.get<ChapterNotificationHelper>()
                val manga = room.manga(cover = "")
                val rows = helper.persistNewChapterNotifications(manga, room.chapters(manga, 1))
                helper.displayNotifications(rows)
                assertEquals(rows, room.updates())
                assertEquals(rows.single().id.toInt(), posting.posted.single().first)
                assertNull(posting.posted.single().second.getLargeIcon())
                assertFalse(context is me.manga.kira.MyApp)
            } finally {
                app.close()
            }
        }

    private fun assertNotificationContent(notification: Notification, row: ChapterNotification) {
        assertEquals(row.mangaTitle, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            context.getString(R.string.chapter_is_available, row.chapterNumber),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals(CHAPTER_NOTIFICATION_CHANNEL, notification.channelId)
        assertNotNull(notification.getLargeIcon())
    }

    private fun denyNotifications(denial: String) {
        when (denial) {
            "app" -> shadowOf(manager).setNotificationsEnabled(false)
            "permission" -> shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            "channel" ->
                manager.createNotificationChannel(
                    NotificationChannel(CHAPTER_NOTIFICATION_CHANNEL, "Fixture", NotificationManager.IMPORTANCE_NONE),
                )
        }
    }

    private suspend fun persistenceFailure(
        helper: ChapterNotificationHelper,
        manga: SavedMangaEntity,
        chapters: List<SavedChapterEntity>,
    ) =
        runCatching {
            helper.displayNotifications(helper.persistNewChapterNotifications(manga, chapters))
        }.exceptionOrNull()

    /** Only detects forbidden entry; does not model transport or capability decisions. */
    private class CountingCovers : NotificationCovers {
        var calls = 0

        override suspend fun withCover(url: String, canPost: () -> Boolean, post: (Bitmap?) -> Unit) {
            calls++
            error("Cover must not start after storage failure")
        }
    }
}
