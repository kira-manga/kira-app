package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPBodyStream
import platform.Foundation.HTTPMethod
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.valueForHTTPHeaderField

/** Closed cases, not caller-supplied route predicates; the existing Session policy stays unchanged. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal sealed class IosComplaintInstallationPolicy {
    abstract fun acceptsRequest(request: NSURLRequest): Boolean

    abstract fun acceptsResponse(
        task: NSURLSessionDataTask,
        response: NSHTTPURLResponse,
    ): Boolean

    fun receiveBudget(response: NSHTTPURLResponse): ComplaintReceiveBudget? =
        when (this) {
            is Session, is Enrollment -> response.complaintSessionBudget()
            is History -> response.complaintHistoryBudget()
        }

    class Session(
        private val target: ComplaintSessionTarget,
    ) : IosComplaintInstallationPolicy() {
        override fun acceptsRequest(request: NSURLRequest): Boolean = request.isComplaintSessionRequest(target)

        override fun acceptsResponse(
            task: NSURLSessionDataTask,
            response: NSHTTPURLResponse,
        ): Boolean = response.URL?.absoluteString?.let(target::matches) == true
    }

    class Enrollment(
        private val target: ComplaintEnrollmentTarget,
    ) : IosComplaintInstallationPolicy() {
        override fun acceptsRequest(request: NSURLRequest): Boolean = request.isComplaintEnrollmentRequest(target)

        override fun acceptsResponse(
            task: NSURLSessionDataTask,
            response: NSHTTPURLResponse,
        ): Boolean {
            val request = task.originalRequest
            val requestUrl = request?.URL?.absoluteString
            val responseUrl = response.URL?.absoluteString
            if (requestUrl == null || responseUrl == null) return false
            return when (request?.HTTPMethod) {
                "GET" ->
                    target.matchesBootstrap(requestUrl) &&
                        target.matchesBootstrap(responseUrl) &&
                        // This refuses a delivered response, not any hidden Foundation replay before it.
                        response.statusCode !in BOOTSTRAP_FOLLOW_UP_STATUSES
                "POST" -> target.matchesEnrollment(requestUrl) && target.matchesEnrollment(responseUrl)
                else -> false
            }
        }
    }

    class History(
        private val target: ComplaintHistoryTarget,
    ) : IosComplaintInstallationPolicy() {
        override fun acceptsRequest(request: NSURLRequest): Boolean = request.isComplaintHistoryRequest(target)

        override fun acceptsResponse(
            task: NSURLSessionDataTask,
            response: NSHTTPURLResponse,
        ): Boolean = response.isComplaintHistoryResponse(target, task)
    }
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun NSURLRequest.isComplaintEnrollmentRequest(target: ComplaintEnrollmentTarget): Boolean {
    val requestUrl = URL?.absoluteString
    if (requestUrl == null || HTTPBodyStream != null || hasDisallowedEnrollmentHeaders()) {
        return false
    }
    return when (HTTPMethod) {
        "GET" ->
            target.matchesBootstrap(requestUrl) &&
                HTTPBody == null &&
                BOOTSTRAP_BODY_HEADERS.all { valueForHTTPHeaderField(it) == null }
        "POST" ->
            target.matchesEnrollment(requestUrl) &&
                HTTPBody?.length?.let { it in 1uL..MAX_ENROLLMENT_BYTES } == true
        else -> false
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURLRequest.hasDisallowedEnrollmentHeaders(): Boolean =
    valueForHTTPHeaderField("Accept-Encoding")?.equals("identity", ignoreCase = true) != true ||
        CREDENTIAL_HEADERS.any { valueForHTTPHeaderField(it) != null }

private const val MAX_ENROLLMENT_BYTES = 4_096uL
private const val SERVICE_UNAVAILABLE = 503L
private const val MISDIRECTED_REQUEST = 421L
private val BOOTSTRAP_FOLLOW_UP_STATUSES = setOf(SERVICE_UNAVAILABLE, MISDIRECTED_REQUEST)
private val CREDENTIAL_HEADERS = listOf("Cookie", "Authorization", "Proxy-Authorization")
private val BOOTSTRAP_BODY_HEADERS = listOf("Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding")
