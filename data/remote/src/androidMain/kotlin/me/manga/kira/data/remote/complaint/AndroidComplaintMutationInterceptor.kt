package me.manga.kira.data.remote.complaint

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Separate closed POST policy; the accepted installation/history interceptors are unchanged. */
internal class AndroidComplaintMutationInterceptor(
    private val target: ComplaintMutationTarget,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val route = target.route(request.url.toString())
        val body = request.body
        if (request.method != "POST" || route == null || body == null || chain.call().isCanceled()) {
            throw IOException("Complaint mutation request rejected")
        }
        val headers = request.headers.toList().toMutableList()
        val media = body.contentType()?.toString()
        if (request.headers.values("Content-Type").isEmpty() && media != null) headers += "Content-Type" to media
        if (media != "application/json" ||
            !ComplaintMutationRequestHeaders.accepts(route, headers, body.contentLength())
        ) {
            throw IOException("Complaint mutation headers rejected")
        }
        val snapshot = boundedComplaintMutationBody(body)
        if (!ComplaintMutationRequestHeaders.accepts(route, headers, snapshot.contentLength())) {
            throw IOException("Complaint mutation body framing rejected")
        }
        val outgoing = request.newBuilder().method("POST", snapshot).build()
        if (chain.call().isCanceled()) throw IOException("Complaint mutation request cancelled")
        return chain.proceedWithMutationBudget(outgoing, route)
    }

    private fun Interceptor.Chain.proceedWithMutationBudget(
        request: Request,
        route: ComplaintMutationRoute,
    ): Response {
        val response = proceed(request)
        var transferred = false
        return try {
            if (!target.sameRoute(request.url.toString(), response.request.url.toString())) {
                throw IOException("Complaint mutation response target rejected")
            }
            val budget =
                ComplaintMutationReceiveBudget.checked(
                    route,
                    response.code.toLong(),
                    response.headers.values("Content-Type"),
                    response.headers.values("Content-Encoding"),
                    response.headers.values("Content-Length"),
                    response.headers.values("Transfer-Encoding"),
                ) ?: throw IOException("Complaint mutation response headers rejected")
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
}
