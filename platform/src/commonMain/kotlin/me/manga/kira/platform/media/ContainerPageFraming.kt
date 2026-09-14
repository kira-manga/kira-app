package me.manga.kira.platform.media

import okio.BufferedSource

/** Container lengths only; the native sample/status check must establish actual image decodability. */
internal fun inspectWebpFraming(source: BufferedSource, size: Long) {
    requirePageFraming(size >= 20)
    source.skip(4)
    requirePageFraming(source.readUnsignedIntLe() == size - 8)
    source.skip(4)
    var offset = 12L
    var sawPixels = false
    while (offset < size) {
        requirePageFraming(size - offset >= 8)
        val name = source.readUtf8(4)
        val length = source.readUnsignedIntLe()
        val padded = length + (length and 1L)
        requirePageFraming(padded <= size - offset - 8)
        if (name == "VP8 " || name == "VP8L" || name == "ANMF") {
            requirePageFraming(length > 0)
            sawPixels = true
        }
        source.skip(padded)
        offset += 8 + padded
    }
    requirePageFraming(sawPixels && offset == size)
}

/** No item/AV1 parser: check bounded top-level box framing, leaving payload validation to libavif. */
internal fun inspectAvifFraming(source: BufferedSource, size: Long) {
    var offset = 0L
    var avifBrand = false
    var hasImageContainer = false
    while (offset < size) {
        requirePageFraming(size - offset >= 8)
        val shortLength = source.readUnsignedInt()
        val name = source.readUtf8(4)
        val header = if (shortLength == 1L) 16L else 8L
        requirePageFraming(size - offset >= header)
        val length = when (shortLength) {
            0L -> size - offset
            1L -> source.readLong()
            else -> shortLength
        }
        requirePageFraming(length >= header && length <= size - offset)
        val payload = length - header
        if (name == "ftyp") {
            requirePageFraming(offset == 0L && payload >= 8 && payload % 4 == 0L)
            avifBrand = readAvifBrands(source, payload)
        } else {
            if (name == "meta" || name == "moov") hasImageContainer = true
            source.skip(payload)
        }
        offset += length
    }
    requirePageFraming(avifBrand && hasImageContainer && offset == size)
}

private fun readAvifBrands(source: BufferedSource, payload: Long): Boolean {
    var matched = source.readUtf8(4).isAvifBrand()
    source.skip(4) // Minor version is not a compatible brand.
    var remaining = payload - 8
    while (remaining > 0) {
        if (source.readUtf8(4).isAvifBrand()) matched = true
        remaining -= 4
    }
    return matched
}

private fun String.isAvifBrand(): Boolean = this == "avif" || this == "avis"
