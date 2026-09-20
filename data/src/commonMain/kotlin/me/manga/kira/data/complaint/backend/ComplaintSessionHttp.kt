package me.manga.kira.data.complaint.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.platform.storage.InstallationCredentialRecord
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure

/**
 * Dedicated fixed-policy client, borrowing (not creating/owning) its explicit engine.
 * No configured HttpClient is accepted. Native retry/redirect/cache/logging/receive policy remains unqualified.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ComplaintSessionHttp(
    private val endpoint: ComplaintBackendEndpoint,
    engine: HttpClientEngine,
) {
    private val closed = AtomicBoolean(false)
    private val client =
        HttpClient(engine) {
            followRedirects = false
            expectSuccess = false
            useDefaultTransformers = false
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = IO_TIMEOUT_MS
                socketTimeoutMillis = IO_TIMEOUT_MS
            }
        }

    suspend fun fetch(record: InstallationCredentialRecord): ComplaintSessionResult {
        if (closed.load()) return ComplaintSessionResult.Failed(Failure.CLOSED)
        // Never send an existing credential to a different launch scope, or rewrite it on mismatch.
        if (!endpoint.acceptsDataScope(record.material.dataScopeId)) return ComplaintSessionResult.Failed(Failure.CONTRACT)
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { exchange(record) }
                ?: ComplaintSessionResult.Failed(Failure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            ComplaintSessionResult.Failed(Failure.TIMEOUT)
        } catch (_: Exception) {
            ComplaintSessionResult.Failed(if (closed.load()) Failure.CLOSED else Failure.TRANSPORT)
        }
    }

    /** Cancels this client's in-flight requests; supplied-engine ownership never transfers to this class. */
    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            try {
                client.coroutineContext.cancel()
            } finally {
                client.close()
            }
        }
    }

    private suspend fun exchange(record: InstallationCredentialRecord): ComplaintSessionResult =
        client
            .preparePost(endpoint.sessionUrl.toString()) {
                headers {
                    append(HttpHeaders.Accept, "application/json, application/problem+json")
                    append(HttpHeaders.AcceptEncoding, "identity")
                    append(HttpHeaders.CacheControl, "no-store, no-transform")
                }
                setBody(ByteArrayContent(requestBytes(record), ContentType.Application.Json))
            }.execute { response -> ComplaintBoundedResponse.read(response, endpoint, record) }

    private fun requestBytes(record: InstallationCredentialRecord): ByteArray =
        buildJsonObject {
            put("installationId", record.material.installationId)
            put("secret", record.material.secret)
            put("expectedDataScopeId", record.material.dataScopeId)
        }.toString().encodeToByteArray()

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val IO_TIMEOUT_MS = 10_000L
    }
}
