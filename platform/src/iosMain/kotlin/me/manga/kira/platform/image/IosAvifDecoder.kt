package me.manga.kira.platform.image

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.use

/**
 * AVIF stays on the iOS ImageIO thumbnail path because the bundled Skia has no AVIF decoder.
 * Metadata determines the source aspect ratio BEFORE Coil's two-axis target and independent caps
 * are applied. Only the admitted thumbnail is drawn into the requested RGBA output; there is no
 * full-resolution validation decode or PNG round-trip. Native calls and their encoded inputs are
 * serialized to avoid overlapping per-decode working sets.
 *
 * Application input/output allocations are bounded. ImageIO has no native allocation-budget API:
 * source metadata admission and working estimates are not a hard bound on its internal workspace.
 * Real iOS runtime qualification remains necessary for system AVIF support and memory behavior.
 * The factory still claims only AVIF bytes, ahead of the high-quality raster and SVG decoders.
 */
internal class IosAvifDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val limits: AvifDecodeLimits = AvifDecodeLimits(),
) : Decoder {
    override suspend fun decode(): DecodeResult {
        var ownsRead = false
        try {
            return decoderMutex.withLock {
                ownsRead = true
                // Complete source cleanup before creating a native bitmap to hand off to Coil.
                val bytes = source.use { readAvifBytes(source.source(), limits.maxEncodedBytes) }
                decodeIosAvif(bytes, options, limits)
            }
        } finally {
            // Cancellation while queued still owns the claimed source, but never starts native work.
            if (!ownsRead) source.close()
        }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? = if (isAvif(result.source)) IosAvifDecoder(result.source, options) else null
    }

    private companion object {
        val decoderMutex = Mutex()
    }
}

/**
 * Peek the source's `ftyp` box and accept it as AVIF when any 4-byte brand it lists (the major brand
 * at bytes 8..11 OR any compatible brand that follows) is `avif`/`avis`. Reading the full box (bounded
 * to [FTYP_PEEK_BYTES]) — not just the major brand — accepts files whose major brand is e.g. `mif1`
 * with `avif` in the compatible list. Peeking does not consume the source the decoder later reads; any
 * read error declines (returns false) so a non-AVIF input falls through to the Skia decoder.
 */
private fun isAvif(source: ImageSource): Boolean =
    try {
        source.source().peek().use { peek ->
            if (!peek.request(FTYP_HEADER_BYTES) || !peek.rangeEquals(FTYP_OFFSET, fileType)) {
                false
            } else {
                hasAvifBrand(peek)
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

private fun hasAvifBrand(peek: BufferedSource): Boolean {
    // Keep the existing bounded major/compatible-brand sniff, including short ftyp-only fixtures.
    peek.request(FTYP_PEEK_BYTES)
    val available = minOf(FTYP_PEEK_BYTES, peek.buffer.size)
    var offset = BRAND_OFFSET
    while (offset + BRAND_BYTES <= available) {
        if (peek.rangeEquals(offset, avifBrand) || peek.rangeEquals(offset, avisBrand)) return true
        offset += BRAND_BYTES
    }
    return false
}

// "....ftyp" + the 4-byte major brand = 12 bytes; compatible brands are peeked up to 64 bytes.
private const val FTYP_HEADER_BYTES = 12L
private const val FTYP_PEEK_BYTES = 64L
private const val FTYP_OFFSET = 4L
private const val BRAND_OFFSET = 8L
private const val BRAND_BYTES = 4L
private val fileType = "ftyp".encodeUtf8()
private val avifBrand = "avif".encodeUtf8()
private val avisBrand = "avis".encodeUtf8()
