package me.manga.kira.core.util.notification

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace

/** Limits apply before pixels are allocated, not to a full-size bitmap resized afterwards. */
internal object NotificationCoverLimits {
    const val BODY_BYTES = 2 * 1024 * 1024
    const val SOURCE_AXIS = 8192
    const val SOURCE_PIXELS = 16_777_216L
    const val ICON_AXIS = 256
    const val ICON_BYTES = ICON_AXIS * ICON_AXIS * 4
    const val BUDGET_MILLIS = 10_000L
    const val REDIRECTS = 3
    const val RETAINED_COVERS = 2

    fun sampleSize(
        width: Int,
        height: Int,
    ): Int? {
        if (
            width !in 1..SOURCE_AXIS ||
            height !in 1..SOURCE_AXIS ||
            width.toLong() * height > SOURCE_PIXELS
        ) {
            return null
        }
        var sample = 1
        while (
            (width + sample - 1) / sample > ICON_AXIS ||
            (height + sample - 1) / sample > ICON_AXIS
        ) {
            sample *= 2
        }
        return sample
    }
}

/** A fixed-capacity body; only [size] bytes are passed to BitmapFactory. No trimming copy. */
internal data class NotificationCoverBytes(
    val bytes: ByteArray,
    val size: Int,
)

internal class NotificationCoverDecoder(
    private val decode: (ByteArray, Int, Int, BitmapFactory.Options) -> Bitmap? =
        BitmapFactory::decodeByteArray,
) {
    fun decode(body: NotificationCoverBytes): Bitmap? {
        val options = boundedOptions(body) ?: return null
        return decode(body.bytes, 0, body.size, options)?.takeIf {
            it.width in 1..NotificationCoverLimits.ICON_AXIS &&
                it.height in 1..NotificationCoverLimits.ICON_AXIS &&
                it.config == Bitmap.Config.ARGB_8888 &&
                it.allocationByteCount <= NotificationCoverLimits.ICON_BYTES
        }
    }

    private fun boundedOptions(body: NotificationCoverBytes): BitmapFactory.Options? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(body.bytes, 0, body.size, bounds)
        if (bounds.outMimeType !in setOf("image/png", "image/jpeg")) return null
        return NotificationCoverLimits.sampleSize(bounds.outWidth, bounds.outHeight)?.let(::pixelOptions)
    }

    private fun pixelOptions(sample: Int) =
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inDensity = 0
            inTargetDensity = 0
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
}
