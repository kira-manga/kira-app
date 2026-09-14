package me.manga.kira.core.cbz

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import me.manga.kira.core.util.heap.DeviceTier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ordinary codec/ZIP is real. Region and AVIF tests explicitly model only codec output. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OptimizedCbzStreamingTest {
    @Test
    fun allTiersStreamRealWebpBeforeLaterPagesAndPublishOnce() =
        cbzHostTest { fixture ->
            DeviceTier.entries.forEach { tier -> CbzTierStreamingCase(fixture, tier).verify(this) }
        }

    @Test
    fun modeledRegionsAreRequestedOnlyAfterPreviousChunkIsRecycled() =
        cbzHostTest { fixture -> verifyModeledRegions(fixture) }

    @Test
    fun modeledAvifOwnsOneParentAndAtMostOneCropIncludingUnsplitAlias() =
        cbzHostTest { fixture ->
            listOf(5, 6005).forEachIndexed { index, height ->
                CbzModeledAvifCase(fixture, height, index + 1L).verify(this)
            }
        }
}

private suspend fun CoroutineScope.verifyModeledRegions(fixture: CbzHostFixture) {
    val paths = fixture.pages(1, 8, 6005) + fixture.pages(1, 32, 33, chapter = 2L)
    val decoder = CbzObservedDecoder()
    val encodes = AtomicInteger()
    CbzEncodeGate().use { gate ->
        val manager =
            OptimizedCbzManager(fixture.context, cbzTier(), decoder) { bitmap, format, quality, output ->
                if (encodes.incrementAndGet() == 1) gate.hold()
                bitmap.compress(format, quality, output)
            }
        val conversion = async { manager.createCbzParallel(paths, fixture.mangaId, 1L) }
        try {
            gate.awaitEntry()
            assertFirstModeledRegion(fixture, decoder)
        } finally {
            gate.close()
        }
        conversion.await()
    }
    assertModeledRegionsComplete(fixture, paths, decoder)
}

private fun assertFirstModeledRegion(
    fixture: CbzHostFixture,
    decoder: CbzObservedDecoder,
) {
    assertEquals(listOf(Rect(0, 0, 8, 6000)), decoder.regions.toList())
    assertEquals(1, decoder.requested.size)
    assertEquals(1, decoder.bitmaps.size)
    assertFalse(decoder.bitmaps.single().isRecycled)
    assertFalse(fixture.destination().exists())
}

private fun assertModeledRegionsComplete(
    fixture: CbzHostFixture,
    paths: List<String>,
    decoder: CbzObservedDecoder,
) {
    assertEquals(listOf(Rect(0, 0, 8, 6000), Rect(0, 6000, 8, 6005)), decoder.regions.toList())
    assertEquals(paths, decoder.requested.map(File::getAbsolutePath))
    assertEquals(1, decoder.regionCloses.get())
    assertTrue(decoder.bitmaps.all(Bitmap::isRecycled))
    fixture.assertArchive(listOf(8 to 6000, 8 to 5, 32 to 33))
    assertTrue(paths.none { File(it).exists() })
    fixture.assertNoTemporary()
}

private class CbzTierStreamingCase(
    private val fixture: CbzHostFixture,
    private val tier: DeviceTier,
) {
    private val chapter = tier.ordinal + 1L
    private val paths = fixture.pages(12, 512, 512, chapter, noisy = true)
    private val before = fixture.priorArchive(chapter)
    private val output = RecordingArchiveOutput(paths, before)
    private val decoder = CbzObservedDecoder()
    private val encodes = AtomicInteger()
    private val progress = mutableListOf<Int>()

    suspend fun verify(scope: CoroutineScope) {
        CbzEncodeGate().use { gate ->
            val manager = createManager(gate)
            val conversion =
                scope.async { manager.createCbzParallel(paths, fixture.mangaId, chapter, ::recordProgress) }
            try {
                gate.awaitEntry()
                assertFirstEncode()
            } finally {
                gate.close()
            }
            assertEquals(fixture.destination(chapter).absolutePath, conversion.await())
        }
        assertCompleted()
    }

    private fun createManager(gate: CbzEncodeGate): OptimizedCbzManager =
        OptimizedCbzManager(fixture.context, cbzTier(tier), decoder, output) { bitmap, format, quality, stream ->
            assertEquals(listOf(70, 75, 85)[tier.ordinal], quality)
            assertEquals(Bitmap.CompressFormat.WEBP_LOSSY, format)
            if (encodes.incrementAndGet() == 1) gate.hold()
            bitmap.compress(format, quality, stream)
        }

    private fun recordProgress(
        done: Int,
        total: Int,
    ) {
        assertEquals(paths.size, total)
        progress += done
        assertTrue(paths.all { File(it).isFile })
        assertContentEquals(before, fixture.destination(chapter).readBytes())
        if (done == 1) assertTrue(assertNotNull(output.temporaryFile.get()).length() > 0)
    }

    private fun assertFirstEncode() {
        assertEquals(1, decoder.requested.size)
        assertEquals(1, decoder.bitmaps.size)
        assertEquals(1, encodes.get())
        assertContentEquals(before, fixture.destination(chapter).readBytes())
    }

    private fun assertCompleted() {
        assertEquals((1..12).toList(), progress)
        assertEquals(1, output.publications.get())
        assertTrue(decoder.bitmaps.all(Bitmap::isRecycled))
        assertTrue(paths.none { File(it).exists() })
        fixture.assertArchive(List(12) { (512 + it) to 512 }, chapter, ::assertCbzOrdinaryPageContent)
        fixture.assertNoTemporary(chapter)
    }
}

/** Both operations forward to the real defaults; every assertion is before the commit point. */
private class RecordingArchiveOutput(
    private val paths: List<String>,
    private val previous: ByteArray,
) : CbzArchiveOutput() {
    val temporaryFile = AtomicReference<File>()
    val publications = AtomicInteger()

    override fun open(temporary: File): OutputStream =
        super.open(temporary).also { temporaryFile.set(temporary) }

    override fun publish(
        temporary: File,
        destination: File,
    ) {
        assertEquals(1, publications.incrementAndGet())
        assertTrue(paths.all { File(it).isFile })
        assertContentEquals(previous, destination.readBytes())
        super.publish(temporary, destination)
    }
}
