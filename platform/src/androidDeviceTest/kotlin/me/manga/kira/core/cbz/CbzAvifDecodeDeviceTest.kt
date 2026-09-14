package me.manga.kira.core.cbz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.util.heap.DeviceTier
import me.manga.kira.platform.device.DeviceTierProbe
import me.manga.kira.platform.image.AvifDecodeException
import me.manga.kira.platform.image.AvifNativeLimitFixture
import me.manga.kira.platform.image.AvifTestFixtures
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Genuine Android libavif/JNI and CBZ calls, with no decoder/inspector/rename stubs. Not host codec proof. */
class CbzAvifDecodeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeAvifDecodeReturnsOwnedFullSizeOpaqueBitmaps() =
        runTest {
            withSource(AvifTestFixtures.regular()) { source ->
                assertNativeDecode(CbzImageDecoder(), source, REGULAR_WIDTH to REGULAR_HEIGHT)
            }
            withSource(AvifTestFixtures.tall()) { source ->
                assertNativeDecode(CbzImageDecoder(), source, TALL_WIDTH to TALL_HEIGHT)
            }
        }

    @Test
    fun nativeCorruptAvifFailureDoesNotFallbackOrLeakTheSharedPermit() =
        runTest {
            val decoder = CbzImageDecoder()
            val valid = AvifTestFixtures.regular()
            withSource(valid.copyOf(valid.size / 2)) { source ->
                assertFailsWith<AvifDecodeException> { decoder.decodeAvif(source) }
            }
            // A leaked process-wide native permit would prevent the next genuine decode from returning.
            withSource(AvifTestFixtures.tall()) { source ->
                assertNativeDecode(decoder, source, TALL_WIDTH to TALL_HEIGHT)
            }
        }

    @Test
    fun smallerInjectedBudgetRejectsActualAv1ExpansionDespiteAdmittedContainerDimensions() =
        runTest {
            val decoder = CbzImageDecoder()
            // This exact hash-checked helper is promoted with the genuine-AAR qualification sources.
            val bytes = AvifNativeLimitFixture.declaredSmall()
            val edge = AvifNativeLimitFixture.DECLARED_EDGE
            withSource(bytes) { source ->
                // Positive control: a malformed-only failure is not evidence for the native cap.
                assertNativeDecode(decoder, source, edge to edge)
                assertTrue(cbzAvifOutputAdmitted(edge, edge, bytes.size, SMALL_NATIVE_WORKING_BYTES))
                val failure =
                    assertFailsWith<AvifDecodeException> {
                        withTimeout(NATIVE_COMPLETION_TIMEOUT_MILLIS) {
                            decoder.decodeAvif(source, maxWorkingBytes = SMALL_NATIVE_WORKING_BYTES)
                        }
                    }
                // Container parsing/output admission fit. The unchanged 320x640 AV1 frame does not.
                assertEquals("CBZ AVIF pixels were rejected by the bounded native decoder.", failure.message)
                // The genuine native decode must recover, including the shared permit/destination owner.
                assertNativeDecode(decoder, source, edge to edge)
            }
        }

    @Test
    fun optimizedCbzPublishesRealAvifPixelsAsWebpAndCleansItsOwnedSnapshot() =
        runTest {
            withChapter { directory, mangaId ->
                val bytes = AvifTestFixtures.tall()
                val source = File(directory, "avif-with-wrong-extension.png").apply { writeBytes(bytes) }
                val destination = File(directory, "chapter_1.cbz")
                val manager = OptimizedCbzManager(context, lowTier())
                val progress = mutableListOf<Int>()

                val result =
                    manager.createCbzParallel(listOf(source.absolutePath), mangaId, 1L) { done, total ->
                        assertEquals(1, total)
                        progress += done
                        assertContentEquals(bytes, source.readBytes())
                        assertFalse(destination.exists())
                    }

                assertEquals(destination.absolutePath, result)
                assertEquals(listOf(1), progress)
                assertFalse(source.exists())
                assertNativeArchive(destination)
                assertEquals(listOf(destination), directory.listFiles().orEmpty().toList())
            }
        }

    private suspend fun assertNativeDecode(
        decoder: CbzImageDecoder,
        source: File,
        dimensions: Pair<Int, Int>,
    ) {
        // This is the production CbzImageDecoder: both bounded metadata and pixel calls reach real JNI.
        val bitmap = withTimeout(NATIVE_COMPLETION_TIMEOUT_MILLIS) { decoder.decodeAvif(source) }
        try {
            assertEquals(dimensions, bitmap.width to bitmap.height)
            assertEquals(Bitmap.Config.RGB_565, bitmap.config)
            assertFalse(bitmap.isRecycled)
            assertTrue(bitmap.allocationByteCount > 0)
        } finally {
            bitmap.recycle()
        }
        assertTrue(bitmap.isRecycled)
    }

    private fun assertNativeArchive(file: File) {
        ZipFile(file).use { archive ->
            val entry = archive.entries().asSequence().single()
            // Verbatim preservation is not accepted as a successful native transcode witness.
            assertEquals("page_0000.webp", entry.name)
            val bitmap = archive.getInputStream(entry).use { assertNotNull(BitmapFactory.decodeStream(it)) }
            try {
                assertEquals(TALL_WIDTH to TALL_HEIGHT, bitmap.width to bitmap.height)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun withSource(
        bytes: ByteArray,
        block: suspend (File) -> Unit,
    ) {
        val source = File.createTempFile("cbz-native-fixture-", ".avif", context.cacheDir)
        try {
            source.writeBytes(bytes)
            block(source)
            assertContentEquals(bytes, source.readBytes(), "The decoder does not own the caller's encoded file")
        } finally {
            check(source.delete())
        }
    }

    private suspend fun withChapter(block: suspend (File, Long) -> Unit) {
        val mangaId = maxOf(1L, UUID.randomUUID().mostSignificantBits ushr 1)
        val root = File(context.filesDir, "manga/$mangaId")
        val directory = File(root, "chapter_1")
        check(!root.exists() && directory.mkdirs())
        try {
            block(directory, mangaId)
        } finally {
            check(root.deleteRecursively())
        }
    }

    private fun lowTier(): DeviceTierProbe =
        object : DeviceTierProbe {
            override fun detect(): DeviceTier = DeviceTier.LOW
        }
}

private const val REGULAR_WIDTH = 320
private const val REGULAR_HEIGHT = 640
private const val TALL_WIDTH = 32
private const val TALL_HEIGHT = 352
private const val NATIVE_COMPLETION_TIMEOUT_MILLIS = 15_000L
private const val SMALL_NATIVE_WORKING_BYTES = 20L * 1024 * 1024
