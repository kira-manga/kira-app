package me.manga.kira.platform.image

import coil3.decode.Decoder

/**
 * Platform-specific registry of Coil [Decoder.Factory] instances. The Compose UI layer (Phase 10)
 * will install these on the shared `ImageLoader` so manga pages render with their full format
 * coverage on each target:
 *  - Android: AVIF support via `org.aomedia.avif.android` so Cloudflare-protected manga CDNs
 *    that serve AVIF-encoded chapter pages decode at full quality (platform default falls back
 *    to Android's `ImageDecoder` on API 31+ at degraded quality, and cannot decode AVIF at all
 *    on older releases). One of the load-bearing image-quality fixes preserved verbatim from
 *    legacy `:shared`.
 *  - iOS / Desktop: a high-quality Skia decoder (`HighQualitySkiaImageDecoder`) that downsamples
 *    pages with `CATMULL_ROM` cubic resampling instead of Coil 3 stock's nearest-neighbor.
 *    Another of the load-bearing image-quality fixes preserved verbatim from legacy.
 *
 * Relocated from legacy `:shared/.../core/image/ImageDecoderRegistry.kt` as part of the
 * Phase 5.w.1 SPI port. Legacy used an `expect class`; the rework convention is plain interfaces
 * so future per-platform decoders can still hold constructor-injected dependencies (e.g. a
 * `Context` or a JVM cache directory) via their implementing class's primary constructor.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster146.staleKdocSweep.cascade,
 * Task #602, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-sixty-fifth sibling of the cluster57-145
 * sweep — third file of the wave-26 :platform tier cluster146 5-leaf
 * image-plus-device batch alongside Base64ImageConverter plus
 * DominantColorExtractor plus ScreenshotProvider plus DeviceTierProbe):
 *  (a) "Platform-specific-registry-of-Coil-Decoder.Factory-instances +
 *  The-Compose-UI-layer-Phase-10-will-install-these-on-the-shared-
 *  ImageLoader-so-manga-pages-render-with-their-full-format-coverage-
 *  on-each-target + Android-AVIF-support-via-org.aomedia.avif.android-
 *  so-Cloudflare-protected-manga-CDNs-that-serve-AVIF-encoded-chapter-
 *  pages-decode-at-full-quality + platform-default-falls-back-to-
 *  Android-ImageDecoder-on-API-31-plus-at-degraded-quality-and-cannot-
 *  decode-AVIF-at-all-on-older-releases + One-of-the-load-bearing-
 *  image-quality-fixes-preserved-verbatim-from-legacy-:shared + iOS-
 *  Desktop-a-high-quality-Skia-decoder-HighQualitySkiaImageDecoder-
 *  that-downsamples-pages-with-CATMULL_ROM-cubic-resampling-instead-
 *  of-Coil-3-stock-s-nearest-neighbor + Another-of-the-load-bearing-
 *  image-quality-fixes-preserved-verbatim-from-legacy" — LIVE-NOT-
 *  STALE plus FULFILLED-PREDICTION. Verified: 3 actuals shipped at
 *  platform/src/{android,ios,desktop}Main/image/. The Phase 10 wiring
 *  prediction is FULFILLED — the rework Compose UI ImageLoader
 *  (singleton in :composeApp) installs the platform Decoder.Factory
 *  list via Koin-injected ImageDecoderRegistry. The two load-bearing
 *  image-quality fixes (Android AVIF via org.aomedia.avif.android +
 *  iOS/Desktop HighQualitySkiaImageDecoder with CATMULL_ROM cubic
 *  resampling) are preserved verbatim and cross-referenced in memory
 *  records (project_yami_avif_decoder.md + project_yami_desktop_skia_
 *  size_cap.md) as critical post-port quality regressions to NOT
 *  reintroduce.
 *  (b) "Relocated-from-legacy-:shared-core-image-ImageDecoderRegistry-
 *  as-part-of-the-Phase-5.w.1-SPI-port + Legacy-used-an-expect-class-
 *  the-rework-convention-is-plain-interfaces-so-future-per-platform-
 *  decoders-can-still-hold-constructor-injected-dependencies-e.g.-a-
 *  Context-or-a-JVM-cache-directory-via-their-implementing-class-s-
 *  primary-constructor" — LIVE-NOT-STALE plus FULFILLED-PREDICTION.
 *  Verified: the interface-not-expect-class rework convention enabled
 *  AndroidImageDecoderRegistry to take a constructor-injected
 *  Context (verified: it does — used for AVIF decoder asset access);
 *  the prediction "future per-platform decoders can hold constructor-
 *  injected deps" is FULFILLED. The legacy :shared facade is still
 *  LIVE (Task #422 BLOCKER §250 shadow-legacy-facade retire path) but
 *  the rework :composeApp ImageLoader singleton consumes the
 *  :platform binding exclusively — the legacy facade is wired for
 *  not-yet-migrated strangler-fig sites only.
 *  Two classifications STAND on their own merits. Original Phase
 *  5.w.1 (Task #181) :platform-relocation prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
interface ImageDecoderRegistry {

    /**
     * Return the per-platform list of `Decoder.Factory` instances to install on the shared
     * `ImageLoader`. Order is significant — Coil's `ComponentRegistry` tries user-registered
     * decoders before its service-loaded defaults, so the first matching factory in this list
     * wins. Empty list is a valid return when the platform's stock decoders are sufficient.
     */
    fun registerAll(): List<Decoder.Factory>
}
