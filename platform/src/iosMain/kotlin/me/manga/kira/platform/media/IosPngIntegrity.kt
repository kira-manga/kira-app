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

internal data class IosPngRowPass(val rowBytes: Long, val rows: Long)

internal data class IosPngLayout(val passes: List<IosPngRowPass>, val filteredBytes: Long)

/** Checked geometry only: no expanded buffer, inflater, or ImageIO work. Null means work refusal. */
internal fun iosPngLayout(
    width: Int,
    height: Int,
    depth: Int,
    color: Int,
    interlace: Int,
): IosPngLayout? {
    requirePageFraming(width > 0 && height > 0 && interlace in 0..1)
    val channels =
        when (color) {
            0 -> 1.also { requirePageFraming(depth in listOf(1, 2, 4, 8, 16)) }
            2 -> 3.also { requirePageFraming(depth == 8 || depth == 16) }
            3 -> 1.also { requirePageFraming(depth in listOf(1, 2, 4, 8)) }
            4 -> 2.also { requirePageFraming(depth == 8 || depth == 16) }
            6 -> 4.also { requirePageFraming(depth == 8 || depth == 16) }
            else -> throw PageFramingException(PageInvalidReason.INCOMPLETE_OR_CORRUPT)
        }
    // x origin, y origin, x stride, y stride. Empty Adam7 passes have no filter bytes.
    val geometry =
        if (interlace == 0) {
            arrayOf(intArrayOf(0, 0, 1, 1))
        } else {
            arrayOf(
                intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8),
                intArrayOf(2, 0, 4, 4), intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2),
                intArrayOf(0, 1, 1, 2),
            )
        }
    val passes = mutableListOf<IosPngRowPass>()
    var total = 0L
    for ((x, y, dx, dy) in geometry) {
        val columns = if (width <= x) 0L else (width.toLong() - x + dx - 1) / dx
        val rows = if (height <= y) 0L else (height.toLong() - y + dy - 1) / dy
        if (columns == 0L || rows == 0L) continue
        // Int32 dimensions * at most 64 bits/pixel fit Long; check before the row-count product.
        val rowBytes = (columns * channels * depth + 7) / 8
        if (rows > (IOS_PNG_MAX_FILTERED_BYTES - total) / (rowBytes + 1)) return null
        total += rows * (rowBytes + 1)
        passes += IosPngRowPass(rowBytes, rows)
    }
    return IosPngLayout(passes, total)
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
    requirePageFraming(input.word(0) == 0x89504e47L && input.word(4) == 0x0d0a1a0aL)
    val header = input.chunk(8)
    requirePageFraming(header.type == PNG_IHDR && header.length == 13)
    requirePageFraming(input.word(16) == metadata.width.toLong() && input.word(20) == metadata.height.toLong())
    val depth = input.byte(24)
    val color = input.byte(25)
    requirePageFraming(input.byte(26) == 0 && input.byte(27) == 0)
    val layout =
        iosPngLayout(metadata.width, metadata.height, depth, color, input.byte(28))
            ?: return PageInspection.Rejected(PageInspectionRejection.BOUNDED_DECODER_REJECTED, IOS_PNG_MAX_FILTERED_BYTES)

    return memScoped {
        val stream = alloc<z_stream> {
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
            var offset = header.end
            var paletteEntries = 0
            var transparency = false
            var sawIdat = false
            var idatClosed = false
            while (offset < input.size) {
                val chunk = input.chunk(offset)
                if (sawIdat && chunk.type != PNG_IDAT) idatClosed = true
                when (chunk.type) {
                    PNG_IHDR -> requirePageFraming(false)
                    PNG_PLTE -> {
                        requirePageFraming(!sawIdat && !transparency && paletteEntries == 0 && color != 0 && color != 4)
                        requirePageFraming(chunk.length in 3..768 && chunk.length % 3 == 0)
                        paletteEntries = chunk.length / 3
                        requirePageFraming(color != 3 || paletteEntries <= (1 shl depth))
                    }
                    PNG_TRNS -> {
                        requirePageFraming(!sawIdat && !transparency)
                        requirePageFraming(
                            when (color) {
                                0 -> chunk.length == 2
                                2 -> chunk.length == 6
                                3 -> paletteEntries > 0 && chunk.length in 1..paletteEntries
                                else -> false
                            },
                        )
                        transparency = true
                    }
                    PNG_IDAT -> {
                        requirePageFraming(!idatClosed && (color != 3 || paletteEntries > 0))
                        sawIdat = true
                        val payload = input.bytes + chunk.payload ?: throw IOException("PNG snapshot unavailable")
                        pixels.accept(payload, chunk.length)
                    }
                    PNG_IEND -> {
                        requirePageFraming(chunk.length == 0 && chunk.end == input.size && sawIdat && pixels.complete)
                        return@memScoped null
                    }
                    else -> requirePageFraming(chunk.type and 0x20000000 != 0) // Unknown critical chunk.
                }
                offset = chunk.end
            }
            throw PageFramingException(PageInvalidReason.INCOMPLETE_OR_CORRUPT)
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}

private data class IosPngChunk(val type: Int, val length: Int, val payload: Long, val end: Long)

@OptIn(ExperimentalForeignApi::class)
private class IosPngSnapshot(data: CFDataRef) {
    val size = CFDataGetLength(data)
    val bytes = CFDataGetBytePtr(data) ?: throw IOException("PNG snapshot unavailable")

    fun byte(at: Long): Int {
        requirePageFraming(at >= 0 && at < size)
        return bytes[at].toInt()
    }

    fun word(at: Long): Long =
        (byte(at).toLong() shl 24) or (byte(at + 1).toLong() shl 16) or
            (byte(at + 2).toLong() shl 8) or byte(at + 3).toLong()

    fun chunk(at: Long): IosPngChunk {
        requirePageFraming(at >= 0 && at <= size && size - at >= 12)
        val length = word(at)
        requirePageFraming(length <= Int.MAX_VALUE && length <= size - at - 12)
        for (index in 4L..7L) {
            val letter = byte(at + index)
            requirePageFraming(letter in 65..90 || letter in 97..122)
        }
        requirePageFraming(byte(at + 6) in 65..90) // Reserved chunk-name bit must be zero.
        return IosPngChunk(word(at + 4).toInt(), length.toInt(), at + 8, at + length + 12)
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

    fun accept(input: CPointer<UByteVar>, count: Int) {
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
            if (status == Z_STREAM_END) {
                requirePageFraming(stream.avail_in == 0u && rows.complete)
                complete = true
                return
            }
            if (status == Z_MEM_ERROR) throw IOException("PNG validator allocation failed")
            // Z_NEED_DICT, corrupt header/deflate/Adler, and all other errors refuse; no recovery.
            requirePageFraming(status == Z_OK || status == Z_BUF_ERROR)
            if (before == stream.avail_in && produced == 0) {
                requirePageFraming(stream.avail_in == 0u)
                return // Need another IDAT; IEND still requires an observed Z_STREAM_END.
            }
            if (stream.avail_in == 0u && produced < capacity) return
            // A full output buffer can leave pending output even when this IDAT's input is empty.
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosPngRows(private val layout: IosPngLayout) {
    private var pass = 0
    private var rowsLeft = layout.passes[0].rows
    private var rowBytesLeft = 0L
    private var consumed = 0L
    val remaining: Long get() = layout.filteredBytes - consumed
    val complete: Boolean
        get() = remaining == 0L && rowBytesLeft == 0L && rowsLeft == 0L && pass == layout.passes.lastIndex

    fun consume(bytes: CPointer<UByteVar>, count: Int) {
        requirePageFraming(count.toLong() <= remaining)
        var at = 0
        while (at < count) {
            if (rowBytesLeft == 0L) {
                while (rowsLeft == 0L) {
                    pass++
                    requirePageFraming(pass < layout.passes.size)
                    rowsLeft = layout.passes[pass].rows
                }
                requirePageFraming(bytes[at].toInt() in 0..4)
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
