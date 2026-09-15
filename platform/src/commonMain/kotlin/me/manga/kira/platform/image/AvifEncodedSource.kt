package me.manga.kira.platform.image

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.BufferedSource
import okio.use

/**
 * Own and close the claimed AVIF source, including on cancellation or a read failure. Consume at
 * most [maxBytes] + 1 bytes, retaining at most [maxBytes] in the staging buffer. The caller's Okio
 * buffered source may already have prefetched one segment; this cannot undo upstream buffering.
 */
internal suspend fun readAvifBytes(
    source: BufferedSource,
    maxBytes: Int,
): ByteArray =
    source.use {
        require(maxBytes > 0)
        val context = currentCoroutineContext()
        val buffer = Buffer()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            context.ensureActive()
            val remaining = maxBytes.toLong() - buffer.size
            val byteCount = minOf(chunk.size.toLong(), remaining + 1).toInt()
            val read = source.read(chunk, 0, byteCount)
            context.ensureActive()
            if (read == -1) break
            if (read > remaining) throw AvifDecodeException("AVIF encoded input exceeds the decode limit.")
            buffer.write(chunk, 0, read)
        }
        if (buffer.size == 0L) throw AvifDecodeException("AVIF encoded input is empty.")
        buffer.readByteArray()
    }

private const val READ_CHUNK_BYTES = 8192
