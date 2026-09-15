package me.manga.kira.platform.backup

import okio.ByteString.Companion.decodeBase64

/**
 * Synthetic ZIP32 bytes replace the desktop-only ZipOutputStream for three backend witnesses.
 * Generated with Python zipfile, DEFLATE level 6, fixed 1980-01-01 timestamp, create_system=0,
 * DOS attributes directory=0x10/file=0x20, and non-seekable output for signed data descriptors.
 * No extra fields, comments, ZIP64, user files or media samples. Fresh arrays allow isolated mutations.
 * Generation was repeated byte-for-byte; Python ZIP CRC reads and raw zlib inflation verified every
 * payload/descriptor. This provenance check is not Kotlin/Native or Android execution evidence.
 */
internal object BoundedBackupZipBackendFixtures {
    fun deflatedUtf8(): ByteArray = decode(DEFLATED_UTF8)

    fun expandedDeflate(): ByteArray = decode(DEFLATED_EXPANDED)

    private fun decode(encoded: String): ByteArray =
        checkNotNull(encoded.trimIndent().replace("\n", "").decodeBase64()).toByteArray()

    // 610-byte ZIP: pages/ empty; pages/صفحة.png = 9000 bytes, byte[i] = i mod 256.
    // Packed sizes 2/354; CRC32 00000000/38b1ad4a; central offset 472; UTF-8 file flag 0x0808.
    // SHA256 bdfcbf0b1813ec9612d9e2c5132078ce23dcfb08cdeaadec134aeb157a012795.
    private const val DEFLATED_UTF8 = """
        UEsDBBQACAAIAAAAIQAAAAAAAAAAAAAAAAAGAAAAcGFnZXMvAwBQSwcIAAAAAAIAAAAAAAAAUEsDBBQACAgIAAAAIQAAAAAA
        AAAAAAAAAAASAAAAcGFnZXMv2LXZgdit2KkucG5nY2BkYmZhZWPn4OTi5uHl4xcQFBIWERUTl5CUkpaRlZNXUFRSVlFVU9fQ
        1NLW0dXTNzA0MjYxNTO3sLSytrG1s3dwdHJ2cXVz9/D08vbx9fMPCAwKDgkNC4+IjIqOiY2LT0hMSk5JTUvPyMzKzsnNyy8o
        LCouKS0rr6isqq6pratvaGxqbmlta+/o7Oru6e3rnzBx0uQpU6dNnzFz1uw5c+fNX7Bw0eIlS5ctX7Fy1eo1a9et37Bx0+Yt
        W7dt37Fz1+49e/ftP3Dw0OEjR48dP3Hy1OkzZ8+dv3Dx0uUrV69dv3Hz1u07d+/df/Dw0eMnT589f/Hy1es3b9+9//Dx0+cv
        X799//Hz1+8/f//9Zxj1/6j/R/0/6v9R/4/6f9T/o/4f9f+o/0f9P+r/Uf+P+n/U/6P+H/X/qP9H/T/q/1H/j/p/1P+j/h/1
        /6j/R/0/6v9R/4/6f9T/o/4f9f+I8D8AUEsHCEqtsThiAQAAKCMAAFBLAQIUABQACAAIAAAAIQAAAAAAAgAAAAAAAAAGAAAA
        AAAAAAAAEAAAAAAAAABwYWdlcy9QSwECFAAUAAgICAAAACEASq2xOGIBAAAoIwAAEgAAAAAAAAAAACAAAAA2AAAAcGFnZXMv
        2LXZgdit2KkucG5nUEsFBgAAAAACAAIAdAAAANgBAAAAAA==
    """

    // 158-byte ZIP: backup.json = 4096 ASCII 'a' bytes, packed to 22; CRC32 9c99dc73.
    // Signed descriptor starts at 63; central offset 79 (central - 4 is the descriptor size field).
    // SHA256 b81f8f0f11a7ee9a808cfba7a0b410f8c6b83bf1065414c17dcccc9de0b7ba0c.
    private const val DEFLATED_EXPANDED = """
        UEsDBBQACAAIAAAAIQAAAAAAAAAAAAAAAAALAAAAYmFja3VwLmpzb27twQENAAAAwqCs71/CHg4oAAAA4N0AUEsHCHPcmZwW
        AAAAABAAAFBLAQIUABQACAAIAAAAIQBz3JmcFgAAAAAQAAALAAAAAAAAAAAAIAAAAAAAAABiYWNrdXAuanNvblBLBQYAAAAA
        AQABADkAAABPAAAAAAA=
    """
}
