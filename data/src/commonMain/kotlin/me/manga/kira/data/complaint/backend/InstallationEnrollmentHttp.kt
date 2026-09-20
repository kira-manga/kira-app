package me.manga.kira.data.complaint.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.platform.storage.InstallationCredentialRecord
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure
import me.manga.kira.data.complaint.backend.InstallationEnrollmentResult as EnrollmentResult

/**
 * Two fixed routes only. Borrows its explicit engine; the composition root must supply the separately
 * qualified enrollment owner, never the session-only engine or a global arbitrary-host client.
 * No parser result or caller-provided trust Boolean can replace this exchange in the coordinator.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class InstallationEnrollmentHttp(
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
                requestTimeoutMillis = ATTEMPT_TIMEOUT_MS
                connectTimeoutMillis = IO_TIMEOUT_MS
                socketTimeoutMillis = IO_TIMEOUT_MS
            }
        }

    val isClosed: Boolean get() = closed.load()

    suspend fun bootstrap(): EnrollmentResult<InstallationBootstrapResponse> =
        exchange {
            client
                .prepareGet(endpoint.bootstrapUrl.toString()) { installationHeaders() }
                .execute { ComplaintBoundedResponse.bootstrap(it, endpoint) }
        }

    suspend fun enroll(record: InstallationCredentialRecord): EnrollmentResult<Unit> =
        exchange {
            if (!endpoint.acceptsDataScope(record.material.dataScopeId)) {
                EnrollmentResult.Failed(Failure.CONTRACT)
            } else {
                client
                    .preparePost(endpoint.enrollmentUrl.toString()) {
                        installationHeaders()
                        setBody(ByteArrayContent(requestBytes(record), ContentType.Application.Json))
                    }.execute { ComplaintBoundedResponse.enrollment(it, endpoint, record) }
            }
        }

    /** Cancels only this borrowed client's work. Closing it never closes the supplied engine owner. */
    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            try {
                client.coroutineContext.cancel()
            } finally {
                client.close()
            }
        }
    }

    private suspend fun <T> exchange(request: suspend () -> EnrollmentResult<T>): EnrollmentResult<T> {
        if (isClosed) return EnrollmentResult.Failed(Failure.CLOSED)
        return try {
            val result = request()
            if (isClosed) EnrollmentResult.Failed(Failure.CLOSED) else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            EnrollmentResult.Failed(Failure.TIMEOUT)
        } catch (_: Exception) {
            EnrollmentResult.Failed(if (isClosed) Failure.CLOSED else Failure.TRANSPORT)
        }
    }

    private fun HttpRequestBuilder.installationHeaders() {
        headers {
            append(HttpHeaders.Accept, "application/json, application/problem+json")
            append(HttpHeaders.AcceptEncoding, "identity")
            append(HttpHeaders.CacheControl, "no-store, no-transform")
        }
    }

    private fun requestBytes(record: InstallationCredentialRecord): ByteArray =
        buildJsonObject {
            put("installationId", record.material.installationId)
            put("secret", record.material.secret)
            put("platform", record.material.platform.name)
            put("expectedDataScopeId", record.material.dataScopeId)
        }.toString().encodeToByteArray()

    companion object {
        const val ATTEMPT_TIMEOUT_MS = 30_000L
        private const val IO_TIMEOUT_MS = 10_000L
    }
}
