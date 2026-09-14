package me.manga.kira.core.cbz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.image.withAndroidAvifPermit
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Android codec calls only; the manager owns traversal and bitmap lifetimes.
 * Open for transparent cross-module host-test observation/faults, not an alternate scheduler.
 * Modeled region/AVIF results in host tests do not qualify their native Android codecs.
 */
open class CbzImageDecoder {
    open fun isAvif(file: File): Boolean =
        file.inputStream().use { stream ->
            val header = ByteArray(AVIF_HEADER_SIZE)
            val read = stream.read(header)
            read == header.size &&
                header.copyOfRange(FTYP_OFFSET, BRAND_OFFSET).contentEquals(FTYP) &&
                header.copyOfRange(BRAND_OFFSET, AVIF_HEADER_SIZE).let {
                    it.contentEquals(AVIF) || it.contentEquals(AVIS)
                }
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

    /** Keep the caller's allowance at both native boundaries under the shared reader/inspector permit. */
    open suspend fun decodeAvif(file: File, maxWorkingBytes: Long = CbzWriter.DEFAULT_MAX_MEMORY_BYTES): Bitmap =
        withAndroidAvifPermit {
            decodeCbzAvif(file, maxWorkingBytes)
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
private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
private val AVIF = "avif".toByteArray(Charsets.US_ASCII)
private val AVIS = "avis".toByteArray(Charsets.US_ASCII)
