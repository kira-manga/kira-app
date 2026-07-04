package me.manga.kira.platform.image

import coil3.decode.Decoder

/**
 * iOS actual for [ImageDecoderRegistry]. Registers TWO decoder factories, in order (Coil's
 * `ComponentRegistry` tries factories in registration order):
 *  1. [IosAvifDecoder] (#3) — sniffs the `ftyp` brand and claims ONLY AVIF inputs, decoding them via
 *     the iOS system `UIImage`; it declines everything else so non-AVIF formats fall through.
 *  2. [HighQualitySkiaImageDecoder] — downsamples chapter pages with Skia's `CATMULL_ROM` cubic
 *     resampler instead of the default `SamplingMode.DEFAULT = NEAREST` baked into Coil 3's stock
 *     `SkiaImageDecoder`.
 *
 * The Skia decoder is one of the load-bearing image-quality fixes preserved verbatim from legacy
 * `:shared/iosMain`. See the KDoc on [HighQualitySkiaImageDecoder] for the root cause analysis —
 * without it, iOS manga pages exhibit visibly aliased / blocky pixels vs. Android.
 */
class IosImageDecoderRegistry : ImageDecoderRegistry {

    override fun registerAll(): List<Decoder.Factory> = listOf(
        // #3-REAL — AVIF first: it sniffs the ftyp brand and only claims AVIF inputs (decoded via
        // the iOS system UIImage decoder), declining everything else so non-AVIF formats fall
        // through to the Skia decoder. Coil's ComponentRegistry tries factories in order.
        IosAvifDecoder.Factory(),
        HighQualitySkiaImageDecoder.Factory(),
    )
}

/*
 * §253 audit-trail postscript — cluster271 §253 sweep (2026-05-29)
 * Classification: LIVE / FULFILLED-PORT (Phase 5.w.1 rework relocation, iOS actual leg 3/3).
 *
 * This is the :platform REWORK half of the ImageDecoderRegistry 3-actual fan. The :shared LEGACY
 * half was swept in cluster222 (Task #678); this cluster271 sweeps the rework concrete classes and
 * CLOSES this 3-actual rework fan (Android leg 1, Desktop leg 2, iOS leg 3).
 *
 * LIVE evidence — the contract is bound and the Skia-quality fix is actively consumed across the
 * strangler-fig boundary:
 *   - Rework contract: platform commonMain image ImageDecoderRegistry.kt:72 declares
 *     "interface ImageDecoderRegistry" with "fun registerAll(): List<Decoder.Factory>" at line 80
 *     (swept cluster146, Task #602). This class is the iOS implementor of that interface.
 *   - LEGACY-half binding still wired (consumer flip not yet landed): the expect-class
 *     ImageDecoderRegistry is bound "single { ImageDecoderRegistry() }" at
 *     shared iosMain di PlatformModule.ios.kt:112, and consumed at composeApp App.kt:303
 *     ("val decoderFactories = remember { ImageDecoderRegistry().registerAll() }") then installed on
 *     the singleton Coil 3 ImageLoader at App.kt:312 ("decoderFactories.forEach { add(it) }").
 *   - The HighQualitySkiaImageDecoder this leg returns is the load-bearing fix in memory record
 *     project_yami_desktop_skia_size_cap.md — without it iOS manga pages exhibit aliased / blocky
 *     pixels vs Android. See the class KDoc on HighQualitySkiaImageDecoder under platform
 *     nonAndroidMain image for the root cause analysis.
 *
 * Delta-axes vs the Android and Desktop sibling actuals:
 *   1. Platform decode API: registers HighQualitySkiaImageDecoder.Factory (Skia CATMULL_ROM cubic
 *      resampler) — SHARED with the Desktop leg via nonAndroidMain; DIVERGES from Android's AVIF.
 *   2. Threading: none here; registerAll is a pure synchronous list literal. Skia decode runs on
 *      Coil's decode dispatcher when the singleton ImageLoader invokes the Factory.
 *   3. Error handling: none at registry level; the Skia decoder owns its own decode-failure path.
 *   4. DI binding mechanism: plain interface (rework convention, not legacy expect-class); the iOS
 *      class is the per-leg binding unit once the rework per-platform Koin binding is added.
 *   5. Behavioural-contract parity across the 3 actuals: CONFIRMED — single-element
 *      List<Decoder.Factory>; byte-for-byte identical body to the Desktop leg, differing from Android
 *      only by Factory identity. The 2-AGREE-1-DIVERGE shape (iOS plus Desktop agree, Android
 *      diverges on AVIF) matches the legacy half noted in cluster222.
 *
 * CORRECTION (#3 / B5c, 2026-06-09): delta-axis 5 above ("single-element List<Decoder.Factory>;
 * byte-for-byte identical body to the Desktop leg") is now STALE. IosAvifDecoder.Factory() was
 * PREPENDED ahead of HighQualitySkiaImageDecoder.Factory(), so this leg returns a TWO-element list
 * and the iOS leg intentionally diverges from Desktop (which has no AVIF decoder). The class KDoc
 * above is the current spec.
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the class-level block above
 * the class declaration) and 0 interior delimiters in its body. This appended block adds exactly one
 * opener and one closer, with zero forbidden two-character sequences in the prose. Balanced.
 */
