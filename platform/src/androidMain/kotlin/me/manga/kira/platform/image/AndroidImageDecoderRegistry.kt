package me.manga.kira.platform.image

import coil3.decode.Decoder

/**
 * Android actual for [ImageDecoderRegistry]. Registers [AvifDecoderCoil.Factory] so the singleton
 * `ImageLoader` can decode AVIF-encoded manga pages served by Cloudflare-protected CDNs at full
 * quality.
 *
 * Without this registration, Coil 3 falls back to the platform default — Android 31+ uses the
 * system `ImageDecoder` which decodes AVIF at degraded quality, and older releases cannot decode
 * AVIF at all. This is one of the load-bearing image-quality fixes preserved verbatim from
 * legacy `:shared/androidMain`.
 */
class AndroidImageDecoderRegistry : ImageDecoderRegistry {

    override fun registerAll(): List<Decoder.Factory> = listOf(
        AvifDecoderCoil.Factory(),
    )
}

/*
 * §253 audit-trail postscript — cluster271 §253 sweep (2026-05-29)
 * Classification: LIVE / FULFILLED-PORT (Phase 5.w.1 rework relocation, Android actual leg 1/3).
 *
 * This is the :platform REWORK half of the ImageDecoderRegistry 3-actual fan. The :shared LEGACY
 * half (the expect-class plus its three actuals under shared core image) was swept in cluster222
 * (Task #678); this cluster271 sweeps the rework concrete classes.
 *
 * LIVE evidence — the contract this class implements is bound and the format-fix it carries is
 * actively consumed across the strangler-fig boundary:
 *   - Rework contract: platform commonMain image ImageDecoderRegistry.kt:72 declares
 *     "interface ImageDecoderRegistry" with "fun registerAll(): List<Decoder.Factory>" at line 80
 *     (already swept cluster146, Task #602). This class is the Android implementor of that interface.
 *   - LEGACY-half binding still wired (consumer flip not yet landed): the expect-class
 *     ImageDecoderRegistry is bound "single { ImageDecoderRegistry() }" at
 *     shared androidMain di PlatformModule.android.kt:128, and consumed at composeApp App.kt:303
 *     ("val decoderFactories = remember { ImageDecoderRegistry().registerAll() }") then installed on
 *     the singleton Coil 3 ImageLoader at App.kt:312 ("decoderFactories.forEach { add(it) }").
 *   - DUAL-LIVE posture documented at shared androidMain core image AvifDecoderCoil.android.kt:305
 *     to 309: both the :shared (consumer-wired) and the :platform (rework, AndroidImageDecoder
 *     Registry-wired) halves LIVE until the consumer flip from :shared to :platform lands.
 *   - The AVIF factory this leg returns is the load-bearing fix in memory record
 *     project_yami_avif_decoder.md — dropping it regresses post-port "image quality bad".
 *
 * Delta-axes vs the iOS and Desktop sibling actuals:
 *   1. Platform decode API: registers AvifDecoderCoil.Factory (org.aomedia.avif.android JNI decoder)
 *      — UNIQUE to this leg; iOS and Desktop register HighQualitySkiaImageDecoder.Factory instead.
 *   2. Threading: none here; registerAll is a pure synchronous list literal. Actual decode work runs
 *      on Coil's own decode dispatcher when the singleton ImageLoader invokes the Factory.
 *   3. Error handling: none at registry level; AvifDecoderCoil owns AVIF-vs-fallback dispatch.
 *   4. DI binding mechanism: rework convention is a plain interface (not the legacy expect-class), so
 *      future Android decoders may take constructor-injected deps (Context, cache dir); the per-leg
 *      class is the binding unit once the per-platform rework Koin binding is added.
 *   5. Behavioural-contract parity across the 3 actuals: CONFIRMED — each returns a single-element
 *      List<Decoder.Factory>; only the Factory identity differs (AVIF on Android, Skia on the rest).
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the class-level block above
 * the class declaration) and 0 interior delimiters in its body. This appended block adds exactly one
 * opener and one closer, with zero forbidden two-character sequences in the prose. Balanced.
 */
