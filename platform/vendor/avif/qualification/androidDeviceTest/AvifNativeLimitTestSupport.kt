package me.manga.kira.platform.image

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.assertEquals

/** Preparation only: move into the admitted platform device-test source set with the patched AAR. */
internal object AvifNativeLimitFixture {
    const val SOURCE_WIDTH = 320
    const val SOURCE_HEIGHT = 640
    const val SOURCE_PIXELS = SOURCE_WIDTH * SOURCE_HEIGHT
    const val DECLARED_EDGE = 32
    const val DECLARED_PIXELS = DECLARED_EDGE * DECLARED_EDGE
    const val OUTPUT_EDGE = 64
    const val NATIVE_AXIS_LIMIT = 32_768
    const val NATIVE_PIXEL_LIMIT = 16_384 * 16_384
    const val TALL_WIDTH = 32
    const val TALL_HEIGHT = 352

    /** Change just the fixed fixture's ispe dimensions, never its actual 320x640 AV1 payload. */
    fun declaredSmall(): ByteArray {
        val bytes = AvifTestFixtures.regular()
        assertEquals(REGULAR_BYTES, bytes.size)
        assertEquals(REGULAR_SHA256, bytes.sha256())
        val editor = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(ISPE_BOX_BYTES, editor.getInt(ISPE_BOX_OFFSET))
        assertEquals(ISPE_TYPE, editor.getInt(ISPE_TYPE_OFFSET))
        assertEquals(SOURCE_WIDTH, editor.getInt(WIDTH_OFFSET))
        assertEquals(SOURCE_HEIGHT, editor.getInt(HEIGHT_OFFSET))
        editor.putInt(WIDTH_OFFSET, DECLARED_EDGE)
        editor.putInt(HEIGHT_OFFSET, DECLARED_EDGE)
        assertEquals(DECLARED_SMALL_SHA256, bytes.sha256())
        return bytes
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private const val REGULAR_BYTES = 10_775
    private const val REGULAR_SHA256 = "4bc2db8cd43917b79f8c4b2ec4f0e4e5e7588336aef8dfbeedbd4326b6fd3a61"
    private const val DECLARED_SMALL_SHA256 = "82e85ff73dc302e51665ea1ee1b713a63a44ed660cff994be1c0366eb2d7eb31"
    private const val ISPE_BOX_BYTES = 20
    private const val ISPE_TYPE = 0x69737065
    private const val ISPE_BOX_OFFSET = 209
    private const val ISPE_TYPE_OFFSET = 213
    private const val WIDTH_OFFSET = 221
    private const val HEIGHT_OFFSET = 225
}

internal fun nativeAvifInput(bytes: ByteArray): ByteBuffer =
    ByteBuffer.allocateDirect(bytes.size).apply {
        put(bytes)
        flip()
    }

internal fun withNativeAvifBitmap(
    width: Int = AvifNativeLimitFixture.OUTPUT_EDGE,
    height: Int = AvifNativeLimitFixture.OUTPUT_EDGE,
    block: (Bitmap) -> Unit,
) {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.TRANSPARENT)
        block(bitmap)
    } finally {
        bitmap.recycle()
    }
}

internal fun assertNativeAvifPixelsDrawn(bitmap: Bitmap) {
    assertEquals(OPAQUE_ALPHA, Color.alpha(bitmap.getPixel(0, 0)))
}

private const val OPAQUE_ALPHA = 255
