package me.manga.kira.platform.image

import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.util.component1
import coil3.util.component2
import okio.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.impl.use as skiaUse

/**
 * Drop-in replacement for Coil 3's stock `coil3.decode.SkiaImageDecoder` that produces sharp
 * bitmaps when the request asks for a smaller size than the source image. Ported verbatim from
 * legacy `:shared/nonAndroidMain/.../core/image/HighQualitySkiaImageDecoder.kt`.
 *
 * **Why this exists.** Skiko's `Canvas.drawImageRect(image, src, dst)` (the 3-arg overload Coil's
 * upstream `Bitmap.makeFromImage` calls) uses `SamplingMode.DEFAULT`, which is
 * `FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)` — i.e. nearest-neighbor with no mipmaps.
 * When `screenWidthPx` is passed via `rememberSourceImageRequest` and Coil downscales a manga page
 * from its native ~1500-2000 px width to the device's ~1200 px display width, that nearest-neighbor
 * step bakes aliased / blocky pixels into the decoded bitmap before Compose ever draws it. This was
 * the root cause of the "image quality is so bad on iOS / JVM but fine on Android" report — Android
 * Coil uses `BitmapFactory` with proper bilinear, so its decoded output is sharp.
 *
 * **The fix.** We mirror upstream's decode loop verbatim, but call the 11-arg `drawImageRect`
 * overload with [SamplingMode.CATMULL_ROM] — a Mitchell-style cubic resampler. Cubic produces
 * visibly sharper edges and smoother gradients than bilinear, at trivial extra CPU cost (decode is
 * a one-time event per page; we're not in a render hot loop). The destination bitmap stays N32
 * (32-bit RGBA) — RGB_565 would halve memory but introduces visible banding on manga screentones,
 * which is the wrong trade-off for this app.
 *
 * **Registration.** [IosImageDecoderRegistry] / [DesktopImageDecoderRegistry] add `Factory()` to
 * the list they return from `registerAll()`. Coil's `ComponentRegistry` tries user-registered
 * decoders before its service-loaded defaults (the stock `SkiaImageDecoder.Factory`), so this one
 * always wins on iOS and Desktop. Android keeps its own `BitmapFactory`-backed path and is
 * unaffected.
 */
@OptIn(ExperimentalCoilApi::class)
internal class HighQualitySkiaImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        return decodeEncodedImageWithSkia(bytes, options)
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder = HighQualitySkiaImageDecoder(result.source, options)
    }
}

/**
 * Decode [encoded] image bytes with Skia and downsample to the size [options] requests, using the
 * CATMULL_ROM cubic resampler (see [HighQualitySkiaImageDecoder] for the quality rationale). Shared
 * by the iOS AVIF decoder, which converts AVIF → PNG via the system (UIImage) decoder and then
 * re-uses this exact quality / sizing path so AVIF pages render identically to every other format.
 *
 * #3 hardening: [Image.makeFromEncoded] throws on undecodable input — Skiko's bundled Skia ships no
 * libavif, so a raw AVIF reaching here throws. Surface that as an explicit, Coil-friendly decode
 * failure (a clear message instead of a bare native exception) rather than relying on Coil's outer
 * catch. The Skia [Image] holds native heap memory the GC can't reclaim, so it is always closed in
 * `finally`.
 *
 * KNOWN LIMITATION (Desktop AVIF, deferred): there is no AVIF decode path on the JVM/Skiko classpath,
 * so Desktop AVIF still fails cleanly here. iOS routes AVIF through `IosAvifDecoder` BEFORE reaching
 * this path. The iOS/Desktop CBZ writers transcode decodable pages to WebP; only un-decodable formats
 * (AVIF on Desktop) fall back to verbatim, and those then fail this decoder on read-back — present and
 * honestly labelled, not silently dropped. See audit `ios-desktop-verbatim-cbz-avif-undecodable`.
 */
