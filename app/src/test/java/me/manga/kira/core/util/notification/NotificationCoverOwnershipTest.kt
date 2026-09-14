package me.manga.kira.core.util.notification

import android.app.Application
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCoverOwnershipTest {
    @Test
    fun separateLoaders_shareTwoPermitsUntilSynchronousFanOutReturns() =
        runBlocking {
            val requests = AtomicInteger()
            val retained = RetainedNotificationCovers(notificationCoverCalls(requests = requests))
            val jobs = retained.launchIn(this, 5)
            try {
                retained.awaitEntered(2)
                assertEquals(2, requests.get())
                assertEquals(2, retained.active.get())
                retained.release()
                retained.awaitEntered()
                assertEquals(3, requests.get())
            } finally {
                retained.releaseAndJoin(jobs)
            }
            assertEquals(5, requests.get())
            assertEquals(2, retained.peak.get())
            assertEquals(0, retained.active.get())
        }

    @Test
    fun revokedWhileQueued_doesNotFetchDecodeOrPost() =
        runBlocking {
            val requests = AtomicInteger()
            val calls = notificationCoverCalls(requests = requests)
            val retained = RetainedNotificationCovers(calls)
            val holders = retained.launchIn(this, 2)
            val allowed = AtomicBoolean(true)
            val posts = AtomicInteger()
            var queued: Job? = null
            try {
                retained.awaitEntered(2)
                queued =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        NotificationCoverLoader(calls).withCover("https://example.test/revoked", allowed::get) {
                            posts.incrementAndGet()
                        }
                    }
                assertFalse(queued.isCompleted)
                allowed.set(false)
            } finally {
                retained.releaseAndJoin(holders, queued)
            }
            assertEquals(2, requests.get())
            assertEquals(0, posts.get())
        }

    @Test
    fun queueAndLoadShareTenSeconds_butTextFallbackNeedsNoImagePermit() =
        runTest {
            val requests = AtomicInteger()
            val calls = notificationCoverCalls(requests = requests)
            val retained = RetainedNotificationCovers(calls)
            val holders = retained.launchIn(this, 2)
            val text = AtomicInteger()
            var queued: Job? = null
            try {
                retained.awaitEntered(2)
                queued = launch { loadTextOnly(calls, text) }
                runCurrent()
                advanceTimeBy(NotificationCoverLimits.BUDGET_MILLIS + 1)
                runCurrent()
                assertTrue(queued.isCompleted)
                assertEquals(1, text.get())
                assertEquals(2, requests.get())
                assertTrue(holders.none { it.isCompleted })
            } finally {
                retained.releaseAndJoin(holders, queued)
            }
        }

    @Test
    fun cancelledBlockingDecode_keepsItsPermitUntilActualWorkExits() =
        runBlocking {
            val requests = AtomicInteger()
            val calls = notificationCoverCalls(requests = requests)
            val blocking = BlockingNotificationDecode()
            val retained = RetainedNotificationCovers(calls)
            val followingPosts = AtomicInteger()
            val decoding = launch(Dispatchers.Default) { blocking.load(calls) }
            var holders = emptyList<Job>()
            var following: Job? = null
            try {
                blocking.awaitEntered()
                holders = retained.launchIn(this, 1)
                retained.awaitEntered()
                following = launch(start = CoroutineStart.UNDISPATCHED) { loadFollowing(calls, followingPosts) }
                assertCancellationStillOwned(decoding, following, requests)
                blocking.release()
                decoding.join()
                following.join()
                assertEquals(0, blocking.posts.get())
                assertEquals(1, followingPosts.get())
                assertEquals(3, requests.get())
            } finally {
                blocking.release()
                withContext(NonCancellable) { decoding.join() }
                retained.releaseAndJoin(holders, following)
            }
        }

    @Test
    fun cancellationAfterRealDecode_preventsPost_andNextMangaCanLoad() =
        runBlocking {
            val posts = AtomicInteger()
            lateinit var worker: Job
            val decoder =
                NotificationCoverDecoder { bytes, offset, size, options ->
                    BitmapFactory.decodeByteArray(bytes, offset, size, options).also {
                        if (!options.inJustDecodeBounds) worker.cancel()
                    }
                }
            worker =
                launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    NotificationCoverLoader(notificationCoverCalls(), decoder)
                        .withCover("https://example.test/cancelled", { true }) { posts.incrementAndGet() }
                }
            worker.start()
            worker.join()
            assertTrue(worker.isCancelled)
            assertEquals(0, posts.get())
            loadFollowing(notificationCoverCalls(), posts)
            assertEquals(1, posts.get())
        }
}

private fun assertCancellationStillOwned(decoding: Job, following: Job, requests: AtomicInteger) {
    decoding.cancel()
    assertTrue(decoding.isCancelled)
    assertFalse(decoding.isCompleted)
    assertFalse(following.isCompleted)
    assertEquals(2, requests.get())
}

private suspend fun loadTextOnly(calls: Call.Factory, posts: AtomicInteger) {
    NotificationCoverLoader(calls).withCover("https://example.test/queued", { true }) {
        assertNull(it)
        posts.incrementAndGet()
    }
}

private suspend fun loadFollowing(calls: Call.Factory, posts: AtomicInteger) {
    NotificationCoverLoader(calls).withCover("https://example.test/following", { true }) {
        assertNotNull(it)
        posts.incrementAndGet()
    }
}
