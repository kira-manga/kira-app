package me.manga.kira.core.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use as skiaUse

/**
 * iOS (Skiko) PNG encoder for the Reader share-current-page action (Reader parity item #5).
 *
 * Bridges the Compose [ImageBitmap] captured in `:ui` into the PNG `ByteArray` the
 * `IosScreenshotProvider.shareBitmapBytes` SPI consumes (which presents a
 * `UIActivityViewController`). Same Skiko path as the Desktop actual — Compose Multiplatform renders
 * via Skia on both non-Android targets.
 *
 * Returns `null` on any failure so the share quietly no-ops rather than crashing.
 */
actual fun encodeImageBitmapToPng(bitmap: ImageBitmap): ByteArray? = runCatching {
    val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
    try {
        image.encodeToData(EncodedImageFormat.PNG)?.skiaUse { it.bytes }
    } finally {
        image.close()
    }
}.getOrNull()