@OptIn(ExperimentalCoilApi::class)
internal fun decodeEncodedImageWithSkia(encoded: ByteArray, options: Options): DecodeResult {
    val image = try {
        Image.makeFromEncoded(encoded)
    } catch (t: Throwable) {
        throw IllegalStateException(
            "Unable to decode image: unsupported or corrupt encoded bytes " +
                "(e.g. AVIF, which Skia cannot decode on this platform).",
            t,
        )
    }
    try {
        val srcWidth = image.width
        val srcHeight = image.height
        val (dstWidth, dstHeight) = DecodeUtils.computeDstSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            targetSize = options.size,
            scale = options.scale,
            maxSize = options.maxBitmapSize,
        )
        var multiplier = DecodeUtils.computeSizeMultiplier(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
            maxSize = options.maxBitmapSize,
        )
        // Match upstream: never upscale via the decode step unless an exact size was demanded.
        if (options.precision == Precision.INEXACT) {
            multiplier = multiplier.coerceAtMost(1.0)
        }
        val outWidth = (multiplier * srcWidth).toInt().coerceAtLeast(1)
        val outHeight = (multiplier * srcHeight).toInt().coerceAtLeast(1)

        val bitmap = Bitmap()
        check(bitmap.allocN32Pixels(outWidth, outHeight)) { "allocN32Pixels failed for ${outWidth}x$outHeight" }
        try {
            Canvas(bitmap).skiaUse { canvas ->
                canvas.drawImageRect(
                    image = image,
                    src = Rect.makeWH(srcWidth.toFloat(), srcHeight.toFloat()),
                    dst = Rect.makeWH(outWidth.toFloat(), outHeight.toFloat()),
                    samplingMode = SamplingMode.CATMULL_ROM,
                    paint = null,
                    strict = true,
                )
            }
            bitmap.setImmutable()
            return DecodeResult(
                image = bitmap.asImage(),
                isSampled = outWidth < srcWidth || outHeight < srcHeight,
            )
        } catch (t: Throwable) {
            // On the success path ownership of the bitmap transfers to the returned Coil image; on
            // any failure here that handoff never happens, so release its native memory before
            // propagating (the outer finally only closes the source [image]).
            bitmap.close()
            throw t
        }
    } finally {
        image.close()
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster247.staleKdocSweep.cascade, Task #703, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster247 leaf 5 of 5 — :platform nonAndroidMain image HighQualitySkiaImageDecoder,
 * sibling 511 CLOSER of 5-LEAF-MIXED-OUTLIER-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 235 leaves with this commit.
 *
 * File-shape note: 114-line file (pre-postscript) — file-level KDoc (27
 * lines) preserved verbatim. 1 internal class (HighQualitySkiaImageDecoder)
 * implementing coil3.decode.Decoder with 1 nested public Factory class
 * implementing Decoder.Factory. 17 imports (coil3 + okio + skia). 1
 * @OptIn(ExperimentalCoilApi::class) class-level annotation. NO companion.
 * nonAndroidMain-ONLY-SOURCE-SET-OUTLIER (iOS plus Desktop share, Android
 * uses BitmapFactory path).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - NONANDROIDMAIN-ONLY-COIL-DECODER-OUTLIER-LIVE — File lives in
 *     `nonAndroidMain` source set (iOS plus Desktop targets share this
 *     code; Android skips it entirely). 1-DIVERGES from typical
 *     :platform 3-actual fan shape (most :platform facades have triad
 *     android+desktop+ios). PRESERVE — load-bearing because Android's
 *     Coil decoder path uses BitmapFactory (proper bilinear sampling),
 *     which already produces sharp decoded bitmaps; the
 *     HighQualitySkiaImageDecoder fix only applies to Skiko-backed
 *     decode (iOS + Desktop). Project-memory `project_yami_desktop_
 *     skia_size_cap.md` documents this as commit 98bf8ed (the
 *     "telephoto rewrite" hypothesis was wrong; the actual issue was
 *     SamplingMode.DEFAULT being NEAREST plus 4096 maxBitmapSize cap).
 *
 *   - COIL-STOCK-DECODER-DROP-IN-REPLACEMENT-LIVE — KDoc declares the
 *     class IS a "drop-in replacement for Coil 3's stock
 *     coil3.decode.SkiaImageDecoder". The drop-in posture IS load-
 *     bearing because IosImageDecoderRegistry plus DesktopImageDecoder
 *     Registry register Factory() at the top of the decoder list, so
 *     Coil's ComponentRegistry tries this class BEFORE its service-
 *     loaded default SkiaImageDecoder.Factory. PRESERVE.
 *
 *   - LEGACY-SHARED-PORT-CITATION-LIVE — KDoc cites legacy `:shared/
 *     nonAndroidMain/.../core/image/HighQualitySkiaImageDecoder.kt` as
 *     the verbatim port source. The citation IS load-bearing as
 *     archaeological residue (legacy file MAY still exist or MAY have
 *     been retired in a later phase). PRESERVE-AS-DOCUMENTED.
 *
 *   - CATMULL-ROM-SAMPLING-AXIS-LIVE — Class calls Canvas.drawImageRect
 *     11-arg overload with samplingMode=SamplingMode.CATMULL_ROM (a
 *     Mitchell-style cubic resampler). KDoc justifies CATMULL_ROM over
 *     bilinear with "visibly sharper edges and smoother gradients at
 *     trivial extra CPU cost (decode IS one-time per page)". PRESERVE
 *     — defends against future "switch to LINEAR sampling for perf"
 *     refactor (which would re-introduce the soft/blurry decoded-
 *     bitmap regression).
 *
 *   - N32-PIXEL-FORMAT-VS-RGB565-AXIS-LIVE — Class calls
 *     `bitmap.allocN32Pixels(outWidth, outHeight)` for the destination
 *     buffer. KDoc justifies N32 (32-bit RGBA) over RGB_565 with
 *     "RGB_565 would halve memory but introduces visible banding on
 *     manga screentones — wrong trade-off for this app". 1-DIVERGES
 *     from Android's reader-page buildImageRequest which uses RGB_565
 *     (project memory `project_yami_image_quality_buildrequest.md`
 *     documents this — Android's screen-width sizing pre-empts the
 *     banding issue). PRESERVE.
 *
 *   - COMPUTEDSTSIZE-PLUS-COMPUTESIZEMULTIPLIER-UPSTREAM-MIRROR-LIVE —
 *     Decode loop mirrors Coil's upstream `Bitmap.makeFromImage`
 *     decode shape verbatim (computeDstSize + computeSizeMultiplier +
 *     Precision.INEXACT no-upscale guard + outWidth/outHeight
 *     coerceAtLeast(1)). The verbatim-upstream-mirror IS load-bearing
 *     because future Coil upgrades may change the decode contract;
 *     mirroring upstream lets a future patch land by syncing this
 *     file against the upstream diff. PRESERVE.
 *
 *   - INEXACT-PRECISION-NO-UPSCALE-LIVE — `if (options.precision ==
 *     Precision.INEXACT) { multiplier = multiplier.coerceAtMost(1.0) }`
 *     guard. The INEXACT-no-upscale rule IS load-bearing because
 *     INEXACT mode means "best-effort fit" — upscaling a 1500px source
 *     to a 2000px target would just waste memory without adding
 *     quality. EXACT mode bypasses this guard. PRESERVE.
 *
 *   - ISSAMPLED-FLAG-COIL-CONTRACT-LIVE — DecodeResult IS returned
 *     with `isSampled = outWidth < srcWidth || outHeight < srcHeight`.
 *     The isSampled flag IS load-bearing for Coil's transition-handling
 *     contract (Coil uses it to decide whether to cross-fade between
 *     placeholder and final bitmap). PRESERVE.
 *
 *   - SKIA-IMAGE-CLOSE-FINALLY-LIVE — `try { ... } finally {
 *     image.close() }` block. The finally-close IS load-bearing
 *     because org.jetbrains.skia.Image holds native heap memory that
 *     Kotlin/Native's GC cannot reclaim without explicit close()
 *     (Skiko's Image IS a manual-lifetime object). PRESERVE — defends
 *     against future "rely on GC for image cleanup" refactor (which
 *     would leak native memory on iOS / Desktop).
 *
 *   - OKIO-SOURCE-USE-PLUS-SKIA-USE-RENAMED-IMPORT-LIVE — File imports
 *     both `okio.use` (extension for closeable Source) plus
 *     `org.jetbrains.skia.impl.use as skiaUse` (extension for Skia
 *     Managed objects). The renamed import IS load-bearing for
 *     disambiguating the two use blocks in the decode method (one
 *     reads the source bytes, the other holds the Canvas drawing
 *     scope). PRESERVE.
 *
 *   - INTERNAL-CLASS-PUBLIC-FACTORY-VISIBILITY-LIVE — Outer class IS
 *     declared `internal class` (visible only within :platform module);
 *     nested Factory IS public (default class visibility). The split
 *     visibility IS load-bearing because consumers (IosImageDecoder
 *     Registry, DesktopImageDecoderRegistry) only need to construct
 *     Factory() — the inner Decoder implementation IS an implementation
 *     detail. PRESERVE.
 *
 *   - EXPERIMENTAL-COIL-API-OPTIN-LIVE — @OptIn(ExperimentalCoilApi::
 *     class) at class scope. The opt-in IS required because
 *     coil3.decode.Decoder plus coil3.decode.SkiaImageDecoder.Factory
 *     plus DecodeUtils.computeDstSize ARE flagged experimental in
 *     Coil 3.x. 2-AGREE-WITH-other-Coil-consumers-in-:ui-and-:platform.
 *     PRESERVE.
 *
 *   - SINGLE-PLATFORM-NO-EXPECT-OUTLIER-FLAG-LIVE — File has NO
 *     commonMain expect declaration. The nonAndroidMain-only shape IS
 *     intentional (Android uses a different decoder path entirely).
 *     2-AGREE-WITH-cluster247-LEAF-4 (ForegroundActivityProvider IS
 *     androidMain-only, also a single-platform outlier). PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-LIVE — Outer class has NO companion (Factory
 *     IS a nested non-companion class). 5-AGREE-AT-cluster247.
 *     PRESERVE.
 *
 *   - WAVE-REGISTER-CLOSES-cluster247-LIVE — HighQualitySkiaImage
 *     Decoder.kt CLOSES cluster247 5-LEAF-MIXED-OUTLIER-PLATFORM-
 *     ACTUAL-SUB-TIER-OPENER sweep. cluster247-CLOSER classification:
 *     MIXED-OUTLIER-PLATFORM-ACTUAL-COHESIVE-BATCH-CLOSES-3-FAN-PLUS-
 *     2-OUTLIER-SHAPE. PRESERVE.
 *
 *   - cluster248-PREDICTION — Next candidate sweep targets (in
 *     priority order): (a) :platform androidMain remaining 27 unswept
 *     facades scout — AndroidAdProvider, AndroidAnalyticsClient,
 *     AndroidCbzWriter, AndroidConnectivityObserver,
 *     AndroidConsentFlowClient, AndroidCrashReporter,
 *     AndroidDeviceTierProbe, AndroidAppFileSystem,
 *     AndroidFileSizeFormatter, AndroidBase64ImageConverter,
 *     AndroidDominantColorExtractor, AndroidImageDecoderRegistry,
 *     AndroidScreenshotProvider, AvifDecoderCoil, AndroidIntentLauncher,
 *     AndroidBackgroundJobScheduler, AndroidLocaleSwitcher,
 *     AndroidNotificationPresenter, AndroidPushTokenProvider,
 *     AndroidRemoteDocStore, AndroidInAppReviewClient,
 *     AndroidSecureStorage, AndroidSettingsFactory, AndroidToastShower,
 *     AndroidAppUpdateClient, AndroidAppVersionProvider. (b) :platform
 *     desktopMain remaining 24 unswept facades scout. (c) :platform
 *     iosMain remaining 24 unswept facades scout. Total :platform
 *     unswept = 76 post-cluster247 (78 minus leaves 4+5). Bedrock-span
 *     re-opens IF cluster248 lands on cohesive 3-actual fan or 5-leaf
 *     cohesive sub-tier; OUTLIER-CLASSIFICATION continues IF cluster248
 *     lands on another single-platform outlier or mixed-axis batch.
 *     RESERVE per autonomous-cascade standing directive.
 */

