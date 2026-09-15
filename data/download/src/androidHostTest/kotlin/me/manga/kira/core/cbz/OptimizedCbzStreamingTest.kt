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

/**
 * Ordinary codec/ZIP and host atomic file promotion are real. Region/AVIF codec output is modeled;
 * these host cases do not qualify Android Os.rename or native region/AVIF pixels.
 */
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
        cbzHostTest { fixture ->
            verifyModeledRegions(fixture)
        }

    @Test
    fun modeledAvifOwnsOneParentAndAtMostOneCropIncludingUnsplitAlias() =
        cbzHostTest { fixture ->
            listOf(CBZ_UNSPLIT_AVIF_HEIGHT, CBZ_SPLIT_PAGE_HEIGHT).forEachIndexed { index, height ->
                CbzModeledAvifCase(fixture, height, index + 1L).verify(this)
            }
        }
}

private suspend fun CoroutineScope.verifyModeledRegions(fixture: CbzHostFixture) {
    val paths =
        fixture.pages(1, CBZ_REGION_PAGE_WIDTH, CBZ_SPLIT_PAGE_HEIGHT) +
            fixture.pages(1, CBZ_SMALL_PAGE_WIDTH, CBZ_SMALL_PAGE_HEIGHT, chapter = 2L)
    val decoder = CbzObservedDecoder(paths)
    val encodes = AtomicInteger()
    CbzEncodeGate().use { gate ->
        val archive = CbzHostArchiveOutput()
        val manager =
            OptimizedCbzManager(fixture.context, cbzTier(), decoder, archive) { bitmap, format, quality, output ->
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
    assertEquals(
        listOf(Rect(0, 0, CBZ_REGION_PAGE_WIDTH, CBZ_LOW_REGION_HEIGHT)),
        decoder.regions.toList(),
    )
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
    assertEquals(
        listOf(
            Rect(0, 0, CBZ_REGION_PAGE_WIDTH, CBZ_LOW_REGION_HEIGHT),
            Rect(0, CBZ_LOW_REGION_HEIGHT, CBZ_REGION_PAGE_WIDTH, CBZ_SPLIT_PAGE_HEIGHT),
        ),
        decoder.regions.toList(),
    )
    // The observer compared each live snapshot with the corresponding source bytes, in order.
    assertEquals(paths.size, decoder.requested.size)
    assertTrue(decoder.requested.none(File::exists))
    assertEquals(1, decoder.regionCloses.get())
    assertTrue(decoder.bitmaps.all(Bitmap::isRecycled))
    fixture.assertArchive(
        listOf(
            CBZ_REGION_PAGE_WIDTH to CBZ_LOW_REGION_HEIGHT,
            CBZ_REGION_PAGE_WIDTH to CBZ_REGION_TAIL_HEIGHT,
            CBZ_SMALL_PAGE_WIDTH to CBZ_SMALL_PAGE_HEIGHT,
        ),
    )
    assertTrue(paths.none { File(it).exists() })
    fixture.assertNoTemporary()
    fixture.assertNoTemporary(2L)
}

private class CbzTierStreamingCase(
    private val fixture: CbzHostFixture,
    private val tier: DeviceTier,
) {
    private val chapter = tier.ordinal + 1L
    private val paths =
        fixture.pages(
            CBZ_NOISY_PAGE_COUNT,
            CBZ_NOISY_PAGE_SIDE,
            CBZ_NOISY_PAGE_SIDE,
            chapter,
            noisy = true,
        )
    private val before = fixture.priorArchive(chapter)
    private val output = RecordingArchiveOutput(paths, before)
    private val decoder = CbzObservedDecoder(paths)
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
            assertEquals(listOf(CBZ_LOW_QUALITY, CBZ_MID_QUALITY, CBZ_HIGH_QUALITY)[tier.ordinal], quality)
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
        assertEquals((1..CBZ_NOISY_PAGE_COUNT).toList(), progress)
        assertEquals(1, output.publications.get())
        assertEquals(paths.size, decoder.requested.size)
        assertTrue(decoder.requested.none(File::exists))
        assertTrue(decoder.bitmaps.all(Bitmap::isRecycled))
        assertTrue(paths.none { File(it).exists() })
        fixture.assertArchive(
            List(CBZ_NOISY_PAGE_COUNT) { (CBZ_NOISY_PAGE_SIDE + it) to CBZ_NOISY_PAGE_SIDE },
            chapter,
            ::assertCbzOrdinaryPageContent,
        )
        fixture.assertNoTemporary(chapter)
    }
}

/** Real file sink and host atomic promotion; every assertion is before the commit point. */
private class RecordingArchiveOutput(
    private val paths: List<String>,
    private val previous: ByteArray,
) : CbzHostArchiveOutput() {
    val temporaryFile = AtomicReference<File>()
    val publications = AtomicInteger()

    override fun open(temporary: File): OutputStream = super.open(temporary).also { temporaryFile.set(temporary) }

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
