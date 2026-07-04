package me.manga.kira.platform.image

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Desktop actual for [DominantColorExtractor]. `ImageIO.read` decodes the bytes, then
 * `getScaledInstance(1, 1, SCALE_AREA_AVERAGING)` produces the spatial-average pixel — same
 * shape as the iOS CoreGraphics path. `TYPE_INT_ARGB` for the backing `BufferedImage` so the
 * `getRGB(0, 0)` return value is already in the packed ARGB layout this interface specifies.
 *
 * Verbatim port from legacy `:shared/desktopMain/.../core/image/DominantColorExtractor.desktop.kt`.
 * Same caveat as iOS — less perceptually accurate than Android's Palette, but adequate for the
 * UI accents that consume this.
 */
class DesktopDominantColorExtractor : DominantColorExtractor {

    private val log = Logger.withTag(TAG)

    override suspend fun extract(bytes: ByteArray): Long = withContext(Dispatchers.Default) {
        if (bytes.isEmpty()) return@withContext 0L
        try {
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@withContext 0L
            val scaled = image.getScaledInstance(
                SCALE_TARGET_PX,
                SCALE_TARGET_PX,
                Image.SCALE_AREA_AVERAGING,
            )
            val buffered = BufferedImage(SCALE_TARGET_PX, SCALE_TARGET_PX, BufferedImage.TYPE_INT_ARGB)
            val g = buffered.createGraphics()
            try {
                g.drawImage(scaled, 0, 0, null)
            } finally {
                g.dispose()
            }
            val argb = buffered.getRGB(0, 0)
            argb.toLong() and ARGB_MASK
        } catch (e: Exception) {
            log.e(e) { "extract failed" }
            0L
        }
    }

    private companion object {
        const val TAG = "DominantColorExtractor"
        const val SCALE_TARGET_PX: Int = 1
        const val ARGB_MASK: Long = 0xFFFFFFFFL
    }
}

/*
 * §253 audit-trail postscript — cluster270 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (platform-facade, Phase 5.w.3 relocation; leaf 2 of 3
 * in the DominantColorExtractor 3-actual fan: Desktop concrete impl).
 *
 * UNIT KIND: platform-facade — concrete Desktop (JVM) impl of the commonMain SPI
 * interface DominantColorExtractor (platform/src/commonMain/.../image/
 * DominantColorExtractor.kt line 60, swept in cluster146). This file declares
 * "class DesktopDominantColorExtractor : DominantColorExtractor" (line 21) and
 * overrides "suspend fun extract(bytes: ByteArray): Long".
 *
 * LIVE evidence:
 *  - The commonMain SPI is LIVE: the cover-art-tint consumer resolves it via Koin
 *    per the cluster146 postscript on DominantColorExtractor.kt (lines 50-51).
 *  - The per-platform Desktop binding is FORECAST-NOT-YET-LANDED for the rework
 *    graph: migration/phase-8-12-koin-wiring-plan.md lines 104-106 ("platformModule
 *    — Desktop actual. Same shape. Construct with no Context.") mirror the Android
 *    8.11 binding for the Desktop actual. Grep for DesktopDominantColorExtractor
 *    across *.kt returns only this declaration — no *ReworkModule.kt nor :platform
 *    di module binds it by name yet.
 *  - The legacy expect-class binding remains LIVE in the pre-rework graph:
 *    shared/.../di/PlatformModule.desktop.kt line 114 binds
 *    "single { DominantColorExtractor() }" against the legacy
 *    shared/.../core/image expect class — that LEGACY decl is superseded by this
 *    relocated interface-plus-impl and becomes orphaned at Phase 8-12 wiring.
 *
 * Delta-axes (Desktop actual distinct approach):
 *  1. Platform API: javax.imageio.ImageIO.read decode, then
 *     getScaledInstance(1, 1, Image.SCALE_AREA_AVERAGING) drawn into a
 *     TYPE_INT_ARGB BufferedImage; getRGB(0, 0) yields packed ARGB directly.
 *  2. Threading: withContext(Dispatchers.Default) — same off-thread shape as the
 *     Android actual.
 *  3. Error handling: try-catch returning 0L (Logger.e) plus early 0L for empty
 *     input or a null ImageIO.read result; matches the SPI 0L-on-failure contract.
 *  4. DI binding mechanism: constructor-less; bound per-platform as
 *     single<DominantColorExtractor> { DesktopDominantColorExtractor() } following
 *     the Desktop-actual section of the wiring plan.
 *  5. Resource hygiene: Graphics2D obtained via createGraphics() is released in a
 *     finally block (g.dispose()) — the Desktop analogue of Android's recycle().
 *  6. Behavioural-contract parity: confirmed — spatial-average sampling identical
 *     in shape to the iOS 1-pixel CoreGraphics path; same ARGB_MASK 0xFFFFFFFFL
 *     packing and 0L failure sentinel. Less perceptually accurate than Android
 *     Palette but contract-identical (documented caveat, file lines 17-19).
 *
 * Nested-comment hazard check: this file has one legitimate KDoc opener (the
 * class-level KDoc at line 11) plus its closer; the appended block adds exactly
 * one opener and one closer, with no interior comment delimiters (no slash-star,
 * no star-slash, no slash-star-star anywhere in the prose). Balanced.
 */
