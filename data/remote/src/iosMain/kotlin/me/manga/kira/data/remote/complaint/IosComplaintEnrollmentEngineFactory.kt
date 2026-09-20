package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL

/**
 * Owns only GET bootstrap and POST enrollment at one validated HTTPS origin/base path.
 * Reuses the isolated session allocator, system trust, delegate and bounded receive guard;
 * no caller-provided native session, trust override or credential store is accepted.
 * Closing cancels owned work; Foundation retry and native drainage still need qualification.
 */
fun createIosComplaintEnrollmentEngineOwner(enrollmentUrl: Url): ComplaintSessionEngineOwner? {
    val target = iosComplaintEnrollmentTarget(enrollmentUrl) ?: return null
    return createIosComplaintInstallationEngineOwner(IosComplaintInstallationPolicy.Enrollment(target))
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosComplaintEnrollmentTarget(url: Url): ComplaintEnrollmentTarget? =
    url
        .takeIf { ComplaintEnrollmentTarget.checked(it) != null }
        ?.toString()
        ?.let { NSURL.URLWithString(it) }
        ?.absoluteString
        ?.let { ComplaintEnrollmentTarget.checked(Url(it)) }
