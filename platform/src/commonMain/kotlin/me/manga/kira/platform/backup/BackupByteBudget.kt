package me.manga.kira.platform.backup

import okio.Buffer
import okio.IOException
import okio.Sink
import okio.Source

/** One operation's checked byte counter. Share it across entries for aggregate admission. */
class BackupByteBudget(
    val limit: Long,
) {
    init {
        require(limit >= 0)
    }

    var used: Long = 0
        private set

    val remaining: Long get() = limit - used

    /** Unknown/negative metadata is not admission; a known over-limit size rejects before I/O. */
    fun checkDeclared(size: Long?) {
        if (size != null && size >= 0 && size > remaining) throw BackupImportLimitExceeded()
    }

    /** Charges actual bytes using subtraction, so even a Long-sized budget cannot overflow. */
    fun consume(count: Long) {
        if (count < 0 || count > remaining) throw BackupImportLimitExceeded()
        used += count // Addition is safe because count <= limit - used.
    }
}

/**
 * Copies actual bytes, checking every budget BEFORE the sink receives a chunk. Metadata is only an
 * early hint. Reads at most the tightest remaining allowance plus one byte; never loops on zero.
 * The caller owns/closes both streams. [checkpoint] is also used by coordinated native callbacks.
 */
fun copyBackupBytes(
    source: Source,
    sink: Sink,
    budgets: List<BackupByteBudget>,
    checkpoint: () -> Unit,
): Long = copyCheckedBackupBytes(source, sink, budgets, checkpoint, null)

internal fun copyCheckedBackupBytes(
    source: Source,
    sink: Sink,
    budgets: List<BackupByteBudget>,
    checkpoint: () -> Unit,
    crc: Crc32?,
): Long {
    require(budgets.isNotEmpty())
    val buffer = Buffer()
    val bytes = ByteArray(BACKUP_COPY_CHUNK_BYTES)
    var total = 0L
    while (true) {
        checkpoint()
        val remaining = budgets.minOf { it.remaining }
        val requested = minOf(BACKUP_COPY_CHUNK_BYTES.toLong(), remaining.coerceAtMost(Long.MAX_VALUE - 1) + 1)
        val count = source.read(buffer, requested)
        if (count == -1L) break
        if (count <= 0 || count > requested) throw IOException("Backup source made invalid read progress")
        // Do not partially charge earlier budgets when a later aggregate rejects this chunk.
        budgets.forEach { it.checkDeclared(count) }
        budgets.forEach { it.consume(count) }
        writeBackupChunk(buffer, bytes, count.toInt(), sink, crc)
        total += count
    }
    checkpoint()
    return total
}

private fun writeBackupChunk(
    buffer: Buffer,
    bytes: ByteArray,
    count: Int,
    sink: Sink,
    crc: Crc32?,
) {
    var offset = 0
    while (offset < count) {
        val read = buffer.read(bytes, offset, count - offset)
        check(read > 0)
        offset += read
    }
    crc?.update(bytes, length = count)
    buffer.write(bytes, 0, count)
    sink.write(buffer, count.toLong())
}

private const val BACKUP_COPY_CHUNK_BYTES = 8192
