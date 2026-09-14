package me.manga.kira.platform.media

import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Source
import okio.use

/** Takes a bounded encoded snapshot for writers that must validate and emit the very same bytes. */
fun readPageSnapshot(
    system: FileSystem,
    path: Path,
    policy: PageBytePolicy = PageBytePolicy(),
): ByteArray {
    val metadata = system.metadata(path)
    if (!metadata.isRegularFile) throw IOException("Downloaded page is not a regular file")
    policy.checkFileSize(metadata.size)
    return readPageSnapshot(system.source(path), policy)
}

/** Owns/closes [source]. Reads no more than limit+1 bytes and checks before making the final array. */
fun readPageSnapshot(source: Source, policy: PageBytePolicy = PageBytePolicy()): ByteArray = source.use {
    val buffer = Buffer()
    var count = 0L
    while (true) {
        val requested = minOf(SNAPSHOT_CHUNK_BYTES, policy.maxEncodedBytes - count + 1)
        val read = it.read(buffer, requested)
        if (read == -1L) break
        if (read == 0L) throw IOException("Page source made no read progress")
        count = policy.checkedTotal(count, read.toInt())
    }
    policy.checkFileSize(count)
    buffer.readByteArray()
}

private const val SNAPSHOT_CHUNK_BYTES: Long = 8192
