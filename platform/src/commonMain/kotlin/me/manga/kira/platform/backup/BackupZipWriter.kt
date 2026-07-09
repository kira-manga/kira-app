package me.manga.kira.platform.backup

import okio.BufferedSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer

/**
 * The archive would exceed a hard ZIP32 ceiling (4 GiB total or 65535 entries). Surfaced as a
 * distinct type so the caller can map it to a typed "backup too large" error instead of a
 * generic IO failure.
 */
class ZipLimitExceededException(message: String) : IOException(message)

/**
 * Minimal STORE-method (uncompressed) ZIP writer over an okio [BufferedSink] — the commonMain
 * generalization of iosMain's CBZ-specific `StoreZipWriter`, used for backup archives on every
 * platform.
 *
 * Differences from the CBZ writer, both load-bearing for backups:
 *  - [writeEntryFromFile] STREAMS an on-disk file into the archive in fixed-size chunks
 *    (two passes: CRC-32 first, bytes second) — backup entries are whole chapter CBZs that can be
 *    tens of MB each, so the whole-entry `ByteArray` buffering the CBZ writer does per page is
 *    not acceptable here.
 *  - Sizes/offsets are tracked as Long and guarded against the ZIP32 ceilings with a typed
 *    [ZipLimitExceededException] rather than `check()`.
 *
 * STORE fits backups: the payload is already-compressed data (WebP pages inside CBZs; the JSON
 * manifest is small). The reader side (okio `openZip`, see DefaultCbzReader) reads STORE and
 * DEFLATE transparently on all platforms.
 *
 * Usage: construct over an open sink, write entries in order, then [finish] exactly once. The
 * caller owns the sink lifecycle and deletes the partial output file on failure/cancel.
 */
class BackupZipWriter(private val sink: BufferedSink) {

    private class CentralEntry(
        val nameBytes: ByteArray,
        val crc32: Int,
        val size: Long,
        val localHeaderOffset: Long,
    )

    private val entries = mutableListOf<CentralEntry>()
    private var bytesWritten: Long = 0L
    private var finished = false

    /** Append one STORE entry from in-memory bytes (the `backup.json` manifest). */
    fun writeEntryBytes(name: String, data: ByteArray) {
        val crc = Crc32().apply { update(data) }
        beginEntry(name, data.size.toLong(), crc.value) { nameBytes, offset ->
            writeBytes(data)
            CentralEntry(nameBytes, crc.value, data.size.toLong(), offset)
        }
    }

    /**
     * Append one STORE entry by streaming [path] from [fileSystem]. Two passes over the file:
     * pass 1 computes the CRC-32 (the STORE local header carries the CRC before the data), pass 2
     * streams the bytes to the sink. Peak memory is one [CHUNK_SIZE] buffer.
     *
     * @throws IOException if the file's size changes between the passes (torn source — the
     *   archive would be corrupt).
     */
    fun writeEntryFromFile(name: String, fileSystem: FileSystem, path: Path) {
        val size = fileSystem.metadata(path).size
            ?: throw IOException("No size metadata for $path")

        val crc = Crc32()
        val chunk = ByteArray(CHUNK_SIZE)
        var hashed = 0L
        fileSystem.source(path).buffer().use { source ->
            while (true) {
                val read = source.read(chunk)
                if (read == -1) break
                crc.update(chunk, 0, read)
                hashed += read
            }
        }
        if (hashed != size) throw IOException("Source changed while hashing: $path ($hashed != $size)")

        beginEntry(name, size, crc.value) { nameBytes, offset ->
            var streamed = 0L
            fileSystem.source(path).buffer().use { source ->
                while (true) {
                    val read = source.read(chunk)
                    if (read == -1) break
                    sink.write(chunk, 0, read)
                    bytesWritten += read
                    streamed += read
                }
            }
            if (streamed != size) throw IOException("Source changed while streaming: $path ($streamed != $size)")
            CentralEntry(nameBytes, crc.value, size, offset)
        }
    }

