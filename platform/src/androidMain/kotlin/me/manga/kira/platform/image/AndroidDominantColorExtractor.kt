package me.manga.kira.platform.image

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.palette.graphics.Palette
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android actual for [DominantColorExtractor]. Uses `androidx.palette` for the perceptual
 * dominant-color sampling — Palette's clustering algorithm gives noticeably better results
 * than the naive 1×1 downscale used on iOS / Desktop (it skews toward saturated, vibrant
 * pixels rather than the spatial average), which is what users expect from the Android
 * port that defined the original behavior.
 *
 * Verbatim port from legacy `:shared/androidMain/.../core/image/DominantColorExtractor.android.kt`.
 * `BitmapFactory.decodeByteArray` is allowed to materialise the full bitmap here (unlike the
 * `inJustDecodeBounds` probe in [Base64ImageConverter]) because Palette needs real pixel data.
 * The bitmap is recycled immediately after Palette.generate() to limit transient memory.
 */
class AndroidDominantColorExtractor : DominantColorExtractor {

    private val log = Logger.withTag(TAG)

    override suspend fun extract(bytes: ByteArray): Long = withContext(Dispatchers.Default) {
        if (bytes.isEmpty()) return@withContext 0L
        try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext 0L
            val palette = Palette.from(bitmap).generate()
            val argb = palette.getDominantColor(Color.BLACK)
            bitmap.recycle()
            argb.toLong() and ARGB_MASK
        } catch (e: Exception) {
            log.e(e) { "extract failed" }
            0L
        }
    }

    private companion object {
        const val TAG = "DominantColorExtractor"
        const val ARGB_MASK = 0xFFFFFFFFL
    }
}

/*
 * §253 audit-trail postscript — cluster270 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (platform-facade, Phase 5.w.3 relocation; leaf 1 of 3
 * in the DominantColorExtractor 3-actual fan: Android concrete impl).
 *
 * UNIT KIND: platform-facade — concrete Android impl of the commonMain SPI
 * interface DominantColorExtractor (platform/src/commonMain/.../image/
 * DominantColorExtractor.kt line 60, swept in cluster146). This file declares
 * "class AndroidDominantColorExtractor : DominantColorExtractor" (line 22) and
 * overrides "suspend fun extract(bytes: ByteArray): Long".
 *
 * LIVE evidence:
 *  - The commonMain SPI is LIVE: its own cluster146 postscript verifies the
 *    "cover-art tinting in the library UI" consumer resolves the SPI via Koin
 *    (DominantColorExtractor.kt lines 50-51).
 *  - The per-platform binding is FORECAST-NOT-YET-LANDED for the rework graph:
 *    migration/phase-8-12-koin-wiring-plan.md line 97 specifies
 *    "single<DominantColorExtractor> { AndroidDominantColorExtractor() }" at
 *    step 8.11. No *ReworkModule.kt nor :platform di module yet binds this
 *    concrete actual by name (Grep for AndroidDominantColorExtractor across
 *    *.kt returns only this declaration plus the HighQualitySkiaImageDecoder
 *    cross-reference comment at nonAndroidMain line 255).
 *  - The legacy expect-class binding remains LIVE in the pre-rework graph:
 *    shared/.../di/PlatformModule.android.kt line 130 binds
 *    "single { DominantColorExtractor() }" against the legacy
 *    shared/.../core/image expect class — that LEGACY decl is the orphan-on-
 *    arrival of Phase 8-12, superseded by this relocated interface-plus-impl.
 *
 * Delta-axes (Android actual distinct approach):
 *  1. Platform API: androidx.palette.graphics.Palette clustering over a full
 *     BitmapFactory.decodeByteArray bitmap — the only actual using perceptual
 *     vibrant-pixel sampling rather than a 1-pixel spatial average.
 *  2. Threading: withContext(Dispatchers.Default) wrapping the decode-plus-
 *     Palette.generate work off the caller thread.
 *  3. Error handling: try-catch returning 0L on failure (Logger.e), and an
 *     early 0L for empty input or a null decode result — honors the SPI
 *     contract that 0L means "fall back to theme accent".
 *  4. DI binding mechanism: constructor-less; bound per-platform as
 *     single<DominantColorExtractor> { AndroidDominantColorExtractor() } (see
 *     plan line 97), in contrast to the legacy expect-class single resolution.
 *  5. Resource hygiene: bitmap.recycle() immediately after Palette.generate()
 *     to bound transient memory — an Android-only concern absent on the other
 *     two actuals.
 *  6. Behavioural-contract parity: confirmed — all three actuals return ARGB
 *     packed into the low 32 bits via ARGB_MASK 0xFFFFFFFFL and 0L on failure;
 *     the Palette path differs only in perceptual accuracy, not in contract.
 *
 * Nested-comment hazard check: this file has one legitimate KDoc opener (the
 * class-level KDoc at line 10) plus its closer; the appended block adds exactly
 * one opener and one closer, with no interior comment delimiters (no slash-star,
 * no star-slash, no slash-star-star anywhere in the prose). Balanced.
 */
