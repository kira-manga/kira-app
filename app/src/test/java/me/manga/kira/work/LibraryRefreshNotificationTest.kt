package me.manga.kira.work

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.database.sqlite.SQLiteException
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.util.notification.CHAPTER_NOTIFICATION_CHANNEL
import me.manga.kira.core.util.notification.NotificationCoverLoader
import me.manga.kira.core.util.notification.NotificationCovers
import me.manga.kira.core.util.notification.NotificationPostingShadow
import me.manga.kira.core.util.notification.NotificationRoomFixture
import me.manga.kira.core.util.notification.notificationCoverCalls
import me.manga.kira.locale.LocaleOnlyTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LocaleOnlyTestApplication::class, shadows = [NotificationPostingShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRefreshNotificationTest {
    private lateinit var context: Application
    private lateinit var room: NotificationRoomFixture
    private lateinit var posting: NotificationPostingShadow
    private var activeWork: NotificationWorkerRun? = null

    @Before
    fun open() {
        context = RuntimeEnvironment.getApplication()
        room = NotificationRoomFixture(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        posting = Shadow.extract(manager)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @After
    fun close() {
        check(activeWork?.mayUseRoom != true) { "Retain Room: actual startWork coroutine was not joined" }
        room.close()
    }

    @Test
    fun startWorkCancellationJoinsWorkerAndBlockingCoverBeforeAnyLatePost() =
        runBlocking {
            val covers = BlockingRefreshCover()
            val run = NotificationWorkerRun().also { activeWork = it }
            try {
                startRefresh(run, covers)
                val item = awaitBlockedDecode(covers)
                val committed = room.updates()
                assertEquals(2, committed.size)
                assertEquals(1, covers.requests.get())
                cancelBlockedDecode(run, covers, item)

                covers.release()
                run.join()

                assertFinishedCancellation(run, covers, item)
                assertEquals(committed, room.updates())
                assertTrue(chapterPosts().isEmpty())
                RefreshReplacementWitness(context, room, posting).assertNoRepeat(committed) { activeWork = it }
                assertFollowingMangaCanPost(covers.requests)
            } finally {
                covers.release()
                run.close()
            }
        }

    @Test
    fun foregroundChannelSecurityFailureStillCommitsUpdatesAndCompletesStartWork() =
        runBlocking {
            val requests = AtomicInteger()
            val run = NotificationWorkerRun().also { activeWork = it }
            posting.failingChannel = "library_refresh"
            try {
                startRefresh(run, NotificationCoverLoader(notificationCoverCalls(requests = requests)))
                run.join()

                assertEquals(
                    ListenableWorker.Result.success(),
                    run.future.get(NOTIFICATION_WAIT_MILLIS, TimeUnit.MILLISECONDS),
                )
                assertEquals(1, posting.rejectedChannels.get())
                val rows = room.updates()
                assertEquals(2, rows.size)
                room.assertStoredWithRealChapterIds(rows)
                assertEquals(rows.asReversed().map { it.id.toInt() }, chapterPosts().map { it.first })
                assertEquals(1, requests.get())
            } finally {
                run.close()
                posting.failingChannel = null
            }
        }

    @Test
    fun noNewChaptersRemainsSuccessfulWithoutCoverOrNewUpdates() =
        runBlocking {
            val manga = room.manga()
            val chapters = room.chapters(manga, 2)
            room.db.chapterDao().insertChapters(chapters)
            val repository = room.repository()
            val covers = NoCoverExpected()
            val helper = room.helper(covers)
            val worker = notificationRefreshWorker(context, repository, helper, chapters.asReversed())
            assertTrue(worker.refreshWork.refreshManga(manga))
            assertEquals(
                2,
                room.db
                    .chapterDao()
                    .getChaptersByMangaIdR(manga.id)
                    .size,
            )
            assertTrue(room.updates().isEmpty())
            assertEquals(0, covers.calls)
            assertTrue(chapterPosts().isEmpty())
        }

    @Test
    fun atomicWorkerInsertErrorIsFailureWithoutRetryOrCover() =
        runBlocking {
            val manga = room.manga()
            room.sql.beforeChapterInsert = { ordinal ->
                if (ordinal == 1) throw SQLiteException("fixture_atomic_worker_insert")
            }
            val repository = room.repository()
            val covers = NoCoverExpected()
            val helper = room.helper(covers)
            val worker =
                notificationRefreshWorker(context, repository, helper, room.chapters(manga, 2).asReversed())
            assertFalse(worker.refreshWork.refreshManga(manga))
            assertEquals(1, room.sql.chapterInserts.get())
            assertTrue(
                room.db
                    .chapterDao()
                    .getChaptersByMangaIdR(manga.id)
                    .isEmpty(),
            )
            assertTrue(room.updates().isEmpty())
            assertEquals(0, covers.calls)
            assertTrue(chapterPosts().isEmpty())
        }

    @Test
    fun optionalCoverExpiryOutsideThirtySecondPersistenceBudgetPreservesCommittedSuccess() =
        runBlocking {
            val deadline = RefreshDeadlineWitness(room)
            val manga = room.manga()
            val repository = room.repository()
            val helper = room.helper(deadline.covers, deadline.discoveries)
            val worker =
                notificationRefreshWorker(context, repository, helper, room.chapters(manga, 2).asReversed())
            val item = async(deadline.dispatcher) { worker.refreshWork.refreshManga(manga) }
            try {
                assertDeadlineSplit(deadline, item)
            } finally {
                item.cancel()
                withContext(NonCancellable) {
                    deadline.scheduler.awaitCondition { item.isCompleted }
                    item.join()
                }
            }
        }

    private suspend fun startRefresh(
        run: NotificationWorkerRun,
        covers: NotificationCovers,
    ) {
        val manga = room.manga()
        val repository = room.repository()
        val helper = room.helper(covers)
        val worker =
            notificationRefreshWorker(
                context,
                repository,
                helper,
                room.chapters(manga, 2).asReversed(),
                run::captureExecution,
            )
        run.start(worker)
    }

    private suspend fun awaitBlockedDecode(covers: BlockingRefreshCover): Job =
        withTimeout(NOTIFICATION_WAIT_MILLIS) {
            covers.decodeEntered.await()
            covers.itemEntered.await()
        }

    private suspend fun cancelBlockedDecode(
        run: NotificationWorkerRun,
        covers: BlockingRefreshCover,
        item: Job,
    ) {
        val execution = run.execution()
        assertTrue(run.stopAndCancel())
        withTimeout(NOTIFICATION_WAIT_MILLIS) {
            while (!execution.isCancelled || !item.isCancelled) delay(1)
        }
        assertTrue(run.worker.isStopped)
        assertEquals(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY, run.worker.stopReason)
        assertTrue(run.future.isCancelled)
        assertFalse(covers.decodeExited.isCompleted)
        assertFalse(item.isCompleted)
        assertFalse(execution.isCompleted)
        assertTrue(chapterPosts().isEmpty())
    }

    private suspend fun assertFinishedCancellation(
        run: NotificationWorkerRun,
        covers: BlockingRefreshCover,
        item: Job,
    ) {
        withTimeout(NOTIFICATION_WAIT_MILLIS) { item.join() }
        assertTrue(run.completion.await() is CancellationException)
        assertTrue(run.execution().isCompleted)
        assertTrue(item.isCompleted)
        assertTrue(covers.decodeExited.isCompleted)
        assertEquals(1, covers.requests.get())
    }

    private suspend fun assertFollowingMangaCanPost(requests: AtomicInteger) {
        val following = room.manga("following")
        val helper = room.helper(NotificationCoverLoader(notificationCoverCalls(requests = requests)))
        val rows = helper.persistNewChapterNotifications(following, room.chapters(following, 1))
        helper.displayNotifications(rows)
        assertEquals(2, requests.get())
        assertEquals(rows.map { it.id.toInt() }, chapterPosts().map { it.first })
    }

    private suspend fun assertDeadlineSplit(
        deadline: RefreshDeadlineWitness,
        item: Deferred<Boolean>,
    ) {
        val scheduler = deadline.scheduler
        scheduler.awaitCondition { deadline.storageWaiting.isCompleted }
        scheduler.advanceTimeBy(25_000)
        scheduler.awaitCondition { deadline.coverWaiting.isCompleted }
        val committed = room.updates()
        assertEquals(2, committed.size)
        assertEquals(25_000L, scheduler.currentTime)
        scheduler.advanceTimeBy(6_000)
        scheduler.runCurrent()
        assertFalse(item.isCompleted)
        assertFalse(deadline.coverExpired.isCompleted)
        assertTrue(chapterPosts().isEmpty())
        scheduler.advanceTimeBy(4_000)
        scheduler.awaitCondition { item.isCompleted }
        assertTrue(deadline.coverExpired.isCompleted)
        assertTrue(item.await())
        assertEquals(35_000L, scheduler.currentTime)
        assertEquals(committed, room.updates())
        assertEquals(1, deadline.attempts.get()) // Exactly one atomic chapter/Updates persistence call.
        assertEquals(committed.asReversed().map { it.id.toInt() }, chapterPosts().map { it.first })
        chapterPosts().forEach { assertNull(it.second.getLargeIcon()) }
    }

    private fun chapterPosts() = posting.posted.filter { it.second.channelId == CHAPTER_NOTIFICATION_CHANNEL }

    /** Pump only ready test continuations while Room/IO returns; never auto-advance an IO timeout. */
    private suspend fun TestCoroutineScheduler.awaitCondition(ready: () -> Boolean) {
        withTimeout(NOTIFICATION_WAIT_MILLIS) {
            while (!ready()) {
                runCurrent()
                delay(1)
            }
            runCurrent()
        }
    }
}