    private inline fun beginEntry(
        name: String,
        size: Long,
        crc: Int,
        writeData: (nameBytes: ByteArray, offset: Long) -> CentralEntry,
    ) {
        check(!finished) { "writeEntry called after finish()" }
        if (entries.size >= MAX_ZIP32_ENTRIES) {
            throw ZipLimitExceededException("ZIP32 entry overflow: more than $MAX_ZIP32_ENTRIES entries")
        }
        if (size > MAX_ZIP32_OFFSET || bytesWritten + size > MAX_ZIP32_OFFSET) {
            throw ZipLimitExceededException("ZIP32 offset overflow: archive would exceed 4 GiB")
        }
        val nameBytes = name.encodeToByteArray()
        val offset = bytesWritten

        // ---- Local file header (signature 0x04034b50) ----
        writeIntLe(LOCAL_FILE_HEADER_SIG)
        writeShortLe(VERSION_NEEDED)
        writeShortLe(0)                     // general-purpose bit flag
        writeShortLe(METHOD_STORE)
        writeShortLe(0)                     // last mod file time
        writeShortLe(0)                     // last mod file date
        writeIntLe(crc)
        writeIntLe(size.asZipSize())        // compressed size (== uncompressed for STORE)
        writeIntLe(size.asZipSize())        // uncompressed size
        writeShortLe(nameBytes.size)
        writeShortLe(0)                     // extra field length
        writeBytes(nameBytes)

        entries += writeData(nameBytes, offset)
    }

    /** Emit the central directory + end-of-central-directory record. Call exactly once. */
    fun finish() {
        check(!finished) { "finish() called more than once" }
        finished = true

        val centralDirOffset = bytesWritten
        for (entry in entries) {
            // ---- Central directory file header (signature 0x02014b50) ----
            writeIntLe(CENTRAL_DIR_HEADER_SIG)
            writeShortLe(VERSION_MADE_BY)
            writeShortLe(VERSION_NEEDED)
            writeShortLe(0)                            // general-purpose bit flag
            writeShortLe(METHOD_STORE)
            writeShortLe(0)                            // last mod file time
            writeShortLe(0)                            // last mod file date
            writeIntLe(entry.crc32)
            writeIntLe(entry.size.asZipSize())         // compressed size
            writeIntLe(entry.size.asZipSize())         // uncompressed size
            writeShortLe(entry.nameBytes.size)
            writeShortLe(0)                            // extra field length
            writeShortLe(0)                            // file comment length
            writeShortLe(0)                            // disk number start
            writeShortLe(0)                            // internal file attributes
            writeIntLe(0)                              // external file attributes
            writeIntLe(entry.localHeaderOffset.asZipSize())
            writeBytes(entry.nameBytes)
        }
        val centralDirSize = bytesWritten - centralDirOffset

        // ---- End of central directory record (signature 0x06054b50) ----
        writeIntLe(END_OF_CENTRAL_DIR_SIG)
        writeShortLe(0)                                // number of this disk
        writeShortLe(0)                                // disk with start of central directory
        writeShortLe(entries.size)                     // central-dir entries on this disk
        writeShortLe(entries.size)                     // total central-dir entries
        writeIntLe(centralDirSize.asZipSize())
        writeIntLe(centralDirOffset.asZipSize())
        writeShortLe(0)                                // ZIP file comment length
    }

    // Values up to 0xFFFFFFFF are written as their raw little-endian bit pattern; the ZIP32
    // guards above ensure the Long always fits.
    private fun Long.asZipSize(): Int = (this and 0xFFFFFFFFL).toInt()

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

    companion object {
        const val MAX_ZIP32_OFFSET: Long = 0xFFFFFFFFL
        const val MAX_ZIP32_ENTRIES: Int = 0xFFFF

        private const val LOCAL_FILE_HEADER_SIG: Int = 0x04034b50
        private const val CENTRAL_DIR_HEADER_SIG: Int = 0x02014b50
        private const val END_OF_CENTRAL_DIR_SIG: Int = 0x06054b50
        private const val VERSION_NEEDED: Int = 20
        private const val VERSION_MADE_BY: Int = 20
        private const val METHOD_STORE: Int = 0
        private const val CHUNK_SIZE: Int = 64 * 1024
    }
}
