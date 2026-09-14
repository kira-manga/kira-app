package me.manga.kira.platform.cbz

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.core.cbz.CBZ_BUFFER_SIZE
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Both Android writers verify exact entry names/counts and real payload CRCs, including verbatim pages. */
internal suspend fun validateAndroidCbzArchive(
    file: File,
    expectedNames: List<String>,
) {
    if (expectedNames.isEmpty()) throw IOException("Cannot publish an empty CBZ")
    val buffer = ByteArray(CBZ_BUFFER_SIZE)
    ZipFile(file).use { archive ->
        if (archive.size() != expectedNames.size) throw IOException("CBZ entry count mismatch")
        val entries = archive.entries()
        expectedNames.forEach { expected ->
            currentCoroutineContext().ensureActive()
            val entry = entries.nextElement()
            if (entry.isDirectory || entry.name != expected || entry.size <= 0) throw IOException("Invalid CBZ entry")
            val checksum = CRC32()
            var size = 0L
            archive.getInputStream(entry).use { source ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    checksum.update(buffer, 0, read)
                    size += read
                }
            }
            if (size != entry.size || checksum.value != entry.crc) throw IOException("CBZ payload integrity failure")
        }
    }
}
