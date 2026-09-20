package me.manga.kira.data.remote.complaint

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException

/** Closed owner mutation/status policy; installation deletion and history remain separate. */
internal class AndroidComplaintMutationInterceptor(
    private val target: ComplaintMutationTarget,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val route = target.route(request.url.toString())
        if (route == null || request.method != route.method) {
            throw IOException("Complaint mutation request rejected")
        }
        if (chain.call().isCanceled()) throw IOException("Complaint mutation request rejected")
        val snapshot = request.checkedBody(route)
        val outgoing = request.newBuilder().method(route.method, snapshot).build()
        if (chain.call().isCanceled()) throw IOException("Complaint mutation request cancelled")
        return chain.proceedWithMutationBudget(outgoing, route)
    }

    private fun Request.checkedBody(route: ComplaintMutationRoute): RequestBody {
        val body = this.body ?: throw IOException("Complaint mutation request rejected")
        val targetId = target.preconditionTargetId(url.toString())
        val headers = this.headers.toList().toMutableList()
        val media = body.contentType()?.toString()
        if (this.headers.values("Content-Type").isEmpty() && media != null) headers += "Content-Type" to media
        val expectedMedia = if (route == ComplaintMutationRoute.OWNER_DELETE) null else "application/json"
        if (media != expectedMedia ||
            !ComplaintMutationRequestHeaders.accepts(route, headers, body.contentLength(), targetId)
        ) {
            throw IOException("Complaint mutation headers rejected")
        }
        val snapshot =
            if (route == ComplaintMutationRoute.OWNER_DELETE) {
                boundedComplaintOwnerDeleteBody(body)
            } else {
                boundedComplaintMutationBody(body)
            }
        if (!ComplaintMutationRequestHeaders.accepts(route, headers, snapshot.contentLength(), targetId)) {
            throw IOException("Complaint mutation body framing rejected")
        }
        return snapshot
    }

    private fun Interceptor.Chain.proceedWithMutationBudget(
        request: Request,
        route: ComplaintMutationRoute,
    ): Response {
        val response = proceed(request)
        var transferred = false
        return try {
            response.requireRequestBinding(request, route)
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

    private fun Response.requireRequestBinding(
        sent: Request,
        route: ComplaintMutationRoute,
    ) {
        // Held OkHttp RetryAndFollowUp restores this application request after Bridge adds native headers.
        val sameDelete =
            route != ComplaintMutationRoute.OWNER_DELETE ||
                (request.headers == sent.headers && request.body === sent.body)
        if (request.method != sent.method ||
            !target.sameRoute(sent.url.toString(), request.url.toString()) || !sameDelete
        ) {
            throw IOException("Complaint mutation response target rejected")
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
