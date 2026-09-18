package me.manga.kira.data.remote.complaint

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Checks the closed authenticated route before HTTP, then bounds bytes before handing them to Ktor. */
internal class AndroidComplaintHistoryInterceptor(
    private val target: ComplaintHistoryTarget,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!permitted(request)) throw IOException("Complaint history request rejected")
        return chain.proceedWithComplaintHistoryBudget(request, target)
    }

    private fun permitted(request: Request): Boolean =
        request.method == "GET" &&
            target.matches(request.url.toString()) &&
            request.body == null &&
            FORBIDDEN_HEADERS.all { request.header(it) == null } &&
            (target.route(request.url.toString()) != ComplaintHistoryRoute.DETAIL ||
                ComplaintHistoryRequestHeaders.detailForbiddenHeaders.all { request.header(it) == null }) &&
            ComplaintHistoryRequestHeaders.accepts(
                request.headers.values("Authorization"),
                request.headers.values("Accept-Encoding"),
            )

    private companion object {
        // Ktor drops GET content during conversion; its surviving media/framing must also fail.
        val FORBIDDEN_HEADERS =
            listOf(
                "Cookie",
                "Cookie2",
                "Proxy-Authorization",
                "Content-Type",
                "Content-Length",
                "Transfer-Encoding",
                "Content-Encoding",
            )
    }
}

private fun Interceptor.Chain.proceedWithComplaintHistoryBudget(
    request: Request,
    target: ComplaintHistoryTarget,
): Response {
    val response = proceed(request)
    var transferred = false
    return try {
        if (!target.samePage(request.url.toString(), response.request.url.toString())) {
            throw IOException("Complaint history response target rejected")
        }
        val budget =
            if (target.route(request.url.toString()) == ComplaintHistoryRoute.DETAIL) {
                ComplaintHistoryReceiveBudget.checkedDetail(
                    response.code.toLong(),
                    response.headers.values("Content-Type"),
                    response.headers.values("Content-Encoding"),
                    response.headers.values("Content-Length"),
                    response.headers.values("Transfer-Encoding"),
                )
            } else {
                ComplaintHistoryReceiveBudget.checked(
                    response.code.toLong(),
                    response.headers.values("Content-Encoding"),
                    response.headers.values("Content-Length"),
                    response.headers.values("Transfer-Encoding"),
                )
            } ?: throw IOException("Complaint history response headers rejected")
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

/**
 * Bodyless GET can still be replayed for 503/Retry-After:0 or coalesced 421 by OkHttp 5.3.2.
 * Reject the raw response before its follow-up interceptor, without forwarding a prefix/status.
 */
internal class AndroidComplaintHistoryFollowUpGuard(
    private val target: ComplaintHistoryTarget,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (chain.request().method == "GET" &&
            target.matches(chain.request().url.toString()) &&
            response.code in FOLLOW_UP_STATUSES
        ) {
            try {
                chain.call().cancel()
            } finally {
                response.close()
            }
            throw IOException("Complaint history follow-up rejected")
        }
        return response
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
        const val MISDIRECTED_REQUEST = 421
        val FOLLOW_UP_STATUSES = setOf(SERVICE_UNAVAILABLE, MISDIRECTED_REQUEST)
    }
}
