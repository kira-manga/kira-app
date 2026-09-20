package me.manga.kira.data.remote.complaint

import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Owns only POST [enrollmentUrl] and the same-origin GET route formed by appending `/bootstrap`.
 * Requires the exact credential-free HTTPS `/api/v1/installations` route, optionally beneath an
 * existing valid base prefix. Invalid targets return null. No configured client or trust override
 * is accepted; platform trust/hostname checks remain enabled. This does not activate enrollment.
 */
fun createAndroidComplaintEnrollmentEngineOwner(enrollmentUrl: Url): ComplaintSessionEngineOwner? {
    val target = androidComplaintEnrollmentTarget(enrollmentUrl) ?: return null
    val resources = AndroidComplaintSessionResources()
    var transferred = false
    return try {
        val engine =
            OkHttp.create {
                config { complaintEnrollmentPolicy(target, resources) }
            }
        resources.own(engine).also { transferred = true }
    } finally {
        if (!transferred) resources.close()
    }
}

/** Validate before native IPv6/path canonicalization, just as for the separate session entry. */
internal fun androidComplaintEnrollmentTarget(url: Url): ComplaintEnrollmentTarget? =
    url
        .takeIf { ComplaintEnrollmentTarget.checked(it) != null }
        ?.toString()
        ?.toHttpUrlOrNull()
        ?.let { ComplaintEnrollmentTarget.checked(Url(it.toString())) }

internal fun OkHttpClient.Builder.complaintEnrollmentPolicy(
    target: ComplaintEnrollmentTarget,
    resources: AndroidComplaintSessionResources,
): OkHttpClient.Builder =
    complaintSessionBasePolicy(resources).apply {
        addInterceptor(AndroidComplaintEnrollmentInterceptor(target))
        addNetworkInterceptor(AndroidComplaintBootstrapFollowUpGuard(target))
    }
