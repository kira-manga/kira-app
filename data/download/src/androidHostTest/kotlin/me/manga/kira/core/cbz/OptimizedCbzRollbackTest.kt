package me.manga.kira.core.cbz

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import me.manga.kira.platform.cbz.CbzWriter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OptimizedCbzRollbackTest {
    @Test
    fun sourceCodecAndArchiveFailuresPreserveInputsAndPreviousFinal() =
        cbzHostTest { fixture ->
            CbzFault.entries.forEach { CbzRollbackCase(fixture, it).verify() }
        }

    @Test
    fun modeledAvifCropFailuresReleaseParentAndPreserveTypedFailure() =
        cbzHostTest { fixture ->
            listOf(OutOfMemoryError("synthetic crop OOM"), CancellationException("synthetic crop cancellation"))
                .forEachIndexed { index, failure -> CbzCropFailureCase(fixture, index + 1L, failure).verify() }
        }

    @Test
    fun emptyInputDoesNotPublishAnEmptyArchiveOrReplaceExistingFinal() =
        cbzHostTest { fixture ->
            val previous = fixture.priorArchive()
            val manager = OptimizedCbzManager(fixture.context, cbzTier(), output = CbzHostArchiveOutput())
            assertFailsWith<IllegalArgumentException> { manager.createCbzParallel(emptyList(), fixture.mangaId, 1L) }
            assertContentEquals(previous, fixture.destination().readBytes())
            fixture.assertNoTemporary()
        }
}

private class CbzRollbackCase(
    private val fixture: CbzHostFixture,
    private val fault: CbzFault,
) {
    private val chapter = fault.ordinal + 1L
    private val paths = createPaths()
    private val originals = paths.associateWith { path -> File(path).takeIf(File::isFile)?.readBytes() }
    private val previous = fixture.priorArchive(chapter)
    private val cleanupFailure = IOException("synthetic region close failure")
    private val decoder =
        CbzObservedDecoder().apply {
            if (fault == CbzFault.NULL_REGION) nullRegionAt = 2
            if (fault == CbzFault.REGION_CLOSE || fault == CbzFault.REGION_CLOSE_DURING_OOM) {
                regionCloseFailure = cleanupFailure
            }
        }
    private val failure = fault.failure()

    suspend fun verify() {
        val manager = cbzFaultManager(fixture, decoder, fault, failure)
        val observed =
            assertNotNull(
                runCatching {
                    manager.createCbzParallel(paths, fixture.mangaId, chapter) { _, _ ->
                        if (fault == CbzFault.PROGRESS) throw failure
                    }
                }.exceptionOrNull(),
                fault.name,
            )
        assertFailure(observed)
        assertUnchanged()
    }

    private fun createPaths(): List<String> {
        val paths =
            if (fault.regional) {
                fixture.pages(1, CBZ_FAULT_PAGE_WIDTH, CBZ_SPLIT_PAGE_HEIGHT, chapter)
            } else {
                fixture.pages(chapter = chapter)
            }
        if (fault == CbzFault.MISSING_SOURCE) check(File(paths.first()).delete())
        if (fault == CbzFault.CORRUPT_SOURCE) File(paths.first()).writeText("not an image")
        return paths
    }

    private fun assertFailure(observed: Throwable) {
        when (fault) {
            CbzFault.ENCODE_OOM, CbzFault.REGION_CLOSE_DURING_OOM, CbzFault.ENCODE_CANCEL ->
                assertTypedFailure(failure, observed)
            else -> assertIs<IOException>(observed)
        }
        if (fault == CbzFault.REGION_CLOSE_DURING_OOM) assertTrue(cleanupFailure in failure.suppressed)
    }

    private fun assertUnchanged() {
        assertContentEquals(previous, fixture.destination(chapter).readBytes(), fault.name)
        originals.forEach { (path, bytes) ->
            if (bytes == null) assertFalse(File(path).exists()) else assertContentEquals(bytes, File(path).readBytes())
        }
        assertTrue(decoder.bitmaps.all(Bitmap::isRecycled), fault.name)
        if (fault.regional) assertEquals(1, decoder.regionCloses.get())
        fixture.assertNoTemporary(chapter)
    }
}

private class CbzCropFailureCase(
    private val fixture: CbzHostFixture,
    private val chapter: Long,
    private val failure: Throwable,
) {
    private val bytes = byteArrayOf(0, 0, 0, CBZ_AVIF_HEADER_LENGTH) + "ftypavif".toByteArray(Charsets.US_ASCII)
    private val source = File(fixture.directory(chapter), "modeled.avif").apply { writeBytes(bytes) }
    private val inspector = CbzModeledAvifInspector(source, CBZ_SPLIT_PAGE_HEIGHT)
    private val previous = fixture.priorArchive(chapter)
    private var parent: Bitmap? = null
    private var firstCrop: Bitmap? = null
    private val decoder =
        object : CbzImageDecoder() {
            override suspend fun decodeAvif(file: File, maxWorkingBytes: Long): Bitmap {
                assertEquals(CbzWriter.DEFAULT_MAX_MEMORY_BYTES, maxWorkingBytes)
                inspector.assertDecoderSnapshot(file)
                return Bitmap
                    .createBitmap(
                        CBZ_AVIF_PAGE_WIDTH,
                        CBZ_SPLIT_PAGE_HEIGHT,
                        Bitmap.Config.ARGB_8888,
                    ).also { parent = it }
            }

            override fun crop(
                parent: Bitmap,
                region: Rect,
            ): Bitmap {
                assertFalse(parent.isRecycled)
                firstCrop?.let {
                    assertTrue(it.isRecycled)
                    throw failure
                }
                return super.crop(parent, region).also { firstCrop = it }
            }
        }

    suspend fun verify() {
        val manager =
            OptimizedCbzManager(
                fixture.context,
                cbzTier(),
                decoder,
                CbzHostArchiveOutput(),
                pagePolicy = CbzPagePolicy(inspector = inspector),
            )
        val observed =
            assertNotNull(
                runCatching {
                    manager.createCbzParallel(listOf(source.absolutePath), fixture.mangaId, chapter)
                }.exceptionOrNull(),
            )
        assertTypedFailure(failure, observed)
        assertTrue(assertNotNull(parent).isRecycled)
        assertTrue(assertNotNull(firstCrop).isRecycled)
        assertContentEquals(bytes, source.readBytes())
        assertContentEquals(previous, fixture.destination(chapter).readBytes())
        inspector.assertReleased()
        fixture.assertNoTemporary(chapter)
    }
}

private fun assertTypedFailure(
    expected: Throwable,
    observed: Throwable,
) {
    assertEquals(expected.javaClass, observed.javaClass)
    // Coroutines may copy a throwable for recovered async stack traces, keeping the original cause.
    if (observed !== expected) assertSame(expected, observed.cause)
}
