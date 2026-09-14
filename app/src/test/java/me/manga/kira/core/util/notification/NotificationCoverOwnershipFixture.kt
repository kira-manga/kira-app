package me.manga.kira.core.util.notification

import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Test-owned synchronous sinks retain real covers until explicitly released and joined. */
internal class RetainedNotificationCovers(private val calls: Call.Factory) {
    private val entered = LinkedBlockingQueue<Unit>()
    private val release = Semaphore(0)
    val active = AtomicInteger()
    val peak = AtomicInteger()

    fun launchIn(scope: CoroutineScope, count: Int): List<Job> =
        List(count) { scope.launch(Dispatchers.Default) { load() } }

    private suspend fun load() {
        NotificationCoverLoader(calls).withCover("https://example.test/held", { true }) { bitmap ->
            assertNotNull(bitmap)
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            entered.add(Unit)
            try {
                assertTrue(release.tryAcquire(10, TimeUnit.SECONDS))
            } finally {
                active.decrementAndGet()
            }
        }
    }

    suspend fun awaitEntered(count: Int = 1) =
        withContext(Dispatchers.IO) {
            repeat(count) { assertNotNull(entered.poll(5, TimeUnit.SECONDS)) }
        }

    fun release(count: Int = 1) = release.release(count)

    suspend fun releaseAndJoin(holders: List<Job>, following: Job? = null) {
        release(holders.size)
        withContext(NonCancellable) {
            holders.joinAll()
            following?.join()
        }
    }
}

/** Non-preemptible work probe around the real decoder; not a claim about native preemption. */
internal class BlockingNotificationDecode {
    private val entered = CountDownLatch(1)
    private val release = Semaphore(0)
    val posts = AtomicInteger()
    private val decoder =
        NotificationCoverDecoder { bytes, offset, size, options ->
            if (!options.inJustDecodeBounds) {
                entered.countDown()
                assertTrue(release.tryAcquire(10, TimeUnit.SECONDS))
            }
            BitmapFactory.decodeByteArray(bytes, offset, size, options)
        }

    suspend fun load(calls: Call.Factory) {
        NotificationCoverLoader(calls, decoder).withCover("https://example.test/decoding", { true }) {
            posts.incrementAndGet()
        }
    }

    suspend fun awaitEntered() =
        withContext(Dispatchers.IO) {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
        }

    fun release() = release.release()
}
