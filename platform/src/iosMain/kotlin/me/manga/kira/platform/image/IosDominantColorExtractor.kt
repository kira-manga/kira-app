package me.manga.kira.platform.image

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage

/**
 * iOS actual for [DominantColorExtractor]. Decodes [UIImage] then draws into a 1×1
 * CoreGraphics RGBA bitmap context — the single resulting pixel is, by construction, the
 * spatial average of the source image, which is the cheapest passable approximation of a
 * dominant color on iOS without pulling in a clustering library. Less perceptually accurate
 * than the Android Palette path but acceptable for the UI accents that consume this
 * (cover-art tint behind library covers, splash transition).
 *
 * Verbatim port from legacy `:shared/iosMain/.../core/image/DominantColorExtractor.ios.kt`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDominantColorExtractor : DominantColorExtractor {

    private val log = Logger.withTag(TAG)

    override suspend fun extract(bytes: ByteArray): Long {
        if (bytes.isEmpty()) return 0L
        return try {
            val image = bytes.usePinned { pinned ->
                val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                UIImage.imageWithData(data)
            } ?: return 0L

            val cgImage = image.CGImage ?: return 0L
            val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return 0L

            val pixel = UByteArray(RGBA_BYTES_PER_PIXEL)
            try {
                pixel.usePinned { pinned ->
                    val ctx = CGBitmapContextCreate(
                        data = pinned.addressOf(0),
                        width = SCALE_TARGET_PX,
                        height = SCALE_TARGET_PX,
                        bitsPerComponent = BITS_PER_CHANNEL,
                        bytesPerRow = RGBA_BYTES_PER_PIXEL.toULong(),
                        space = colorSpace,
                        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
                    ) ?: return@usePinned
                    try {
                        CGContextDrawImage(
                            ctx,
                            CGRectMake(0.0, 0.0, SCALE_TARGET_PX.toDouble(), SCALE_TARGET_PX.toDouble()),
                            cgImage,
                        )
                    } finally {
                        CGContextRelease(ctx)
                    }
                }
            } finally {
                CGColorSpaceRelease(colorSpace)
            }
            val r = pixel[0].toInt() and BYTE_MASK
            val g = pixel[1].toInt() and BYTE_MASK
            val b = pixel[2].toInt() and BYTE_MASK
            val a = pixel[3].toInt() and BYTE_MASK
            packArgb(a, r, g, b)
        } catch (e: Exception) {
            log.e(e) { "extract failed" }
            0L
        }
    }

    private fun packArgb(a: Int, r: Int, g: Int, b: Int): Long {
        val v = ((a and BYTE_MASK) shl A_SHIFT) or
            ((r and BYTE_MASK) shl R_SHIFT) or
            ((g and BYTE_MASK) shl G_SHIFT) or
            (b and BYTE_MASK)
        return v.toLong() and ARGB_MASK
    }

    private companion object {
        const val TAG = "DominantColorExtractor"
        const val SCALE_TARGET_PX: ULong = 1u
        const val BITS_PER_CHANNEL: ULong = 8u
        const val RGBA_BYTES_PER_PIXEL: Int = 4
        const val BYTE_MASK: Int = 0xFF
        const val A_SHIFT: Int = 24
        const val R_SHIFT: Int = 16
        const val G_SHIFT: Int = 8
        const val ARGB_MASK: Long = 0xFFFFFFFFL
    }
}

/*
 * §253 audit-trail postscript — cluster270 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (platform-facade, Phase 5.w.3 relocation; leaf 3 of 3
 * CLOSER in the DominantColorExtractor 3-actual fan: iOS concrete impl).
 *
 * UNIT KIND: platform-facade — concrete iOS (Kotlin/Native) impl of the commonMain
 * SPI interface DominantColorExtractor (platform/src/commonMain/.../image/
 * DominantColorExtractor.kt line 60, swept in cluster146). This file declares
 * "class IosDominantColorExtractor : DominantColorExtractor" (line 28) and
 * overrides "suspend fun extract(bytes: ByteArray): Long".
 *
 * LIVE evidence:
 *  - The commonMain SPI is LIVE: the cover-art-tint consumer resolves it via Koin
 *    per the cluster146 postscript on DominantColorExtractor.kt (lines 50-51).
 *  - The per-platform iOS binding is FORECAST-NOT-YET-LANDED for the rework graph:
 *    migration/phase-8-12-koin-wiring-plan.md lines 100-102 ("platformModule — iOS
 *    actual. Same shape, but constructors take no Context") mirror the Android 8.11
 *    binding for the iOS actual. Grep for IosDominantColorExtractor across *.kt
 *    returns only this declaration — no *ReworkModule.kt nor :platform di module
 *    binds it by name yet.
 *  - The legacy expect-class binding remains LIVE in the pre-rework graph:
 *    shared/.../di/PlatformModule.ios.kt line 114 binds
 *    "single { DominantColorExtractor() }" against the legacy
 *    shared/.../core/image expect class — that LEGACY decl is superseded by this
 *    relocated interface-plus-impl and becomes orphaned at Phase 8-12 wiring.
 *
 * Delta-axes (iOS actual distinct approach):
 *  1. Platform API: CoreGraphics cinterop — UIImage.imageWithData decode, then
 *     CGBitmapContextCreate of a 1x1 kCGImageAlphaPremultipliedLast RGBA context
 *     and CGContextDrawImage; the single resulting pixel is the spatial average.
 *  2. Threading: NO withContext wrapper — extract is a plain suspend body running
 *     on the caller's dispatcher. This is the one actual that does NOT hop to
 *     Dispatchers.Default, a deliberate divergence from Android/Desktop (the
 *     cinterop pinned-buffer work is synchronous and short).
 *  3. Error handling: try-catch returning 0L (Logger.e) plus early 0L guards for
 *     empty input, null UIImage, null CGImage, and null colorSpace; honors the
 *     SPI 0L-on-failure contract.
 *  4. DI binding mechanism: constructor-less; bound per-platform as
 *     single<DominantColorExtractor> { IosDominantColorExtractor() } per the
 *     iOS-actual section of the wiring plan.
 *  5. Memory model: kotlinx.cinterop usePinned for both the input ByteArray
 *     (NSData.create over its address) and the 4-byte UByteArray pixel sink;
 *     manual packArgb assembles ARGB from the RGBA channel bytes — the only
 *     actual that packs by hand rather than reading a pre-packed pixel.
 *  6. Behavioural-contract parity: confirmed — packArgb masks each channel to a
 *     byte and ANDs the result with ARGB_MASK 0xFFFFFFFFL, producing exactly the
 *     same low-32-bit ARGB layout as Android getDominantColor and Desktop
 *     getRGB; 0L failure sentinel identical across all three.
 *
 * Nested-comment hazard check: this file has one legitimate KDoc opener (the
 * class-level KDoc at line 17) plus its closer; the appended block adds exactly
 * one opener and one closer, with no interior comment delimiters (no slash-star,
 * no star-slash, no slash-star-star anywhere in the prose). Balanced.
 */
