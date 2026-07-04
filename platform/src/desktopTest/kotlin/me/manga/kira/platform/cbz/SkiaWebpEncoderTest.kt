package me.manga.kira.platform.cbz

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [SkiaWebpEncoder] on the Desktop (JVM/skiko-awt) target. The encoder is the *identical*
 * `org.jetbrains.skia` multiplatform API on iOS, so passing here is strong (not total — see the
 * plan's required iOS device smoke) evidence the WebP transcode works on both non-Android targets.
 */
class SkiaWebpEncoderTest {

    @Test
    fun decodableImage_transcodesToSingleWebpPage() {
        val png = solidPng(width = 8, height = 8)
        val pages = SkiaWebpEncoder.encodeToWebpPages(png, quality = 75, maxHeight = Int.MAX_VALUE)

        assertEquals(1, pages?.size, "a short page yields exactly one WebP entry")
        assertTrue(isWebp(pages!!.single()), "output carries the RIFF/WEBP magic, i.e. it really is WebP")
    }

    @Test
    fun undecodableBytes_returnNull_soCallerStoresVerbatim() {
        val garbage = ByteArray(64) { it.toByte() }
        assertNull(
            SkiaWebpEncoder.encodeToWebpPages(garbage, quality = 75, maxHeight = Int.MAX_VALUE),
            "skiko can't decode this → null so the writer falls back to verbatim",
        )
    }

    @Test
    fun tallImage_splitsIntoMemoryBoundedBands_eachWebp() {
        val png = solidPng(width = 4, height = 10)
        // maxHeight = 2 forces a split into ceil(10/2) = 5 bands (width is tiny so the memory cap
        // never binds, isolating the maxHeight path).
        val pages = SkiaWebpEncoder.encodeToWebpPages(png, quality = 75, maxHeight = 2)

        assertEquals(5, pages?.size, "a 10px-tall image splits into five 2px bands")
        assertTrue(pages!!.all { isWebp(it) }, "every band is a valid WebP page")
    }

    @Test
    fun verbatimPageExtension_prefersKnownNameThenSniffsThenImg() {
        // Known extension in the filename wins.
        assertEquals("jpg", verbatimPageExtension("image_3.jpg", byteArrayOf()))
        assertEquals("avif", verbatimPageExtension("image_7.avif", byteArrayOf()))
        // No usable name → sniff the magic bytes (ISO-BMFF `....ftyp` → avif).
        val ftyp = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 0, 0, 0, 0)
        assertEquals("avif", verbatimPageExtension("page", ftyp))
        // Neither name nor magic → honest last resort, never a cosmetic ".webp".
        assertEquals("img", verbatimPageExtension("page", byteArrayOf(1, 2, 3)))
    }

    // --- helpers --------------------------------------------------------------------------------

    /** A valid PNG of a solid colour, built with Skia so the test has no binary fixtures. */
    private fun solidPng(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
        try {
            Canvas(bitmap).clear(Color.makeRGB(10, 120, 200))
            bitmap.setImmutable()
            val image = Image.makeFromBitmap(bitmap)
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG, 100)
                    ?: error("PNG encode failed in test setup")
                try {
                    return data.bytes
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            bitmap.close()
        }
    }

    private fun isWebp(b: ByteArray): Boolean =
        b.size >= 12 &&
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
}
