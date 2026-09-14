package me.manga.kira.platform.media

import me.manga.kira.platform.backup.Crc32
import okio.BufferedSource

/** Bounded streaming CRC/framing checks supplement native decoders that tolerate a truncated PNG. */
internal fun inspectPngFraming(
    source: BufferedSource,
    size: Long,
) {
    source.skip(PNG_START.size.toLong())
    var offset = PNG_START.size.toLong()
    var sawHeader = false
    var sawPixels = false
    while (offset < size) {
        requirePageFraming(size - offset >= PNG_CHUNK_OVERHEAD)
        val length = source.readUnsignedInt()
        requirePageFraming(length <= size - offset - PNG_CHUNK_OVERHEAD)
        val type = source.readByteArray(4)
        val name = type.decodeToString()
        requirePageFraming(if (!sawHeader) name == "IHDR" && length == 13L else name != "IHDR")
        sawHeader = true
        val crc = Crc32().apply { update(type) }
        readPngPayload(source, length, crc)
        requirePageFraming(source.readInt() == crc.value)
        offset += length + PNG_CHUNK_OVERHEAD
        if (name == "IDAT" && length > 0) sawPixels = true
        if (name == "IEND") {
            requirePageFraming(length == 0L && sawPixels && offset == size)
            return
        }
    }
    requirePageFraming(false)
}

private fun readPngPayload(
    source: BufferedSource,
    length: Long,
    crc: Crc32,
) {
    var remaining = length
    val buffer = ByteArray(PNG_READ_BYTES)
    while (remaining > 0) {
        val read = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        requirePageFraming(read > 0)
        crc.update(buffer, 0, read)
        remaining -= read
    }
}

private const val PNG_CHUNK_OVERHEAD: Long = 12
private const val PNG_READ_BYTES: Int = 8192
