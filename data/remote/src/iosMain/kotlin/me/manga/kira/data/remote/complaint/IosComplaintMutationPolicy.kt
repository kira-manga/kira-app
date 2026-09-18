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
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** New closed case; reuses the existing sticky-failure native receive guard without widening old cases. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal class IosComplaintMutationPolicy(
    private val target: ComplaintMutationTarget,
) : IosComplaintInstallationPolicy() {
    @Suppress("ReturnCount")
    override fun acceptsRequest(request: NSURLRequest): Boolean {
        val url = request.URL?.absoluteString ?: return false
        val route = target.route(url) ?: return false
        val length = request.HTTPBody?.length ?: return false
        return request.HTTPMethod == route.method &&
            request.HTTPBodyStream == null &&
            length in 1uL..Policy.MAX_REQUEST_BYTES.toULong() &&
            mutationHeaders(request)?.let {
                ComplaintMutationRequestHeaders.accepts(route, it, length.toLong(), target.editTargetId(url))
            } == true
    }

    @Suppress("ReturnCount")
    override fun acceptsResponse(
        task: NSURLSessionDataTask,
        response: NSHTTPURLResponse,
    ): Boolean {
        val request = task.originalRequest ?: return false
        val requestUrl = request.URL?.absoluteString ?: return false
        val responseUrl = response.URL?.absoluteString ?: return false
        return acceptsRequest(request) && target.sameRoute(requestUrl, responseUrl)
    }

    fun mutationReceiveBudget(response: NSHTTPURLResponse): ComplaintReceiveBudget? {
        val route = response.URL?.absoluteString?.let(target::route) ?: return null
        return ComplaintMutationReceiveBudget.checked(
            route,
            response.statusCode,
            ComplaintMutationResponseHeaders(
                media = response.mutationHeader("Content-Type"),
                encoding = response.mutationHeader("Content-Encoding"),
                length = response.mutationHeader("Content-Length"),
                transfer = response.mutationHeader("Transfer-Encoding"),
            ),
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun mutationHeaders(request: NSURLRequest): List<Pair<String, String>>? {
    val result = mutableListOf<Pair<String, String>>()
    for ((key, value) in request.allHTTPHeaderFields ?: emptyMap<Any?, Any?>()) {
        if (key !is String || value !is String) return null
        result += key to value
    }
    return result
}

/** Foundation-combined duplicate content fields cannot grant the larger acknowledgement budget. */
@OptIn(ExperimentalForeignApi::class)
private fun NSHTTPURLResponse.mutationHeader(name: String): List<String> {
    val values = mutableListOf<String>()
    for ((key, value) in allHeaderFields) {
        if (key is String && key.equals(name, ignoreCase = true)) {
            if (values.isNotEmpty() || value !is String) return listOf("", "")
            values += value
        }
    }
    return values
}
