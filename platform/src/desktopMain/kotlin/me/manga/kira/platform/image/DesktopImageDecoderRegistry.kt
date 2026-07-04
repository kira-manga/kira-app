package me.manga.kira.platform.image

import coil3.decode.Decoder

/**
 * Desktop actual for [ImageDecoderRegistry]. Registers [HighQualitySkiaImageDecoder] so chapter
 * pages decoded by Coil are downsampled with Skia's `CATMULL_ROM` cubic resampler instead of the
 * default `SamplingMode.DEFAULT = NEAREST` baked into Coil 3's stock `SkiaImageDecoder`. Same
 * fix as iOS; see the KDoc on [HighQualitySkiaImageDecoder] for the root cause.
 *
 * One of the load-bearing image-quality fixes preserved verbatim from legacy
 * `:shared/desktopMain`.
 */
class DesktopImageDecoderRegistry : ImageDecoderRegistry {

    override fun registerAll(): List<Decoder.Factory> = listOf(
        HighQualitySkiaImageDecoder.Factory(),
    )
}

/*
 * §253 audit-trail postscript — cluster271 §253 sweep (2026-05-29)
 * Classification: LIVE / FULFILLED-PORT (Phase 5.w.1 rework relocation, Desktop actual leg 2/3).
 *
 * This is the :platform REWORK half of the ImageDecoderRegistry 3-actual fan. The :shared LEGACY
 * half was swept in cluster222 (Task #678); this cluster271 sweeps the rework concrete classes.
 *
 * LIVE evidence — the contract is bound and the Skia-quality fix is actively consumed across the
 * strangler-fig boundary:
 *   - Rework contract: platform commonMain image ImageDecoderRegistry.kt:72 declares
 *     "interface ImageDecoderRegistry" with "fun registerAll(): List<Decoder.Factory>" at line 80
 *     (swept cluster146, Task #602). This class is the Desktop implementor of that interface.
 *   - LEGACY-half binding still wired (consumer flip not yet landed): the expect-class
 *     ImageDecoderRegistry is bound "single { ImageDecoderRegistry() }" at
 *     shared desktopMain di PlatformModule.desktop.kt:112, and consumed at composeApp App.kt:303
 *     ("val decoderFactories = remember { ImageDecoderRegistry().registerAll() }") then installed on
 *     the singleton Coil 3 ImageLoader at App.kt:312 ("decoderFactories.forEach { add(it) }").
 *   - The HighQualitySkiaImageDecoder this leg returns is the load-bearing fix in memory record
 *     project_yami_desktop_skia_size_cap.md (commit 98bf8ed) — Coil's stock Skia decoder used
 *     NEAREST sampling; this replaces it with CATMULL_ROM cubic resampling. See the class KDoc on
 *     HighQualitySkiaImageDecoder under platform nonAndroidMain image for the root cause.
 *
 * Delta-axes vs the Android and iOS sibling actuals:
 *   1. Platform decode API: registers HighQualitySkiaImageDecoder.Factory (Skia CATMULL_ROM cubic
 *      resampler) — SHARED with the iOS leg via nonAndroidMain; DIVERGES from Android's AVIF factory.
 *   2. Threading: none here; registerAll is a pure synchronous list literal. Skia decode runs on
 *      Coil's decode dispatcher when the singleton ImageLoader invokes the Factory.
 *   3. Error handling: none at registry level; the Skia decoder owns its own decode-failure path.
 *   4. DI binding mechanism: plain interface (rework convention, not legacy expect-class); the
 *      Desktop class is the per-leg binding unit once the rework per-platform Koin binding is added.
 *   5. Behavioural-contract parity across the 3 actuals: CONFIRMED — single-element
 *      List<Decoder.Factory>; identical shape to iOS, differing only from Android by Factory identity.
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the class-level block above
 * the class declaration) and 0 interior delimiters in its body. This appended block adds exactly one
 * opener and one closer, with zero forbidden two-character sequences in the prose. Balanced.
 */
