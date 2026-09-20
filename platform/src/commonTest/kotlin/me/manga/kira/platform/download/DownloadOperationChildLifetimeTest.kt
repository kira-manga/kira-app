package me.manga.kira.platform.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Explicit child-handoff rules, not a substitute for native terminal and receiver acknowledgements. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadOperationChildLifetimeTest {
    @Test
    fun cancelledJoinDoesNotReleaseAnApplicationScopeChild() = runTest {
        val gate = DownloadOperationExclusion()
        val childFinished = CompletableDeferred<Unit>()
        val childStarted = CompletableDeferred<Unit>()
        val parent = launch {
            gate.withOperation { operation ->
                val childOwnership = operation.retain()
                val child = backgroundScope.launch(childOwnership) {
                    gate.withOperation {
                        childStarted.complete(Unit)
                        childFinished.await()
                    }
                }
                child.invokeOnCompletion { childOwnership.release() }
                child.join()
            }
        }
        runCurrent()
        assertTrue(childStarted.isCompleted)
        parent.cancelAndJoin()
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        childFinished.complete(Unit)
        runCurrent()
        gate.withExclusive {}
    }

    @Test
    fun lazyNeverStartedChildReleasesItsOwnHandleOnActualJobCompletion() = runTest {
        val gate = DownloadOperationExclusion()
        var ran = false
        val childOwnership = gate.withOperation { it.retain() }
        val child = backgroundScope.launch(childOwnership, start = CoroutineStart.LAZY) { ran = true }
        child.invokeOnCompletion { childOwnership.release() }
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        child.cancelAndJoin()
        assertFalse(ran)
        gate.withExclusive {}
    }

    @Test
    fun lateAcknowledgementReleasesOnlyItsEventNotItsNativeTaskOrSiblingReceiver() = runTest {
        val gate = DownloadOperationExclusion()
        val nativeTask = gate.withOperation { it.retain() }
        val firstReceiver = nativeTask.retain()
        val secondReceiver = nativeTask.retain()
        firstReceiver.release()
        firstReceiver.release()
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        nativeTask.release() // Actual terminal callback, not a cancellation request.
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        secondReceiver.release()
        gate.withExclusive {}
        firstReceiver.release() // A duplicate old acknowledgement cannot affect a later writer.
        gate.withExclusive {}
    }

    @Test
    fun releasedContextCannotRecaptureWithAnOldOperation() = runTest {
        val gate = DownloadOperationExclusion()
        val old = gate.withOperation { it.retain() }
        old.release()
        var captured = false
        val stale = backgroundScope.launch(old) {
            assertFailsWith<IllegalStateException> { gate.withOperation { captured = true } }
        }
        stale.join()
        assertFalse(captured)
        gate.withExclusive {}
    }

    @Test
    fun releasedOperationContextCannotUpgradeToAnExclusiveWriter() = runTest {
        val gate = DownloadOperationExclusion()
        val old = gate.withOperation { it.retain() }
        old.release()
        withContext(old) {
            assertFailsWith<IllegalStateException> { gate.withExclusive {} }
        }
        gate.withExclusive {}
    }

    @Test
    fun staleExclusiveContextCannotClaimEitherAnUnownedGateOrItsSuccessor() = runTest {
        val gate = DownloadOperationExclusion()
        val stale = gate.withExclusive { currentCoroutineContext().minusKey(Job) }
        withContext(stale) {
            assertFailsWith<IllegalStateException> { gate.requireExclusive() }
        }
        gate.withExclusive {
            withContext(stale) {
                assertFailsWith<IllegalStateException> { gate.requireExclusive() }
            }
            gate.requireExclusive()
        }
    }
}
