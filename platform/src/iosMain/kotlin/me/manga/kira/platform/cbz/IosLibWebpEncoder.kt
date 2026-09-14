package me.manga.kira.platform.cbz

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.platform.download.BgDownloadLog
import me.manga.kira.platform.media.PageImageMetadata
import okio.IOException
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextGetBytesPerRow
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGBitmapContextGetHeight
import platform.CoreGraphics.CGBitmapContextGetWidth
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.ImageIO.CGImageSourceCreateWithData
import kotlin.time.TimeSource

/**
 * ImageIO/CoreGraphics + libwebp encoding of an already-validated encoded snapshot. Metadata comes
 * from the writer's bounded PageMediaInspector on these same bytes. [CbzTranscodeBudget] reserves
 * source copies, full decoded/native buffers, codec workspace and ONE output band before any full
 * ImageIO decode or RGBA context allocation. A denied valid page is explicitly preserved, not tried
 * repeatedly at full resolution. The admission estimate is not a hard native allocator/RSS bound;
 * low-memory iPhone foreground/background profiling remains required.
 *
 * Each encoded band is size-checked, copied and WebPFree'd before emission; the writer streams it to
 * its staged ZIP before the next band starts. All errors/cancellation propagate and native handles
 * are released in finally. The premultiplied-RGBA opaque-page color behavior is unchanged.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosLibWebpEncoder {
    suspend fun encodeValidatedPage(
        source: ByteArray,
        metadata: PageImageMetadata,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long,
        native: IosCbzNativeCodec = IosCbzNativeCodec(),
        emit: suspend (ByteArray) -> Unit,
    ): CbzPageEncoding {
        currentCoroutineContext().ensureActive()
        val admission = CbzTranscodeBudget.admit(metadata.width, metadata.height, source.size.toLong(), maxHeight, maxMemoryBytes)
        return when (admission) {
            CbzTranscodeAdmission.PreserveBudget -> CbzPageEncoding.PreserveOriginal(CbzPreservationReason.MEMORY_BUDGET)
            CbzTranscodeAdmission.PreserveWebpDimensions -> CbzPageEncoding.PreserveOriginal(CbzPreservationReason.WEBP_DIMENSIONS)
            is CbzTranscodeAdmission.Admitted -> encodeAdmitted(source, admission.plan, quality, native, emit)
        }
    }

    private suspend fun encodeAdmitted(
        source: ByteArray,
        plan: CbzTranscodePlan,
        quality: Int,
        native: IosCbzNativeCodec,
        emit: suspend (ByteArray) -> Unit,
    ): CbzPageEncoding.Encoded {
        val mark = TimeSource.Monotonic.markNow()
        // Owned CFData avoids an autoreleased NSData copy surviving past the current page.
        val data = source.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), source.size.toLong()) }
            ?: throw IOException("CBZ source buffer allocation failed")
        try {
            val cgSource = CGImageSourceCreateWithData(data, null) ?: throw IOException("CBZ image source creation failed")
            try {
                currentCoroutineContext().ensureActive()
                val image = native.decode(cgSource) ?: throw IOException("CBZ full source decode failed")
                try {
                    requireAdmittedImage(image, plan)
                    return encodeImage(image, plan, quality, native, mark, source.size, emit)
                } finally {
                    native.releaseImage(image)
                }
            } finally {
                CFRelease(cgSource)
            }
        } finally {
            CFRelease(data)
        }
    }

    private fun requireAdmittedImage(image: CGImageRef, plan: CbzTranscodePlan) {
        if (CGImageGetWidth(image) != plan.width.toULong() || CGImageGetHeight(image) != plan.height.toULong()) {
            throw IOException("CBZ decoded dimensions differ from validated metadata")
        }
        val contextBytes = plan.rgbaRowBytes.toLong() * plan.height
        val decodedAllowance = plan.sourceAllocationAllowanceBytes - contextBytes
        val rowBytes = CGImageGetBytesPerRow(image)
        if (rowBytes == 0uL || rowBytes > decodedAllowance.toULong() / plan.height.toULong()) {
            throw IOException("CBZ decoded source exceeds its admitted native allocation")
        }
    }

    private suspend fun encodeImage(
        image: CGImageRef,
        plan: CbzTranscodePlan,
        quality: Int,
        native: IosCbzNativeCodec,
        mark: TimeSource.Monotonic.ValueTimeMark,
        sourceSize: Int,
        emit: suspend (ByteArray) -> Unit,
    ): CbzPageEncoding.Encoded {
        currentCoroutineContext().ensureActive()
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: throw IOException("CBZ RGB color space creation failed")
        try {
            val context = native.createContext(plan, colorSpace) ?: throw IOException("CBZ RGBA context allocation failed")
            try {
                if (CGBitmapContextGetWidth(context) != plan.width.toULong() ||
                    CGBitmapContextGetHeight(context) != plan.height.toULong() ||
                    CGBitmapContextGetBytesPerRow(context) != plan.rgbaRowBytes.toULong()
                ) throw IOException("CBZ native context differs from its admitted dimensions or stride")
                currentCoroutineContext().ensureActive()
                CGContextDrawImage(context, CGRectMake(0.0, 0.0, plan.width.toDouble(), plan.height.toDouble()), image)
                val pixels: CPointer<UByteVar> = CGBitmapContextGetData(context)?.reinterpret()
                    ?: throw IOException("CBZ native context has no pixels")
                val decodeMs = mark.elapsedNow().inWholeMilliseconds
                val result = emitBands(pixels, plan, quality, native, emit)
                BgDownloadLog.dlperf(
                    "webpEncode",
                    "enc" to "libwebp",
                    "dims" to "${plan.width}x${plan.height}",
                    "estimatedPeakBytes" to plan.estimatedPeakBytes,
                    "bands" to result.bandCount,
                    "srcKiB" to (sourceSize / 1024),
                    "decodeMs" to decodeMs,
                    "totalMs" to mark.elapsedNow().inWholeMilliseconds,
                    "q" to quality,
                )
                return result
            } finally {
                native.releaseContext(context)
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }

    private suspend fun emitBands(
        pixels: CPointer<UByteVar>,
        plan: CbzTranscodePlan,
        quality: Int,
        native: IosCbzNativeCodec,
        emit: suspend (ByteArray) -> Unit,
    ): CbzPageEncoding.Encoded {
        var top = 0
        var count = 0
        while (top < plan.height) {
            currentCoroutineContext().ensureActive()
            val height = minOf(plan.bandHeight, plan.height - top)
            // width/height/stride were checked before allocation; Long offsets cannot wrap here.
            val band = (pixels + top.toLong() * plan.rgbaRowBytes) ?: throw IOException("CBZ native band pointer is null")
            emitOneBand(band, plan, height, quality, native, emit)
            currentCoroutineContext().ensureActive()
            count++
            top += height
        }
        return CbzPageEncoding.Encoded(count)
    }

    /** Keep the encoded array out of the outer loop's suspension state before its next encode. */
    private suspend fun emitOneBand(
        rgba: CPointer<UByteVar>,
        plan: CbzTranscodePlan,
        height: Int,
        quality: Int,
        native: IosCbzNativeCodec,
        emit: suspend (ByteArray) -> Unit,
    ) {
        val encoded = encodeBand(rgba, plan, height, quality, native)
        currentCoroutineContext().ensureActive()
        emit(encoded)
    }

    private fun encodeBand(
        rgba: CPointer<UByteVar>,
        plan: CbzTranscodePlan,
        height: Int,
        quality: Int,
        native: IosCbzNativeCodec,
    ): ByteArray = memScoped {
        val output = alloc<CPointerVar<UByteVar>> { value = null }
        try {
            val size = native.encodeBand(rgba, plan.width, height, plan.rgbaRowBytes, quality, output.ptr)
            val pointer = output.value ?: throw IOException("CBZ WebP encode returned no output")
            // size_t is unsigned: check it BEFORE narrowing to Long/Int or making a Kotlin copy.
            if (size == 0uL || size > plan.maxEncodedBandBytes.toULong() || size > Int.MAX_VALUE.toULong()) {
                throw IOException("CBZ WebP output exceeds its admitted allowance or encoding failed")
            }
            native.copyEncoded(pointer, size.toInt())
        } finally {
            output.value?.let { native.freeEncoded(it) }
        }
    }
}
