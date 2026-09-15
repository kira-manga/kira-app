package me.manga.kira.platform.media

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import okio.IOException
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_MEM_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit
import platform.zlib.z_stream

/** Finite filtered-byte work admission, not a native allocation/RSS guarantee. */
internal const val IOS_PNG_MAX_FILTERED_BYTES: Long = 1_073_741_824
private const val PNG_INFLATE_SCRATCH_BYTES: Int = 32_768

internal data class IosPngRowPass(
    val rowBytes: Long,
    val rows: Long,
)

internal data class IosPngLayout(
    val passes: List<IosPngRowPass>,
    val filteredBytes: Long,
)

/** Checked geometry only: no expanded buffer, inflater, or ImageIO work. Null means work refusal. */
internal fun iosPngLayout(
    width: Int,
    height: Int,
    depth: Int,
    color: Int,
    interlace: Int,
): IosPngLayout? {
    requirePageFraming(width > 0 && height > 0 && interlace in 0..1)
    val channels = iosPngChannels(depth, color)
    // x origin, y origin, x stride, y stride. Empty Adam7 passes have no filter bytes.
    val geometry =
        if (interlace == 0) {
            arrayOf(intArrayOf(0, 0, 1, 1))
        } else {
            arrayOf(
                intArrayOf(0, 0, PNG_ADAM7_WIDE_STEP, PNG_ADAM7_WIDE_STEP),
                intArrayOf(PNG_ADAM7_HALF_STEP, 0, PNG_ADAM7_WIDE_STEP, PNG_ADAM7_WIDE_STEP),
                intArrayOf(0, PNG_ADAM7_HALF_STEP, PNG_ADAM7_HALF_STEP, PNG_ADAM7_WIDE_STEP),
                intArrayOf(2, 0, PNG_ADAM7_HALF_STEP, PNG_ADAM7_HALF_STEP),
                intArrayOf(0, 2, 2, PNG_ADAM7_HALF_STEP),
                intArrayOf(1, 0, 2, 2),
                intArrayOf(0, 1, 1, 2),
            )
        }
    val passes = mutableListOf<IosPngRowPass>()
    var total = 0L
    for (pass in geometry) {
        val x = pass[0]
        val y = pass[1]
        val dx = pass[2]
        val dy = pass[PNG_PASS_Y_STRIDE_INDEX]
        val columns = if (width <= x) 0L else (width.toLong() - x + dx - 1) / dx
        val rows = if (height <= y) 0L else (height.toLong() - y + dy - 1) / dy
        if (columns == 0L || rows == 0L) continue
        // Int32 dimensions * at most 64 bits/pixel fit Long; check before the row-count product.
        val rowBytes = (columns * channels * depth + PNG_ROW_PADDING_BITS) / PNG_BITS_PER_BYTE
        if (rows > (IOS_PNG_MAX_FILTERED_BYTES - total) / (rowBytes + 1)) return null
        total += rows * (rowBytes + 1)
        passes += IosPngRowPass(rowBytes, rows)
    }
    return IosPngLayout(passes, total)
}

private fun iosPngChannels(
    depth: Int,
    color: Int,
): Int =
    when (color) {
        PNG_COLOR_GRAY -> 1.also { requirePageFraming(depth in listOf(1, 2, PNG_DEPTH_4, PNG_DEPTH_8, PNG_DEPTH_16)) }
        PNG_COLOR_RGB -> {
            requirePageFraming(depth == PNG_DEPTH_8 || depth == PNG_DEPTH_16)
            PNG_RGB_CHANNELS
        }
        PNG_COLOR_INDEXED -> 1.also { requirePageFraming(depth in listOf(1, 2, PNG_DEPTH_4, PNG_DEPTH_8)) }
        PNG_COLOR_GRAY_ALPHA -> 2.also { requirePageFraming(depth == PNG_DEPTH_8 || depth == PNG_DEPTH_16) }
        PNG_COLOR_RGBA -> PNG_RGBA_CHANNELS.also { requirePageFraming(depth == PNG_DEPTH_8 || depth == PNG_DEPTH_16) }
        else -> throw PageFramingException(PageInvalidReason.INCOMPLETE_OR_CORRUPT)
    }

