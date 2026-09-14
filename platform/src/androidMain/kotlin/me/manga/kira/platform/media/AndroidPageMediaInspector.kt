package me.manga.kira.platform.media

import android.os.Build
import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Source
import okio.Timeout
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Frames and samples one immutable snapshot; file input is mapped, never read into a whole array. */
class AndroidPageMediaInspector(
    private val policy: PageInspectionPolicy = PageInspectionPolicy(),
    private val system: FileSystem = FileSystem.SYSTEM,
) : PageMediaInspector {
    init {
        require(policy.maxSourcePixels <= 268_435_456)
        require(policy.maxSourceDimension <= 32_768)
    }

    override fun inspect(encoded: ByteArray): PageInspection = inspectBuffer(ByteBuffer.wrap(encoded).asReadOnlyBuffer())

    override fun inspect(path: Path): PageInspection =
        try {
            val metadata = system.metadata(path)
            if (!metadata.isRegularFile) throw IOException("Page is not a regular file")
            val size = metadata.size ?: throw IOException("Page size is unavailable")
            encodedPageRejection(size, policy) ?: RandomAccessFile(path.toFile(), "r").use { file ->
                // Recheck THIS handle before mapping; the caller keeps its snapshot immutable.
                val length = file.length()
                encodedPageRejection(length, policy) ?: inspectBuffer(file.channel.map(FileChannel.MapMode.READ_ONLY, 0, length))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            PageInspection.ReadFailure(failure)
        }

    private fun inspectBuffer(encoded: ByteBuffer): PageInspection =
        inspectPageInput(policy, encoded.remaining().toLong(), { PageBufferSource(encoded.asReadOnlyBuffer()) }) { format ->
            if (format == PageImageFormat.AVIF) {
                // AVIF shares the decoder/CBZ owner's fair native permit; no second AVIF lock.
                inspectAndroidAvifPage(encoded, policy)
            } else {
                synchronized(PAGE_PROBE_LOCK) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        inspectAndroidImageDecoderPage(encoded, format, policy)
                    } else {
                        inspectAndroidBitmapFactoryPage(encoded, format, policy)
                    }
                }
            }
        }
}

private val PAGE_PROBE_LOCK = Any()

private class PageBufferSource(
    private val encoded: ByteBuffer,
) : Source {
    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (!encoded.hasRemaining()) return -1
        val bytes = ByteArray(minOf(byteCount, encoded.remaining().toLong(), 8192L).toInt())
        encoded.get(bytes)
        sink.write(bytes)
        return bytes.size.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = Unit
}
