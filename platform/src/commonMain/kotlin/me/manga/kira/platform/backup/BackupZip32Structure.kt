package me.manga.kira.platform.backup

import okio.FileHandle
import okio.IOException

internal class ZipStructureReader(
    private val handle: FileHandle,
    val size: Long,
) {
    fun bytes(
        offset: Long,
        count: Int,
    ): ByteArray {
        requireZip(offset >= 0 && count >= 0 && offset <= size && count.toLong() <= size - offset)
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val next = handle.read(offset + read, bytes, read, count - read)
            if (next == -1) throw InvalidBackupArchive()
            if (next <= 0) throw IOException("Backup metadata source made no progress")
            read += next
        }
        return bytes
    }
}

internal data class Zip32Directory(
    val offset: Long,
    val size: Long,
    val entryCount: Int,
)

internal fun readZip32Directory(
    reader: ZipStructureReader,
    limits: BackupZipLimits,
): Zip32Directory {
    if (reader.size > limits.maxArchiveBytes) throw BackupImportLimitExceeded()
    requireZip(reader.size >= ZIP_END_BYTES)
    val tailSize = minOf(reader.size, ZIP_END_BYTES + ZIP_MAX_COMMENT_BYTES).toInt()
    val tailOffset = reader.size - tailSize
    val tail = reader.bytes(tailOffset, tailSize)
    val end = findZipEnd(tail)
    val endOffset = tailOffset + end
    if (endOffset >= ZIP64_LOCATOR_BYTES) {
        // Okio probes here even when these bytes belong to an admitted central-entry comment.
        // Do not let its second parser redirect to a different, unbounded ZIP64 directory.
        requireZip(reader.bytes(endOffset - ZIP64_LOCATOR_BYTES, ZIP64_LOCATOR_SIGNATURE_BYTES).u32(0) != ZIP64_LOCATOR_SIGNATURE)
    }
    val count = tail.u16(end + ZIP_END_ENTRIES_OFFSET)
    val size = tail.u32(end + ZIP_END_DIRECTORY_SIZE_OFFSET)
    val offset = tail.u32(end + ZIP_END_DIRECTORY_OFFSET_OFFSET)
    requireZip(tail.u16(end + ZIP_END_DISK_OFFSET) == 0 && tail.u16(end + ZIP_END_DIRECTORY_DISK_OFFSET) == 0)
    requireZip(tail.u16(end + ZIP_END_DISK_ENTRIES_OFFSET) == count && count != ZIP_UINT16_MAX)
    requireZip(size != ZIP_UINT32_MAX && offset != ZIP_UINT32_MAX)
    if (count > limits.maxEntries || size > limits.maxDirectoryBytes) throw BackupImportLimitExceeded()
    requireZip(offset <= endOffset && size == endOffset - offset)
    return Zip32Directory(offset, size, count)
}

private fun findZipEnd(tail: ByteArray): Int {
    for (offset in tail.size - ZIP_END_BYTES.toInt() downTo 0) {
        if (tail.u32(offset) == ZIP_END_SIGNATURE) {
            // Match Okio's last-signature choice: a malformed later candidate is not ignorable.
            requireZip(offset + ZIP_END_BYTES + tail.u16(offset + ZIP_END_COMMENT_OFFSET) == tail.size.toLong())
            return offset
        }
    }
    throw InvalidBackupArchive()
}

internal fun validateZipExtra(bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        requireZip(bytes.size - offset >= ZIP_EXTRA_HEADER_BYTES)
        val type = bytes.u16(offset)
        val length = bytes.u16(offset + 2)
        requireZip(type != ZIP64_EXTRA && type != ZIP_AES_EXTRA && type != ZIP_ENCRYPTION_EXTRA)
        // Names must have exactly one UTF-8 interpretation, with no alternate path override.
        requireZip(type != ZIP_UNICODE_PATH_EXTRA && type != ZIP_LANGUAGE_ENCODING_EXTRA)
        offset += ZIP_EXTRA_HEADER_BYTES
        requireZip(length <= bytes.size - offset)
        offset += length
    }
}

internal fun requireZip(valid: Boolean) {
    if (!valid) throw InvalidBackupArchive()
}

internal fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u32(offset: Int): Long =
    (u16(offset).toLong() or (u16(offset + 2).toLong() shl 16))

internal const val ZIP_UINT32_MAX = 0xFFFFFFFFL
internal const val ZIP_UINT16_MAX = 0xFFFF
private const val ZIP_END_BYTES = 22L
private const val ZIP_MAX_COMMENT_BYTES = 65_535L
private const val ZIP_END_SIGNATURE = 0x06054B50L
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50L
private const val ZIP64_LOCATOR_BYTES = 20L
private const val ZIP64_LOCATOR_SIGNATURE_BYTES = 4
private const val ZIP_END_DISK_OFFSET = 4
private const val ZIP_END_DIRECTORY_DISK_OFFSET = 6
private const val ZIP_END_DISK_ENTRIES_OFFSET = 8
private const val ZIP_END_ENTRIES_OFFSET = 10
private const val ZIP_END_DIRECTORY_SIZE_OFFSET = 12
private const val ZIP_END_DIRECTORY_OFFSET_OFFSET = 16
private const val ZIP_END_COMMENT_OFFSET = 20
private const val ZIP_EXTRA_HEADER_BYTES = 4
private const val ZIP64_EXTRA = 0x0001
private const val ZIP_AES_EXTRA = 0x9901
private const val ZIP_ENCRYPTION_EXTRA = 0x0017
private const val ZIP_UNICODE_PATH_EXTRA = 0x7075
private const val ZIP_LANGUAGE_ENCODING_EXTRA = 0x0008
