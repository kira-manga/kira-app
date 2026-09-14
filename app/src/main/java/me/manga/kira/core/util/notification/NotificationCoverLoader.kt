package me.manga.kira.core.util.notification

import android.graphics.Bitmap
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android-local optional cover lifetime. [post] is synchronous and must not cache the bitmap.
 * The caller owns this entire operation; ancestor cancellation is never a text-only success.
 */
interface NotificationCovers {
    suspend fun withCover(url: String, canPost: () -> Boolean, post: (Bitmap?) -> Unit)
}

internal class NotificationCoverLoader(
    private val calls: Call.Factory = client,
    private val decoder: NotificationCoverDecoder = NotificationCoverDecoder(),
) : NotificationCovers {
    override suspend fun withCover(url: String, canPost: () -> Boolean, post: (Bitmap?) -> Unit) {
        currentCoroutineContext().ensureActive()
        if (!canPost()) return
        var acquired = false
        var bitmap: Bitmap? = null
        try {
            // The budget includes the queue, but not posting. Never replay a partially posted batch.
            val finished =
                withTimeoutOrNull(NotificationCoverLimits.BUDGET_MILLIS) {
                    permits.acquire()
                    acquired = true
                    currentCoroutineContext().ensureActive()
                    if (canPost()) bitmap = load(url)
                    true
                } ?: false
            if (!finished) bitmap = null
            currentCoroutineContext().ensureActive()
            if (canPost()) post(bitmap)
        } finally {
            // No helper cache or escaped collection. SystemUI may retain its own delivered icon.
            bitmap = null
            if (acquired) permits.release()
        }
    }

    private suspend fun load(url: String): Bitmap? =
        try {
            ownedLoad(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            Logger.withTag("NotificationCover").w { "cover_unavailable" }
            null
        }

    private suspend fun ownedLoad(url: String): Bitmap? =
        coroutineScope {
            val currentCall = AtomicReference<Call?>()
            // Runs on cancellation, not only on Job completion after a blocking read has returned.
            val cancellation =
                launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        currentCall.get()?.cancel()
                    }
                }
            try {
                // This structured child cannot complete while its blocking read/decode still runs.
                // Native decode is bounded-input, not preemptible: timeout can require a longer join.
                withContext(Dispatchers.IO) {
                    val body = fetch(url, currentCall) ?: return@withContext null
                    currentCoroutineContext().ensureActive()
                    val bitmap = decoder.decode(body)
                    currentCoroutineContext().ensureActive()
                    bitmap
                }
            } finally {
                withContext(NonCancellable) { cancellation.cancelAndJoin() }
            }
        }

    private suspend fun fetch(url: String, currentCall: AtomicReference<Call?>): NotificationCoverBytes? {
        var destination = safeUrl(url) ?: return null
        repeat(NotificationCoverLimits.REDIRECTS + 1) { hop ->
            currentCoroutineContext().ensureActive()
            val call = calls.newCall(Request.Builder().url(destination).build())
            currentCall.set(call)
            try {
                // Covers cancellation before/while the call was published to the watcher.
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    if (response.code in REDIRECT_CODES) {
                        if (hop == NotificationCoverLimits.REDIRECTS) return null
                        destination = redirect(destination, response.header("Location")) ?: return null
                    } else {
                        return readBody(response)
                    }
                }
            } finally {
                currentCall.compareAndSet(call, null)
                call.cancel()
            }
        }
        return null
    }

    private suspend fun readBody(response: Response): NotificationCoverBytes? {
        // OkHttp transparently decodes gzip and removes its Content-Encoding/Length headers.
        val encoding = response.header("Content-Encoding")
        val body = response.body
        if (
            !response.isSuccessful || body.contentLength() > NotificationCoverLimits.BODY_BYTES ||
            (encoding != null && !encoding.equals("identity", ignoreCase = true))
        ) {
            return null
        }
        return body.byteStream().use { readCoverBytes(it) }
    }

    private companion object {
        val permits = Semaphore(NotificationCoverLimits.RETAINED_COVERS)
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        // Ordinary Android OkHttp transport retains platform TLS and per-host cleartext policy.
        val client =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .cache(null)
                .callTimeout(NotificationCoverLimits.BUDGET_MILLIS, TimeUnit.MILLISECONDS)
                .connectTimeout(NotificationCoverLimits.BUDGET_MILLIS, TimeUnit.MILLISECONDS)
                .readTimeout(NotificationCoverLimits.BUDGET_MILLIS, TimeUnit.MILLISECONDS)
                .build()
    }
}

internal fun safeUrl(value: String): HttpUrl? =
    value.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }

internal fun redirect(previous: HttpUrl, location: String?): HttpUrl? {
    val next = location?.takeIf { it.isNotBlank() }?.let(previous::resolve) ?: return null
    return next.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && (!previous.isHttps || it.isHttps)
    }
}

internal suspend fun readCoverBytes(input: InputStream): NotificationCoverBytes? {
    val bytes = ByteArray(NotificationCoverLimits.BODY_BYTES)
    var count = 0
    while (count < bytes.size) {
        currentCoroutineContext().ensureActive()
        val read = input.readAtLeastOne(bytes, count)
        if (read == -1) return NotificationCoverBytes(bytes, count)
        count += read
    }
    currentCoroutineContext().ensureActive()
    // A single sentinel, never an unbounded bytes()/readBytes() or a growing accumulator.
    return if (input.read() == -1) NotificationCoverBytes(bytes, count) else null
}

private fun InputStream.readAtLeastOne(bytes: ByteArray, offset: Int): Int {
    val count = read(bytes, offset, bytes.size - offset)
    if (count != 0) return count
    // Defensive progress for an InputStream that unexpectedly returns zero for a nonempty request.
    val single = read()
    if (single != -1) bytes[offset] = single.toByte()
    return if (single == -1) -1 else 1
}
