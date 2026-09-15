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

/** Called only under [withAndroidAvifPermit]; native helpers never acquire a nested permit. */
@OptIn(ExperimentalCoilApi::class)
internal suspend fun decodeAndroidAvif(
    source: BufferedSource,
    options: Options,
    limits: AvifDecodeLimits,
): DecodeResult {
    val bytes = readAvifBytes(source, limits.maxEncodedBytes)
    val parsePixelLimit = androidAvifNativePixelLimit(limits, bytes.size)
    currentCoroutineContext().ensureActive()
    val input =
        ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }
    val info = readNativeAvifInfo(input, limits, parsePixelLimit)
    val plan = planNativeAvifDecode(info, options, limits, bytes.size)
    return decodeNativeAvifPixels(input, plan, limits)
}

private suspend fun readNativeAvifInfo(
    input: ByteBuffer,
    limits: AvifDecodeLimits,
    pixelLimit: Int,
): AomAvifDecoder.Info {
    val info = AomAvifDecoder.Info()
    val accepted =
        AomAvifDecoder.getInfoWithLimits(
            input,
            input.capacity(),
            info,
            pixelLimit,
            minOf(limits.maxSourceDimension, ANDROID_AVIF_NATIVE_MAX_DIMENSION),
        )
    currentCoroutineContext().ensureActive()
    if (!accepted) throw AvifDecodeException("AVIF metadata was rejected by the bounded native decoder.")
    return info
}

@OptIn(ExperimentalCoilApi::class)
private fun planNativeAvifDecode(
    info: AomAvifDecoder.Info,
    options: Options,
    limits: AvifDecodeLimits,
    encodedBytes: Int,
): AndroidAvifDecodePlan {
    val sourceSize = AvifPixelSize(info.width, info.height)
    limits.checkSource(sourceSize)
    val output = avifDecodeSize(sourceSize, options.size, options.scale, options.precision, options.maxBitmapSize)
    val bitmapBytes = if (info.alphaPresent) RGBA_BYTES else RGB_565_BYTES
    val pixelLimit = androidAvifNativePixelLimit(limits, encodedBytes, output, bitmapBytes)
    if (sourceSize.pixels > pixelLimit) {
        throw AvifDecodeException("AVIF source exceeds the output-reserved working-memory estimate.")
    }
    return AndroidAvifDecodePlan(sourceSize, output, info.alphaPresent, pixelLimit)
}

private suspend fun decodeNativeAvifPixels(
    input: ByteBuffer,
    plan: AndroidAvifDecodePlan,
    limits: AvifDecodeLimits,
): DecodeResult {
    val context = currentCoroutineContext()
    context.ensureActive()
    val bitmap = createOutputBitmap(plan.output, plan.hasAlpha, limits.maxOutputBytes)
    var handedOff = false
    try {
        input.rewind()
        val accepted = decodeWithNativeLimits(input, bitmap, plan.nativePixelLimit, limits.maxSourceDimension)
        context.ensureActive()
        if (!accepted) throw AvifDecodeException("AVIF pixels were rejected by the bounded native decoder.")
        val result = DecodeResult(image = bitmap.asImage(), isSampled = plan.output.isSmallerThan(plan.source))
        handedOff = true
        return result
    } finally {
        if (!handedOff) bitmap.recycle()
    }
}

private fun decodeWithNativeLimits(
    input: ByteBuffer,
    bitmap: Bitmap,
    pixelLimit: Int,
    dimensionLimit: Int,
): Boolean =
    AomAvifDecoder.decodeWithLimits(
        input,
        input.capacity(),
        bitmap,
        NATIVE_THREADS,
        pixelLimit,
        minOf(dimensionLimit, ANDROID_AVIF_NATIVE_MAX_DIMENSION),
    )

private fun createOutputBitmap(
    size: AvifPixelSize,
    hasAlpha: Boolean,
    maxBytes: Long,
): Bitmap {
    if (size.width > ANDROID_AVIF_NATIVE_MAX_DIMENSION || size.height > ANDROID_AVIF_NATIVE_MAX_DIMENSION) {
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

private data class AndroidAvifDecodePlan(
    val source: AvifPixelSize,
    val output: AvifPixelSize,
    val hasAlpha: Boolean,
    val nativePixelLimit: Int,
)

private const val RGBA_BYTES = 4
private const val RGB_565_BYTES = 2
private const val NATIVE_THREADS = 1
