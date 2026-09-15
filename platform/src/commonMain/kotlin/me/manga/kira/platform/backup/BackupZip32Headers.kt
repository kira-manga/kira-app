package me.manga.kira.platform.backup

/** Vetted ZIP32 entry metadata. Payload validation still requires reading every byte and its CRC. */
data class BackupZipEntry internal constructor(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val crc32: Int,
)

internal data class Zip32Record(
    val entry: BackupZipEntry,
    val localOffset: Long,
    val compressedSize: Long,
    val flags: Int,
    val method: Int,
)

internal data class Zip32CentralRecord(
    val record: Zip32Record,
    val nextOffset: Long,
)

internal fun readZip32CentralRecord(
    reader: ZipStructureReader,
    offset: Long,
    directoryEnd: Long,
): Zip32CentralRecord {
    requireZip(offset <= directoryEnd && ZIP_CENTRAL_BYTES <= directoryEnd - offset)
    val header = reader.bytes(offset, ZIP_CENTRAL_BYTES)
    requireZip(header.u32(0) == ZIP_CENTRAL_SIGNATURE)
    val nameLength = header.u16(CENTRAL_NAME_LENGTH)
    val extraLength = header.u16(CENTRAL_EXTRA_LENGTH)
    val length = ZIP_CENTRAL_BYTES + nameLength + extraLength + header.u16(CENTRAL_COMMENT_LENGTH)
    requireZip(length.toLong() <= directoryEnd - offset)
    if (nameLength > MAX_ZIP_NAME_BYTES) throw BackupImportLimitExceeded()
    val name = decodeZipName(reader.bytes(offset + ZIP_CENTRAL_BYTES, nameLength))
    validateZipExtra(reader.bytes(offset + ZIP_CENTRAL_BYTES + nameLength, extraLength))
    validateCentralHeader(header, name.endsWith('/'))
    val entry = BackupZipEntry(name, name.endsWith('/'), header.u32(CENTRAL_SIZE), header.u32(CENTRAL_CRC).toInt())
    val record =
        Zip32Record(
            entry,
            header.u32(CENTRAL_LOCAL_OFFSET),
            header.u32(CENTRAL_PACKED_SIZE),
            header.u16(CENTRAL_FLAGS),
            header.u16(CENTRAL_METHOD),
        )
    return Zip32CentralRecord(record, offset + length)
}

private fun validateCentralHeader(header: ByteArray, directory: Boolean) {
    val method = header.u16(CENTRAL_METHOD)
    validateZipMethodFlags(method, header.u16(CENTRAL_FLAGS))
    requireZip(header.u16(CENTRAL_VERSION) <= ZIP32_VERSION && header.u16(CENTRAL_DISK) == 0)
    requireZip(header.u32(CENTRAL_SIZE) != ZIP_UINT32_MAX && header.u32(CENTRAL_PACKED_SIZE) != ZIP_UINT32_MAX)
    requireZip(header.u32(CENTRAL_LOCAL_OFFSET) != ZIP_UINT32_MAX)
    if (method == ZIP_STORED) requireZip(header.u32(CENTRAL_SIZE) == header.u32(CENTRAL_PACKED_SIZE))
    val kind = (header.u32(CENTRAL_EXTERNAL_ATTRIBUTES) ushr UNIX_MODE_SHIFT).toInt() and UNIX_KIND_MASK
    requireZip(kind == 0 || kind == (if (directory) UNIX_DIRECTORY else UNIX_FILE))
    requireZip(header.u32(CENTRAL_EXTERNAL_ATTRIBUTES) and DOS_DIRECTORY_FLAG == 0L || directory)
    if (directory) requireZip(header.u32(CENTRAL_SIZE) == 0L && header.u32(CENTRAL_CRC) == 0L)
}

/** Returns the end of this local record, including any ZIP32 data descriptor. */
internal fun verifyZip32LocalRecord(
    reader: ZipStructureReader,
    record: Zip32Record,
    directoryOffset: Long,
): Long {
    val offset = record.localOffset
    requireZip(offset <= directoryOffset && ZIP_LOCAL_BYTES <= directoryOffset - offset)
    val local = reader.bytes(offset, ZIP_LOCAL_BYTES)
    requireZip(local.u32(0) == ZIP_LOCAL_SIGNATURE && local.u16(LOCAL_VERSION) <= ZIP32_VERSION)
    requireZip(local.u16(LOCAL_FLAGS) == record.flags && local.u16(LOCAL_METHOD) == record.method)
    val nameLength = local.u16(LOCAL_NAME_LENGTH)
    val extraLength = local.u16(LOCAL_EXTRA_LENGTH)
    val dataOffset = offset + ZIP_LOCAL_BYTES + nameLength + extraLength
    requireZip(dataOffset <= directoryOffset && record.compressedSize <= directoryOffset - dataOffset)
    if (nameLength > MAX_ZIP_NAME_BYTES) throw BackupImportLimitExceeded()
    requireZip(decodeZipName(reader.bytes(offset + ZIP_LOCAL_BYTES, nameLength)) == record.entry.name)
    validateZipExtra(reader.bytes(offset + ZIP_LOCAL_BYTES + nameLength, extraLength))
    verifyLocalSizes(local, record)
    val dataEnd = dataOffset + record.compressedSize
    return if (record.flags and ZIP_DESCRIPTOR_FLAG == 0) dataEnd else verifyDescriptor(reader, record, dataEnd, directoryOffset)
}

