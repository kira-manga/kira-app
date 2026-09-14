package me.manga.kira.platform.image

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidAvifNativeAllowanceDeviceTest {
    @Test
    fun facadeUsesTheSameEncodedSourceAndOutputEstimatesForBothConsumers() {
        val limits = AvifDecodeLimits()
        assertEquals(7_864_319, androidAvifNativePixelLimit(limits, encodedBytes = 10))
        assertEquals(7_864_316, androidAvifNativePixelLimit(limits, 10, AvifPixelSize(2, 3)))
    }

    @Test
    fun facadeCapsAnInjectedEnormousBudgetAtTheReviewedNativeMaximum() {
        val limits = AvifDecodeLimits(maxSourcePixels = Long.MAX_VALUE, maxWorkingBytes = Long.MAX_VALUE)
        assertEquals(268_435_456, androidAvifNativePixelLimit(limits, encodedBytes = 10))
    }

    @Test
    fun facadeKeepsTheBitmapByteLimitIndependentOfItsNativeAllowance() {
        val limits = AvifDecodeLimits(maxOutputBytes = 23)
        assertFailsWith<AvifDecodeException> { androidAvifNativePixelLimit(limits, 10, AvifPixelSize(2, 3)) }
        assertEquals(7_864_317, androidAvifNativePixelLimit(limits, 10, AvifPixelSize(2, 3), bitmapBytesPerPixel = 2))
    }
}
