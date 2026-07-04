package me.manga.kira.core.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use as skiaUse

/**
 * Desktop (Skiko) PNG encoder for the Reader share-current-page action (Reader parity item #5).
 *
 * Bridges the Compose [ImageBitmap] captured in `:ui` into the PNG `ByteArray` the
 * `DesktopScreenshotProvider.shareBitmapBytes` SPI consumes (which on Desktop writes the bytes to a
 * file and copies the path to the clipboard). Uses Skiko's `Image.makeFromBitmap(...).encodeToData`
 * — the same Skia path the rework already relies on for its non-Android image pipeline.
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
