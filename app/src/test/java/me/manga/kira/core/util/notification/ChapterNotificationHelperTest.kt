package me.manga.kira.core.util.notification

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.runBlocking
import me.manga.kira.R
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.di.appKoinModule
import me.manga.kira.domain.model.library.LibraryChapterNotification
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.locale.LocaleOnlyTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LocaleOnlyTestApplication::class, shadows = [NotificationPostingShadow::class])
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
    fun persistsOnlyDiscoveriesThenPostsNewestSixReversedWithOneCover() =
        runBlocking {
            val manga = room.manga()
            val chapters = room.chapters(manga, 9)
            // Already saved chapters are not discoveries, even if an older refresh never notified them.
            room.db.chapterDao().insertChapters(chapters.take(3))
            val cover = NotificationNativeCoverWitness()
            val helper = room.helper(cover.loader)
            val receipt = helper.persistFixtureNotifications(manga, chapters)
            val committed = receipt.notifications
            assertEquals(6, receipt.addedChapters)
            assertEquals(6, committed.size)
            assertEquals(setOf(manga.title), committed.map { it.manga.title }.toSet())
            assertEquals(setOf(manga.imageUrl), committed.map { it.manga.coverUrl }.toSet())
            room.assertStoredWithRealChapterIds(receipt)
            val beforeDisplay = room.updates()
            assertTrue(posting.posted.isEmpty())
            cover.assertIdle()

            helper.displayNotifications(committed)

            val expected =
                (9 downTo 4).map { number -> committed.single { it.chapter.number == number.toString() } }
            assertEquals(expected.map { it.notificationId.toInt() }, posting.posted.map { it.first })
            cover.assertSingleDecode()
            posting.posted.zip(expected).forEach { (record, row) -> assertNotificationContent(record.second, row) }
            assertEquals(beforeDisplay, room.updates())
        }

    @Test
    fun sharedChapterUrlDiscoveryKeepsCapturedOwnerAcrossDelayedDisplay() =
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
                    val rows = helper.persistFixtureNotifications(manga, room.chapters(manga, 8)).notifications
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
    fun candidateIdsAndStateCannotRetargetDiscoveryToAnotherManga() =
        runBlocking {
            val other = room.manga("other")
            val target = room.manga("target")
            val otherChapter = room.chapters(other, 1).single()
            val otherId = room.db.chapterDao().insertChapters(listOf(otherChapter)).single()
            val stale = otherChapter.copy(id = otherId, isRead = true, isDownloaded = true, isBookmarked = true)
            val covers = CountingCovers()
            val helper = room.helper(covers)

            val row = helper.persistFixtureNotifications(target, listOf(stale)).notifications.single()

            val saved = checkNotNull(room.db.chapterDao().getChapterByIdSuspend(row.chapterId))
            assertEquals(target.id, saved.mangaId)
            assertTrue(saved.id != otherId)
            assertTrue(saved.isNew && saved.fetchedAt > 0)
            assertFalse(saved.isRead || saved.isDownloaded || saved.isBookmarked)
            assertEquals(otherChapter.copy(id = otherId), room.db.chapterDao().getChapterByIdSuspend(otherId))
            assertEquals(0, covers.calls)
            assertTrue(posting.posted.isEmpty())
        }

    @Test
    fun duplicateCandidatesAndRepeatedRefreshProduceOnlyOneCommittedRow() =
        runBlocking {
            val manga = room.manga()
            val chapter = room.chapters(manga, 1).single()
            val covers = CountingCovers()
            val helper = room.helper(covers)

            val receipt = helper.persistFixtureNotifications(manga, listOf(chapter, chapter))
            val rows = receipt.notifications
            assertEquals(1, rows.size)
            assertTrue(helper.persistFixtureNotifications(manga, listOf(chapter)).notifications.isEmpty())
            room.assertStoredWithRealChapterIds(receipt)
            assertEquals(1, room.sql.chapterInserts.get())
            assertEquals(1, room.sql.notificationInserts.get())
            assertEquals(0, covers.calls)
            assertTrue(posting.posted.isEmpty())
        }

    @Test
    fun notificationWriteFailureRollsBackChaptersWithoutCoverOrStorageRetry() =
        runBlocking {
            val manga = room.manga()
            room.sql.beforeNotificationInsert = { ordinal ->
                if (ordinal == 2) throw SQLiteException("fixture_notification_write")
            }
            val covers = CountingCovers()
            val helper = room.helper(covers)

            val error = persistenceFailure(helper, manga, room.chapters(manga, 2))

            assertTrue(error is AppError.Storage.Io && error.cause is SQLiteException)
            assertEquals(2, room.sql.chapterInserts.get())
            assertEquals(2, room.sql.notificationInserts.get())
            assertTrue(room.db.chapterDao().getChaptersByMangaIdR(manga.id).isEmpty())
            assertTrue("the first notification must roll back too", room.updates().isEmpty())
            assertEquals(0, covers.calls)
            assertTrue(posting.posted.isEmpty())
        }

    @Test
    fun removedAndReaddedParentRejectsOldWorkerSnapshot() =
        runBlocking {
            val old = room.manga()
            room.db.libraryDeo().removeMangaWithChapters(old.id)
            val replacement = room.manga()
            assertTrue(replacement.id != old.id)
            val covers = CountingCovers()
            val helper = room.helper(covers)

            val result = helper.persistNewChapterNotifications(notificationRefreshRequest(old, room.chapters(old, 2)))
            assertTrue(result is AppResult.Failure)
            assertTrue(room.db.chapterDao().getChaptersByMangaIdR(replacement.id).isEmpty())
            assertTrue(room.updates().isEmpty())
            assertEquals(0, room.sql.chapterInserts.get())
            assertEquals(0, covers.calls)
            assertTrue(posting.posted.isEmpty())
        }

    @Test
    fun notificationServiceFailureDoesNotBlockConstructionOrPersistence() =
        runBlocking {
            val brokenContext = MissingNotificationService(context)
            val cover = NotificationNativeCoverWitness()
            val helper = room.helper(cover.loader, context = brokenContext)
            val manga = room.manga()
            val rows = helper.persistFixtureNotifications(manga, room.chapters(manga, 8)).notifications
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
            val dependencies =
                module {
                    single<LibraryRepository> { room.ownedLibrary }
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
                val receipt = helper.persistFixtureNotifications(manga, room.chapters(manga, 1))
                val rows = receipt.notifications
                helper.displayNotifications(rows)
                room.assertStoredWithRealChapterIds(receipt)
                assertEquals(rows.single().notificationId.toInt(), posting.posted.single().first)
                posting.assertTextOnly()
                assertFalse(context is me.manga.kira.MyApp)
            } finally {
                app.close()
            }
        }

    private fun assertNotificationContent(
        notification: Notification,
        row: LibraryChapterNotification,
    ) {
        assertEquals(row.manga.title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            context.getString(R.string.chapter_is_available, row.chapter.number),
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
}
