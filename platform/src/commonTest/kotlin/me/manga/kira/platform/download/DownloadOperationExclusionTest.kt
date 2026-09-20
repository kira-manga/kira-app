package me.manga.kira.platform.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Common ownership tests; these do not certify Apple callbacks, native task drain or graph wiring. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadOperationExclusionTest {
    @Test
    fun exclusiveBlocksTheFirstCaptureUntilItsWriterActuallyUnwinds() = runTest {
        val gate = DownloadOperationExclusion()
        val finishWriter = CompletableDeferred<Unit>()
        val trace = mutableListOf<String>()
        val writer = launch {
            gate.withExclusive {
                trace += "writer"
                finishWriter.await()
                trace += "rollback-or-commit"
            }
        }
        runCurrent()
        val operation = launch { gate.withOperation { trace += "capture" } }
        runCurrent()
        assertEquals(listOf("writer"), trace)
        finishWriter.complete(Unit)
        writer.join()
        operation.join()
        assertEquals(listOf("writer", "rollback-or-commit", "capture"), trace)
    }

    @Test
    fun busyExclusiveCannotQueueAheadOfFreshCancellationWork() = runTest {
        val gate = DownloadOperationExclusion()
        val producer = gate.withOperation { it.retain() }
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive { error("must not enter") } }
        var cancellationRan = false
        gate.withOperation { cancellationRan = true }
        assertTrue(cancellationRan)
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        producer.release()
        gate.withExclusive { assertTrue(cancellationRan) }
    }

    @Test
    fun nestedStructuredOperationsHaveIndependentHandlesAndKeepTheOuterCaptureOwned() = runTest {
        val gate = DownloadOperationExclusion()
        gate.withOperation { outer ->
            assertSame(outer, currentCoroutineContext()[DownloadOperationExclusion.Operation])
            gate.withOperation { inner ->
                assertFalse(outer === inner)
                assertSame(inner, currentCoroutineContext()[DownloadOperationExclusion.Operation])
                assertFailsWith<IllegalStateException> { gate.withExclusive {} }
            }
            assertSame(outer, currentCoroutineContext()[DownloadOperationExclusion.Operation])
            assertFailsWith<IllegalStateException> { gate.withExclusive {} }
        }
        gate.withExclusive {}
    }

    @Test
    fun duplicateReleaseCannotConsumeAnotherOwnersRetention() = runTest {
        val gate = DownloadOperationExclusion()
        val first = gate.withOperation { it.retain() }
        val second = first.retain()
        first.release()
        first.release()
        assertFailsWith<IllegalStateException> { first.retain() }
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        second.release()
        second.release()
        gate.withExclusive {}
    }

    @Test
    fun cancelledAdmissionNeverCapturesAndDoesNotLeaveAnOwnedHandle() = runTest {
        val gate = DownloadOperationExclusion()
        val finishWriter = CompletableDeferred<Unit>()
        val writer = launch { gate.withExclusive { finishWriter.await() } }
        runCurrent()
        var captured = false
        val reader = launch { gate.withOperation { captured = true } }
        runCurrent()
        reader.cancelAndJoin()
        assertFalse(captured)
        finishWriter.complete(Unit)
        writer.join()
        gate.withExclusive {}
    }

    @Test
    fun producerCancellationRetainsOwnershipThroughRealNonCancellableCleanup() = runTest {
        val gate = DownloadOperationExclusion()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val producer = launch {
            gate.withOperation {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        cleanupFinished.await()
                    }
                }
            }
        }
        runCurrent()
        producer.cancel()
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        cleanupFinished.complete(Unit)
        producer.join()
        gate.withExclusive {}
    }

    @Test
    fun failingExclusiveReleasesOnlyAfterItsFinally() = runTest {
        val gate = DownloadOperationExclusion()
        var unwound = false
        assertFailsWith<IllegalArgumentException> {
            gate.withExclusive {
                try {
                    throw IllegalArgumentException("rollback")
                } finally {
                    unwound = true
                    assertFailsWith<IllegalStateException> { gate.withExclusive {} }
                }
            }
        }
        gate.withOperation { assertTrue(unwound) }
    }

    @Test
    fun exclusiveToOperationUpgradeIsRefusedRatherThanSuspendingUnderItsOwnWriter() = runTest {
        val gate = DownloadOperationExclusion()
        gate.withExclusive {
            assertFailsWith<IllegalStateException> { gate.withOperation {} }
        }
        gate.withOperation {}
    }

    @Test
    fun differentGraphInstanceCannotSilentlyInheritAnOperation() = runTest {
        val first = DownloadOperationExclusion()
        val other = DownloadOperationExclusion()
        first.withOperation {
            assertFailsWith<IllegalStateException> { other.withOperation {} }
            assertFailsWith<IllegalStateException> { other.withExclusive {} }
        }
        first.withExclusive {}
        other.withExclusive {}
    }

    @Test
    fun foreignExclusiveCannotShadowItsAncestorBeforeTryingToRecapture() = runTest {
        val first = DownloadOperationExclusion()
        val other = DownloadOperationExclusion()
        first.withExclusive {
            assertFailsWith<IllegalStateException> {
                other.withExclusive { error("must refuse before shadowing the ancestor context") }
            }
            assertFailsWith<IllegalStateException> { other.withOperation {} }
            assertFailsWith<IllegalStateException> { first.withOperation {} }
        }
        first.withExclusive {}
        other.withExclusive {}
    }

    @Test
    fun exclusiveAssertionRequiresThisGraphsLiveWriterContext() = runTest {
        val gate = DownloadOperationExclusion()
        val other = DownloadOperationExclusion()
        assertFailsWith<IllegalStateException> { gate.requireExclusive() }
        gate.withOperation {
            assertFailsWith<IllegalStateException> { gate.requireExclusive() }
        }
        gate.withExclusive {
            gate.requireExclusive()
            withContext(NonCancellable) { gate.requireExclusive() }
            assertFailsWith<IllegalStateException> { other.requireExclusive() }
        }
        assertFailsWith<IllegalStateException> { gate.requireExclusive() }
    }

    @Test
    fun initialNativeReservationExistsBeforeItsLazyOwnerClaimsIt() = runTest {
        val recovery = DownloadOperationExclusion.recovering()
        assertFailsWith<DownloadOperationBusy> { recovery.exclusion.withExclusive {} }
        recovery.exclusion.withOperation {} // Recovery/cancellation work is not frozen behind a writer.
        val native = recovery.takeOperation()
        assertFailsWith<IllegalStateException> { recovery.takeOperation() }
        val receiver = native.retain()
        native.release()
        assertFailsWith<DownloadOperationBusy> { recovery.exclusion.withExclusive {} }
        receiver.release()
        recovery.exclusion.withExclusive {}
    }
}
