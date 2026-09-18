package me.manga.kira.data.remote.complaint

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Closed creation/status POST and content PATCH policy; installation/history remain separate. */
internal class AndroidComplaintMutationInterceptor(
    private val target: ComplaintMutationTarget,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val route = target.route(request.url.toString())
        val body = request.body
        if (route == null || request.method != route.method || body == null) {
            throw IOException("Complaint mutation request rejected")
        }
        if (chain.call().isCanceled()) throw IOException("Complaint mutation request rejected")
        val editTargetId = target.editTargetId(request.url.toString())
        val headers = request.headers.toList().toMutableList()
        val media = body.contentType()?.toString()
        if (request.headers.values("Content-Type").isEmpty() && media != null) headers += "Content-Type" to media
        if (media != "application/json" ||
            !ComplaintMutationRequestHeaders.accepts(route, headers, body.contentLength(), editTargetId)
        ) {
            throw IOException("Complaint mutation headers rejected")
        }
        val snapshot = boundedComplaintMutationBody(body)
        if (!ComplaintMutationRequestHeaders.accepts(route, headers, snapshot.contentLength(), editTargetId)) {
            throw IOException("Complaint mutation body framing rejected")
        }
        val outgoing = request.newBuilder().method(route.method, snapshot).build()
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
            if (response.request.method != request.method ||
                !target.sameRoute(request.url.toString(), response.request.url.toString())
            ) {
                throw IOException("Complaint mutation response target rejected")
            }
            val budget =
                response.mutationBudget(route) ?: throw IOException("Complaint mutation response headers rejected")
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

    private fun Response.mutationBudget(route: ComplaintMutationRoute): ComplaintReceiveBudget? =
        ComplaintMutationReceiveBudget.checked(
            route,
            code.toLong(),
            ComplaintMutationResponseHeaders(
                media = headers.values("Content-Type"),
                encoding = headers.values("Content-Encoding"),
                length = headers.values("Content-Length"),
                transfer = headers.values("Transfer-Encoding"),
            ),
        )
}
