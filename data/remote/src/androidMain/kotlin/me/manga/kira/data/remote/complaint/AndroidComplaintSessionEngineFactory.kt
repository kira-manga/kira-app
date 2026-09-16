package me.manga.kira.data.remote.complaint

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient.Builder as OkHttpBuilder

/**
 * Creates an isolated Android session engine for exactly [sessionUrl], or null for an invalid
 * credential-free HTTPS session target. No configured client, trust override or interceptor is
 * accepted. The caller owns the result; no DI/feature activation occurs here.
 */
fun createAndroidComplaintSessionEngineOwner(sessionUrl: Url): ComplaintSessionEngineOwner? {
    val target = androidComplaintSessionTarget(sessionUrl) ?: return null
    val resources = AndroidComplaintSessionResources()
    var transferred = false
    return try {
        val engine =
            OkHttp.create {
                config { complaintSessionPolicy(target, resources) }
            }
        resources.own(engine).also { transferred = true }
    } finally {
        if (!transferred) resources.close()
    }
}

/** Validate before native canonicalization, which compresses IPv6 but can also normalize paths. */
internal fun androidComplaintSessionTarget(url: Url): ComplaintSessionTarget? =
    url
        .takeIf { ComplaintSessionTarget.checked(it) != null }
        ?.toString()
        ?.toHttpUrlOrNull()
        ?.let { ComplaintSessionTarget.checked(Url(it.toString())) }

internal class AndroidComplaintSessionResources {
    val dispatcher = Dispatcher()

    // A canceled active call can release its connection after close() evicts idle connections.
    // Zero idle retention closes that late release too, without touching another client's pool.
    val pool = ConnectionPool(0, 1, TimeUnit.MINUTES)

    fun own(engine: HttpClientEngine): ComplaintSessionEngineOwner =
        OwnedComplaintSessionEngine(
            engine,
            dispatcher::cancelAll,
            ::close,
        )

    fun close() {
        try {
            pool.evictAll()
        } finally {
            dispatcher.executorService.shutdown()
        }
    }
}

/** Applied after Ktor's retry=true default, not only on a preconfigured native client. */
internal fun OkHttpBuilder.complaintSessionPolicy(
    target: ComplaintSessionTarget,
    resources: AndroidComplaintSessionResources,
): OkHttpBuilder =
    complaintSessionBasePolicy(resources).apply {
        addInterceptor(AndroidComplaintSessionInterceptor(target))
    }

/** Fixed native isolation shared by the two closed entries, with no caller-supplied policy. */
internal fun OkHttpBuilder.complaintSessionBasePolicy(resources: AndroidComplaintSessionResources) =
    apply {
        dispatcher(resources.dispatcher)
        connectionPool(resources.pool)
        retryOnConnectionFailure(false)
        followRedirects(false)
        followSslRedirects(false)
        cache(null)
        cookieJar(CookieJar.NO_COOKIES)
        authenticator(Authenticator.NONE)
        proxyAuthenticator(Authenticator.NONE)
        eventListener(EventListener.NONE)
        connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        interceptors().clear()
        networkInterceptors().clear()
    }
