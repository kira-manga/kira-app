package me.manga.kira.platform.media

import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.IOException
import okio.Source
import okio.Timeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure framing/admission checks; no fake result here is native decoder qualification. */
class PageMediaPolicyTest {
    @Test
    fun exactByteLimitIsAllowedAndUnknownOrUnderstatedLengthCannotOverrideActualBytes() {
        val policy = PageBytePolicy(8)
        policy.checkDeclaredLength(null)
        policy.checkDeclaredLength(-1)
        policy.checkDeclaredLength(1)
        assertEquals(8L, policy.checkedTotal(5, 3))
        policy.checkFileSize(8)
        assertFailsWith<PageByteLimitExceeded> { policy.checkedTotal(8, 1) }
        assertFailsWith<PageByteLimitExceeded> { policy.checkDeclaredLength(9) }
        assertFailsWith<PageByteLimitExceeded> { policy.checkFileSize(9) }
        assertFailsWith<IOException> { policy.checkFileSize(0) }
        assertFailsWith<IOException> { policy.checkFileSize(null) }
    }

    @Test
    fun axesAndPixelProductAreIndependentAndLongAspectRatioIsNotARefusal() {
        val policy = PageInspectionPolicy(maxSourcePixels = 1024, maxSourceDimension = 1024)
        assertNull(policy.rejectionFor(PageImageMetadata(PageImageFormat.PNG, 1, 1024)))
        assertEquals(PageInspectionRejection.SOURCE_PIXELS, policy.rejectionFor(PageImageMetadata(PageImageFormat.PNG, 33, 33))?.reason)
        assertEquals(PageInspectionRejection.SOURCE_AXIS, policy.rejectionFor(PageImageMetadata(PageImageFormat.PNG, 1025, 1))?.reason)
        assertEquals(
            Int.MAX_VALUE.toLong() * Int.MAX_VALUE,
            PageImageMetadata(PageImageFormat.PNG, Int.MAX_VALUE, Int.MAX_VALUE).pixelCount,
        )
    }

    @Test
    fun framingNeverMakesAValidPageWithoutTheNativeVerdict() {
        var decoded = 0
        val png = PageMediaTestImages.png()
        val rejected =
            inspect(png) { format ->
                decoded++
                assertEquals(PageImageFormat.PNG, format)
                PageInspection.Rejected(PageInspectionRejection.DECODER_UNAVAILABLE)
            }
        assertIs<PageInspection.Rejected>(rejected)
        assertEquals(1, decoded)
        assertIs<PageInspection.Invalid>(
            inspect(png) {
                PageInspection.Valid(PageImageMetadata(PageImageFormat.JPEG, 8, 9))
            },
            "native/framing disagreement fails closed",
        )
    }

    @Test
    fun emptyHtmlTruncatedAndBadPngCrcNeverReachTheDecoder() {
        val invalid =
            listOf(
                byteArrayOf(),
                PageMediaTestImages.html(),
                PageMediaTestImages.png().dropLast(1).toByteArray(),
                PageMediaTestImages.badPngCrc(),
            )
        for (bytes in invalid) {
            assertIs<PageInspection.Invalid>(inspect(bytes) { error("invalid framing reached native decoder") })
        }
    }

    @Test
    fun byteRefusalHappensBeforeAnySourceOpenAndNativeCancellationIsNotConvertedToInvalid() {
        val oversized =
            inspectPageInput(PageInspectionPolicy(bytePolicy = PageBytePolicy(1)), 2, { error("must not open") }) {
                error("must not decode")
            }
        assertEquals(PageInspectionRejection.ENCODED_BYTES, assertIs<PageInspection.Rejected>(oversized).reason)
        assertFailsWith<CancellationException> { inspect(PageMediaTestImages.png()) { throw CancellationException("cancel") } }
    }

    @Test
    fun snapshotReadsAtMostLimitPlusOneAndClosesItsInputOnRefusal() {
        val source = RecordingSource(ByteArray(100), chunkSize = 3)
        assertFailsWith<PageByteLimitExceeded> { readPageSnapshot(source, PageBytePolicy(8)) }
        assertEquals(9L, source.readBytes)
        assertTrue(source.closed)
        val exact = RecordingSource(byteArrayOf(1, 2, 3), chunkSize = 2)
        assertContentEquals(byteArrayOf(1, 2, 3), readPageSnapshot(exact, PageBytePolicy(3)))
        assertTrue(exact.closed)
    }

    @Test
    fun zeroProgressSourceFailsAndClosesRatherThanSpinning() {
        var closed = false
        val source =
            object : Source {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long = 0

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() {
                    closed = true
                }
            }
        assertFailsWith<IOException> { readPageSnapshot(source) }
        assertTrue(closed)
    }

    private fun inspect(
        bytes: ByteArray,
        decode: (PageImageFormat) -> PageInspection,
    ): PageInspection = inspectPageInput(PageInspectionPolicy(), bytes.size.toLong(), { Buffer().write(bytes) }, decode)
}

private class RecordingSource(
    bytes: ByteArray,
    private val chunkSize: Int,
) : Source {
    private val input = Buffer().write(bytes)
    var readBytes = 0L
    var closed = false

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long =
        input.read(sink, minOf(byteCount, chunkSize.toLong())).also {
            if (it > 0) readBytes += it
        }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }
}
