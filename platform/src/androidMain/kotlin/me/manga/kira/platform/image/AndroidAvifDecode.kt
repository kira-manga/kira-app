package me.manga.kira.platform.image

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import java.nio.ByteBuffer
import org.aomedia.avif.android.AvifDecoder as AomAvifDecoder

/** Called only under [AvifDecoderCoil]'s native decoder mutex. */
@OptIn(ExperimentalCoilApi::class)
internal suspend fun decodeAndroidAvif(
    source: BufferedSource,
    options: Options,
    limits: AvifDecodeLimits,
): DecodeResult {
    val bytes = readAvifBytes(source, limits.maxEncodedBytes)
    limits.checkEncoded(bytes.size, AvifMemoryModel(bitmapBytesPerPixel = RGBA_BYTES, encodedCopies = ENCODED_COPIES))
    currentCoroutineContext().ensureActive()
    val input =
        ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }
    val info = AomAvifDecoder.Info()
    if (!AomAvifDecoder.getInfo(input, input.capacity(), info)) {
        throw AvifDecodeException("Unable to read AVIF image metadata.")
    }
    val sourceSize = AvifPixelSize(info.width, info.height)
    limits.checkSource(sourceSize)
    val output = avifDecodeSize(sourceSize, options.size, options.scale, options.precision, options.maxBitmapSize)
    val bitmapBytes = if (info.alphaPresent) RGBA_BYTES else RGB_565_BYTES
    val memory = AvifMemoryModel(bitmapBytesPerPixel = bitmapBytes, encodedCopies = ENCODED_COPIES)
    limits.admit(bytes.size, sourceSize, output, memory)
    return decodeNativeAvifPixels(input, sourceSize, output, info.alphaPresent, limits)
}

private suspend fun decodeNativeAvifPixels(
    input: ByteBuffer,
    source: AvifPixelSize,
    output: AvifPixelSize,
    hasAlpha: Boolean,
    limits: AvifDecodeLimits,
): DecodeResult {
    val context = currentCoroutineContext()
    context.ensureActive()
    val bitmap = createOutputBitmap(output, hasAlpha, limits.maxOutputBytes)
    var handedOff = false
    try {
        input.rewind()
        if (!AomAvifDecoder.decode(input, input.capacity(), bitmap, NATIVE_THREADS)) {
            throw AvifDecodeException("Unable to decode AVIF image pixels.")
        }
        context.ensureActive()
        val result = DecodeResult(image = bitmap.asImage(), isSampled = output.isSmallerThan(source))
        handedOff = true
        return result
    } finally {
        if (!handedOff) bitmap.recycle()
    }
}

private fun createOutputBitmap(size: AvifPixelSize, hasAlpha: Boolean, maxBytes: Long): Bitmap {
    if (size.width > NATIVE_MAX_DIMENSION || size.height > NATIVE_MAX_DIMENSION) {
        throw AvifDecodeException("AVIF target exceeds the native scaling dimension limit.")
    }
    val config = if (hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
    val bitmap = createBitmap(size.width, size.height, config)
    if (bitmap.allocationByteCount.toLong() > maxBytes) {
        bitmap.recycle()
        throw AvifDecodeException("AVIF bitmap allocation exceeds the output limit.")
    }
    return bitmap
}

private const val ENCODED_COPIES = 3
private const val RGBA_BYTES = 4
private const val RGB_565_BYTES = 2
private const val NATIVE_MAX_DIMENSION = 32_768
private const val NATIVE_THREADS = 1
