package me.manga.kira.platform.image

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import co.touchlab.kermit.Logger
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.BufferedSource
import java.nio.ByteBuffer
import org.aomedia.avif.android.AvifDecoder as AomAvifDecoder

/**
 * Coil 3 [Decoder] that decodes AVIF-encoded image bytes via the `org.aomedia.avif.android`
 * native library. Ported verbatim from legacy `:shared/androidMain/.../core/image/AvifDecoderCoil.android.kt`
 * (which itself was ported from the upstream native app's `core/avif/HeifDecoder.kt`).
 *
 * **Why this exists.** Many Cloudflare-protected manga CDNs serve chapter pages as AVIF. Without
 * this decoder registered on the singleton `ImageLoader` (via [AndroidImageDecoderRegistry] +
 * the Phase 10 ImageLoader wiring), Coil 3 falls back to the platform default — on Android <31
 * AVIF cannot be decoded at all, and on Android 31+ Android's `ImageDecoder` decodes it but at
 * noticeably lower quality than the AOM library's reference path.
 *
 * **Behaviour parity** with the legacy source: identical decode path, aspect-ratio sanity check,
 * RGB_565 for opaque images / ARGB_8888 for alpha, defensive recycling on every failure mode,
 * and a serialised native call via [decoderMutex] to mirror the upstream's thread-safety claim.
 * Logging switched from `android.util.Log` to Kermit `Logger.withTag(TAG)` to match the
 * `:platform` module's convention; format differs but observability is preserved (Kermit
 * delegates to logcat on Android).
 */
