package me.manga.kira.platform.image

import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DataSource
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Size
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoilApi::class)
class AvifDecoderCoilFailureDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val options get() = Options(context, size = Size(1, 1))

    @Test
    fun declaredSourceBudgetRejectsEvenATinyTarget() =
        runTest {
            val source = TrackingSource(Buffer().write(AvifTestFixtures.regular()))
            val decoder = AvifDecoderCoil(source.buffered, options, AvifDecodeLimits(maxSourcePixels = REGULAR_PIXELS - 1))
            val failure = assertFailsWith<AvifDecodeException> { decoder.decode() }
            assertTrue(failure.message.orEmpty().contains("bounded native decoder"))
            assertTrue(source.closed)
            // Declared metadata only; actual-AV1 pixel enforcement needs the native mismatch controls.
        }

    @Test
    fun encodedAndOutputBudgetFailuresAreTerminalAndCloseTheSource() =
        runTest {
            val bytes = AvifTestFixtures.regular()
            val budgets =
                listOf(
                    AvifDecodeLimits(maxEncodedBytes = bytes.size - 1),
                    AvifDecodeLimits(maxOutputBytes = 1),
                    AvifDecodeLimits(maxWorkingBytes = 1),
                )
            for (limits in budgets) {
                val source = TrackingSource(Buffer().write(bytes))
                assertFailsWith<AvifDecodeException> { AvifDecoderCoil(source.buffered, options, limits).decode() }
                assertTrue(source.closed)
            }
        }

    @Test
    fun nonAvifFactoryDeclineDoesNotConsumeOrCloseTheSharedSource() {
        val bytes = "not an AVIF file; another decoder owns this".encodeToByteArray()
        val source = TrackingSource(Buffer().write(bytes))
        val fetch = SourceFetchResult(ImageSource(source.buffered, options.fileSystem), "image/png", DataSource.MEMORY)
        val loader = newImageLoader()
        try {
            assertNull(AvifDecoderCoil.Factory().create(fetch, options, loader))
            assertFalse(source.closed)
            // Keep the same buffered source: factory peeking may have prefetched upstream bytes.
            assertContentEquals(bytes, source.buffered.readByteArray())
        } finally {
            fetch.source.close()
            loader.shutdown()
        }
    }

    @Test
    fun claimedMalformedAvifDoesNotFallThroughToAnotherDecoder() =
        runTest {
            val fallback = RecordingFallback()
            val loader = newImageLoader(fallback)
            val headerOnly =
                Buffer()
                    .writeInt(FTYP_BYTES)
                    .writeUtf8("ftypavif")
                    .writeInt(0)
                    .writeUtf8("avifmif1")
                    .readByteArray()
            try {
                val result = loader.execute(ImageRequest.Builder(context).data(headerOnly).build())
                assertIs<ErrorResult>(result)
                assertEquals(0, fallback.calls)
            } finally {
                loader.shutdown()
            }
        }

    private fun newImageLoader(afterAvif: Decoder.Factory? = null): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                AndroidImageDecoderRegistry().registerAll().forEach { add(it) }
                if (afterAvif != null) add(afterAvif)
            }.memoryCache(null)
            .diskCache(null)
            .build()

    private class RecordingFallback : Decoder.Factory {
        var calls = 0

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            calls++
            return null
        }
    }

    private class TrackingSource(
        delegate: Source,
    ) : ForwardingSource(delegate) {
        val buffered: BufferedSource = buffer()
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}

private const val REGULAR_PIXELS = 320L * 640
private const val FTYP_BYTES = 24
