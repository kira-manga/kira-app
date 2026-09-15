package me.manga.kira.platform.image

import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvifDecodeSizeTest {
    @Test
    fun fitAndFillUseBothRequestedAxes() {
        assertEquals(AvifPixelSize(50, 100), size(Size(100, 100), scale = Scale.FIT))
        assertEquals(AvifPixelSize(100, 200), size(Size(100, 100), scale = Scale.FILL))
    }

    @Test
    fun widthOnlyRequestKeepsTheTallPageAspectRatio() {
        for (scale in Scale.entries) {
            assertEquals(
                AvifPixelSize(32, 352),
                avifDecodeSize(
                    source = AvifPixelSize(32, 352),
                    target = Size(Dimension.Pixels(32), Dimension.Undefined),
                    scale = scale,
                    precision = Precision.EXACT,
                    maxBitmapSize = Size(Dimension.Pixels(80), Dimension.Undefined),
                ),
            )
        }
    }

    @Test
    fun heightOnlyRequestKeepsTheSourceAspectRatio() {
        for (scale in Scale.entries) {
            assertEquals(AvifPixelSize(32, 64), size(Size(Dimension.Undefined, Dimension.Pixels(64)), scale))
        }
    }

    @Test
    fun maximumWidthDoesNotBecomeAMaximumHeight() {
        assertEquals(
            AvifPixelSize(64, 128),
            size(Size(64, 128), maximum = Size(Dimension.Pixels(64), Dimension.Undefined)),
        )
    }

    @Test
    fun maximumHeightDoesNotBecomeAMaximumWidth() {
        assertEquals(
            AvifPixelSize(40, 80),
            size(Size.ORIGINAL, maximum = Size(Dimension.Undefined, Dimension.Pixels(80))),
        )
    }

    @Test
    fun unequalMaximumAxesBothConstrainFill() {
        assertEquals(AvifPixelSize(24, 48), size(Size(320, 640), maximum = Size(80, 48)))
    }

    @Test
    fun originalRemainsOriginalWithoutAMaximum() {
        val original = AvifPixelSize(320, 640)
        val output = size(Size.ORIGINAL)
        assertEquals(original, output)
        assertFalse(output.isSmallerThan(original))
    }

    @Test
    fun originalStillHonorsTheIndependentMaximum() {
        val output = size(Size.ORIGINAL, maximum = Size(Dimension.Pixels(80), Dimension.Undefined))
        assertEquals(AvifPixelSize(80, 160), output)
        assertTrue(output.isSmallerThan(AvifPixelSize(320, 640)))
    }

    @Test
    fun inexactNeverUpscalesButExactCan() {
        assertEquals(AvifPixelSize(320, 640), size(Size(640, 1280), precision = Precision.INEXACT))
        val exact = size(Size(640, 1280), precision = Precision.EXACT)
        assertEquals(AvifPixelSize(640, 1280), exact)
        assertFalse(exact.isSmallerThan(AvifPixelSize(320, 640)))
    }

    @Test
    fun verySmallFitNeverProducesAZeroAxis() {
        assertEquals(AvifPixelSize(1, 1), size(Size(1, 1), scale = Scale.FIT))
    }

    private fun size(
        target: Size,
        scale: Scale = Scale.FILL,
        precision: Precision = Precision.EXACT,
        maximum: Size = Size.ORIGINAL,
    ): AvifPixelSize = avifDecodeSize(AvifPixelSize(320, 640), target, scale, precision, maximum)
}
