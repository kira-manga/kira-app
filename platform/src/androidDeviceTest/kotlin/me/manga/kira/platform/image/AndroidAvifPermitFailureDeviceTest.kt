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

    @Test
    fun workerFailureAndInterruptAreVisibleBeforeCompletionEvenForFatalThrowables() {
        val expected = AssertionError("fatal worker probe")
        val outcome = WorkerOutcome()
        val worker =
            outcome.start {
                Thread.currentThread().interrupt()
                throw expected
            }
        try {
            outcome.await()
            assertSame(expected, outcome.failure.get())
            assertTrue(outcome.interrupted.get())
        } finally {
            worker.join(TimeUnit.SECONDS.toMillis(AVIF_PERMIT_WAIT_SECONDS))
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun normallyReturningWorkerPublishesItsInterruptStateWithoutAFailure() {
        val outcome = WorkerOutcome()
        val worker = outcome.start { Thread.currentThread().interrupt() }
        try {
            outcome.await()
            assertEquals(null, outcome.failure.get())
            assertTrue(outcome.interrupted.get())
        } finally {
            worker.join(TimeUnit.SECONDS.toMillis(AVIF_PERMIT_WAIT_SECONDS))
        }
        assertFalse(worker.isAlive)
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
                block()
                complete(Thread.currentThread())
            }.apply {
                name = "avif-permit-test"
                isDaemon = true
                // This owned thread is an outcome boundary. On failure the handler runs before
                // termination; no finally may release awaiters before the Throwable is recorded.
                uncaughtExceptionHandler =
                    Thread.UncaughtExceptionHandler { thread, error ->
                        failure.set(error)
                        complete(thread)
                    }
                start()
            }

        private fun complete(thread: Thread) {
            interrupted.set(thread.isInterrupted)
            finished.countDown()
        }

        fun await() {
            assertTrue(finished.await(AVIF_PERMIT_WAIT_SECONDS, TimeUnit.SECONDS))
        }
    }
}
