package me.manga.kira.platform.image

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidAvifNativeAllowanceDeviceTest {
    @Test
    fun facadeUsesTheSameEncodedSourceAndOutputEstimatesForBothConsumers() {
        val limits = AvifDecodeLimits()
        assertEquals(EXPECTED_HEADER_PIXEL_ALLOWANCE, androidAvifNativePixelLimit(limits, ENCODED_BYTES))
        assertEquals(
            EXPECTED_RGBA_PIXEL_ALLOWANCE,
            androidAvifNativePixelLimit(limits, ENCODED_BYTES, AvifPixelSize(OUTPUT_WIDTH, OUTPUT_HEIGHT)),
        )
    }

    @Test
    fun facadeCapsAnInjectedEnormousBudgetAtTheReviewedNativeMaximum() {
        val limits = AvifDecodeLimits(maxSourcePixels = Long.MAX_VALUE, maxWorkingBytes = Long.MAX_VALUE)
        assertEquals(EXPECTED_NATIVE_MAX_PIXELS, androidAvifNativePixelLimit(limits, ENCODED_BYTES))
    }

    @Test
    fun facadeKeepsTheBitmapByteLimitIndependentOfItsNativeAllowance() {
        val limits = AvifDecodeLimits(maxOutputBytes = 23)
        assertFailsWith<AvifDecodeException> {
            androidAvifNativePixelLimit(limits, ENCODED_BYTES, AvifPixelSize(OUTPUT_WIDTH, OUTPUT_HEIGHT))
        }
        assertEquals(
            EXPECTED_RGB_565_PIXEL_ALLOWANCE,
            androidAvifNativePixelLimit(
                limits,
                ENCODED_BYTES,
                AvifPixelSize(OUTPUT_WIDTH, OUTPUT_HEIGHT),
                bitmapBytesPerPixel = 2,
            ),
        )
    }

    private companion object {
        const val ENCODED_BYTES = 10
        const val OUTPUT_WIDTH = 2
        const val OUTPUT_HEIGHT = 3
        const val EXPECTED_HEADER_PIXEL_ALLOWANCE = 7_864_319
        const val EXPECTED_RGBA_PIXEL_ALLOWANCE = 7_864_316
        const val EXPECTED_RGB_565_PIXEL_ALLOWANCE = 7_864_317
        const val EXPECTED_NATIVE_MAX_PIXELS = 268_435_456
    }
}
