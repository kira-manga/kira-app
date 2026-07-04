package me.manga.kira.platform.cbz

import okio.BufferedSink

/**
 * Minimal, dependency-free ZIP writer that stores every entry uncompressed (STORE / method 0).
 *
 * iOS / Kotlin-Native has no `java.util.zip`, so the ZIP container is assembled by hand directly
 * on an okio [BufferedSink]. A STORE-method archive is a fully valid ZIP — the reader side
 * ([DefaultCbzReader] via okio's `openZip`) dispatches on each entry's stored compression method
 * and reads STORE (0) and DEFLATE (8) transparently, so a STORE-only archive is readable on every
 * platform with zero reader changes.
 *
 * STORE (rather than DEFLATE) is the right fit here: the entries handed to [writeEntry] are
 * already-compressed image bytes — WebP transcoded by [SkiaWebpEncoder] for decodable pages, or the
 * original page bytes verbatim under their true extension when a format can't be decoded — so
 * re-deflating them would only burn CPU for no size win. Peak-memory bounding lives in
 * [SkiaWebpEncoder] (it bands tall pages); this writer just streams the finished entry bytes -> sink.
 *
 * All multi-byte fields are little-endian, per the ZIP (APPNOTE) specification. Filenames are
 * ASCII (`page_NNNN.webp`, or `page_NNNN.<ext>` for verbatim fallbacks), so their UTF-8 encoding
 * equals their ASCII bytes and no general-purpose "UTF-8 names" flag is required.
 *
 * Usage: construct over an open sink, call [writeEntry] once per page in order, then [finish]
 * exactly once to emit the central directory + end-of-central-directory record. The caller owns
 * the sink lifecycle (open / close).
 */
internal class StoreZipWriter(private val sink: BufferedSink) {

    private data class CentralEntry(
        val nameBytes: ByteArray,
        val crc32: Int,
        val size: Int,
        val localHeaderOffset: Int,
    )

    private val entries = mutableListOf<CentralEntry>()

    /** Running count of bytes written to [sink] so far — used as each entry's local-header offset. */
    private var bytesWritten: Long = 0L

    private var finished = false

    /**
     * Append one STORE entry: a local file header followed by [data] verbatim.
     *
     * @param name entry path inside the archive (ASCII; e.g. `page_0001.webp`).
     * @param data the raw, uncompressed bytes to store.
     */
    fun writeEntry(name: String, data: ByteArray) {
        check(!finished) { "writeEntry called after finish()" }
        check(bytesWritten <= MAX_ZIP32_OFFSET) { "ZIP32 offset overflow: archive exceeds 4 GiB" }
        val nameBytes = name.encodeToByteArray()
        val crc = crc32(data)
        val size = data.size
        val offset = bytesWritten.toInt()

        // ---- Local file header (signature 0x04034b50) ----
        writeIntLe(LOCAL_FILE_HEADER_SIG)
        writeShortLe(VERSION_NEEDED)        // version needed to extract (2.0)
        writeShortLe(0)                     // general-purpose bit flag
        writeShortLe(METHOD_STORE)          // compression method = STORE
        writeShortLe(0)                     // last mod file time
        writeShortLe(0)                     // last mod file date
        writeIntLe(crc)                     // CRC-32
        writeIntLe(size)                    // compressed size (== uncompressed for STORE)
        writeIntLe(size)                    // uncompressed size
        writeShortLe(nameBytes.size)        // file name length
        writeShortLe(0)                     // extra field length
        writeBytes(nameBytes)               // file name
        writeBytes(data)                    // file data (stored verbatim)

        entries += CentralEntry(nameBytes, crc, size, offset)
    }

