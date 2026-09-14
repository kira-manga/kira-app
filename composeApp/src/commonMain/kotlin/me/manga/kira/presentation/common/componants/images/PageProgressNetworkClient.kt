package me.manga.kira.presentation.common.componants.images

import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.PageProgressAttempt
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.buffer
import okio.use
import kotlin.time.TimeSource

/**
 * Read the execution token before OkHttp/Ktor discard Coil Extras. Untagged images bypass the
 * decorator entirely, even when a cover and a Reader page use the same URL. Request/cache/header
 * policy and response metadata belong to the existing client/fetcher and are left unchanged.
 */
internal class PageProgressNetworkClient(
    private val delegate: NetworkClient,
    private val clock: TimeSource = TimeSource.Monotonic,
) : NetworkClient {
    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (NetworkResponse) -> T,
    ): T {
        val attempt = request.extras[pageProgressAttemptKey] ?: return delegate.executeRequest(request, block)
        return delegate.executeRequest(request) { response ->
            val body = response.body
            val wrapped =
                body?.let {
                    PageProgressResponseBody(it, attempt, response.headers["content-length"]?.toLongOrNull(), clock)
                }
            block(if (wrapped == null) response else response.copy(body = wrapped))
        }
    }
}

private class PageProgressResponseBody(
    private val delegate: NetworkResponseBody,
    private val attempt: PageProgressAttempt,
    private val contentLength: Long?,
    private val clock: TimeSource,
) : NetworkResponseBody {
    override suspend fun writeTo(sink: BufferedSink) {
        val counter = PageProgressByteCounter(attempt, contentLength, clock)
        val counting =
            object : Sink by sink {
                override fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    sink.write(source, byteCount)
                    counter.wrote(byteCount)
                }
            }.buffer()
        delegate.writeTo(counting)
        // Drain our buffer without flushing or closing the caller's sink. On failure/cancellation
        // do not flush a partial buffer; native client cancellation and body.close remain in charge.
        counting.emit()
        attempt.report(PageDownloadProgress.Decoding)
    }

    override suspend fun writeTo(
        fileSystem: FileSystem,
        path: Path,
    ) {
        // Coil supplies the destination. Only tagged requests give up Ktor/JVM's FileChannel fast
        // path; using the sink overload counts bytes on both adapters without extra pump coroutines.
        fileSystem.sink(path).buffer().use { writeTo(it) }
    }

    override fun close() = delegate.close()
}

private class PageProgressByteCounter(
    private val attempt: PageProgressAttempt,
    private val contentLength: Long?,
    private val clock: TimeSource,
) {
    private var written = 0L
    private var lastFraction = -1
    private var lastMark = clock.markNow()

    fun wrote(byteCount: Long) {
        written += byteCount
        val fraction = contentLength?.takeIf { it > 0L }?.let { (written.toFloat() / it).coerceIn(0f, 1f) }
        val scaledFraction = fraction?.let { (it * FRACTION_SCALE).toInt() }
        val advanced = scaledFraction != null && scaledFraction - lastFraction >= THROTTLE_FRACTION
        if (advanced || lastMark.elapsedNow().inWholeMilliseconds >= THROTTLE_MILLIS) {
            attempt.report(PageDownloadProgress.InProgress(fraction))
            lastMark = clock.markNow()
            if (scaledFraction != null) lastFraction = scaledFraction
        }
    }
}

private const val FRACTION_SCALE = 10_000f
private const val THROTTLE_FRACTION = 100
private const val THROTTLE_MILLIS = 50L
