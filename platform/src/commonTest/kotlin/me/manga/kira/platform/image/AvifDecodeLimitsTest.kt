package me.manga.kira.platform.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AvifDecodeLimitsTest {
    private val memory = AvifMemoryModel(bitmapBytesPerPixel = 4, encodedCopies = 3, fixedBytes = 7)
    private val source = AvifPixelSize(3, 4)
    private val output = AvifPixelSize(2, 3)
    private val limits =
        AvifDecodeLimits(
            maxEncodedBytes = 10,
            maxSourcePixels = 12,
            maxSourceDimension = 4,
            maxOutputBytes = 24,
            // 7 fixed + 10 * 3 encoded + 12 * 32 source + 6 * (4 + 8) output.
            maxWorkingBytes = 493,
        )

    @Test
    fun exactLimitsAreAdmitted() {
        assertEquals(AvifOutputAllocation(rowBytes = 8, byteCount = 24), limits.admit(10, source, output, memory))
    }

    @Test
    fun encodedLimitPlusOneIsRejected() {
        assertFailsWith<AvifDecodeException> { limits.admit(11, source, output, memory) }
    }

    @Test
    fun sourcePixelsAreCheckedEvenWhenTheOutputIsTiny() {
        assertFailsWith<AvifDecodeException> { limits.admit(10, AvifPixelSize(4, 4), AvifPixelSize(1, 1), memory) }
    }

    @Test
    fun eachSourceAxisIsCheckedIndependently() {
        assertFailsWith<AvifDecodeException> { limits.checkSource(AvifPixelSize(5, 1)) }
        assertFailsWith<AvifDecodeException> { limits.checkSource(AvifPixelSize(1, 5)) }
    }

    @Test
    fun outputByteLimitIsCheckedBeforeAllocation() {
        assertFailsWith<AvifDecodeException> { limits.copy(maxOutputBytes = 23).admit(10, source, output, memory) }
    }

    @Test
    fun workingEstimateIncludesEncodedCopiesSourcePlanesAndOutputCopies() {
        assertFailsWith<AvifDecodeException> { limits.copy(maxWorkingBytes = 492).admit(10, source, output, memory) }
    }

    @Test
    fun encodedCopyAllowanceIsCheckedBeforeCreatingNativeInput() {
        limits.copy(maxWorkingBytes = 37).checkEncoded(10, memory)
        assertFailsWith<AvifDecodeException> { limits.copy(maxWorkingBytes = 36).checkEncoded(10, memory) }
    }

    @Test
    fun tallSourceWithinBudgetIsNotRejectedForItsAspectRatio() {
        val tall = AvifPixelSize(32, 352)
        val allocation = AvifDecodeLimits().admit(1114, tall, tall, memory)
        assertEquals(32 * 352 * 4, allocation.byteCount)
    }

    @Test
    fun zeroAndNegativeMetadataFailBeforeSizing() {
        assertFailsWith<AvifDecodeException> { AvifPixelSize(0, 1) }
        assertFailsWith<AvifDecodeException> { AvifPixelSize(1, -1) }
    }

    @Test
    fun hostileMetadataCannotOverflowTheWorkingEstimate() {
        val hugeLimits =
            limits.copy(
                maxSourcePixels = Long.MAX_VALUE,
                maxSourceDimension = Int.MAX_VALUE,
                maxWorkingBytes = Long.MAX_VALUE,
            )
        val hostileSize = AvifPixelSize(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(4_611_686_014_132_420_609L, hostileSize.pixels)
        assertFailsWith<AvifDecodeException> { hugeLimits.admit(10, hostileSize, output, memory) }
    }

    @Test
    fun outputLengthCannotOverflowAnIntEvenWithAnInjectedHugeBudget() {
        val hugeLimits = limits.copy(maxOutputBytes = Long.MAX_VALUE)
        assertFailsWith<AvifDecodeException> { hugeLimits.outputAllocation(AvifPixelSize(Int.MAX_VALUE, 2), 4) }
        assertFailsWith<AvifDecodeException> { hugeLimits.outputAllocation(AvifPixelSize(1, Int.MAX_VALUE), 4) }
    }
}
