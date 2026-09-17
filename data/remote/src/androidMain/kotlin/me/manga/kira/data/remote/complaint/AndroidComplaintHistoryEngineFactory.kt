package me.manga.kira.data.remote.complaint

import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Owns only authenticated GET history at the exact credential-free, queryless HTTPS [historyUrl]
 * ending in `/api/v1/complaints`, optionally beneath a valid base prefix. Invalid targets return
 * null before native allocation. Only bounded limit/cursor queries are admitted by this engine.
 * No configured client, trust override or caller policy is accepted; this does not select history.
 */
fun createAndroidComplaintHistoryEngineOwner(historyUrl: Url): ComplaintSessionEngineOwner? {
    val target = androidComplaintHistoryTarget(historyUrl) ?: return null
    val resources = AndroidComplaintSessionResources()
    var transferred = false
    return try {
        val engine =
            OkHttp.create {
                config { complaintHistoryPolicy(target, resources) }
            }
        resources.own(engine).also { transferred = true }
    } finally {
        if (!transferred) resources.close()
    }
}

/** Validate before native IPv6/path canonicalization; no normalization can authorize a new base. */
internal fun androidComplaintHistoryTarget(url: Url): ComplaintHistoryTarget? =
    url
        .takeIf { ComplaintHistoryTarget.checked(it) != null }
        ?.toString()
        ?.toHttpUrlOrNull()
        ?.let { ComplaintHistoryTarget.checked(Url(it.toString())) }

internal fun OkHttpClient.Builder.complaintHistoryPolicy(
    target: ComplaintHistoryTarget,
    resources: AndroidComplaintSessionResources,
): OkHttpClient.Builder =
    complaintSessionBasePolicy(resources).apply {
        addInterceptor(AndroidComplaintHistoryInterceptor(target))
        addNetworkInterceptor(AndroidComplaintHistoryFollowUpGuard(target))
    }
