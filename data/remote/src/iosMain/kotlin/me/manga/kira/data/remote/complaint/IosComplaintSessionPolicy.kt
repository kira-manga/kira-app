package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPBodyStream
import platform.Foundation.HTTPMethod
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.valueForHTTPHeaderField

@OptIn(ExperimentalForeignApi::class)
internal fun iosComplaintSessionTarget(url: Url): ComplaintSessionTarget? =
    url
        .takeIf { ComplaintSessionTarget.checked(it) != null }
        ?.toString()
        ?.let { NSURL.URLWithString(it) }
        ?.absoluteString
        ?.let { ComplaintSessionTarget.checked(Url(it)) }

@OptIn(ExperimentalForeignApi::class)
internal fun iosComplaintSessionConfiguration(): NSURLSessionConfiguration =
    NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
        URLCache = null
        URLCredentialStorage = null
        HTTPCookieStorage = null
        HTTPShouldSetCookies = false
        requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
        HTTPMaximumConnectionsPerHost = 1
        timeoutIntervalForResource = SESSION_RESOURCE_TIMEOUT_SECONDS
    }

/** Nil UseCredential declines HTTP/proxy credentials without selecting challenge cancellation. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal fun iosComplaintSessionChallengeDisposition(method: String): NSURLSessionAuthChallengeDisposition =
    if (method == NSURLAuthenticationMethodServerTrust) {
        NSURLSessionAuthChallengePerformDefaultHandling
    } else {
        NSURLSessionAuthChallengeUseCredential
    }

@OptIn(ExperimentalForeignApi::class)
internal fun NSURLRequest.isComplaintSessionRequest(target: ComplaintSessionTarget): Boolean =
    HTTPMethod == "POST" &&
        URL?.absoluteString?.let(target::matches) == true &&
        HTTPBody != null &&
        HTTPBodyStream == null &&
        valueForHTTPHeaderField("Accept-Encoding")?.equals("identity", ignoreCase = true) == true &&
        listOf("Cookie", "Authorization", "Proxy-Authorization").all { valueForHTTPHeaderField(it) == null }

@OptIn(ExperimentalForeignApi::class)
internal fun NSHTTPURLResponse.complaintSessionBudget(): ComplaintSessionReceiveBudget? =
    ComplaintSessionReceiveBudget.checked(
        selectedHeader("Content-Encoding"),
        selectedHeader("Content-Length"),
        selectedHeader("Transfer-Encoding"),
    )

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal fun NSHTTPURLResponse.complaintHistoryBudget(): ComplaintReceiveBudget? =
    ComplaintHistoryReceiveBudget.checked(
        statusCode,
        selectedHeader("Content-Encoding"),
        selectedHeader("Content-Length"),
        selectedHeader("Transfer-Encoding"),
    )

/** Foundation may combine duplicate fields; combined selected values fail the shared grammar. */
@OptIn(ExperimentalForeignApi::class)
private fun NSHTTPURLResponse.selectedHeader(name: String): List<String> {
    val values = mutableListOf<String>()
    for ((key, value) in allHeaderFields) {
        if (key is String && key.equals(name, ignoreCase = true)) {
            if (values.isNotEmpty() || value !is String) return listOf("", "")
            values += value
        }
    }
    return values
}

private const val SESSION_RESOURCE_TIMEOUT_SECONDS = 30.0
