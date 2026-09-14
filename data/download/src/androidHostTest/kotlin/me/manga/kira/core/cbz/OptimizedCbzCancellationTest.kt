package me.manga.kira.core.cbz

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OptimizedCbzCancellationTest {
    @Test
    fun sameManagerSerializesCallsAndCancelledWaiterDoesNoDecode() =
        cbzHostTest { fixture -> CbzQueuedCancellationCase(fixture).verify(this) }

    @Test
    fun activeCancellationAfterWrittenRegionRecyclesAndRollsBackOnlyTemp() =
        cbzHostTest { fixture -> CbzActiveCancellationCase(fixture).verify(this) }

    @Test
    fun publishedArchiveSurvivesCancelledWithContextReturn() =
        cbzHostTest { fixture ->
            val paths = fixture.pages()
            val manager = OptimizedCbzManager(fixture.context, cbzTier(), output = CbzHostArchiveOutput())
            CbzReturnDispatcher().use { caller ->
                val conversion = async(caller) { manager.createCbzParallel(paths, fixture.mangaId, 1L) }
                try {
                    withTimeout(CBZ_GATE_TIMEOUT_SECONDS * CBZ_MILLIS_PER_SECOND) { caller.returned.await() }
                    assertFalse(conversion.isCompleted)
                    fixture.assertArchive(CBZ_SMALL_PAGE_DIMENSIONS)
                    assertTrue(paths.none { File(it).exists() })
                    conversion.cancel()
                } finally {
                    caller.close()
                }
                assertFailsWith<CancellationException> { conversion.await() }
                conversion.join()
            }
            fixture.assertArchive(CBZ_SMALL_PAGE_DIMENSIONS)
            fixture.assertNoTemporary()
        }
}

private class CbzQueuedCancellationCase(
    private val fixture: CbzHostFixture,
) {
    private val first = fixture.pages()
    private val second = fixture.pages(chapter = 2L)
    private val decoder = CbzObservedDecoder()
    private val encodes = AtomicInteger()

    suspend fun verify(scope: CoroutineScope) {
        CbzEncodeGate().use { gate ->
            val archive = CbzHostArchiveOutput()
            val manager =
                OptimizedCbzManager(fixture.context, cbzTier(), decoder, archive) { bitmap, format, quality, output ->
                    if (encodes.incrementAndGet() == 1) gate.hold()
                    bitmap.compress(format, quality, output)
                }
            val active = scope.async { manager.createCbzParallel(first, fixture.mangaId, 1L) }
            try {
                gate.awaitEntry()
                cancelQueued(scope, manager)
            } finally {
                gate.close()
            }
            active.await()
            // Reuse the same manager after cancellation; the permit must not leak.
            manager.createCbzParallel(second, fixture.mangaId, 2L)
        }
        fixture.assertArchive(CBZ_SMALL_PAGE_DIMENSIONS)
        fixture.assertArchive(CBZ_SMALL_PAGE_DIMENSIONS, 2L)
        assertTrue(decoder.bitmaps.all(Bitmap::isRecycled))
    }

    private suspend fun cancelQueued(
        scope: CoroutineScope,
        manager: OptimizedCbzManager,
    ) {
        // Same dispatcher + UNDISPATCHED enters the manager up to its contended lock,
        // rather than cancelling a coroutine that has not actually attempted entry.
        val queued =
            scope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.createCbzParallel(second, fixture.mangaId, 2L)
            }
        assertFalse(queued.isCompleted)
        assertEquals(1, decoder.requested.size)
        assertEquals(1, encodes.get())
        queued.cancel()
        assertFailsWith<CancellationException> { queued.await() }
        queued.join()
        assertTrue(second.all { File(it).isFile })
        assertFalse(fixture.destination(2L).exists())
        fixture.assertNoTemporary(2L)
    }
}

private class CbzActiveCancellationCase(
    private val fixture: CbzHostFixture,
) {
    private val paths = fixture.pages(1, CBZ_FAULT_PAGE_WIDTH, CBZ_CANCEL_PAGE_HEIGHT)
    private val originals = paths.map { File(it).readBytes() }
    private val previous = fixture.priorArchive()
    private val decoder = CbzObservedDecoder()
    private val encodes = AtomicInteger()

    suspend fun verify(scope: CoroutineScope) {
        CbzEncodeGate().use { gate ->
            val archive = CbzHostArchiveOutput()
            val manager =
                OptimizedCbzManager(fixture.context, cbzTier(), decoder, archive) { bitmap, format, quality, output ->
                    if (encodes.incrementAndGet() == 2) gate.hold()
                    bitmap.compress(format, quality, output)
                }
            val conversion = scope.async { manager.createCbzParallel(paths, fixture.mangaId, 1L) }
            try {
                gate.awaitEntry()
                assertEquals(2, decoder.regions.size)
                assertTrue(decoder.bitmaps.first().isRecycled)
                assertFalse(decoder.bitmaps.last().isRecycled)
                conversion.cancel()
            } finally {
                gate.close()
            }
            assertFailsWith<CancellationException> { conversion.await() }
            conversion.join() // No cleanup assertion based only on a cancelled Deferred.
        }
        assertCompleted()
    }

    private fun assertCompleted() {
        assertEquals(2, decoder.regions.size)
        assertEquals(1, decoder.regionCloses.get())
        assertTrue(decoder.bitmaps.all(Bitmap::isRecycled))
        assertContentEquals(previous, fixture.destination().readBytes())
        paths.forEachIndexed { index, path -> assertContentEquals(originals[index], File(path).readBytes()) }
        fixture.assertNoTemporary()
    }
}
