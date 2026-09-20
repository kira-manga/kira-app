package me.manga.kira.data.remote.complaint

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException

/** Runs before RetryAndFollowUpInterceptor, so even 503/Retry-After:0 cannot replay the POST body. */
internal class AndroidComplaintSessionInterceptor(
    private val target: ComplaintSessionTarget,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!permitted(request)) throw IOException("Complaint session request rejected")
        val body = request.body ?: throw IOException("Complaint session body required")
        if (body.isDuplex()) throw IOException("Complaint session duplex body rejected")
        val outgoing = request.newBuilder().method("POST", OneShotSessionBody(body)).build()
        return chain.proceedWithComplaintSessionBudget(outgoing)
    }

    private fun permitted(request: Request): Boolean =
        request.method == "POST" &&
            target.matches(request.url.toString()) &&
            request.headers.values("Accept-Encoding") == listOf("identity") &&
            request.header("Cookie") == null &&
            request.header("Authorization") == null &&
            request.header("Proxy-Authorization") == null
}

internal fun Interceptor.Chain.proceedWithComplaintSessionBudget(request: Request): Response {
    val response = proceed(request)
    var transferred = false
    return try {
        val budget =
            ComplaintSessionReceiveBudget.checked(
                response.headers.values("Content-Encoding"),
                response.headers.values("Content-Length"),
                response.headers.values("Transfer-Encoding"),
            ) ?: throw IOException("Complaint session response headers rejected")
        response
            .newBuilder()
            .body(AndroidComplaintSessionResponseBody(response.body, budget, call()::cancel))
            .build()
            .also { transferred = true }
    } finally {
        if (!transferred) {
            try {
                response.close()
            } finally {
                call().cancel()
            }
        }
    }
}

internal class OneShotSessionBody(
    private val delegate: RequestBody,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)

    override fun isOneShot(): Boolean = true
}
