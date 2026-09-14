package me.manga.kira.core.cbz

import android.graphics.Bitmap
import android.os.Build
import android.system.Os
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Small I/O decorator boundary for host faults; ordinary callers use these real implementations. */
open class CbzArchiveOutput {
    open fun open(temporary: File): OutputStream = FileOutputStream(temporary)

    /**
     * Owned sibling file, same filesystem. No delete/copy fallback if atomic rename fails.
     * A decorator must not add fallible work after the rename's commit point.
     */
    open fun publish(
        temporary: File,
        destination: File,
    ) {
        Os.rename(temporary.absolutePath, destination.absolutePath)
    }
}

/** The central directory alone does not check payload CRC; read every entry with a fixed buffer. */
internal suspend fun validateCbzArchive(
    file: File,
    expectedEntries: Int,
) {
    if (expectedEntries <= 0) throw IOException("Cannot publish an empty CBZ")
    val buffer = ByteArray(CBZ_BUFFER_SIZE)
    ZipFile(file).use { archive ->
        if (archive.size() != expectedEntries) throw IOException("CBZ entry count mismatch")
        val entries = archive.entries()
        var index = 0
        while (entries.hasMoreElements()) {
            currentCoroutineContext().ensureActive()
            validateCbzEntry(archive, entries.nextElement(), index, buffer)
            index++
        }
    }
}

private suspend fun validateCbzEntry(
    archive: ZipFile,
    entry: ZipEntry,
    index: Int,
    buffer: ByteArray,
) {
    if (entry.isDirectory || entry.name != cbzEntryName(index) || entry.size <= 0) {
        throw IOException("Invalid CBZ entry at index $index")
    }
    val crc = CRC32()
    var size = 0L
    archive.getInputStream(entry).use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            crc.update(buffer, 0, count)
            size += count
        }
    }
    if (size != entry.size || crc.value != entry.crc) throw IOException("CBZ payload integrity failure")
}

internal fun cbzWebpFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }

internal fun cbzEntryName(index: Int): String = String.format(Locale.ROOT, "page_%04d.webp", index)

/** No fallible callback/suspension after publication may report a loose-path fallback. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun deleteCbzSourcesAfterCommit(paths: List<String>) {
    try {
        paths.forEach { File(it).deleteCbzOwnedFileQuietly() }
    } catch (_: Throwable) {
        // Even allocation during best-effort cleanup must not turn a committed CBZ into OOM fallback.
    }
}

/** Cleanup must neither roll back a published archive nor mask CE/OOM with a secondary failure. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun File.deleteCbzOwnedFileQuietly() {
    try {
        delete()
    } catch (_: Throwable) {
        // Owned temp, or loose input after commit; never use this for final-archive rollback.
    }
}

internal const val CBZ_BUFFER_SIZE = 64 * 1024
