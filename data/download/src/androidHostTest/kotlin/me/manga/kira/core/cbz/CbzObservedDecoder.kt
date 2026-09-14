package me.manga.kira.core.cbz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Ordinary pages use the real codec; only region pixels are modeled, never native proof. */
internal class CbzObservedDecoder(
    private val originals: List<String> = emptyList(),
) : CbzImageDecoder() {
    val requested = CopyOnWriteArrayList<File>()
    val bitmaps = CopyOnWriteArrayList<Bitmap>()
    val regions = CopyOnWriteArrayList<Rect>()
    val regionCloses = AtomicInteger()
    var nullRegionAt: Int? = null
    var regionCloseFailure: Throwable? = null

    override fun bounds(file: File): BitmapFactory.Options {
        assertPreviousRecycled()
        if (originals.isNotEmpty()) {
            val original = File(originals[requested.size])
            assertNotEquals(original, file, "Decode must use the owned snapshot, not reopen the source")
            assertContentEquals(
                original.readBytes(),
                file.readBytes(),
                "Snapshot requests must retain input byte order",
            )
        }
        requested += file
        return super.bounds(file)
    }

    override fun decode(
        file: File,
        sampleSize: Int,
        config: Bitmap.Config?,
    ): Bitmap? {
        assertPreviousRecycled()
        assertEquals(requested.last(), file, "Full decode must use the snapshot whose bounds were observed")
        return super.decode(file, sampleSize, config)?.also { bitmaps += it }
    }

    override fun openRegions(stream: InputStream): CbzRegions =
        object : CbzRegions {
            override fun decode(region: Rect): Bitmap? {
                assertPreviousRecycled()
                regions += Rect(region)
                if (regions.size == nullRegionAt) return null
                return Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888).also {
                    bitmaps += it
                }
            }

            override fun close() {
                regionCloses.incrementAndGet()
                regionCloseFailure?.let { throw it }
            }
        }

    private fun assertPreviousRecycled() {
        bitmaps.lastOrNull()?.let { assertTrue(it.isRecycled, "Requested another decode while still owning a bitmap") }
    }
}
