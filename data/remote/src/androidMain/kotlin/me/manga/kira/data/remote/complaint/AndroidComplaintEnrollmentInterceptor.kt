package me.manga.kira.data.remote.complaint

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** The two fixed methods/routes share the accepted response bound, not the session route. */
internal class AndroidComplaintEnrollmentInterceptor(
    private val target: ComplaintEnrollmentTarget,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!credentialFreeIdentityRequest(request)) throw IOException("Complaint enrollment headers rejected")
        val outgoing =
            when {
                request.method == "GET" && target.matchesBootstrap(request.url.toString()) -> bootstrap(request)
                request.method == "POST" && target.matchesEnrollment(request.url.toString()) -> enrollment(request)
                else -> throw IOException("Complaint enrollment request rejected")
            }
        return chain.proceedWithComplaintSessionBudget(outgoing)
    }

    private fun bootstrap(request: Request): Request {
        // Ktor discards a GET body during OkHttp conversion; also reject its surviving framing/media headers.
        if (request.body != null || BOOTSTRAP_BODY_HEADERS.any { request.header(it) != null }) {
            throw IOException("Complaint bootstrap body rejected")
        }
        return request
    }

    private fun enrollment(request: Request): Request {
        val body = request.body ?: throw IOException("Complaint enrollment body required")
        if (body.isDuplex() || body.contentLength() !in 1L..MAX_ENROLLMENT_BYTES) {
            throw IOException("Complaint enrollment body rejected")
        }
        return request.newBuilder().method("POST", OneShotSessionBody(body)).build()
    }

    private fun credentialFreeIdentityRequest(request: Request): Boolean =
        request.headers.values("Accept-Encoding") == listOf("identity") &&
            request.header("Cookie") == null &&
            request.header("Authorization") == null &&
            request.header("Proxy-Authorization") == null

    private companion object {
        const val MAX_ENROLLMENT_BYTES = 4 * 1_024L
        val BOOTSTRAP_BODY_HEADERS = listOf("Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding")
    }
}

/**
 * OkHttp 5.3.2 can follow a bodyless GET after 503/Retry-After:0 or a coalesced 421 despite retry=false.
 * Refuse those raw responses before its follow-up interceptor sees them; never forge a status or a
 * GET body. A later explicit acquisition may try again. Lower-layer duplication is still possible.
 */
internal class AndroidComplaintBootstrapFollowUpGuard(
    private val target: ComplaintEnrollmentTarget,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (chain.request().method == "GET" &&
            target.matchesBootstrap(chain.request().url.toString()) &&
            response.code in BOOTSTRAP_FOLLOW_UP_STATUSES
        ) {
            try {
                chain.call().cancel()
            } finally {
                response.close()
            }
            throw IOException("Complaint bootstrap follow-up rejected")
        }
        return response
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
        const val MISDIRECTED_REQUEST = 421
        val BOOTSTRAP_FOLLOW_UP_STATUSES = setOf(SERVICE_UNAVAILABLE, MISDIRECTED_REQUEST)
    }
}
