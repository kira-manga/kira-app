package me.manga.kira.core.cbz

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.image.ANDROID_AVIF_NATIVE_MAX_DIMENSION
import me.manga.kira.platform.image.AvifDecodeException
import me.manga.kira.platform.image.AvifDecodeLimits
import me.manga.kira.platform.image.AvifPixelSize
import me.manga.kira.platform.image.androidAvifNativePixelLimit
import me.manga.kira.platform.image.readAvifBytes
import okio.buffer
import okio.source
import org.aomedia.avif.android.AvifDecoder
import java.io.File
import java.nio.ByteBuffer

/** Pre-decode budget decision only; callers must already hold a validated immutable source. */
internal fun cbzAvifOutputAdmitted(width: Int, height: Int, encodedBytes: Int, maxWorkingBytes: Long): Boolean =
    try {
        val limits = cbzAvifLimits(maxWorkingBytes)
        val size = AvifPixelSize(width, height)
        limits.checkSource(size)
        size.pixels <= androidAvifNativePixelLimit(limits, encodedBytes, size)
    } catch (_: AvifDecodeException) {
        false
    }

/** Called under the shared Android native permit. No native false/OOM is a preservation signal. */
internal suspend fun decodeCbzAvif(file: File): Bitmap {
    val limits = cbzAvifLimits(CbzWriter.DEFAULT_MAX_MEMORY_BYTES)
    val bytes = file.source().buffer().use { readAvifBytes(it, limits.maxEncodedBytes) }
    val parsePixelLimit = androidAvifNativePixelLimit(limits, bytes.size)
    currentCoroutineContext().ensureActive()
    val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
        put(bytes)
        rewind()
    }
    val info = boundedCbzAvifInfo(buffer, limits, parsePixelLimit)
    val size = AvifPixelSize(info.width, info.height)
    limits.checkSource(size)
    val bytesPerPixel = if (info.alphaPresent) RGBA_BYTES else RGB_565_BYTES
    val pixelLimit = androidAvifNativePixelLimit(limits, bytes.size, size, bytesPerPixel)
    if (size.pixels > pixelLimit) throw AvifDecodeException("CBZ AVIF source exceeds its output-reserved allowance.")
    return decodeCbzAvifBitmap(buffer, info, limits, pixelLimit)
}

private fun cbzAvifLimits(maxWorkingBytes: Long): AvifDecodeLimits =
    AvifDecodeLimits(maxWorkingBytes = minOf(maxWorkingBytes, CbzWriter.DEFAULT_MAX_MEMORY_BYTES))

private suspend fun boundedCbzAvifInfo(
    buffer: ByteBuffer,
    limits: AvifDecodeLimits,
    pixelLimit: Int,
): AvifDecoder.Info {
    val info = AvifDecoder.Info()
    val accepted = AvifDecoder.getInfoWithLimits(
        buffer, buffer.capacity(), info, pixelLimit,
        minOf(limits.maxSourceDimension, ANDROID_AVIF_NATIVE_MAX_DIMENSION),
    )
    currentCoroutineContext().ensureActive()
    if (!accepted) throw AvifDecodeException("CBZ AVIF metadata was rejected by the bounded native decoder.")
    return info
}

@Suppress("TooGenericExceptionCaught") // Recycle only our destination; preserve CE/OOM/native failure.
private suspend fun decodeCbzAvifBitmap(
    buffer: ByteBuffer,
    info: AvifDecoder.Info,
    limits: AvifDecodeLimits,
    pixelLimit: Int,
): Bitmap {
    currentCoroutineContext().ensureActive()
    val bitmap = createBitmap(
        info.width, info.height,
        if (info.alphaPresent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565,
    )
    try {
        if (bitmap.allocationByteCount.toLong() > limits.maxOutputBytes) {
            throw AvifDecodeException("CBZ AVIF bitmap allocation exceeds its output allowance.")
        }
        buffer.rewind()
        val accepted = AvifDecoder.decodeWithLimits(
            buffer, buffer.capacity(), bitmap, NATIVE_THREADS, pixelLimit,
            minOf(limits.maxSourceDimension, ANDROID_AVIF_NATIVE_MAX_DIMENSION),
        )
        currentCoroutineContext().ensureActive()
        if (!accepted) throw AvifDecodeException("CBZ AVIF pixels were rejected by the bounded native decoder.")
        return bitmap
    } catch (failure: Throwable) {
        bitmap.recycleAfterFailure(failure)
        throw failure
    }
}

private const val RGBA_BYTES = 4
private const val RGB_565_BYTES = 2
private const val NATIVE_THREADS = 1