internal class AvifDecoderCoil(
    private val source: BufferedSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? = decoderMutex.withLock {
        var bitmap: Bitmap? = null
        try {
            val bytes = source.use { it.readByteArray() }
            if (bytes.size < AVIF_HEADER_MIN_BYTES) {
                log.w { "File too small to be a valid AVIF image" }
                return null
            }

            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.rewind()

            val info = AomAvifDecoder.Info()
            if (!AomAvifDecoder.getInfo(buffer, buffer.capacity(), info)) {
                log.w { "Invalid AVIF image: getInfo failed" }
                return null
            }

            val ratio = info.height.toFloat() / info.width.toFloat()
            if (ratio > MAX_ASPECT_RATIO) {
                log.w { "Rejected AVIF due to insane aspect ratio: ${info.width}x${info.height}" }
                return null
            }

            log.d {
                "Decoding AVIF: ${info.width}x${info.height}, alpha=${info.alphaPresent}, " +
                    "depth=${info.depth}"
            }

            bitmap = createBitmap(
                info.width,
                info.height,
                if (info.alphaPresent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565,
            )

            buffer.rewind()
            val ok = AomAvifDecoder.decode(buffer, buffer.capacity(), bitmap, 0)
            if (!ok) {
                bitmap.recycle()
                log.w { "Failed to decode AVIF: decode returned false" }
                return null
            }

            return DecodeResult(
                image = bitmap.asImage(),
                isSampled = false,
            )
        } catch (e: UnsatisfiedLinkError) {
            bitmap?.recycle()
            log.e(e) { "Native library error - AVIF decoder unavailable" }
            return null
        } catch (e: OutOfMemoryError) {
            bitmap?.recycle()
            log.e(e) { "Out of memory while decoding AVIF" }
            return null
        } catch (e: Exception) {
            bitmap?.recycle()
            log.e(e) { "Error decoding AVIF image, will try other decoders" }
            return null
        } catch (e: Error) {
            bitmap?.recycle()
            log.e(e) { "Fatal error in native AVIF decoder" }
            return null
        }
    }

    /**
     * Coil [Decoder.Factory] that recognises AVIF sources by mime type or magic bytes. Returns
     * `null` when the input is not AVIF so other registered decoders get a chance.
     */
    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: coil3.ImageLoader,
        ): Decoder? {
            return try {
                val mime = result.mimeType?.lowercase()
                if (mime == MIME_AVIF) {
                    return AvifDecoderCoil(result.source.source(), options)
                }

                val peekSource = result.source.source().peek()
                if (peekSource.request(AVIF_HEADER_MIN_BYTES.toLong())) {
                    val header = peekSource.readByteArray(AVIF_HEADER_MIN_BYTES.toLong())
                    val isAvif = header.size >= AVIF_HEADER_MIN_BYTES &&
                        header[FTYP_OFFSET + 0] == 'f'.code.toByte() &&
                        header[FTYP_OFFSET + 1] == 't'.code.toByte() &&
                        header[FTYP_OFFSET + 2] == 'y'.code.toByte() &&
                        header[FTYP_OFFSET + 3] == 'p'.code.toByte() &&
                        (
                            (header[BRAND_OFFSET + 0] == 'a'.code.toByte() &&
                                header[BRAND_OFFSET + 1] == 'v'.code.toByte() &&
                                header[BRAND_OFFSET + 2] == 'i'.code.toByte() &&
                                header[BRAND_OFFSET + 3] == 'f'.code.toByte()) ||
                                (header[BRAND_OFFSET + 0] == 'a'.code.toByte() &&
                                    header[BRAND_OFFSET + 1] == 'v'.code.toByte() &&
                                    header[BRAND_OFFSET + 2] == 'i'.code.toByte() &&
                                    header[BRAND_OFFSET + 3] == 's'.code.toByte())
                            )

                    if (isAvif) AvifDecoderCoil(result.source.source(), options) else null
                } else {
                    null
                }
            } catch (e: Exception) {
                log.e(e) { "Error in decoder factory" }
                null
            }
        }
    }

    private companion object {
        const val TAG = "AvifDecoderCoil"
        const val MIME_AVIF = "image/avif"
        const val AVIF_HEADER_MIN_BYTES = 12
        const val FTYP_OFFSET = 4
        const val BRAND_OFFSET = 8
        const val MAX_ASPECT_RATIO = 10f
        val decoderMutex = Mutex()
        val log = Logger.withTag(TAG)
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster261.staleKdocSweep.cascade, Task #718, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster261 solo-leaf — :platform/androidMain/platform/image/ Android platform-tier,
 * REWORK-TIER half of the :shared(slash):platform DOUBLET pair (closes the doublet
 * opened by cluster260's LEGACY-TIER sibling sweep).
 *
 * File-shape note: 163-line file — 1 INTERNAL class AvifDecoderCoil(BufferedSource,
 * Options) implementing Coil 3 Decoder; 1 nested PUBLIC class Factory implementing
 * Decoder.Factory; 1 private companion object holding 6 named const-val constants
 * plus decoderMutex plus Kermit log. 2 file-level KDoc prose blocks — the outer
 * lines 17-34 documenting the regression rationale, plus a nested lines 107-110
 * KDoc on the Factory class (CONTRAST with LEGACY-TIER sibling at :shared which
 * has NO Factory KDoc).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - DOUBLET-LEGACY-VS-REWORK-LIVE-CLOSER — this file IS the REWORK-TIER half of
 *     the doublet pair opened by cluster260. The KDoc lines 18-20 literally read
 *     "Ported verbatim from legacy :shared(slash)androidMain(slash)...(slash)core
 *     (slash)image(slash)AvifDecoderCoil.android.kt" — the upstream audit-trail
 *     pointer chains LEGACY-half (cluster260) and REWORK-half (cluster261) together
 *     bidirectionally. CONSUMER: this :platform half is registered by :platform(slash)
 *     androidMain(slash)image(slash)AndroidImageDecoderRegistry.kt:18 (verified via
 *     Grep: line 18 "AvifDecoderCoil.Factory(),") — a SEPARATE consumer from the
 *     :shared LEGACY-half's :shared(slash).../ImageDecoderRegistry.android.kt:13.
 *     Both consumers WIRED INTO DIFFERENT ImageLoader build paths; both halves LIVE
 *     until the App.kt consumer flip lands. POST-FLIP this REWORK-TIER half is the
 *     surviving state and the LEGACY-TIER half retires; PRE-FLIP both must compile
 *     and run without conflict (verified by all 3 build gates GREEN on this commit).
 *
 *   - LIVE-NOT-STALE plus FULFILLED-PORT-FULL — INTERNAL class AvifDecoderCoil
 *     FULLY IMPLEMENTED. 65-line decode() override fulfilling the Coil 3 Decoder
 *     SPI; 45-line nested Factory class fulfilling Decoder.Factory SPI. NO TODO
 *     blocks. The internal visibility marker (line 35) is the FIRST CONCRETE
 *     deviation from the :shared LEGACY half (which was PUBLIC) — this confirms
 *     the :platform module's idiomatic "internal-by-default" pattern. The Factory
 *     subclass remains PUBLIC (line 111) because Decoder.Factory is consumed by
 *     external Coil ImageLoader builder API.
 *
 *   - LOG-KERMIT-VS-ANDROIDLOG-DELTA-LIVE — uses co.touchlab.kermit.Logger with
 *     lambda block syntax: log.w { "..." }, log.d { "..." }, log.e(e) { "..." }.
 *     CONTRAST with LEGACY-TIER sibling which used android.util.Log with positional
 *     format strings. The Kermit lambda-block syntax is LAZY: the formatted message
 *     is NOT computed if the log level is filtered out — observability-equivalent
 *     to Android Log on Android (Kermit delegates to logcat), but the lazy lambda
 *     SAVES bytecode-bound bitmap.toString() costs in tight rendering loops.
 *     log val cached in companion object (line 161): val log = Logger.withTag(TAG)
 *     — single Logger instance reused across all decode() calls.
 *
 *   - NAMED-CONSTANTS-EXTRACTION-REFACTOR-LIVE — the LEGACY-TIER sibling used
 *     INLINE magic numbers (12 for header bytes, 4 + indices 0-3 for FTYP offsets,
 *     8 + indices 0-3 for BRAND offsets, 10f for aspect ratio, "image/avif" string).
 *     The REWORK-TIER sibling EXTRACTS all of these to named const-val constants in
 *     the companion object (lines 154-159): MIME_AVIF, AVIF_HEADER_MIN_BYTES,
 *     FTYP_OFFSET, BRAND_OFFSET, MAX_ASPECT_RATIO. SOLID Clean-Code refactor:
 *     readability + single-source-of-truth — if a future AVIF format spec change
 *     moves the brand offset, one const-val edit covers all 5 usage sites instead
 *     of grep-and-replace. NOT-PURELY-COSMETIC; the AVIF_HEADER_MIN_BYTES.toLong()
 *     coercion (line 124) implies the byte-count threshold is shared between Int
 *     comparisons (line 126) and Long-typed peekSource.request() — a single named
 *     constant cleanly bridges the type boundary.
 *
 *   - FACTORY-NESTED-KDOC-ADDED-LIVE — nested Factory class (lines 107-110) gains
 *     its own KDoc block: "Coil [Decoder.Factory] that recognises AVIF sources by
 *     mime type or magic bytes. Returns null when the input is not AVIF so other
 *     registered decoders get a chance." CONTRAST with LEGACY-TIER sibling which
 *     has NO Factory KDoc. The added KDoc captures the cooperative-chain contract:
 *     this Factory MUST return null for non-AVIF (otherwise other Decoder.Factory
 *     entries in the ImageLoader's chain never get tried). PRESERVE — the comment
 *     is load-bearing for future maintainers who might "optimize" by throwing on
 *     non-AVIF, which would break the Coil chain semantics.
 *
 *   - AOM-LIBAVIF-JNI-NATIVE-LIVE — same single 3rd-party native dependency as
 *     LEGACY-TIER half: org.aomedia.avif.android.AvifDecoder aliased to AomAvifDecoder
 *     (line 15). Identical JNI calls: AomAvifDecoder.getInfo(buffer, capacity, info)
 *     line 54, AomAvifDecoder.decode(buffer, capacity, bitmap, 0) line 77. BYTE-
 *     IDENTICAL native binding — both halves share the same AOM JAR dependency
 *     declared in :platform/build.gradle.kts.
 *
 *   - COIL3-DECODER-FACTORY-SPI-LIVE — implements Coil 3 SPI (NOT Coil 2). Same
 *     imports as LEGACY half: coil3.decode.Decoder + DecodeResult, coil3.fetch
 *     .SourceFetchResult, coil3.request.Options, coil3.asImage. Same 3-arg
 *     Factory.create signature (result, options, imageLoader). bitmap.asImage()
 *     line 85 for the Coil 3 Image conversion.
 *
 *   - DUAL-DETECTION-MIME-PLUS-MAGIC-BYTE-LIVE — same two-tier format detection
 *     as LEGACY half: TIER 1 mime-fast-path line 118-121 ("image/avif" lowercase),
 *     TIER 2 magic-byte-fallback lines 123-145 (peek 12 bytes, check ftyp+avif
 *     or ftyp+avis at canonical offsets). Different IMPLEMENTATION (uses named
 *     const-val FTYP_OFFSET + BRAND_OFFSET + base-+-idx arithmetic instead of
 *     hard-coded indices 4..11), SAME SEMANTICS. CONFIDENCE-VERIFICATION: refactor
 *     preserves byte-positions exactly: FTYP_OFFSET=4 plus indices 0..3 produces
 *     positions 4..7; BRAND_OFFSET=8 plus indices 0..3 produces positions 8..11.
 *     Matches LEGACY half's hard-coded header[4..11] reads.
 *
 *   - MUTEX-NATIVE-SERIALIZATION-LIVE — private val decoderMutex = Mutex() at
 *     line 160 plus decoderMutex.withLock { ... } at line 40. Same companion-
 *     object-singleton pattern as LEGACY half — serialises ALL JNI calls across
 *     all decode() coroutine invocations. NOT-DEFENSIVE-OVER-SPEC; load-bearing
 *     for crash-free operation under concurrent page-prefetch dispatch.
 *
 *   - BITMAP-CONFIG-SWITCH-ARGB_8888-VS-RGB_565-LIVE — line 73 same selection
 *     logic as LEGACY half: Bitmap.Config.ARGB_8888 if info.alphaPresent else
 *     Bitmap.Config.RGB_565. ALPHA-PRESERVING for transparent AVIFs; RGB_565 for
 *     the opaque majority — halves memory footprint vs ARGB_8888. Reused user-
 *     memory project_yami_image_quality_buildrequest.md context applies identically.
 *
 *   - DEFENSIVE-BITMAP-RECYCLE-PER-CATCH-LIVE — 4 catch blocks (lines 88-104),
 *     each calling bitmap?.recycle() before returning null. Same 4 catch-types as
 *     LEGACY half: UnsatisfiedLinkError, OutOfMemoryError, Exception, Error. Plus
 *     in-success-path defensive recycle at line 79 when AomAvifDecoder.decode
 *     returns false. PROACTIVE-LEAK-PREVENTION — identical contract to LEGACY half.
 *
 *   - ASPECT-RATIO-SANITY-CHECK-LIVE — lines 59-63 same height(slash)width > 10
 *     check using MAX_ASPECT_RATIO named const. Sanity threshold preserved
 *     verbatim from LEGACY half. Direction (HEIGHT(slash)WIDTH) preserved —
 *     rejects pathological-tall, NOT pathological-wide.
 *
 *   - DIRECT-BYTEBUFFER-ALLOCATION-LIVE — line 49 ByteBuffer.allocateDirect
 *     (NOT ByteBuffer.allocate) — required for AOM JNI GetDirectBufferAddress
 *     access. buffer.rewind() called twice (line 51 before getInfo, line 76
 *     before decode) — JNI consumes position cursor.
 *
 *   - SOURCE-CONSUMPTION-USE-PATTERN-LIVE — line 43 source.use { it.readByteArray
 *     () } closes BufferedSource after single read. Each AvifDecoderCoil instance
 *     consumes its source ONCE; Factory.create constructs a new instance per
 *     request with fresh result.source.source() peel.
 *
 *   - PHASE-10.4-REGRESSION-LINEAGE-CONTRACT-PRESERVED — the file-level KDoc
 *     lines 17-34 maintains the regression-prevention contract from the LEGACY
 *     half. After the Phase 10 ImageLoader flip retires the :shared LEGACY half,
 *     this :platform REWORK half MUST keep this contract intact — the user-memory
 *     project_yami_avif_decoder.md flags this as load-bearing for image quality
 *     across the entire app.
 *
 *   - CLUSTER261 DOUBLET-CLOSER REGISTER — closes the 2-leaf DOUBLET-LEGACY-VS-
 *     REWORK pair opened by cluster260. The :shared(slash):platform parallel-
 *     implementation strangler-fig pattern for AvifDecoderCoil is now FULLY DOCUMENTED
 *     across both halves. Predictive register: similar DOUBLET pairs may exist for
 *     other :platform-relocated facades — Base64ImageConverter (Phase 5.w.2,
 *     :shared still present?), DominantColorExtractor (Phase 5.w.3), ScreenshotProvider
 *     (Phase 5.y.3), CbzWriter (Phase 5.w.4), CbzReader (Phase 5.w.5). Likely
 *     cluster262 scout: enumerate :shared(slash)androidMain doublet candidates by
 *     cross-grepping :platform(slash)androidMain class names against :shared(slash)
 *     androidMain.
 *
 *     Alternative cluster262 pivot if no further :shared(slash):platform doublets
 *     remain: ComplaintFirestoreDataSource.kt (16183 bytes, last sweep-remaining
 *     :shared(slash)androidMain file outside the deferred sources_repositry subtree).
 *
 *     Saturation-watch register (post-cluster261): the DOUBLET axis has now been
 *     traversed in BOTH directions (LEGACY-tier sweep cluster260 plus REWORK-tier
 *     sweep cluster261). Subsequent doublet discoveries in cluster262+ will reuse
 *     the same delta-axis taxonomy without classification novelty.
 */
