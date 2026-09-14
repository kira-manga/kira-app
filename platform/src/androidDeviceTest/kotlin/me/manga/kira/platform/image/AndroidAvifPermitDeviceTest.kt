package me.manga.kira.platform.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidAvifPermitDeviceTest {
    @Test
    fun contendedCancellationDoesNotReleaseAnotherOwnersPermit() =
        runTest {
            val semaphore = Semaphore(1, true)
            val permit = AndroidAvifPermit(semaphore)
            val entered = AtomicBoolean(false)
            semaphore.acquire()
            val waiting =
                launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    permit.withPermit { entered.set(true) }
                }
            try {
                withContext(Dispatchers.Default) { awaitAvifPermitCondition { semaphore.hasQueuedThreads() } }
                waiting.cancelAndJoin()
                assertFalse(entered.get())
                assertEquals(0, semaphore.availablePermits())
            } finally {
                waiting.cancel()
                semaphore.release()
            }
            assertEquals(42, permit.withPermit { 42 })
            assertEquals(1, semaphore.availablePermits())
        }

    @Test
    fun cancellationAfterEnteringTheOperationReleasesExactlyOnePermit() =
        runTest {
            val semaphore = Semaphore(1, true)
            val permit = AndroidAvifPermit(semaphore)
            val entered = CompletableDeferred<Unit>()
            val owner =
                launch {
                    permit.withPermit {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            try {
                entered.await()
                assertEquals(0, semaphore.availablePermits())
                owner.cancelAndJoin()
                assertEquals(1, semaphore.availablePermits())
                assertEquals(42, permit.withNativePermit { 42 })
            } finally {
                owner.cancel()
            }
        }

    @Test
    fun throwingOperationReleasesThePermitAndPreservesTheFailure() =
        runTest {
            val semaphore = Semaphore(1, true)
            val permit = AndroidAvifPermit(semaphore)
            val expected = IllegalStateException("decode failed")
            val actual = assertFailsWith<IllegalStateException> { permit.withPermit { throw expected } }
            assertSame(expected, actual)
            assertEquals(1, semaphore.availablePermits())
            assertEquals(42, permit.withPermit { 42 })
            assertEquals(1, semaphore.availablePermits())
        }

    @Test
    fun cancellationAfterAcquireBeforeReturnDispatchCannotLoseOwnership() {
        val semaphore = Semaphore(1, true)
        val permit = AndroidAvifPermit(semaphore)
        val dispatcher = AvifPermitReturnDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = AtomicBoolean(false)
        semaphore.acquire()
        var initiallyHeld = true
        var heldTask: Runnable? = null
        val waiting =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                permit.withPermit { entered.set(true) }
            }
        try {
            awaitAvifPermitCondition { semaphore.hasQueuedThreads() }
            semaphore.release()
            initiallyHeld = false
            heldTask = dispatcher.takeTask()
            assertEquals(0, semaphore.availablePermits())
            val resume = checkNotNull(heldTask)
            heldTask = null
            cancelAcquiredReturn(waiting, resume, entered, semaphore)
            assertEquals(42, permit.withNativePermit { 42 })
        } finally {
            scope.cancel()
            if (initiallyHeld) semaphore.release()
            dispatcher.finishCancelled(waiting, heldTask)
        }
    }

    private fun cancelAcquiredReturn(
        waiting: Job,
        resume: Runnable,
        entered: AtomicBoolean,
        semaphore: Semaphore,
    ) {
        // The real IO acquisition block has completed and its return dispatch is held.
        waiting.cancel()
        resume.run()
        assertTrue(waiting.isCompleted)
        assertFalse(entered.get())
        assertEquals(1, semaphore.availablePermits())
    }
}
