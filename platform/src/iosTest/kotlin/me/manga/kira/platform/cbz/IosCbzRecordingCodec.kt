package me.manga.kira.platform.cbz

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGImageRef
import platform.ImageIO.CGImageSourceRef
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Observes real native calls/ownership; overrides in tests introduce only the named fault. */
@OptIn(ExperimentalForeignApi::class)
internal open class IosCbzRecordingCodec : IosCbzNativeCodec() {
    private val bands = mutableListOf<RecordedBand>()
    var decodes = 0
        private set
    var contexts = 0
        private set
    var encodes = 0
        private set
    var copies = 0
        private set
    var frees = 0
        private set
    var liveImages = 0
        private set
    var liveContexts = 0
        private set
    var liveOutputs = 0
        private set

    override fun decode(source: CGImageSourceRef): CGImageRef? {
        decodes++
        return super.decode(source)?.also { liveImages++ }
    }

    override fun createContext(
        plan: CbzTranscodePlan,
        colorSpace: CGColorSpaceRef,
    ): CGContextRef? {
        contexts++
        return super.createContext(plan, colorSpace)?.also { liveContexts++ }
    }

    override fun encodeBand(
        band: IosRgbaBand,
        quality: Int,
        output: CPointer<CPointerVar<UByteVar>>,
    ): ULong {
        assertNull(output.pointed.value, "native output must be initialized before every encode")
        encodes++
        bands += RecordedBand(band.width, band.height, band.stride, quality)
        return super.encodeBand(band, quality, output).also {
            if (output.pointed.value != null) liveOutputs++
        }
    }

    override fun copyEncoded(
        pointer: CPointer<UByteVar>,
        size: Int,
    ): ByteArray {
        copies++
        return super.copyEncoded(pointer, size)
    }

    override fun freeEncoded(pointer: CPointer<UByteVar>) {
        frees++
        liveOutputs--
        super.freeEncoded(pointer)
    }

    override fun releaseImage(image: CGImageRef) {
        liveImages--
        super.releaseImage(image)
    }

    override fun releaseContext(context: CGContextRef) {
        liveContexts--
        super.releaseContext(context)
    }

    fun assertReleased() {
        assertEquals(0, liveImages)
        assertEquals(0, liveContexts)
        assertEquals(0, liveOutputs)
    }

    fun assertBands(
        width: Int,
        heights: List<Int>,
        quality: Int,
    ) {
        assertEquals(heights.map { RecordedBand(width, it, width * RGBA_BYTES_PER_PIXEL, quality) }, bands)
    }

    fun assertNoTranscode() {
        assertEquals(0, decodes)
        assertEquals(0, contexts)
        assertEquals(0, encodes)
        assertEquals(0, copies)
        assertEquals(0, frees)
        assertReleased()
    }

    private data class RecordedBand(
        val width: Int,
        val height: Int,
        val stride: Int,
        val quality: Int,
    )

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}
