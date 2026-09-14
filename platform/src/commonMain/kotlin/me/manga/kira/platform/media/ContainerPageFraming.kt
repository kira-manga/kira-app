package me.manga.kira.platform.media

import okio.BufferedSource

/** Container lengths only; the native sample/status check must establish actual image decodability. */
internal fun inspectWebpFraming(
    source: BufferedSource,
    size: Long,
) {
    requirePageFraming(size >= RIFF_HEADER_BYTES + RIFF_CHUNK_HEADER_BYTES)
    source.skip(FOURCC_BYTES)
    requirePageFraming(source.readUnsignedIntLe() == size - RIFF_SIZE_PREFIX_BYTES)
    source.skip(FOURCC_BYTES)
    var offset = RIFF_HEADER_BYTES
    var sawPixels = false
    while (offset < size) {
        requirePageFraming(size - offset >= RIFF_CHUNK_HEADER_BYTES)
        val name = source.readUtf8(FOURCC_BYTES)
        val length = source.readUnsignedIntLe()
        val padded = length + (length and 1L)
        requirePageFraming(padded <= size - offset - RIFF_CHUNK_HEADER_BYTES)
        if (name == "VP8 " || name == "VP8L" || name == "ANMF") {
            requirePageFraming(length > 0)
            sawPixels = true
        }
        source.skip(padded)
        offset += RIFF_CHUNK_HEADER_BYTES + padded
    }
    requirePageFraming(sawPixels && offset == size)
}

/** No item/AV1 parser: check bounded top-level box framing, leaving payload validation to libavif. */
internal fun inspectAvifFraming(
    source: BufferedSource,
    size: Long,
) {
    var offset = 0L
    var avifBrand = false
    var hasImageContainer = false
    while (offset < size) {
        requirePageFraming(size - offset >= BOX_HEADER_BYTES)
        val shortLength = source.readUnsignedInt()
        val name = source.readUtf8(FOURCC_BYTES)
        val header = if (shortLength == 1L) EXTENDED_BOX_HEADER_BYTES else BOX_HEADER_BYTES
        requirePageFraming(size - offset >= header)
        val length =
            when (shortLength) {
                0L -> size - offset
                1L -> source.readLong()
                else -> shortLength
            }
        requirePageFraming(length >= header && length <= size - offset)
        val payload = length - header
        if (name == "ftyp") {
            requirePageFraming(offset == 0L && payload >= FTYP_HEADER_BYTES && payload % FOURCC_BYTES == 0L)
            avifBrand = readAvifBrands(source, payload)
        } else {
            if (name == "meta" || name == "moov") hasImageContainer = true
            source.skip(payload)
        }
        offset += length
    }
    requirePageFraming(avifBrand && hasImageContainer && offset == size)
}

private fun readAvifBrands(
    source: BufferedSource,
    payload: Long,
): Boolean {
    var matched = source.readUtf8(FOURCC_BYTES).isAvifBrand()
    source.skip(FTYP_MINOR_VERSION_BYTES) // Minor version is not a compatible brand.
    var remaining = payload - FTYP_HEADER_BYTES
    while (remaining > 0) {
        if (source.readUtf8(FOURCC_BYTES).isAvifBrand()) matched = true
        remaining -= FOURCC_BYTES
    }
    return matched
}

private fun String.isAvifBrand(): Boolean = this == "avif" || this == "avis"

private const val FOURCC_BYTES: Long = 4
private const val RIFF_HEADER_BYTES: Long = 12
private const val RIFF_SIZE_PREFIX_BYTES: Long = 8
private const val RIFF_CHUNK_HEADER_BYTES: Long = 8
private const val BOX_HEADER_BYTES: Long = 8
private const val EXTENDED_BOX_HEADER_BYTES: Long = 16
private const val FTYP_HEADER_BYTES: Long = 8
private const val FTYP_MINOR_VERSION_BYTES: Long = 4
