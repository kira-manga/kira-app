package me.manga.kira.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.platform.image.ScreenshotProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderShareCoordinatorTest {
    @Test
    fun burstAndTwoOwnersHoldOneCaptureUntilDispatchReturns() = runTest {
        val coordinator = ReaderShareCoordinator { throw it }
        val other = readerShareOwner(backgroundScope)
        val stages = RetainedReaderShare()
        val first = checkNotNull(coordinator.tryShare(backgroundScope, stages::capture, stages::share))
        assertEquals(0, stages.captures)
        assertBusyRejects(coordinator, other, stages)
        runCurrent()
        assertEquals(1, stages.captures)
        stages.captured.complete(Unit)
        runCurrent()
        assertEquals(1, stages.encodes)
        assertBusyRejects(coordinator, backgroundScope, stages)
        stages.encoded.complete(Unit)
        runCurrent()
        assertEquals(1, stages.writes)
        assertBusyRejects(coordinator, other, stages)
        stages.dispatched.complete(Unit)
        first.join()
        assertFalse(coordinator.isSharing.value)
        assertEquals(listOf(1, 1, 1, 1), stages.counts())
        checkNotNull(coordinator.tryShare(other, stages::capture, stages::share)).join()
        assertEquals(listOf(2, 2, 2, 2), stages.counts())
        assertEquals(1, stages.peakEncoders)
    }

    @Test
    fun cancelledBeforeStartNeverCapturesAndReleasesAdmission() = runTest {
        val coordinator = ReaderShareCoordinator { throw it }
        val owner = readerShareOwner(backgroundScope)
        var captures = 0
        val operation = checkNotNull(coordinator.tryShare(owner, { ++captures }, {}))
        owner.cancel()
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(0, captures)
        assertFalse(coordinator.isSharing.value)
        assertNull(coordinator.tryShare(owner, { ++captures }, {}))
        checkNotNull(coordinator.tryShare(backgroundScope, { ++captures }, {})).join()
        assertEquals(1, captures)
        assertFalse(coordinator.isSharing.value)
    }

    @Test
    fun cancellationWaitsForNonCooperativeEncodeBeforeRearming() = runTest {
        val coordinator = ReaderShareCoordinator { throw it }
        val stages = RetainedReaderShare(ignoreEncodeCancellation = true)
        stages.captured.complete(Unit)
        stages.dispatched.complete(Unit)
        val operation = checkNotNull(coordinator.tryShare(backgroundScope, stages::capture, stages::share))
        runCurrent()
        assertEquals(1, stages.activeEncoders)
        operation.cancel()
        runCurrent()
        assertTrue(operation.isCancelled)
        assertFalse(operation.isCompleted)
        assertBusyRejects(coordinator, backgroundScope, stages)
        stages.encoded.complete(Unit)
        operation.join()
        assertEquals(listOf(1, 1, 0, 0), stages.counts())
        assertEquals(0, stages.activeEncoders)
        assertFalse(coordinator.isSharing.value)
        checkNotNull(coordinator.tryShare(backgroundScope, stages::capture, stages::share)).join()
        assertEquals(listOf(2, 2, 1, 1), stages.counts())
        assertEquals(1, stages.peakEncoders)
    }

    @Test
    fun nullAndFailedStagesReleaseWithoutSwallowingCancellation() = runTest {
        val failures = mutableListOf<Throwable>()
        val coordinator = ReaderShareCoordinator(failures::add)
        val captureFailure = IllegalStateException("capture")
        val encodeFailure = IllegalArgumentException("encode")
        checkNotNull(coordinator.tryShare<Int>(backgroundScope, { null }, { error("No image") })).join()
        assertFalse(coordinator.isSharing.value)
        checkNotNull(coordinator.tryShare<Int>(backgroundScope, { throw captureFailure }, {})).join()
        checkNotNull(coordinator.tryShare(backgroundScope, { 1 }, { throw encodeFailure })).join()
        val cancelled = checkNotNull(coordinator.tryShare<Int>(backgroundScope, { throw CancellationException() }, {}))
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertFalse(coordinator.isSharing.value)
        assertEquals(2, failures.size)
        assertSame(captureFailure, failures[0])
        assertSame(encodeFailure, failures[1])
        var handoffs = 0
        checkNotNull(coordinator.tryShare(backgroundScope, { 1 }, { handoffs++ })).join()
        assertEquals(1, handoffs)
    }

    @Test
    fun inactiveReentryAndClosedAttachmentCannotCapture() = runTest {
        val owner = ReaderShareLifecycleOwner()
        val coordinator = ReaderShareCoordinator { throw it }
        val sharing = ReaderSharing(backgroundScope, owner.lifecycle, coordinator, NoImageScreenshotProvider)
        val capture = ReaderCaptureProbe()
        sharing.request { capture.empty() }
        assertEquals(0, capture.calls)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        sharing.request { capture.hold() }
        runCurrent()
        assertTrue(capture.active)
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        sharing.cancel()
        runCurrent()
        assertFalse(capture.active)
        assertFalse(coordinator.isSharing.value)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        sharing.close()
        sharing.request { capture.empty() }
        runCurrent()
        assertEquals(1, capture.calls)
    }
}

private fun readerShareOwner(parent: CoroutineScope): CoroutineScope =
    CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

private fun assertBusyRejects(coordinator: ReaderShareCoordinator, owner: CoroutineScope, stages: RetainedReaderShare) {
    assertTrue(coordinator.isSharing.value)
    repeat(100) { assertNull(coordinator.tryShare(owner, stages::capture, stages::share)) }
}

private class RetainedReaderShare(private val ignoreEncodeCancellation: Boolean = false) {
    val captured = CompletableDeferred<Unit>()
    val encoded = CompletableDeferred<Unit>()
    val dispatched = CompletableDeferred<Unit>()
    var captures = 0
    var encodes = 0
    var writes = 0
    var handoffs = 0
    var activeEncoders = 0
    var peakEncoders = 0

    suspend fun capture(): Int {
        captures++
        captured.await()
        return captures
    }

    suspend fun share(snapshot: Int) {
        assertEquals(captures, snapshot)
        encodes++
        activeEncoders++
        peakEncoders = maxOf(peakEncoders, activeEncoders)
        try {
            if (ignoreEncodeCancellation) withContext(NonCancellable) { encoded.await() } else encoded.await()
        } finally {
            activeEncoders--
        }
        currentCoroutineContext().ensureActive()
        writes++
        dispatched.await()
        handoffs++
    }

    fun counts(): List<Int> = listOf(captures, encodes, writes, handoffs)
}

private class ReaderShareLifecycleOwner : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }
}

private object NoImageScreenshotProvider : ScreenshotProvider {
    override suspend fun shareBitmapBytes(bytes: ByteArray, title: String) {
        error("No bitmap was captured")
    }
}

private class ReaderCaptureProbe {
    var calls = 0
    var active = false

    fun empty(): ImageBitmap? {
        calls++
        return null
    }

    suspend fun hold(): ImageBitmap? {
        calls++
        active = true
        try {
            awaitCancellation()
        } finally {
            active = false
        }
    }
}
