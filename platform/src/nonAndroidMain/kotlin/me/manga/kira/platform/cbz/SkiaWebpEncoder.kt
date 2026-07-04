package me.manga.kira.platform.cbz

import co.touchlab.kermit.Logger
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import me.manga.kira.platform.download.BgDownloadLog
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.impl.use as skiaUse

/**
 * Shared (iOS + Desktop) WebP page encoder backed by skiko (`org.jetbrains.skia`).
 *
 * This is the non-Android counterpart of Android's `Bitmap.compress(WEBP_LOSSY, quality)` — it lets
 * [DesktopCbzWriter] / [IosCbzWriter] produce **real** WebP-encoded CBZ pages instead of storing the
 * downloaded bytes verbatim, so the "convert to WebP" / Yami-Compressor storage saving works on iOS
 * and Desktop, not just Android. skiko is already linked into both targets (it backs
 * [me.manga.kira.platform.image.HighQualitySkiaImageDecoder]); Skia's WebP encoder is compiled in.
 *
 * Decode-then-encode necessarily materialises a bitmap, so [encodeToWebpPages]:
 *  - **bounds peak memory** by splitting images taller than the caller's `maxHeight` into vertical
 *    bands, and further capping each band's height to [MAX_BAND_BYTES] worth of N32 pixels regardless
 *    of width (so one band's bitmap never exceeds ~64 MiB) — the non-Android analogue of Android's
 *    `createCbzWithSplitting`. Each band becomes its own output page, exactly as on Android.
 *  - **releases every native handle** (`Image`, `Bitmap`, `Data`, `Canvas`) in `finally`, mirroring
 *    `HighQualitySkiaImageDecoder`'s explicit-close discipline (Kotlin/Native's GC will not reclaim
 *    skiko's native heap on its own).
 *  - **returns `null` rather than throwing** when the source cannot be decoded (e.g. AVIF — skiko
 *    ships no libavif) or any encode step fails. The caller then stores the original bytes verbatim
 *    under their *true* extension, so a page is never lost and a non-WebP page is never mislabelled
 *    `.webp`.
 */
internal object SkiaWebpEncoder {

    private val log = Logger.withTag(TAG)

    /**
     * Decode [source] (any format Skia decodes: jpg/png/webp/gif/bmp) and re-encode it to one or more
     * WebP pages at [quality] (0..100), splitting taller-than-[maxHeight] images into vertical bands.
     * [maxMemoryBytes] further caps each band so one band bitmap never exceeds the smaller of the
     * caller's budget and the built-in [MAX_BAND_BYTES] ceiling. Returns the WebP byte arrays in
     * top-to-bottom order, or `null` if the source is undecodable or any encode step fails (caller
     * stores verbatim).
     */
    fun encodeToWebpPages(
        source: ByteArray,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long = MAX_BAND_BYTES,
    ): List<ByteArray>? =
        runCatching { encode(source, quality, maxHeight, maxMemoryBytes) }
            .getOrElse { t ->
                if (t is CancellationException) throw t
                log.w(t) { "WebP encode failed (${t.message}); caller will store the page verbatim" }
                null
            }

    private fun encode(source: ByteArray, quality: Int, maxHeight: Int, maxMemoryBytes: Long): List<ByteArray> {
        val mark = TimeSource.Monotonic.markNow() // DLPERF: per-page Skia decode + WebP re-encode cost
        val image = Image.makeFromEncoded(source) // throws on undecodable input (e.g. AVIF)
        val decodeMs = mark.elapsedNow().inWholeMilliseconds
        try {
            val width = image.width
            val height = image.height
            require(width > 0 && height > 0) { "non-positive image dimensions ${width}x$height" }

            val bandHeight = effectiveBandHeight(width, maxHeight, maxMemoryBytes)
            val pages = if (height <= bandHeight) {
                listOf(encodeWebp(image, quality))
            } else {
                buildList {
                    var top = 0
                    while (top < height) {
                        val h = minOf(bandHeight, height - top)
                        add(encodeBand(image, width, top, h, quality))
                        top += h
                    }
                }
            }
            // DLPERF: dims + the decoded-bitmap size (peak native alloc if unbanded) + band count + I/O +
            // decode/total ms — quantifies the per-page CPU + memory pressure of the WebP transcode.
            BgDownloadLog.dlperf(
                "webpEncode",
                "enc" to "skia",
                "dims" to "${width}x$height",
                "decodedMiB" to (width.toLong() * height * BYTES_PER_PIXEL / (1024 * 1024)),
                "bands" to pages.size,
                "srcKiB" to (source.size / 1024),
                "outKiB" to (pages.sumOf { it.size } / 1024),
                "decodeMs" to decodeMs,
                "totalMs" to mark.elapsedNow().inWholeMilliseconds,
                "q" to quality,
            )
            return pages
        } finally {
            image.close()
        }
    }

    /** Encode a whole [image] to WebP bytes. Throws if Skia returns no data (→ verbatim fallback). */
    private fun encodeWebp(image: Image, quality: Int): ByteArray {
        val data = image.encodeToData(EncodedImageFormat.WEBP, quality)
            ?: error("Skia WEBP encoder returned no data")
        return data.skiaUse { it.bytes }
    }

    /** Copy the `[top, top+height)` band of [source] into a fresh N32 bitmap and encode it to WebP. */
    private fun encodeBand(source: Image, width: Int, top: Int, height: Int, quality: Int): ByteArray {
        val bitmap = Bitmap()
        try {
            check(bitmap.allocN32Pixels(width, height)) { "allocN32Pixels failed for ${width}x$height" }
            Canvas(bitmap).skiaUse { canvas ->
                canvas.drawImageRect(
                    source,
                    Rect.makeXYWH(0f, top.toFloat(), width.toFloat(), height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                )
            }
            bitmap.setImmutable()
            val bandImage = Image.makeFromBitmap(bitmap)
            try {
                return encodeWebp(bandImage, quality)
            } finally {
                bandImage.close()
            }
        } finally {
            bitmap.close()
        }
    }

    /**
     * Band height = the smaller of the caller's [maxHeight], the tallest band that keeps one N32
     * bitmap under the byte budget (the smaller of [maxMemoryBytes] and [MAX_BAND_BYTES]) for this
     * [width], and WebP's hard [WEBP_MAX_DIMENSION] pixel limit (so a tall, narrow page can't produce
     * a band Skia's WebP encoder refuses). Always ≥ 1 so a single absurdly-wide row still makes
     * progress.
     */
    private fun effectiveBandHeight(width: Int, maxHeight: Int, maxMemoryBytes: Long): Int {
        val byteBudget = minOf(maxMemoryBytes, MAX_BAND_BYTES).coerceAtLeast(1L)
        val byMemory = (byteBudget / (width.toLong() * BYTES_PER_PIXEL)).toInt().coerceAtLeast(1)
        return minOf(maxHeight, byMemory, WEBP_MAX_DIMENSION).coerceAtLeast(1)
    }

    private const val TAG = "CbzWriter"
    private const val BYTES_PER_PIXEL = 4
    private const val MAX_BAND_BYTES = 64L * 1024 * 1024 // ~64 MiB peak per band bitmap
    private const val WEBP_MAX_DIMENSION = 16383 // WebP hard width/height limit; taller bands fail to encode
}
