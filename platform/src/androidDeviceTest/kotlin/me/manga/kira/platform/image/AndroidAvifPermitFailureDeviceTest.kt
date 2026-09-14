package me.manga.kira.platform.image

import kotlinx.coroutines.CancellationException
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidAvifPermitFailureDeviceTest {
    @Test
    fun interruptedContendedWorkerCannotEnterOrReleaseTheOwnersPermit() {
        val semaphore = Semaphore(1, true)
        val permit = AndroidAvifPermit(semaphore)
        val entered = AtomicBoolean(false)
        val outcome = WorkerOutcome()
        semaphore.acquire()
        val worker = outcome.start { permit.withNativePermit { entered.set(true) } }
        try {
            awaitAvifPermitCondition { semaphore.hasQueuedThreads() }
            worker.interrupt()
            outcome.await()
            assertFalse(entered.get())
            assertInterrupted(outcome)
            assertEquals(0, semaphore.availablePermits())
        } finally {
            worker.interrupt()
            semaphore.release()
            worker.join(TimeUnit.SECONDS.toMillis(AVIF_PERMIT_WAIT_SECONDS))
        }
        assertFalse(worker.isAlive)
        assertEquals(AVIF_PERMIT_RESULT, permit.withNativePermit { AVIF_PERMIT_RESULT })
        assertEquals(1, semaphore.availablePermits())
    }

    @Test
    fun interruptionAfterAcquireIsCancellationNotAValidResult() {
        val semaphore = Semaphore(1, true)
        val permit = AndroidAvifPermit(semaphore)
        val outcome = WorkerOutcome()
        val returned = AtomicBoolean(false)
        val worker =
            outcome.start {
                permit.withNativePermit { Thread.currentThread().interrupt() }
                returned.set(true)
            }
        try {
            outcome.await()
            assertFalse(returned.get())
            assertInterrupted(outcome)
            assertEquals(1, semaphore.availablePermits())
            assertEquals(AVIF_PERMIT_RESULT, permit.withNativePermit { AVIF_PERMIT_RESULT })
        } finally {
            worker.interrupt()
            worker.join(TimeUnit.SECONDS.toMillis(AVIF_PERMIT_WAIT_SECONDS))
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun synchronousThrowReleasesExactlyOnePermit() {
        val semaphore = Semaphore(1, true)
        val permit = AndroidAvifPermit(semaphore)
        val expected = IllegalStateException("probe failed")
        assertSame(expected, assertFailsWith<IllegalStateException> { permit.withNativePermit { throw expected } })
        assertEquals(1, semaphore.availablePermits())
        assertEquals(AVIF_PERMIT_RESULT, permit.withNativePermit { AVIF_PERMIT_RESULT })
        assertEquals(1, semaphore.availablePermits())
    }

    private fun assertInterrupted(outcome: WorkerOutcome) {
        assertIs<CancellationException>(outcome.failure.get())
        assertIs<InterruptedException>(outcome.failure.get()?.cause)
        assertTrue(outcome.interrupted.get())
    }

    private class WorkerOutcome {
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean(false)
        private val finished = CountDownLatch(1)

        fun start(block: () -> Unit): Thread =
            Thread {
                try {
                    block()
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted)
                    finished.countDown()
                }
            }.apply {
                name = "avif-permit-test"
                isDaemon = true
                start()
            }

        fun await() {
            assertTrue(finished.await(AVIF_PERMIT_WAIT_SECONDS, TimeUnit.SECONDS))
        }
    }
}
