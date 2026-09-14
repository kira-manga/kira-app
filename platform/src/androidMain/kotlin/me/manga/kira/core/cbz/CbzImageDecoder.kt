package me.manga.kira.core.cbz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aomedia.avif.android.AvifDecoder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Android codec calls only; the manager owns traversal and bitmap lifetimes.
 * Open for transparent cross-module host-test observation/faults, not an alternate scheduler.
 * Modeled region/AVIF results in host tests do not qualify their native Android codecs.
 */
open class CbzImageDecoder {
    private val avifDecoderMutex = Mutex()

    open fun isAvif(file: File): Boolean =
        file.inputStream().use { stream ->
            val header = ByteArray(AVIF_HEADER_SIZE)
            val read = stream.read(header)
            read == header.size &&
                header.copyOfRange(FTYP_OFFSET, BRAND_OFFSET).contentEquals(FTYP) &&
                header.copyOfRange(BRAND_OFFSET, AVIF_HEADER_SIZE).let { it.contentEquals(AVIF) || it.contentEquals(AVIS) }
        }

    open fun bounds(file: File): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
        }

    open fun decode(
        file: File,
        sampleSize: Int,
        config: Bitmap.Config?,
    ): Bitmap? =
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                if (config != null) inPreferredConfig = config
            },
        )

    open fun openRegions(stream: InputStream): CbzRegions = AndroidCbzRegions(stream)

    open fun crop(
        parent: Bitmap,
        region: Rect,
    ): Bitmap = Bitmap.createBitmap(parent, region.left, region.top, region.width(), region.height())

    /** One probe and one full decode; preserves the existing dimension/config policy and JNI mutex. */
    open suspend fun decodeAvif(file: File): Bitmap =
        avifDecoderMutex.withLock {
            currentCoroutineContext().ensureActive()
            val buffer = readAvifBuffer(file)
            val info = readAvifInfo(buffer)
            decodeAvifBitmap(buffer, info)
        }
}

/** One native decoder; callers close this before closing the supplied stream. */
interface CbzRegions : AutoCloseable {
    fun decode(region: Rect): Bitmap?
}

private class AndroidCbzRegions(
    stream: InputStream,
) : CbzRegions {
    private val decoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(stream)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(stream, false)
        } ?: throw IOException("Could not open region decoder")

    override fun decode(region: Rect): Bitmap? = decoder.decodeRegion(region, BitmapFactory.Options())

    override fun close() = decoder.recycle()
}

private fun AvifDecoder.Info.validDimensions(): Boolean =
    width in 1..MAX_AVIF_DIMENSION && height in 1..MAX_AVIF_DIMENSION

private fun readAvifBuffer(file: File): ByteBuffer {
    val bytes = file.readBytes()
    if (bytes.size < AVIF_HEADER_SIZE) throw IOException("AVIF input is too short")
    return ByteBuffer.allocateDirect(bytes.size).apply {
        put(bytes)
        rewind()
    }
}

private suspend fun readAvifInfo(buffer: ByteBuffer): AvifDecoder.Info {
    val info = AvifDecoder.Info()
    currentCoroutineContext().ensureActive()
    if (!AvifDecoder.getInfo(buffer, buffer.capacity(), info) || !info.validDimensions()) {
        throw IOException("Invalid AVIF dimensions or header")
    }
    currentCoroutineContext().ensureActive()
    return info
}

@Suppress("TooGenericExceptionCaught") // Cleanup and rethrow, including CE/OOM; no null fallback.
private suspend fun decodeAvifBitmap(
    buffer: ByteBuffer,
    info: AvifDecoder.Info,
): Bitmap {
    val bitmap =
        createBitmap(
            info.width,
            info.height,
            if (info.alphaPresent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565,
        )
    // A failed native call still owns its allocated destination. Never translate CE/OOM.
    try {
        buffer.rewind()
        if (!AvifDecoder.decode(buffer, buffer.capacity(), bitmap, 0)) {
            throw IOException("AVIF decode failed")
        }
        currentCoroutineContext().ensureActive()
        return bitmap
    } catch (failure: Throwable) {
        bitmap.recycleAfterFailure(failure)
        throw failure
    }
}

internal fun cbzSampleSize(
    width: Int,
    height: Int,
    threshold: Int,
): Int {
    var size = 1
    while (width / size > threshold || height / size > threshold) {
        size *= 2
    }
    return size
}

/** Like use(), but Bitmap is not Closeable. Preserve a primary codec/cancellation failure. */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> Bitmap.useForCbz(block: (Bitmap) -> T): T {
    var primary: Throwable? = null
    try {
        return block(this)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        val failure = primary
        if (failure == null) recycle() else recycleAfterFailure(failure)
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun Bitmap.recycleAfterFailure(failure: Throwable) {
    try {
        recycle()
    } catch (cleanup: Throwable) {
        failure.addSuppressed(cleanup)
    }
}

private const val AVIF_HEADER_SIZE = 12
private const val FTYP_OFFSET = 4
private const val BRAND_OFFSET = 8
private const val MAX_AVIF_DIMENSION = 8192
private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
private val AVIF = "avif".toByteArray(Charsets.US_ASCII)
private val AVIS = "avis".toByteArray(Charsets.US_ASCII)
