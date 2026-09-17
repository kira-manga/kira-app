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

@OptIn(ExperimentalForeignApi::class)
internal fun NSURLRequest.isComplaintHistoryRequest(target: ComplaintHistoryTarget): Boolean =
    HTTPMethod == "GET" &&
        URL?.absoluteString?.let(target::matches) == true &&
        HTTPBody == null &&
        HTTPBodyStream == null &&
        FORBIDDEN_HEADERS.all { valueForHTTPHeaderField(it) == null } &&
        ComplaintHistoryRequestHeaders.accepts(
            listOfNotNull(valueForHTTPHeaderField("Authorization")),
            listOfNotNull(valueForHTTPHeaderField("Accept-Encoding")),
        )

// Each missing native URL is an immediate refusal; retain explicit guard ordering.
@Suppress("ReturnCount")
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal fun NSHTTPURLResponse.isComplaintHistoryResponse(
    target: ComplaintHistoryTarget,
    task: NSURLSessionDataTask,
): Boolean {
    val request = task.originalRequest ?: return false
    val requestUrl = request.URL?.absoluteString ?: return false
    val responseUrl = URL?.absoluteString ?: return false
    return request.isComplaintHistoryRequest(target) &&
        target.samePage(requestUrl, responseUrl) &&
        // Delivered-response refusal is NOT evidence that Foundation made no hidden replay.
        statusCode !in FOLLOW_UP_STATUSES
}

private const val SERVICE_UNAVAILABLE = 503L
private const val MISDIRECTED_REQUEST = 421L
private val FOLLOW_UP_STATUSES = setOf(SERVICE_UNAVAILABLE, MISDIRECTED_REQUEST)
private val FORBIDDEN_HEADERS =
    listOf(
        "Cookie",
        "Cookie2",
        "Proxy-Authorization",
        "Content-Type",
        "Content-Length",
        "Transfer-Encoding",
        "Content-Encoding",
    )
