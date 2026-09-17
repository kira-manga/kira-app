package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSURL

/**
 * Owns one serialized CREATE/status exchange at a time, never GET history or installation work.
 * [createUrl] must be the exact credential-free, queryless HTTPS `/api/v1/complaints` target;
 * status is derived beneath the same checked deployment prefix. Invalid targets return null
 * before native allocation. No session/delegate/trust override or activation is accepted.
 * The borrowing client must retain the fixed no-redirect/no-retry policy.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
fun createIosComplaintMutationEngineOwner(createUrl: Url): ComplaintSessionEngineOwner? {
    val target = iosComplaintMutationTarget(createUrl) ?: return null
    return createIosComplaintInstallationEngineOwner(IosComplaintMutationPolicy(target))
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosComplaintMutationTarget(url: Url): ComplaintMutationTarget? =
    url
        .takeIf { ComplaintMutationTarget.checked(it) != null }
        ?.toString()
        ?.let { NSURL.URLWithString(it) }
        ?.absoluteString
        ?.let { ComplaintMutationTarget.checked(Url(it)) }
