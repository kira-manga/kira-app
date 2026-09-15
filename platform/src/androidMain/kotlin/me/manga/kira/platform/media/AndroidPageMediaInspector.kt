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
        require(policy.maxSourcePixels <= MAX_NATIVE_SOURCE_PIXELS)
        require(policy.maxSourceDimension <= MAX_NATIVE_SOURCE_DIMENSION)
    }

    override fun inspect(encoded: ByteArray): PageInspection =
        inspectBuffer(
            ByteBuffer.wrap(encoded).asReadOnlyBuffer(),
        )

    override fun inspect(path: Path): PageInspection =
        try {
            val metadata = system.metadata(path)
            if (!metadata.isRegularFile) throw IOException("Page is not a regular file")
            val size = metadata.size ?: throw IOException("Page size is unavailable")
            encodedPageRejection(size, policy) ?: RandomAccessFile(path.toFile(), "r").use { file ->
                // Recheck THIS handle before mapping; the caller keeps its snapshot immutable.
                val length = file.length()
                encodedPageRejection(length, policy)
                    ?: inspectBuffer(file.channel.map(FileChannel.MapMode.READ_ONLY, 0, length))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            PageInspection.ReadFailure(failure)
        }

    private fun inspectBuffer(encoded: ByteBuffer): PageInspection =
        inspectPageInput(
            policy,
            encoded.remaining().toLong(),
            { PageBufferSource(encoded.asReadOnlyBuffer()) },
        ) { format ->
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
        return when {
            byteCount == 0L -> 0L
            !encoded.hasRemaining() -> -1L
            else -> {
                val bytes = ByteArray(minOf(byteCount, encoded.remaining().toLong(), PAGE_READ_BYTES).toInt())
                encoded.get(bytes)
                sink.write(bytes)
                bytes.size.toLong()
            }
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = Unit
}

private const val MAX_NATIVE_SOURCE_PIXELS: Long = 268_435_456
private const val MAX_NATIVE_SOURCE_DIMENSION: Int = 32_768
private const val PAGE_READ_BYTES: Long = 8192
