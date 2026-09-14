package me.manga.kira.platform.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AvifNativeAllowanceTest {
    private val memory = AvifMemoryModel(bitmapBytesPerPixel = 4, encodedCopies = 3, fixedBytes = 7)
    private val output = AvifPixelSize(2, 3)
    private val limits =
        AvifDecodeLimits(
            maxEncodedBytes = 10,
            maxSourcePixels = 100,
            maxOutputBytes = 24,
            // 7 fixed + 10 * 3 encoded + 6 * (4 + 8) output + 12 * 32 source.
            maxWorkingBytes = 493,
        )

    @Test
    fun reservesOutputBeforeDerivingTheSecondNativeCap() {
        assertEquals(14, limits.nativePixelLimit(10, null, memory, 1000))
        assertEquals(12, limits.nativePixelLimit(10, output, memory, 1000))
    }

    @Test
    fun dividesRemainingBytesDownAtEachSourcePixelBoundary() {
        assertEquals(11, limits.copy(maxWorkingBytes = 492).nativePixelLimit(10, output, memory, 1000))
        assertEquals(12, limits.nativePixelLimit(10, output, memory, 1000))
        assertEquals(12, limits.copy(maxWorkingBytes = 524).nativePixelLimit(10, output, memory, 1000))
        assertEquals(13, limits.copy(maxWorkingBytes = 525).nativePixelLimit(10, output, memory, 1000))
    }

    @Test
    fun takesTheMinimumOfPolicyNativeAndCapacity() {
        assertEquals(8, limits.copy(maxSourcePixels = 8).nativePixelLimit(10, output, memory, 1000))
        assertEquals(9, limits.nativePixelLimit(10, output, memory, 9))
        assertEquals(12, limits.nativePixelLimit(10, output, memory, 1000))
        assertFailsWith<IllegalArgumentException> { limits.nativePixelLimit(10, output, memory, 0) }
    }

    @Test
    fun aPositiveNativeLimitMustStillAdmitAtLeastOnePixel() {
        // Fixed + encoded + output = 109 bytes; the next source pixel costs another 32.
        assertFailsWith<AvifDecodeException> {
            limits.copy(maxWorkingBytes = 140).nativePixelLimit(10, output, memory, 1000)
        }
        assertEquals(1, limits.copy(maxWorkingBytes = 141).nativePixelLimit(10, output, memory, 1000))
    }

    @Test
    fun encodedCopiesUseActualLengthNotTheConfiguredMaximum() {
        assertEquals(14, limits.nativePixelLimit(10, null, memory, 1000))
        assertEquals(15, limits.nativePixelLimit(1, null, memory, 1000))
        assertFailsWith<AvifDecodeException> { limits.nativePixelLimit(0, null, memory, 1000) }
        assertFailsWith<AvifDecodeException> { limits.nativePixelLimit(11, null, memory, 1000) }
    }

    @Test
    fun outputAllocationLimitRemainsIndependentOfTheNativePixelLimit() {
        assertFailsWith<AvifDecodeException> {
            limits.copy(maxOutputBytes = 23).nativePixelLimit(10, output, memory, 1000)
        }
        assertFailsWith<AvifDecodeException> {
            limits.copy(maxOutputBytes = Long.MAX_VALUE, maxWorkingBytes = Long.MAX_VALUE)
                .nativePixelLimit(10, AvifPixelSize(Int.MAX_VALUE, Int.MAX_VALUE), memory, Int.MAX_VALUE)
        }
    }

    @Test
    fun rgb565StillReservesEightAdditionalWorkspaceBytesPerOutputPixel() {
        val square = AvifPixelSize(4, 4)
        val squareLimits = limits.copy(maxOutputBytes = 64, maxWorkingBytes = 261)
        assertEquals(1, squareLimits.nativePixelLimit(10, square, memory, 1000))
        assertEquals(2, squareLimits.nativePixelLimit(10, square, memory.copy(bitmapBytesPerPixel = 2), 1000))
    }

    @Test
    fun defaultMemoryModelRetainsTheThirtyTwoByteSourceAllowance() {
        val defaultMemory = AvifMemoryModel(bitmapBytesPerPixel = 4, encodedCopies = 3)
        // (256 MiB - 16 MiB - 10 * 3 - 6 * 12) / 32, rounded down.
        assertEquals(7_864_316, AvifDecodeLimits().nativePixelLimit(10, output, defaultMemory, Int.MAX_VALUE))
        assertEquals(7_864_319, AvifDecodeLimits().nativePixelLimit(10, null, defaultMemory, Int.MAX_VALUE))
    }

    @Test
    fun checkedReservationsDoNotOverflowNearLongMaxValue() {
        val huge = limits.copy(maxWorkingBytes = Long.MAX_VALUE)
        val almostAllFixed = memory.copy(fixedBytes = Long.MAX_VALUE - 62)
        assertEquals(1, huge.nativePixelLimit(10, null, almostAllFixed, 1000))
        assertFailsWith<AvifDecodeException> {
            huge.nativePixelLimit(10, null, almostAllFixed.copy(fixedBytes = Long.MAX_VALUE - 61), 1000)
        }
        assertFailsWith<AvifDecodeException> {
            huge.copy(maxEncodedBytes = Int.MAX_VALUE).nativePixelLimit(
                Int.MAX_VALUE,
                null,
                memory.copy(encodedCopies = Int.MAX_VALUE, fixedBytes = Long.MAX_VALUE - 10),
                Int.MAX_VALUE,
            )
        }
    }

    @Test
    fun nativeIntCapIsAppliedBeforeNarrowingAnEnormousCapacity() {
        val huge = limits.copy(maxSourcePixels = Long.MAX_VALUE, maxWorkingBytes = Long.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, huge.nativePixelLimit(10, null, memory, Int.MAX_VALUE))
    }
}
