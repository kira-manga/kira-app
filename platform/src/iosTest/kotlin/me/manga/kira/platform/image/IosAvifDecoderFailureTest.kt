package me.manga.kira.platform.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IosAvifDecoderFailureTest {
    @Test
    fun encodedSourceAndOutputBudgetsFailTerminallyWithTinyFixtures() =
        runTest {
            val bytes = AvifTestFixtures.regular()
            val limits =
                listOf(
                    AvifDecodeLimits(maxEncodedBytes = bytes.size - 1),
                    AvifDecodeLimits(maxSourcePixels = 204_799),
                    AvifDecodeLimits(maxOutputBytes = 1),
                    AvifDecodeLimits(maxWorkingBytes = 1),
                )
            for (limit in limits) {
                val source = IosAvifTestSource(Buffer().write(bytes))
                val options = iosAvifOptions(Size(64, 128))
                val imageSource = ImageSource(source.buffered, options.fileSystem)
                assertFailsWith<AvifDecodeException> { IosAvifDecoder(imageSource, options, limit).decode() }
                assertTrue(source.closed)
            }
            // A failed admission must not poison the serialized native decoder or its resources.
            withIosAvifBitmap(bytes, iosAvifOptions(Size(64, 128))) { bitmap, _ ->
                assertEquals(64, bitmap.width)
                assertEquals(128, bitmap.height)
            }
        }

    @Test
    fun inputCancellationIsPropagatedAndClosesTheClaimedSource() =
        runTest {
            val cancellation = CancellationException("fixture cancellation")
            val source =
                IosAvifTestSource(Buffer().write(AvifTestFixtures.tall())).apply {
                    afterUpstreamRead = { throw cancellation }
                }
            val options = iosAvifOptions(Size.ORIGINAL)
            // Bypass factory peeking: inject cancellation while reading the claimed source.
            val decoder = IosAvifDecoder(ImageSource(source.buffered, options.fileSystem), options)
            assertSame(cancellation, assertFailsWith<CancellationException> { decoder.decode() })
            assertTrue(source.closed)
        }

    @Test
    fun claimedMalformedAvifDoesNotFallThroughToSkia() =
        runTest {
            val fallback = RecordingFallback()
            val loader = iosAvifImageLoader(fallback)
            val bytes =
                Buffer()
                    .writeInt(24)
                    .writeUtf8("ftypavif")
                    .writeInt(0)
                    .writeUtf8("avifmif1")
                    .readByteArray()
            try {
                val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(bytes).build()
                assertIs<ErrorResult>(loader.execute(request))
                assertEquals(0, fallback.calls)
            } finally {
                loader.shutdown()
            }
        }

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
}