    /**
     * Emit the central directory and the end-of-central-directory record. Call exactly once after
     * all entries have been written. Does not close the sink.
     */
    fun finish() {
        check(!finished) { "finish() called more than once" }
        check(entries.size <= MAX_ZIP32_ENTRIES) { "ZIP32 entry overflow: ${entries.size} entries exceed 65535" }
        check(bytesWritten <= MAX_ZIP32_OFFSET) { "ZIP32 offset overflow: archive exceeds 4 GiB" }
        finished = true

        val centralDirOffset = bytesWritten

        for (entry in entries) {
            // ---- Central directory file header (signature 0x02014b50) ----
            writeIntLe(CENTRAL_DIR_HEADER_SIG)
            writeShortLe(VERSION_MADE_BY)        // version made by
            writeShortLe(VERSION_NEEDED)         // version needed to extract
            writeShortLe(0)                      // general-purpose bit flag
            writeShortLe(METHOD_STORE)           // compression method = STORE
            writeShortLe(0)                      // last mod file time
            writeShortLe(0)                      // last mod file date
            writeIntLe(entry.crc32)              // CRC-32
            writeIntLe(entry.size)               // compressed size
            writeIntLe(entry.size)               // uncompressed size
            writeShortLe(entry.nameBytes.size)   // file name length
            writeShortLe(0)                      // extra field length
            writeShortLe(0)                      // file comment length
            writeShortLe(0)                      // disk number start
            writeShortLe(0)                      // internal file attributes
            writeIntLe(0)                        // external file attributes
            writeIntLe(entry.localHeaderOffset)  // relative offset of local header
            writeBytes(entry.nameBytes)          // file name
        }

        val centralDirSize = bytesWritten - centralDirOffset

        // ---- End of central directory record (signature 0x06054b50) ----
        writeIntLe(END_OF_CENTRAL_DIR_SIG)
        writeShortLe(0)                          // number of this disk
        writeShortLe(0)                          // disk with start of central directory
        writeShortLe(entries.size)               // central-dir entries on this disk
        writeShortLe(entries.size)               // total central-dir entries
        writeIntLe(centralDirSize.toInt())       // size of central directory
        writeIntLe(centralDirOffset.toInt())     // offset of central directory
        writeShortLe(0)                          // ZIP file comment length
    }

    private fun writeIntLe(value: Int) {
        sink.writeIntLe(value)
        bytesWritten += 4
    }

    private fun writeShortLe(value: Int) {
        sink.writeShortLe(value)
        bytesWritten += 2
    }

    private fun writeBytes(bytes: ByteArray) {
        sink.write(bytes)
        bytesWritten += bytes.size
    }

    private companion object {
        const val LOCAL_FILE_HEADER_SIG: Int = 0x04034b50
        const val CENTRAL_DIR_HEADER_SIG: Int = 0x02014b50
        const val END_OF_CENTRAL_DIR_SIG: Int = 0x06054b50
        const val VERSION_NEEDED: Int = 20      // 2.0
        const val VERSION_MADE_BY: Int = 20     // 2.0
        const val METHOD_STORE: Int = 0
        const val MAX_ZIP32_OFFSET: Long = 0xFFFFFFFFL  // ZIP32 4-byte offset ceiling (~4 GiB)
        const val MAX_ZIP32_ENTRIES: Int = 0xFFFF       // ZIP32 2-byte entry-count ceiling

    }
}

/**
 * Table-based CRC-32 (IEEE 802.3 / reflected polynomial 0xEDB88320) over [data].
 *
 * The lookup table is built once and cached. Matches the CRC stored in every ZIP local-file and
 * central-directory header, which okio's reader validates / exposes when reading the archive.
 */
internal fun crc32(data: ByteArray): Int {
    var crc = 0.inv() // 0xFFFFFFFF
    for (b in data) {
        val index = (crc xor b.toInt()) and 0xFF
        crc = (crc ushr 8) xor CRC32_TABLE[index]
    }
    return crc.inv()
}

private val CRC32_TABLE: IntArray = buildCrc32Table()

private fun buildCrc32Table(): IntArray {
    val polynomial = 0xEDB88320.toInt()
    val table = IntArray(256)
    for (i in 0 until 256) {
        var c = i
        repeat(8) {
            c = if (c and 1 != 0) (c ushr 1) xor polynomial else c ushr 1
        }
        table[i] = c
    }
    return table
}
