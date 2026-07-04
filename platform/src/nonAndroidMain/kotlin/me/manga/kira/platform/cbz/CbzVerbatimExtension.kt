package me.manga.kira.platform.cbz

/**
 * Picks the **true** file extension for a page stored verbatim (i.e. when [SkiaWebpEncoder] could not
 * transcode it — in practice an AVIF page, since skiko ships no libavif). Used by the iOS / Desktop
 * CBZ writers so a non-WebP page is never written under a cosmetic `.webp` name.
 *
 * Prefers the source filename's extension when it is a recognised image type; otherwise sniffs the
 * leading magic bytes; otherwise returns `"img"`. Every extension this can return except the
 * last-resort `"img"` is in [DefaultCbzReader]'s inclusion allow-list, so an honestly-named fallback
 * page is still read back and counted (avif was added to that list for exactly this path).
 */
internal fun verbatimPageExtension(fileName: String, bytes: ByteArray): String {
    val fromName = fileName.substringAfterLast('.', "").lowercase()
    return if (fromName in KNOWN_IMAGE_EXTENSIONS) fromName else sniffImageExtension(bytes) ?: "img"
}

private val KNOWN_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")

/** Best-effort image-format sniff from the leading bytes; `null` when unrecognised. */
private fun sniffImageExtension(b: ByteArray): String? = when {
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpg"
    b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
        b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "png"
    b.size >= 6 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() -> "gif"
    // RIFF....WEBP
    b.size >= 12 && b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
        b[2] == 0x46.toByte() && b[3] == 0x46.toByte() &&
        b[8] == 0x57.toByte() && b[9] == 0x45.toByte() &&
        b[10] == 0x42.toByte() && b[11] == 0x50.toByte() -> "webp"
    // ....ftyp (ISO-BMFF; AVIF and friends)
    b.size >= 12 && b[4] == 0x66.toByte() && b[5] == 0x74.toByte() &&
        b[6] == 0x79.toByte() && b[7] == 0x70.toByte() -> "avif"
    else -> null
}
