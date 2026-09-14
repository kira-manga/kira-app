package me.manga.kira.data.remote.ktor

import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.SetupRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.cache.InvalidCacheStateException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.Url
import io.ktor.util.AttributeKey
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.manga.kira.data.remote.ktor.cache.CacheRevalidationAttempt
import me.manga.kira.data.remote.ktor.cache.CacheValidators
import me.manga.kira.data.remote.ktor.cache.ManagedHttpCache
import me.manga.kira.data.remote.ktor.cache.isCacheRecoveryEligible

private val recoveryBudgetKey = AttributeKey<CacheRecoveryBudget>("kira-cache-recovery-budget")

internal class HttpCacheRevalidationRecoveryConfig {
    var owner: ManagedHttpCache? = null
}

/** Recovers only a lost, positively identified cache-owned revalidation, never arbitrary HTTP failures. */
internal val HttpCacheRevalidationRecovery = createClientPlugin(
    "KiraHttpCacheRevalidationRecovery",
    ::HttpCacheRevalidationRecoveryConfig,
) {
    val owner = checkNotNull(pluginConfig.owner)
    on(SetupRequest) { request ->
        // Redirects/retries copy attribute values by reference; a new top-level request gets a new budget.
        request.attributes.put(recoveryBudgetKey, CacheRecoveryBudget())
    }
    onResponse { response ->
        currentCoroutineContext()[CacheRevalidationAttempt]
            ?.takeIf { it.owner === owner }
            ?.observeResponse(response)
    }
    on(Send) { request ->
        recoverCacheState(owner, request)
    }
}

private suspend fun Send.Sender.recoverCacheState(owner: ManagedHttpCache, request: HttpRequestBuilder): HttpClientCall {
    val attempt = CacheRevalidationAttempt(owner, Url(request.url), request.isCacheRecoveryEligible())
    return try {
        executeCacheAttempt(request, attempt)
    } catch (failure: InvalidCacheStateException) {
        currentCoroutineContext().ensureActive()
        val validators = attempt.retryValidators(request) ?: throw failure
        val budget = request.attributes[recoveryBudgetKey]
        if (!budget.take()) throw failure
        val retry = request.recoveryCopy(validators) ?: throw failure
        // Scope only the repair GET, including its receive phase. Normal concurrent requests still cache.
        executeCacheAttempt(retry, CacheRevalidationAttempt(owner, Url(retry.url), eligible = false, bypassStorage = true))
    }
}

private suspend fun Send.Sender.executeCacheAttempt(
    request: HttpRequestBuilder,
    attempt: CacheRevalidationAttempt,
): HttpClientCall = try {
    // Do not use Send.Sender.coroutineContext: that is the client scope, not the requesting coroutine.
    withContext(attempt) { proceed(request) }
} catch (cause: Throwable) {
    // Ktor's DefaultSender has not assigned currentCall when the receive-cache phase throws.
    attempt.disposeFailedResponse(cause)
    throw cause
} finally {
    attempt.releaseResponse()
}

@OptIn(InternalAPI::class)
private fun HttpRequestBuilder.recoveryCopy(validators: CacheValidators): HttpRequestBuilder? {
    // Clone the FAILED HOP, not the original URL/headers: never restore redirect-stripped credentials.
    val retry = HttpRequestBuilder().takeFromWithExecutionContext(this)
    if (!validators.matches(retry.headers::getAll)) return null
    validators.removeFrom(retry.headers)
    return retry
}

private class CacheRecoveryBudget {
    private val mutex = Mutex()
    private var used = false

    suspend fun take(): Boolean = mutex.withLock {
        if (used) return@withLock false
        used = true
        true
    }
}
