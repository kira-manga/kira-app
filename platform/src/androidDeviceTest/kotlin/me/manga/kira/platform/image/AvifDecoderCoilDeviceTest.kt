package me.manga.kira.platform.image

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.Extras
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DecodeResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real libavif/JNI tests. Host-only execution is not a substitute for this suite. */
@OptIn(ExperimentalCoilApi::class)
class AvifDecoderCoilDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeDecodeHonorsRequestedSizeAndWidthOnlyCap() =
        runTest {
            val options =
                options(
                    Size(TARGET_WIDTH, TARGET_HEIGHT),
                    maximum = Size(Dimension.Pixels(TARGET_WIDTH), Dimension.Undefined),
                )
            withDecoded(AvifTestFixtures.regular(), options) { bitmap, result ->
                assertEquals(TARGET_WIDTH, bitmap.width)
                assertEquals(TARGET_HEIGHT, bitmap.height)
                assertEquals(Bitmap.Config.RGB_565, bitmap.config)
                assertTrue(result.isSampled)
            }
        }

    @Test
    fun nativeFitAndFillUseBothAxes() =
        runTest {
            val fit = options(Size(TARGET_WIDTH, TARGET_WIDTH), scale = Scale.FIT)
            withDecoded(AvifTestFixtures.regular(), fit) { bitmap, _ ->
                assertEquals(TARGET_WIDTH / 2, bitmap.width)
                assertEquals(TARGET_WIDTH, bitmap.height)
            }
            val fill = options(Size(TARGET_WIDTH, TARGET_WIDTH), scale = Scale.FILL)
            withDecoded(AvifTestFixtures.regular(), fill) { bitmap, _ ->
                assertEquals(TARGET_WIDTH, bitmap.width)
                assertEquals(TARGET_HEIGHT, bitmap.height)
            }
        }

    @Test
    fun nativeTallPageWithinBudgetIsNotRejected() =
        runTest {
            val options =
                options(
                    Size(Dimension.Pixels(TALL_WIDTH), Dimension.Undefined),
                    maximum = Size(Dimension.Pixels(MAXIMUM_WIDTH), Dimension.Undefined),
                )
            withDecoded(AvifTestFixtures.tall(), options) { bitmap, result ->
                assertEquals(TALL_WIDTH, bitmap.width)
                assertEquals(TALL_HEIGHT, bitmap.height)
                assertFalse(result.isSampled)
            }
        }

    @Test
    fun nativeHeightOnlyRequestAndUnequalMaximumsRemainIndependent() =
        runTest {
            val heightOnly = options(Size(Dimension.Undefined, Dimension.Pixels(MAXIMUM_WIDTH)))
            withDecoded(AvifTestFixtures.regular(), heightOnly) { bitmap, _ ->
                assertEquals(MAXIMUM_WIDTH / 2, bitmap.width)
                assertEquals(MAXIMUM_WIDTH, bitmap.height)
            }
            val unequalMaximums = options(Size.ORIGINAL, maximum = Size(MAXIMUM_WIDTH, MAXIMUM_HEIGHT))
            withDecoded(AvifTestFixtures.regular(), unequalMaximums) { bitmap, _ ->
                assertEquals(MAXIMUM_HEIGHT / 2, bitmap.width)
                assertEquals(MAXIMUM_HEIGHT, bitmap.height)
            }
        }

    @Test
    fun nativeOriginalAndInexactDoNotUpscaleButExactCan() =
        runTest {
            val upscaled = Size(REGULAR_WIDTH * 2, REGULAR_HEIGHT * 2)
            for (options in listOf(options(Size.ORIGINAL), options(upscaled, precision = Precision.INEXACT))) {
                withDecoded(AvifTestFixtures.regular(), options) { bitmap, result ->
                    assertEquals(REGULAR_WIDTH, bitmap.width)
                    assertEquals(REGULAR_HEIGHT, bitmap.height)
                    assertFalse(result.isSampled)
                }
            }
            withDecoded(AvifTestFixtures.regular(), options(upscaled)) { bitmap, result ->
                assertEquals(REGULAR_WIDTH * 2, bitmap.width)
                assertEquals(REGULAR_HEIGHT * 2, bitmap.height)
                assertFalse(result.isSampled)
            }
        }

    @Test
    fun completeCoilPipelineSuccessfullyDecodesTheTallFixture() =
        runTest {
            val loader = newImageLoader()
            try {
                val result = assertIs<SuccessResult>(loader.execute(tallRequest()))
                val bitmap = assertIs<BitmapImage>(result.image).bitmap
                try {
                    assertEquals(TALL_WIDTH, bitmap.width)
                    assertEquals(TALL_HEIGHT, bitmap.height)
                    assertFalse(result.isSampled)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                loader.shutdown()
            }
        }

    private fun tallRequest(): ImageRequest =
        ImageRequest
            .Builder(context)
            .data(AvifTestFixtures.tall())
            .size(Size(Dimension.Pixels(TALL_WIDTH), Dimension.Undefined))
            .scale(Scale.FILL)
            .precision(Precision.EXACT)
            .maxBitmapSize(Size(Dimension.Pixels(MAXIMUM_WIDTH), Dimension.Undefined))
            .build()

    private suspend fun withDecoded(
        bytes: ByteArray,
        options: Options,
        check: (Bitmap, DecodeResult) -> Unit,
    ) {
        val source = TrackingSource(Buffer().write(bytes))
        val result = AvifDecoderCoil(source.buffered, options).decode()
        val bitmap = assertIs<BitmapImage>(result.image).bitmap
        try {
            assertTrue(source.closed)
            check(bitmap, result)
        } finally {
            bitmap.recycle()
        }
    }

    private fun options(
        size: Size,
        scale: Scale = Scale.FILL,
        precision: Precision = Precision.EXACT,
        maximum: Size = Size.ORIGINAL,
    ): Options =
        Options(
            context = context,
            size = size,
            scale = scale,
            precision = precision,
            extras = Extras.Builder().apply { set(Extras.Key.maxBitmapSize, maximum) }.build(),
        )

    private fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                AndroidImageDecoderRegistry().registerAll().forEach { add(it) }
            }.memoryCache(null)
            .diskCache(null)
            .build()

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

private const val TARGET_WIDTH = 64
private const val TARGET_HEIGHT = 128
private const val MAXIMUM_WIDTH = 80
private const val MAXIMUM_HEIGHT = 48
private const val TALL_WIDTH = 32
private const val TALL_HEIGHT = 352
private const val REGULAR_WIDTH = 320
private const val REGULAR_HEIGHT = 640
