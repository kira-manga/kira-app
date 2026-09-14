package me.manga.kira.platform.cbz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect

/**
 * Decodes the exact bounded snapshot accepted by the media inspector, never a reopened source path.
 * Separate from the optimized App64 decoder so its region/AVIF behavior and ownership stay unchanged.
 */
open class AndroidCbzImageDecoder {
    open fun decode(source: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(
            source,
            0,
            source.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )

    open fun crop(parent: Bitmap, region: Rect): Bitmap =
        Bitmap.createBitmap(parent, region.left, region.top, region.width(), region.height())
}