private fun verifyLocalSizes(local: ByteArray, record: Zip32Record) {
    val descriptor = record.flags and ZIP_DESCRIPTOR_FLAG != 0
    fun matches(value: Long, expected: Long) = value == expected || (descriptor && value == 0L)
    requireZip(matches(local.u32(LOCAL_CRC), record.entry.crc32.toLong() and ZIP_UINT32_MAX))
    requireZip(matches(local.u32(LOCAL_PACKED_SIZE), record.compressedSize))
    requireZip(matches(local.u32(LOCAL_SIZE), record.entry.size))
}

private fun verifyDescriptor(
    reader: ZipStructureReader,
    record: Zip32Record,
    offset: Long,
    directoryOffset: Long,
): Long {
    requireZip(offset <= directoryOffset && ZIP_DESCRIPTOR_BYTES <= directoryOffset - offset)
    val unsigned = reader.bytes(offset, ZIP_DESCRIPTOR_BYTES)
    if (descriptorMatches(unsigned, record)) return offset + ZIP_DESCRIPTOR_BYTES
    requireZip(unsigned.u32(0) == ZIP_DESCRIPTOR_SIGNATURE)
    requireZip(ZIP_SIGNED_DESCRIPTOR_BYTES <= directoryOffset - offset)
    requireZip(descriptorMatches(reader.bytes(offset + ZIP_SIGNATURE_BYTES, ZIP_DESCRIPTOR_BYTES), record))
    return offset + ZIP_SIGNED_DESCRIPTOR_BYTES
}

private fun descriptorMatches(bytes: ByteArray, record: Zip32Record): Boolean =
    bytes.u32(0).toInt() == record.entry.crc32 &&
        bytes.u32(ZIP_SIGNATURE_BYTES) == record.compressedSize &&
        bytes.u32(2 * ZIP_SIGNATURE_BYTES) == record.entry.size

private fun validateZipMethodFlags(method: Int, flags: Int) {
    requireZip(method == ZIP_STORED || method == ZIP_DEFLATE)
    val allowed = ZIP_DESCRIPTOR_FLAG or ZIP_UTF8_FLAG or (if (method == ZIP_DEFLATE) ZIP_DEFLATE_FLAGS else 0)
    requireZip(flags and allowed.inv() == 0)
}

private const val ZIP_CENTRAL_BYTES = 46
private const val ZIP_LOCAL_BYTES = 30
private const val ZIP_DESCRIPTOR_BYTES = 12
private const val ZIP_SIGNED_DESCRIPTOR_BYTES = 16
private const val ZIP_SIGNATURE_BYTES = 4
private const val MAX_ZIP_NAME_BYTES = 16 * 1024
private const val ZIP_CENTRAL_SIGNATURE = 0x02014B50L
private const val ZIP_LOCAL_SIGNATURE = 0x04034B50L
private const val ZIP_DESCRIPTOR_SIGNATURE = 0x08074B50L
private const val ZIP32_VERSION = 20
private const val ZIP_STORED = 0
private const val ZIP_DEFLATE = 8
private const val ZIP_DESCRIPTOR_FLAG = 0x0008
private const val ZIP_UTF8_FLAG = 0x0800
private const val ZIP_DEFLATE_FLAGS = 0x0006
private const val UNIX_MODE_SHIFT = 16
private const val UNIX_KIND_MASK = 0xF000
private const val UNIX_FILE = 0x8000
private const val UNIX_DIRECTORY = 0x4000
private const val DOS_DIRECTORY_FLAG = 0x10L
private const val CENTRAL_VERSION = 6
private const val CENTRAL_FLAGS = 8
private const val CENTRAL_METHOD = 10
private const val CENTRAL_CRC = 16
private const val CENTRAL_PACKED_SIZE = 20
private const val CENTRAL_SIZE = 24
private const val CENTRAL_NAME_LENGTH = 28
private const val CENTRAL_EXTRA_LENGTH = 30
private const val CENTRAL_COMMENT_LENGTH = 32
private const val CENTRAL_DISK = 34
private const val CENTRAL_EXTERNAL_ATTRIBUTES = 38
private const val CENTRAL_LOCAL_OFFSET = 42
private const val LOCAL_VERSION = 4
private const val LOCAL_FLAGS = 6
private const val LOCAL_METHOD = 8
private const val LOCAL_CRC = 14
private const val LOCAL_PACKED_SIZE = 18
private const val LOCAL_SIZE = 22
private const val LOCAL_NAME_LENGTH = 26
private const val LOCAL_EXTRA_LENGTH = 28
