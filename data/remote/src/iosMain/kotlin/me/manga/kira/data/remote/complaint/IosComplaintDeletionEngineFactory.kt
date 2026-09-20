package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSURL

/**
 * Owns one serialized body-authenticated delete-all exchange at the exact queryless HTTPS
 * [deletionUrl]. Invalid targets return null before native allocation. No delegate, session,
 * trust override, normal-operation route or activation is accepted by this factory.
 * Foundation replay/redirect behavior still requires separate pinned native qualification.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
fun createIosComplaintDeletionEngineOwner(deletionUrl: Url): ComplaintSessionEngineOwner? {
    val target = iosComplaintDeletionTarget(deletionUrl) ?: return null
    return createIosComplaintInstallationEngineOwner(IosComplaintDeletionPolicy(target))
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosComplaintDeletionTarget(url: Url): ComplaintDeletionTarget? =
    url
        .takeIf { ComplaintDeletionTarget.checked(it) != null }
        ?.toString()
        ?.let { NSURL.URLWithString(it) }
        ?.absoluteString
        ?.let { ComplaintDeletionTarget.checked(Url(it)) }
