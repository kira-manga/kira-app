package me.manga.kira.core.cbz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.media.AndroidPageMediaInspector
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.requireValid
import okio.Path.Companion.toPath
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Inspection and transcode limits are separate: rejected/unreadable input is never preservation. */
class CbzPagePolicy(
    internal val inspector: PageMediaInspector = AndroidPageMediaInspector(),
    internal val bytePolicy: PageBytePolicy = PageBytePolicy(),
    internal val maxMemoryBytes: Long = CbzWriter.DEFAULT_MAX_MEMORY_BYTES,
) {
    init {
        require(maxMemoryBytes > 0)
    }
}

/** The same bounded, privately owned file is inspected and later encoded or preserved. */
internal suspend fun <T> withValidatedCbzSnapshot(
    source: File,
    policy: PageBytePolicy,
    inspector: PageMediaInspector,
    consume: suspend (File, PageImageMetadata, Long) -> T,
): T {
    if (!source.isFile) throw IOException("Missing CBZ source")
    policy.checkFileSize(source.length())
    val snapshot = File.createTempFile(".cbz-page-", ".snapshot", source.parentFile)
    try {
        val bytes = withContext(Dispatchers.IO) {
            snapshot.outputStream().use { copyCbzPage(source, it, policy) }
        }
        // Waiting for the inspector's shared native permit remains requester-cancellable.
        val metadata = runInterruptible(Dispatchers.IO) {
            inspector.inspect(snapshot.absolutePath.toPath()).requireValid()
        }
        currentCoroutineContext().ensureActive()
        return consume(snapshot, metadata, bytes)
    } finally {
        snapshot.deleteCbzOwnedFileQuietly()
    }
}

/** Does not own/close the destination ZIP; cancellation is checked between bounded writes. */
internal suspend fun copyCbzPage(source: File, destination: OutputStream, policy: PageBytePolicy): Long {
    val buffer = ByteArray(CBZ_BUFFER_SIZE)
    var total = 0L
    source.inputStream().use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total = policy.checkedTotal(total, count)
            destination.write(buffer, 0, count)
        }
    }
    policy.checkFileSize(total)
    return total
}
