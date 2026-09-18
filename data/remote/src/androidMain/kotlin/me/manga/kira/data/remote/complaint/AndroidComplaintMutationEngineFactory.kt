package me.manga.kira.data.remote.complaint

import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Owns only report/reply/status POST and content PATCH at the exact HTTPS [createUrl] origin/base prefix.
 * [createUrl] must end in `/api/v1/complaints`, without credentials/query/fragment. Status and
 * canonical reply-parent/edit-target paths are derived under that base. Invalid bases allocate nothing.
 * No configured client, trust override, retries, redirect policy or activation is accepted.
 */
fun createAndroidComplaintMutationEngineOwner(createUrl: Url): ComplaintSessionEngineOwner? {
    val target = androidComplaintMutationTarget(createUrl) ?: return null
    val resources = AndroidComplaintSessionResources()
    var transferred = false
    return try {
        val engine =
            OkHttp.create {
                config { complaintMutationPolicy(target, resources) }
            }
        resources.own(engine).also { transferred = true }
    } finally {
        if (!transferred) resources.close()
    }
}

/** Retains the accepted pre-native grammar check before OkHttp can normalize a configured path. */
internal fun androidComplaintMutationTarget(url: Url): ComplaintMutationTarget? =
    url
        .takeIf { ComplaintMutationTarget.checked(it) != null }
        ?.toString()
        ?.toHttpUrlOrNull()
        ?.let { ComplaintMutationTarget.checked(Url(it.toString())) }

internal fun OkHttpClient.Builder.complaintMutationPolicy(
    target: ComplaintMutationTarget,
    resources: AndroidComplaintSessionResources,
): OkHttpClient.Builder =
    complaintSessionBasePolicy(resources).apply {
        addInterceptor(AndroidComplaintMutationInterceptor(target))
    }
