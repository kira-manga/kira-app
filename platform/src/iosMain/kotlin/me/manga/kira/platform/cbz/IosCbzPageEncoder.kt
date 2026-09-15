package me.manga.kira.platform.cbz

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A normal return represents the entire input, never merely the bands written before a failure. */
internal fun interface IosCbzPageEncoder {
    suspend fun encode(
        page: ValidatedCbzPage,
        options: CbzEncodingOptions,
        emit: suspend (extension: String, bytes: ByteArray) -> Unit,
    )
}

internal object DefaultIosCbzPageEncoder : IosCbzPageEncoder by IosCbzPageTranscoder()

/** The toggle selects an encoder, never a more permissive validation or error-recovery policy. */
internal class IosCbzPageTranscoder(
    private val useLibWebp: Boolean = IosWebpEncoderFlags.USE_LIBWEBP,
    private val native: IosCbzNativeCodec = IosCbzNativeCodec(),
) : IosCbzPageEncoder {
    override suspend fun encode(
        page: ValidatedCbzPage,
        options: CbzEncodingOptions,
        emit: suspend (extension: String, bytes: ByteArray) -> Unit,
    ) {
        val result =
            if (useLibWebp) {
                IosLibWebpEncoder.encodeValidatedPage(page, options, native) { emit("webp", it) }
            } else {
                SkiaWebpEncoder.encodeValidatedPage(page, options) { emit("webp", it) }
            }
        currentCoroutineContext().ensureActive()
        when (result) {
            is CbzPageEncoding.Encoded -> check(result.bandCount > 0) { "CBZ codec produced no bands" }
            is CbzPageEncoding.PreserveOriginal -> emit(page.metadata.format.extension, page.bytes)
        }
    }
}
