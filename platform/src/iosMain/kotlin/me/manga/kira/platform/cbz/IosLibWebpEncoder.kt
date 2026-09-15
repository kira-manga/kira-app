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
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceRef
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
        page: ValidatedCbzPage,
        options: CbzEncodingOptions,
        native: IosCbzNativeCodec = IosCbzNativeCodec(),
        emit: suspend (ByteArray) -> Unit,
    ): CbzPageEncoding {
        currentCoroutineContext().ensureActive()
        return when (val admission = options.admit(page)) {
            CbzTranscodeAdmission.PreserveBudget ->
                CbzPageEncoding.PreserveOriginal(CbzPreservationReason.MEMORY_BUDGET)
            CbzTranscodeAdmission.PreserveWebpDimensions ->
                CbzPageEncoding.PreserveOriginal(CbzPreservationReason.WEBP_DIMENSIONS)
            is CbzTranscodeAdmission.Admitted ->
                encodeAdmitted(page.bytes, admission.plan, BandEncoding(options.quality, native, emit))
        }
    }

    private suspend fun encodeAdmitted(
        source: ByteArray,
        plan: CbzTranscodePlan,
        encoding: BandEncoding,
    ): CbzPageEncoding.Encoded {
        val trace = PageEncodingTrace(TimeSource.Monotonic.markNow(), source.size)
        // Owned CFData avoids an autoreleased NSData copy surviving past the current page.
        val data =
            source.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), source.size.toLong()) }
                ?: throw IOException("CBZ source buffer allocation failed")
        try {
            val cgSource =
                CGImageSourceCreateWithData(data, null) ?: throw IOException("CBZ image source creation failed")
            try {
                return encodeImageSource(cgSource, plan, encoding, trace)
            } finally {
                CFRelease(cgSource)
            }
        } finally {
            CFRelease(data)
        }
    }

    private suspend fun encodeImageSource(
        source: CGImageSourceRef,
        plan: CbzTranscodePlan,
        encoding: BandEncoding,
        trace: PageEncodingTrace,
    ): CbzPageEncoding.Encoded {
        currentCoroutineContext().ensureActive()
        val image = encoding.native.decode(source) ?: throw IOException("CBZ full source decode failed")
        return try {
            requireAdmittedImage(image, plan)
            encodeImage(image, plan, encoding, trace)
        } finally {
            encoding.native.releaseImage(image)
        }
    }

    private fun requireAdmittedImage(
        image: CGImageRef,
        plan: CbzTranscodePlan,
    ) {
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
        encoding: BandEncoding,
        trace: PageEncodingTrace,
    ): CbzPageEncoding.Encoded {
        currentCoroutineContext().ensureActive()
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: throw IOException("CBZ RGB color space creation failed")
        try {
            val context =
                encoding.native.createContext(plan, colorSpace)
                    ?: throw IOException("CBZ RGBA context allocation failed")
            try {
                val pixels = drawAdmittedImage(context, image, plan)
                val decodeMs = trace.mark.elapsedNow().inWholeMilliseconds
                val result = emitBands(pixels, plan, encoding)
                logEncodedPage(plan, trace, result, encoding.quality, decodeMs)
                return result
            } finally {
                encoding.native.releaseContext(context)
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }

    private fun logEncodedPage(
        plan: CbzTranscodePlan,
        trace: PageEncodingTrace,
        result: CbzPageEncoding.Encoded,
        quality: Int,
        decodeMs: Long,
    ) {
        BgDownloadLog.dlperf(
            "webpEncode",
            "enc" to "libwebp",
            "dims" to "${plan.width}x${plan.height}",
            "estimatedPeakBytes" to plan.estimatedPeakBytes,
            "bands" to result.bandCount,
            "srcKiB" to (trace.sourceSize / 1024),
            "decodeMs" to decodeMs,
            "totalMs" to trace.mark.elapsedNow().inWholeMilliseconds,
            "q" to quality,
        )
    }

    private suspend fun drawAdmittedImage(
        context: CGContextRef,
        image: CGImageRef,
        plan: CbzTranscodePlan,
    ): CPointer<UByteVar> {
        if (CGBitmapContextGetWidth(context) != plan.width.toULong() ||
            CGBitmapContextGetHeight(context) != plan.height.toULong() ||
            CGBitmapContextGetBytesPerRow(context) != plan.rgbaRowBytes.toULong()
        ) {
            throw IOException("CBZ native context differs from its admitted dimensions or stride")
        }
        currentCoroutineContext().ensureActive()
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, plan.width.toDouble(), plan.height.toDouble()), image)
        return CGBitmapContextGetData(context)?.reinterpret() ?: throw IOException("CBZ native context has no pixels")
    }

    private suspend fun emitBands(
        pixels: CPointer<UByteVar>,
        plan: CbzTranscodePlan,
        encoding: BandEncoding,
    ): CbzPageEncoding.Encoded {
        var top = 0
        var count = 0
        while (top < plan.height) {
            currentCoroutineContext().ensureActive()
            val height = minOf(plan.bandHeight, plan.height - top)
            // width/height/stride were checked before allocation; Long offsets cannot wrap here.
            val band =
                (pixels + top.toLong() * plan.rgbaRowBytes) ?: throw IOException("CBZ native band pointer is null")
            emitOneBand(band, plan, height, encoding)
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
        encoding: BandEncoding,
    ) {
        val encoded = encodeBand(rgba, plan, height, encoding)
        currentCoroutineContext().ensureActive()
        encoding.emit(encoded)
    }

    private fun encodeBand(
        rgba: CPointer<UByteVar>,
        plan: CbzTranscodePlan,
        height: Int,
        encoding: BandEncoding,
    ): ByteArray =
        memScoped {
            val output = alloc<CPointerVar<UByteVar>> { value = null }
            try {
                val size =
                    encoding.native.encodeBand(
                        IosRgbaBand(rgba, plan.width, height, plan.rgbaRowBytes),
                        encoding.quality,
                        output.ptr,
                    )
                val pointer = output.value ?: throw IOException("CBZ WebP encode returned no output")
                // size_t is unsigned: check it BEFORE narrowing to Long/Int or making a Kotlin copy.
                if (size == 0uL || size > plan.maxEncodedBandBytes.toULong() || size > Int.MAX_VALUE.toULong()) {
                    throw IOException("CBZ WebP output exceeds its admitted allowance or encoding failed")
                }
                encoding.native.copyEncoded(pointer, size.toInt())
            } finally {
                output.value?.let { encoding.native.freeEncoded(it) }
            }
        }

    private data class BandEncoding(
        val quality: Int,
        val native: IosCbzNativeCodec,
        val emit: suspend (ByteArray) -> Unit,
    )

    private data class PageEncodingTrace(
        val mark: TimeSource.Monotonic.ValueTimeMark,
        val sourceSize: Int,
    )
}
