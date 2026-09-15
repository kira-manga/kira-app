package me.manga.kira.platform.cbz

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.platform.backup.Crc32
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/** Verifies the complete staged ZIP before the writer can publish it. */
internal object IosCbzArchiveVerifier {
    /** Reopen and read the staged payloads; a readable directory alone cannot prove a complete ZIP. */
    suspend fun validate(
        system: FileSystem,
        temporary: Path,
        expected: List<ArchivedCbzEntry>,
    ) {
        system.openZip(temporary).use { zip ->
            val root = "/".toPath()
            val names = zip.list(root).map { it.name }.toSet()
            if (names != expected.map { it.name }.toSet()) throw IOException("CBZ entry count mismatch")
            val buffer = ByteArray(VALIDATION_BUFFER_SIZE)
            expected.forEach { entry ->
                currentCoroutineContext().ensureActive()
                validateEntry(zip, root / entry.name, entry, buffer)
            }
        }
    }

    private suspend fun validateEntry(
        zip: FileSystem,
        path: Path,
        entry: ArchivedCbzEntry,
        buffer: ByteArray,
    ) {
        val checksum = Crc32()
        var size = 0L
        zip.source(path).buffer().use { source ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read == -1) break
                size += read
                if (size > entry.size) throw IOException("CBZ entry size mismatch")
                checksum.update(buffer, length = read)
            }
        }
        if (size != entry.size || checksum.value != entry.checksum) throw IOException("CBZ entry payload mismatch")
    }

    private const val VALIDATION_BUFFER_SIZE = 8192
}

internal data class ArchivedCbzEntry(
    val name: String,
    val size: Long,
    val checksum: Int,
)
