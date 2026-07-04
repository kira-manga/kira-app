package me.manga.kira.presentation.common.componants.images

import coil3.request.ImageRequest

/**
 * Platform-specific Coil [ImageRequest.Builder] tweaks that mirror what the upstream Android app's
 * `BaseManga.buildImageRequest` configures per reader page.
 *
 * On Android the native app sets:
 *   - `.allowHardware(false)` — force software-decoded bitmaps so any subsequent canvas reads or
 *     bitmap-pool reuse paths don't trip on hardware-bitmap restrictions.
 *   - `.bitmapConfig(Bitmap.Config.RGB_565)` — 16-bit-per-pixel decode (half the memory of the
 *     default ARGB_8888). Manga pages are predominantly low-color, so the perceptual quality
 *     difference is negligible while the doubled effective cache headroom keeps a lot more pages
 *     resident, which dramatically reduces re-decode / re-sampling pressure mid-scroll. This was
 *     the root cause of the post-Phase 10.4 "image quality so bad I can't read it" regression
 *     reported against the KMP port — without RGB_565 the ARGB_8888 bitmaps fill the memory cache
 *     ~2× faster and Coil starts re-decoding evicted entries, often at sample sizes >1.
 *
 * iOS and Desktop don't have the Android `Bitmap` graph (Skiko / Image I/O handle decode
 * differently), so the actuals there are no-ops — the call returns the builder unchanged.
 */
expect fun ImageRequest.Builder.applyPlatformDecoderHints(): ImageRequest.Builder

/**
 * Whether to apply `.size(Dimension.Pixels(screenWidthPx), Dimension.Undefined)` on chapter-page
 * image requests.
 *
 * Returns `true` on every platform: Coil decodes the bitmap at the device's display width and the
 * resulting bitmap is drawn 1:1 by Compose's `Image(contentScale = FillWidth)` — no draw-time
 * resampling, smaller memory footprint, maximum sharpness.
 *
 * **The iOS / Desktop subtlety.** Coil 3's stock `SkiaImageDecoder` calls
 * `Canvas.drawImageRect(image, src, dst)` with the 3-arg overload, which defaults to
 * `SamplingMode.DEFAULT = FilterMipmap(NEAREST, NONE)` — nearest-neighbor downsample. That bakes
 * aliased / blocky pixels into the bitmap before Compose ever draws it. On Skia-backed targets we
 * register a custom `HighQualitySkiaImageDecoder` (see
 * `shared/.../core/image/HighQualitySkiaImageDecoder.kt`) that uses `SamplingMode.CATMULL_ROM`
 * cubic resampling, so this `true` actually produces sharp output. Android takes a different path
 * (`BitmapFactory` → bilinear) and is unaffected.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster80.staleKdocSweep.cascade,
 * Task #536, 2026-05-28): this size-cap prose plus the sibling
 * Android-decoder-hints prose at L5-22 of this file are jointly
 * classified as follows after recursive symbol verification across
 * the KMP graph (twenty-fourth sibling of the cluster57-79 sweep —
 * third file in the `presentation/common/componants/images/`
 * sub-package, structurally distinct as a paired Coil-decoder-hints
 * plus Skia-size-cap expect/actual seam):
 *  (a) Android `.allowHardware(false)` plus `.bitmapConfig(Bitmap.
 *  Config.RGB_565)` claim — LIVE-NOT-STALE. Auto-memory cite
 *  (project_yami_image_quality_buildrequest.md) confirms Android
 *  needs both flags; the Android actual at `composeApp/src/
 *  androidMain/.../images/PlatformDecoderHints.android.kt` realizes
 *  the pair, while iOS and Desktop actuals return the builder
 *  unchanged.
 *  (b) "Phase 10.4 image-quality regression" cite — LIVE-NOT-STALE.
 *  Auto-memory cite (project_yami_image_quality_buildrequest.md)
 *  documents the same root cause: ARGB_8888 fills the cache and
 *  Coil subsamples evicted pages. The RGB_565 plus
 *  allowHardware(false) pair is the regression-fix landing point.
 *  (c) `shouldConstrainImageSizeToScreen` "returns true everywhere"
 *  claim — LIVE-NOT-STALE. Recursive Grep for `actual fun
 *  shouldConstrainImageSizeToScreen` across the KMP tree finds
 *  Android plus iOS plus Desktop actuals all returning `true`; the
 *  expect at L42 lands consistently.
 *  (d) "iOS / Desktop SkiaImageDecoder subtlety — register custom
 *  HighQualitySkiaImageDecoder for SamplingMode.CATMULL_ROM" cite —
 *  LIVE-NOT-STALE. The cited path `shared/.../core/image/
 *  HighQualitySkiaImageDecoder.kt` is LIVE on disk; a parallel
 *  `platform/.../platform/image/HighQualitySkiaImageDecoder.kt` is
 *  also LIVE. Auto-memory cite (project_yami_desktop_skia_size_cap.
 *  md) documents the same Skia stock-decoder NEAREST-sampling
 *  regression plus 4096 cap that the custom decoder plus
 *  per-request maxBitmapSize-Undefined cure.
 *  Four LIVE-NOT-STALE classifications STAND on their own merits as
 *  a faithful paired Coil-decoder-hints plus Skia-size-cap
 *  expect/actual-seam manifest. Original Phase 10.3-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
expect fun shouldConstrainImageSizeToScreen(): Boolean