/**
 * Called only after the common framing/CRC pass over THIS retained CFData and native source
 * dimension/pixel admission. A valid zlib stream is necessary, never a replacement for ImageIO.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun validateIosPngIntegrity(
    data: CFDataRef,
    metadata: PageImageMetadata,
): PageInspection.Rejected? {
    val input = IosPngSnapshot(data)
    requirePageFraming(
        input.word(0) == PNG_SIGNATURE_FIRST_WORD && input.word(PNG_WORD_BYTES) == PNG_SIGNATURE_LAST_WORD,
    )
    val header = input.chunk(PNG_SIGNATURE_BYTES)
    requirePageFraming(header.type == PNG_IHDR && header.length == PNG_IHDR_BYTES)
    requirePageFraming(
        input.word(PNG_WIDTH_OFFSET) == metadata.width.toLong() &&
            input.word(PNG_HEIGHT_OFFSET) == metadata.height.toLong(),
    )
    val depth = input.byte(PNG_DEPTH_OFFSET)
    val color = input.byte(PNG_COLOR_OFFSET)
    requirePageFraming(input.byte(PNG_COMPRESSION_OFFSET) == 0 && input.byte(PNG_FILTER_OFFSET) == 0)
    val layout =
        iosPngLayout(metadata.width, metadata.height, depth, color, input.byte(PNG_INTERLACE_OFFSET))
            ?: return PageInspection.Rejected(
                PageInspectionRejection.BOUNDED_DECODER_REJECTED,
                IOS_PNG_MAX_FILTERED_BYTES,
            )

    return memScoped {
        val stream =
            alloc<z_stream> {
                zalloc = null
                zfree = null
                opaque = null
                next_in = null
                avail_in = 0u
                next_out = null
                avail_out = 0u
            }
        val output = allocArray<UByteVar>(PNG_INFLATE_SCRATCH_BYTES)
        if (inflateInit(stream.ptr) != Z_OK) throw IOException("PNG validator initialization failed")
        try {
            val pixels = IosPngInflation(stream, output, IosPngRows(layout))
            IosPngChunks(input, depth, color, pixels).validate(header.end)
            null
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosPngChunks(
    private val input: IosPngSnapshot,
    private val depth: Int,
    private val color: Int,
    private val pixels: IosPngInflation,
) {
    private var paletteEntries = 0
    private var transparency = false
    private var sawIdat = false
    private var idatClosed = false

    fun validate(firstOffset: Long) {
        var offset = firstOffset
        while (offset < input.size) {
            val chunk = input.chunk(offset)
            if (sawIdat && chunk.type != PNG_IDAT) idatClosed = true
            when (chunk.type) {
                PNG_IHDR -> requirePageFraming(false)
                PNG_PLTE -> acceptPalette(chunk)
                PNG_TRNS -> acceptTransparency(chunk)
                PNG_IDAT -> acceptIdat(chunk)
                PNG_IEND -> {
                    requirePageFraming(chunk.length == 0 && chunk.end == input.size && sawIdat && pixels.complete)
                    return
                }
                else -> requirePageFraming(chunk.type and PNG_ANCILLARY_CHUNK_BIT != 0) // Unknown critical chunk.
            }
            offset = chunk.end
        }
        throw PageFramingException(PageInvalidReason.INCOMPLETE_OR_CORRUPT)
    }

    private fun acceptPalette(chunk: IosPngChunk) {
        requirePageFraming(
            !sawIdat &&
                !transparency &&
                paletteEntries == 0 &&
                color != PNG_COLOR_GRAY &&
                color != PNG_COLOR_GRAY_ALPHA,
        )
        requirePageFraming(
            chunk.length in PNG_PALETTE_ENTRY_BYTES..PNG_PALETTE_MAX_BYTES &&
                chunk.length % PNG_PALETTE_ENTRY_BYTES == 0,
        )
        paletteEntries = chunk.length / PNG_PALETTE_ENTRY_BYTES
        requirePageFraming(color != PNG_COLOR_INDEXED || paletteEntries <= (1 shl depth))
    }

    private fun acceptTransparency(chunk: IosPngChunk) {
        requirePageFraming(!sawIdat && !transparency)
        requirePageFraming(
            when (color) {
                PNG_COLOR_GRAY -> chunk.length == 2
                PNG_COLOR_RGB -> chunk.length == PNG_RGB_TRANSPARENCY_BYTES
                PNG_COLOR_INDEXED -> paletteEntries > 0 && chunk.length in 1..paletteEntries
                else -> false
            },
        )
        transparency = true
    }

    private fun acceptIdat(chunk: IosPngChunk) {
        requirePageFraming(!idatClosed && (color != PNG_COLOR_INDEXED || paletteEntries > 0))
        sawIdat = true
        val payload = input.bytes + chunk.payload ?: throw IOException("PNG snapshot unavailable")
        pixels.accept(payload, chunk.length)
    }
}

private data class IosPngChunk(
    val type: Int,
    val length: Int,
    val payload: Long,
    val end: Long,
)

@OptIn(ExperimentalForeignApi::class)
private class IosPngSnapshot(
    data: CFDataRef,
) {
    val size = CFDataGetLength(data)
    val bytes = CFDataGetBytePtr(data) ?: throw IOException("PNG snapshot unavailable")

    fun byte(at: Long): Int {
        requirePageFraming(at >= 0 && at < size)
        return bytes[at].toInt()
    }

    fun word(at: Long): Long =
        (byte(at).toLong() shl PNG_WORD_HIGH_SHIFT) or (byte(at + 1).toLong() shl PNG_WORD_MIDDLE_SHIFT) or
            (byte(at + 2).toLong() shl PNG_BITS_PER_BYTE) or byte(at + PNG_WORD_LAST_BYTE_OFFSET).toLong()

    fun chunk(at: Long): IosPngChunk {
        requirePageFraming(at >= 0 && at <= size && size - at >= PNG_CHUNK_OVERHEAD_BYTES)
        val length = word(at)
        requirePageFraming(length <= Int.MAX_VALUE && length <= size - at - PNG_CHUNK_OVERHEAD_BYTES)
        for (index in PNG_WORD_BYTES..PNG_CHUNK_TYPE_LAST_OFFSET) {
            val letter = byte(at + index)
            requirePageFraming(letter in 'A'.code..'Z'.code || letter in 'a'.code..'z'.code)
        }
        // Reserved chunk-name bit must be zero.
        requirePageFraming(byte(at + PNG_CHUNK_RESERVED_OFFSET) in 'A'.code..'Z'.code)
        return IosPngChunk(
            word(at + PNG_WORD_BYTES).toInt(),
            length.toInt(),
            at + PNG_CHUNK_PAYLOAD_OFFSET,
            at + length + PNG_CHUNK_OVERHEAD_BYTES,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosPngInflation(
    private val stream: z_stream,
    private val output: CPointer<UByteVar>,
    private val rows: IosPngRows,
) {
    var complete = false
        private set

    fun accept(
        input: CPointer<UByteVar>,
        count: Int,
    ) {
        if (count == 0) return // Empty IDAT is legal even after the sole stream has ended.
        requirePageFraming(!complete && stream.avail_in == 0u)
        stream.next_in = input
        stream.avail_in = count.toUInt()
        while (true) {
            // One extra byte detects excess output even when the expected rows are already full.
            val capacity = minOf(PNG_INFLATE_SCRATCH_BYTES.toLong(), rows.remaining + 1).toInt()
            stream.next_out = output
            stream.avail_out = capacity.toUInt()
            val before = stream.avail_in
            val status = inflate(stream.ptr, Z_NO_FLUSH)
            requirePageFraming(stream.avail_in <= before && stream.avail_out <= capacity.toUInt())
            val produced = capacity - stream.avail_out.toInt()
            rows.consume(output, produced)
            if (finishInflationStep(status, before, produced, capacity)) return
            // A full output buffer can leave pending output even when this IDAT's input is empty.
        }
    }

    private fun finishInflationStep(
        status: Int,
        before: UInt,
        produced: Int,
        capacity: Int,
    ): Boolean =
        when (status) {
            Z_STREAM_END -> {
                requirePageFraming(stream.avail_in == 0u && rows.complete)
                complete = true
                true
            }
            Z_MEM_ERROR -> throw IOException("PNG validator allocation failed")
            else -> {
                // Z_NEED_DICT, corrupt header/deflate/Adler, and all other errors refuse; no recovery.
                requirePageFraming(status == Z_OK || status == Z_BUF_ERROR)
                if (before == stream.avail_in && produced == 0) {
                    requirePageFraming(stream.avail_in == 0u)
                    true // Need another IDAT; IEND still requires an observed Z_STREAM_END.
                } else {
                    stream.avail_in == 0u && produced < capacity
                }
            }
        }
}

@OptIn(ExperimentalForeignApi::class)
private class IosPngRows(
    private val layout: IosPngLayout,
) {
    private var pass = 0
    private var rowsLeft = layout.passes[0].rows
    private var rowBytesLeft = 0L
    private var consumed = 0L
    val remaining: Long get() = layout.filteredBytes - consumed
    val complete: Boolean
        get() = remaining == 0L && rowBytesLeft == 0L && rowsLeft == 0L && pass == layout.passes.lastIndex

    fun consume(
        bytes: CPointer<UByteVar>,
        count: Int,
    ) {
        requirePageFraming(count.toLong() <= remaining)
        var at = 0
        while (at < count) {
            if (rowBytesLeft == 0L) {
                while (rowsLeft == 0L) {
                    pass++
                    requirePageFraming(pass < layout.passes.size)
                    rowsLeft = layout.passes[pass].rows
                }
                requirePageFraming(bytes[at].toInt() in 0..PNG_MAX_FILTER_TYPE)
                at++
                rowsLeft--
                rowBytesLeft = layout.passes[pass].rowBytes
            }
            val skip = minOf(rowBytesLeft, (count - at).toLong()).toInt()
            rowBytesLeft -= skip
            at += skip
        }
        consumed += count
    }
}

private const val PNG_IHDR = 0x49484452
private const val PNG_PLTE = 0x504c5445
private const val PNG_TRNS = 0x74524e53
private const val PNG_IDAT = 0x49444154
private const val PNG_IEND = 0x49454e44
private const val PNG_COLOR_GRAY = 0
private const val PNG_COLOR_RGB = 2
private const val PNG_COLOR_INDEXED = 3
private const val PNG_COLOR_GRAY_ALPHA = 4
private const val PNG_COLOR_RGBA = 6
private const val PNG_DEPTH_4 = 4
private const val PNG_DEPTH_8 = 8
private const val PNG_DEPTH_16 = 16
private const val PNG_RGB_CHANNELS = 3
private const val PNG_RGBA_CHANNELS = 4
private const val PNG_ADAM7_WIDE_STEP = 8
private const val PNG_ADAM7_HALF_STEP = 4
private const val PNG_PASS_Y_STRIDE_INDEX = 3
private const val PNG_BITS_PER_BYTE = 8
private const val PNG_ROW_PADDING_BITS = 7
private const val PNG_SIGNATURE_FIRST_WORD = 0x89504e47L
private const val PNG_SIGNATURE_LAST_WORD = 0x0d0a1a0aL
private const val PNG_WORD_BYTES = 4L
private const val PNG_SIGNATURE_BYTES = 8L
private const val PNG_IHDR_BYTES = 13
private const val PNG_WIDTH_OFFSET = 16L
private const val PNG_HEIGHT_OFFSET = 20L
private const val PNG_DEPTH_OFFSET = 24L
private const val PNG_COLOR_OFFSET = 25L
private const val PNG_COMPRESSION_OFFSET = 26L
private const val PNG_FILTER_OFFSET = 27L
private const val PNG_INTERLACE_OFFSET = 28L
private const val PNG_PALETTE_ENTRY_BYTES = 3
private const val PNG_PALETTE_MAX_BYTES = 768
private const val PNG_RGB_TRANSPARENCY_BYTES = 6
private const val PNG_ANCILLARY_CHUNK_BIT = 0x20000000
private const val PNG_WORD_HIGH_SHIFT = 24
private const val PNG_WORD_MIDDLE_SHIFT = 16
private const val PNG_WORD_LAST_BYTE_OFFSET = 3L
private const val PNG_CHUNK_OVERHEAD_BYTES = 12L
private const val PNG_CHUNK_TYPE_LAST_OFFSET = 7L
private const val PNG_CHUNK_RESERVED_OFFSET = 6L
private const val PNG_CHUNK_PAYLOAD_OFFSET = 8L
private const val PNG_MAX_FILTER_TYPE = 4
