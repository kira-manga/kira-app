package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPBodyStream
import platform.Foundation.HTTPMethod
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.allHTTPHeaderFields
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

/** Closed case with a zero-byte successful-body budget, using the existing sticky-failure guard. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal class IosComplaintDeletionPolicy(
    private val target: ComplaintDeletionTarget,
) : IosComplaintInstallationPolicy() {
    override fun acceptsRequest(request: NSURLRequest): Boolean {
        val length = request.HTTPBody?.length ?: return false
        return request.URL?.absoluteString?.let(target::matches) == true &&
            request.HTTPMethod == "POST" &&
            request.HTTPBodyStream == null &&
            length in 1uL..Policy.MAX_REQUEST_BYTES.toULong() &&
            deletionHeaders(request)?.let { ComplaintDeletionRequestHeaders.accepts(it, length.toLong()) } == true
    }

    override fun acceptsResponse(
        task: NSURLSessionDataTask,
        response: NSHTTPURLResponse,
    ): Boolean =
        task.originalRequest?.let(::acceptsRequest) == true &&
            response.URL?.absoluteString?.let(target::matches) == true

    fun deletionReceiveBudget(response: NSHTTPURLResponse): ComplaintReceiveBudget? =
        ComplaintDeletionReceiveBudget.checked(
            response.statusCode,
            ComplaintDeletionResponseHeaders(
                media = response.deletionHeader("Content-Type"),
                encoding = response.deletionHeader("Content-Encoding"),
                length = response.deletionHeader("Content-Length"),
                transfer = response.deletionHeader("Transfer-Encoding"),
            ),
        )
}

@OptIn(ExperimentalForeignApi::class)
private fun deletionHeaders(request: NSURLRequest): List<Pair<String, String>>? {
    val result = mutableListOf<Pair<String, String>>()
    for ((key, value) in request.allHTTPHeaderFields ?: emptyMap<Any?, Any?>()) {
        if (key !is String || value !is String) return null
        result += key to value
    }
    return result
}

@OptIn(ExperimentalForeignApi::class)
private fun NSHTTPURLResponse.deletionHeader(name: String): List<String> {
    val values = mutableListOf<String>()
    for ((key, value) in allHeaderFields) {
        if (key is String && key.equals(name, ignoreCase = true)) {
            if (values.isNotEmpty() || value !is String) return listOf("", "")
            values += value
        }
    }
    return values
}
