package me.manga.kira.platform.backup

import okio.Buffer
import okio.Closeable
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.openZip
import okio.use

/**
 * ZIP32 reader admitted BEFORE Okio allocates its index. The caller must keep its archive immutable
 * for this reader's lifetime. Every entry read is actually counted and CRC-checked, independently
 * of the untrusted central-directory sizes. Closing does not delete the caller's archive.
 */
class BoundedBackupZip private constructor(
    private val zip: FileSystem,
    val entries: List<BackupZipEntry>,
) : Closeable {
    private val byName = entries.associateBy { it.name }

    /** Resolves only an exact, unambiguous name admitted from the central directory. */
    fun entry(name: String): BackupZipEntry = byName[name] ?: throw InvalidBackupArchive()

    /** Streams the complete expanded entry, including its CRC check, without owning [sink]. */
    fun copyEntry(
        entry: BackupZipEntry,
        sink: Sink,
        budgets: List<BackupByteBudget>,
        checkpoint: () -> Unit,
    ): Long {
        requireZip(byName[entry.name] == entry)
        budgets.forEach { it.checkDeclared(entry.size) }
        val crc = Crc32()
        val size = zip.source(entry.name.toPath()).use { source ->
            copyCheckedBackupBytes(source, sink, budgets, checkpoint, crc)
        }
        requireZip(size == entry.size && crc.value == entry.crc32)
        return size
    }

    /** Bounded materialization for a manifest or ONE encoded page, never for a complete CBZ. */
    fun readEntry(
        entry: BackupZipEntry,
        budgets: List<BackupByteBudget>,
        checkpoint: () -> Unit,
    ): ByteArray {
        require(budgets.isNotEmpty() && budgets.minOf { it.remaining } <= Int.MAX_VALUE)
        val buffer = Buffer()
        copyEntry(entry, buffer, budgets, checkpoint)
        return buffer.readByteArray()
    }

    override fun close() = zip.close()

    companion object {
        /** Does not enumerate, sort, or index any archive contents before structural admission. */
        fun open(
            system: FileSystem,
            archive: Path,
            limits: BackupZipLimits,
            checkpoint: () -> Unit,
        ): BoundedBackupZip {
            checkpoint()
            val entries = readIndex(system, archive, limits, checkpoint)
            checkpoint()
            return BoundedBackupZip(system.openZip(archive), entries)
        }
    }
}

private fun readIndex(
    system: FileSystem,
    archive: Path,
    limits: BackupZipLimits,
    checkpoint: () -> Unit,
): List<BackupZipEntry> =
    system.openReadOnly(archive).use { handle ->
        val reader = ZipStructureReader(handle, handle.size())
        val directory = readZip32Directory(reader, limits)
        val records = readCentralRecords(reader, directory, limits, checkpoint)
        var previousEnd = 0L
        for (record in records.sortedBy { it.localOffset }) {
            checkpoint()
            // No preamble, hidden local records, overlaps or uninterpreted gaps. A backup has one
            // complete ZIP layout, not a second archive interpretation before its central index.
            requireZip(record.localOffset == previousEnd)
            previousEnd = verifyZip32LocalRecord(reader, record, directory.offset)
        }
        requireZip(previousEnd == directory.offset)
        records.map { it.entry }
    }

private fun readCentralRecords(
    reader: ZipStructureReader,
    directory: Zip32Directory,
    limits: BackupZipLimits,
    checkpoint: () -> Unit,
): List<Zip32Record> {
    val records = ArrayList<Zip32Record>(directory.entryCount)
    val names = Zip32Names(limits)
    var offset = directory.offset
    repeat(directory.entryCount) {
        checkpoint()
        val central = readZip32CentralRecord(reader, offset, directory.offset + directory.size)
        names.add(central.record.entry.name)
        records += central.record
        offset = central.nextOffset
    }
    requireZip(offset == directory.offset + directory.size)
    return records
}
