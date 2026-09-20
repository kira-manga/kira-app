package me.manga.kira.data.remote.complaint

import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Owns only body-authenticated POST at the exact queryless HTTPS [deletionUrl] ending in
 * `/api/v1/installations/delete-all`. No session, status, enrollment, trust override, shared
 * client or feature activation is accepted. Invalid targets return null before allocation.
 */
fun createAndroidComplaintDeletionEngineOwner(deletionUrl: Url): ComplaintSessionEngineOwner? {
    val target = androidComplaintDeletionTarget(deletionUrl) ?: return null
    val resources = AndroidComplaintSessionResources()
    var transferred = false
    return try {
        val engine =
            OkHttp.create {
                config { complaintDeletionPolicy(target, resources) }
            }
        resources.own(engine).also { transferred = true }
    } finally {
        if (!transferred) resources.close()
    }
}

internal fun androidComplaintDeletionTarget(url: Url): ComplaintDeletionTarget? =
    url
        .takeIf { ComplaintDeletionTarget.checked(it) != null }
        ?.toString()
        ?.toHttpUrlOrNull()
        ?.let { ComplaintDeletionTarget.checked(Url(it.toString())) }

internal fun OkHttpClient.Builder.complaintDeletionPolicy(
    target: ComplaintDeletionTarget,
    resources: AndroidComplaintSessionResources,
): OkHttpClient.Builder =
    complaintSessionBasePolicy(resources).apply {
        addInterceptor(AndroidComplaintDeletionInterceptor(target))
    }
