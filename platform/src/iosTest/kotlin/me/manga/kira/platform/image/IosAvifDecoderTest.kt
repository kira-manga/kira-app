package me.manga.kira.platform.image

import coil3.BitmapImage
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Actual ImageIO thumbnail and RGBA/Skia installation; requires a real iOS AVIF-capable runtime. */
@OptIn(ExperimentalCoilApi::class)
class IosAvifDecoderTest {
    @Test
    fun widthOnlyTallRequestUsesMetadataRatherThanTheRequestedLongestEdge() =
        runTest {
            val options =
                iosAvifOptions(
                    Size(Dimension.Pixels(32), Dimension.Undefined),
                    maximum = Size(Dimension.Pixels(80), Dimension.Undefined),
                )
            withIosAvifBitmap(AvifTestFixtures.tall(), options) { bitmap, result ->
                assertEquals(32, bitmap.width)
                assertEquals(352, bitmap.height)
                assertFalse(result.isSampled)
            }
        }

    @Test
    fun requestedSizeAndWidthCapDoNotCollapseIntoAHeightCap() =
        runTest {
            val options =
                iosAvifOptions(Size(64, 128), maximum = Size(Dimension.Pixels(64), Dimension.Undefined))
            withIosAvifBitmap(AvifTestFixtures.regular(), options) { bitmap, result ->
                assertEquals(64, bitmap.width)
                assertEquals(128, bitmap.height)
                assertTrue(result.isSampled)
            }
        }

    @Test
    fun imageIoFitAndFillMatchTheTwoAxisCoilPlan() =
        runTest {
            val fit = iosAvifOptions(Size(64, 64), scale = Scale.FIT)
            withIosAvifBitmap(AvifTestFixtures.regular(), fit) { bitmap, _ ->
                assertEquals(32, bitmap.width)
                assertEquals(64, bitmap.height)
            }
            val fill = iosAvifOptions(Size(64, 64), scale = Scale.FILL)
            withIosAvifBitmap(AvifTestFixtures.regular(), fill) { bitmap, _ ->
                assertEquals(64, bitmap.width)
                assertEquals(128, bitmap.height)
            }
        }

    @Test
    fun heightOnlyRequestAndUnequalCapsRemainIndependent() =
        runTest {
            val heightOnly = iosAvifOptions(Size(Dimension.Undefined, Dimension.Pixels(80)))
            withIosAvifBitmap(AvifTestFixtures.regular(), heightOnly) { bitmap, _ ->
                assertEquals(40, bitmap.width)
                assertEquals(80, bitmap.height)
            }
            val unequalCaps = iosAvifOptions(Size.ORIGINAL, maximum = Size(80, 48))
            withIosAvifBitmap(AvifTestFixtures.regular(), unequalCaps) { bitmap, _ ->
                assertEquals(24, bitmap.width)
                assertEquals(48, bitmap.height)
            }
        }

    @Test
    fun originalAndInexactDoNotUpscale() =
        runTest {
            val requests =
                listOf(
                    iosAvifOptions(Size.ORIGINAL),
                    iosAvifOptions(Size(640, 1280), precision = Precision.INEXACT),
                )
            for (options in requests) {
                withIosAvifBitmap(AvifTestFixtures.regular(), options) { bitmap, result ->
                    assertEquals(320, bitmap.width)
                    assertEquals(640, bitmap.height)
                    assertFalse(result.isSampled)
                }
            }
        }

    @Test
    fun exactUpscaleUsesTheBoundedDrawingStage() =
        runTest {
            withIosAvifBitmap(AvifTestFixtures.regular(), iosAvifOptions(Size(640, 1280))) { bitmap, result ->
                assertEquals(640, bitmap.width)
                assertEquals(1280, bitmap.height)
                assertFalse(result.isSampled)
            }
        }

    @Test
    fun completeCoilPipelineDecodesTheTallPageAtItsRequestedWidth() =
        runTest {
            val loader = iosAvifImageLoader()
            try {
                val request =
                    ImageRequest
                        .Builder(PlatformContext.INSTANCE)
                        .data(AvifTestFixtures.tall())
                        .size(Size(Dimension.Pixels(32), Dimension.Undefined))
                        .scale(Scale.FILL)
                        .precision(Precision.EXACT)
                        .maxBitmapSize(Size(Dimension.Pixels(80), Dimension.Undefined))
                        .build()
                val result = assertIs<SuccessResult>(loader.execute(request))
                val bitmap = assertIs<BitmapImage>(result.image).bitmap
                try {
                    assertEquals(32, bitmap.width)
                    assertEquals(352, bitmap.height)
                } finally {
                    bitmap.close()
                }
            } finally {
                loader.shutdown()
            }
        }
}
