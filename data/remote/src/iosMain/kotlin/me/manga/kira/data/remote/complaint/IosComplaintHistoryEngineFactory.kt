package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL

/**
 * Owns only authenticated GET history at one validated HTTPS origin/base plus `/api/v1/complaints`.
 * [historyUrl] is credential-free and queryless; requests admit bounded list queries or canonical detail IDs.
 * Reuses the isolated owner/delegate: list200 remains2MiB, detail200 JSON is32KiB and problems16KiB.
 * No configured session/trust override or feature activation is accepted.
 * Close cancels admission/work, not synchronous native drainage; Foundation retry needs qualification.
 */
fun createIosComplaintHistoryEngineOwner(historyUrl: Url): ComplaintSessionEngineOwner? {
    val target = iosComplaintHistoryTarget(historyUrl) ?: return null
    return createIosComplaintInstallationEngineOwner(IosComplaintInstallationPolicy.History(target))
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosComplaintHistoryTarget(url: Url): ComplaintHistoryTarget? =
    url
        .takeIf { ComplaintHistoryTarget.checked(it) != null }
        ?.toString()
        ?.let { NSURL.URLWithString(it) }
        ?.absoluteString
        ?.let { ComplaintHistoryTarget.checked(Url(it)) }
