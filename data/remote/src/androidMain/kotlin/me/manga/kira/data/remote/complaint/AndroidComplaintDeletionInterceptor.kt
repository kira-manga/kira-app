package me.manga.kira.data.remote.complaint

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Closed delete-all entry; leaves the already accepted session/history/mutation routes unchanged. */
internal class AndroidComplaintDeletionInterceptor(
    private val target: ComplaintDeletionTarget,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        if (request.method != "POST" || !target.matches(request.url.toString()) || body == null) {
            throw IOException("Complaint deletion request rejected")
        }
        if (chain.call().isCanceled()) throw IOException("Complaint deletion request rejected")
        val headers = request.headers.toList().toMutableList()
        val media = body.contentType()?.toString()
        if (request.headers.values("Content-Type").isEmpty() && media != null) headers += "Content-Type" to media
        if (media != "application/json" || !ComplaintDeletionRequestHeaders.accepts(headers, body.contentLength())) {
            throw IOException("Complaint deletion headers rejected")
        }
        val snapshot = boundedComplaintDeletionBody(body)
        if (!ComplaintDeletionRequestHeaders.accepts(headers, snapshot.contentLength())) {
            throw IOException("Complaint deletion body framing rejected")
        }
        val outgoing = request.newBuilder().method("POST", snapshot).build()
        if (chain.call().isCanceled()) throw IOException("Complaint deletion request cancelled")
        return chain.proceedWithDeletionBudget(outgoing)
    }

    private fun Interceptor.Chain.proceedWithDeletionBudget(request: Request): Response {
        val response = proceed(request)
        var transferred = false
        return try {
            if (response.request.method != "POST" || !target.matches(response.request.url.toString())) {
                throw IOException("Complaint deletion response target rejected")
            }
            val budget = response.deletionBudget() ?: throw IOException("Complaint deletion response headers rejected")
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

    private fun Response.deletionBudget(): ComplaintReceiveBudget? =
        ComplaintDeletionReceiveBudget.checked(
            code.toLong(),
            ComplaintDeletionResponseHeaders(
                media = headers.values("Content-Type"),
                encoding = headers.values("Content-Encoding"),
                length = headers.values("Content-Length"),
                transfer = headers.values("Transfer-Encoding"),
            ),
        )
}
