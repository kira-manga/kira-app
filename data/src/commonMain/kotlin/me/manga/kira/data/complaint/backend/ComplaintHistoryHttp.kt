package me.manga.kira.data.complaint.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Fixed owner-list client borrowing only the separately qualified history engine. No retries/plugins/caches. */
@OptIn(ExperimentalAtomicApi::class)
internal class ComplaintHistoryHttp(private val endpoint: ComplaintBackendEndpoint, engine: HttpClientEngine) {
    private val closed = AtomicBoolean(false)
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        useDefaultTransformers = false
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = IO_TIMEOUT_MS
            socketTimeoutMillis = IO_TIMEOUT_MS
        }
    }

    suspend fun fetch(session: ComplaintSessionResponse, cursor: String?): AppResult<ComplaintHistoryPage> {
        if (closed.load()) return historyUnavailable()
        if (cursor != null && !validHistoryCursor(cursor)) return malformedHistory()
        // These are the only query names/values. Cursor syntax excludes encoding/delimiter aliases.
        val url = Url(endpoint.historyUrl.toString() + "?limit=50" + (cursor?.let { "&cursor=$it" } ?: ""))
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                currentCoroutineContext().ensureActive()
                if (closed.load()) return@withTimeoutOrNull historyUnavailable()
                client.prepareGet(url.toString()) {
                    headers {
                        append(HttpHeaders.Accept, "application/json, application/problem+json")
                        append(HttpHeaders.AcceptEncoding, "identity")
                        append(HttpHeaders.CacheControl, "no-store, no-transform")
                        append(HttpHeaders.Authorization, session.authorizationValue())
                    }
                }.execute { ComplaintHistoryBody.read(it, url) }
            } ?: AppResult.Failure(AppError.Network.Timeout())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            AppResult.Failure(AppError.Network.Timeout())
        } catch (_: Exception) {
            if (closed.load()) historyUnavailable() else AppResult.Failure(AppError.Network.NoConnectivity())
        }
    }

    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            try {
                client.coroutineContext.cancel()
            } finally {
                client.close()
            }
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val IO_TIMEOUT_MS = 10_000L
    }
}

internal fun historyUnavailable(): AppResult.Failure =
    AppResult.Failure(AppError.Platform.FeatureUnavailable("complaint_history"))
