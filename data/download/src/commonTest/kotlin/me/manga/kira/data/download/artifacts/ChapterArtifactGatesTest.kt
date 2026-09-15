package me.manga.kira.data.download.artifacts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class ChapterArtifactGatesTest {
    @Test
    fun custodyDrainsOnlyWhenEveryAdmittedUseActuallyReleases() = runTest {
        val gates = ChapterArtifactGates()
        val gate = gates.chapter(7)
        assertSame(gate, gates.chapter(7))
        gate.transition.withLock {
            gate.acquire("original")
            gate.acquire("original")
        }
        val drained = gate.transition.withLock { requireNotNull(gate.drained("original")) }
        gate.transition.withLock { gate.release("original") }
        assertFalse(drained.isCompleted)
        gate.transition.withLock { gate.release("original") }
        assertTrue(drained.isCompleted)
    }

    @Test
    fun closingParentRefusesCallbackAdmissionInsteadOfDeadlockingProducerDrain() = runTest {
        val parent = ParentArtifactGate()
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val use = async(start = CoroutineStart.UNDISPATCHED) {
            parent.admit { admitted.complete(Unit); release.await() }
        }
        admitted.await()
        val removal = async(start = CoroutineStart.UNDISPATCHED) { parent.remove { "removed" } }
        assertFalse(removal.isCompleted)
        var reopened: Deferred<Unit>? = null
        assertNull(parent.admit(onClosed = { reopened = it }) {
            error("A draining callback must not enqueue another same-parent producer")
        })
        val wake = requireNotNull(reopened)
        assertFalse(wake.isCompleted)
        release.complete(Unit)
        use.await()
        assertEquals("removed", removal.await())
        assertTrue(wake.isCompleted)
        wake.await() // Reopening BEFORE the waiter suspends must not lose the wake.
        assertEquals("admitted", parent.admit { "admitted" })
    }
}
