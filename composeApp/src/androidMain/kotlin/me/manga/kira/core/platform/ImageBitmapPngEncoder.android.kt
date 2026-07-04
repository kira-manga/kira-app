package me.manga.kira.core.platform

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

/**
 * Android PNG encoder for the Reader share-current-page action (Reader parity item #5).
 *
 * Bridges the Compose [ImageBitmap] captured in `:ui` into the PNG `ByteArray` the
 * `AndroidScreenshotProvider.shareBitmapBytes` SPI consumes. Mirrors the legacy
 * `ScreenshotUtils.captureAndShare` PNG path (`bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)`).
 *
 * Returns `null` on any failure (e.g. an OOM on a very large webtoon strip) so the share quietly
 * no-ops rather than crashing.
 */
actual fun encodeImageBitmapToPng(bitmap: ImageBitmap): ByteArray? = runCatching {
    ByteArrayOutputStream().use { stream ->
        if (!bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)) return null
        stream.toByteArray()
    }
}.getOrNull()
