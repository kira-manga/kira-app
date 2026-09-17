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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import me.manga.kira.platform.storage.InstallationCredentialState
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.text.CharacterCodingException
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure

/** One fixed body-authenticated POST; no session refresh, status query, retry loop or credential rewriting. */
@OptIn(ExperimentalAtomicApi::class)
internal class InstallationDeletionHttp(
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

    val isClosed: Boolean get() = closed.load()

    suspend fun delete(request: InstallationDeletionRequest): InstallationDeletionHttpResult {
        currentCoroutineContext().ensureActive()
        return when {
            isClosed -> request.failed(Failure.CLOSED)
            !request.binding.work.isCurrent() ||
                request.binding.record.state != InstallationCredentialState.DELETION_PENDING ->
                request.failed(Failure.INVALIDATED)
            else -> {
                val result = exchange(request)
                currentCoroutineContext().ensureActive()
                if (isClosed) request.failed(Failure.CLOSED) else result
            }
        }
    }

    /** Borrowing client only; the composition root owns and releases the independent native engine. */
    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            try {
                client.coroutineContext.cancel()
            } finally {
                client.close()
            }
        }
    }

    private suspend fun exchange(request: InstallationDeletionRequest): InstallationDeletionHttpResult {
        val bytes = request.bodyBytes()
        return try {
            if (bytes.size !in 1..Policy.MAX_REQUEST_BYTES) return request.failed(Failure.RESPONSE)
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { dispatch(request, bytes) } ?: request.failed(Failure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            request.failed(Failure.TIMEOUT)
        } catch (_: InvalidComplaintHistory) {
            request.failed(Failure.RESPONSE)
        } catch (_: SerializationException) {
            request.failed(Failure.RESPONSE)
        } catch (_: CharacterCodingException) {
            request.failed(Failure.RESPONSE)
        } catch (_: Exception) {
            request.failed(if (isClosed) Failure.CLOSED else Failure.TRANSPORT)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun dispatch(
        request: InstallationDeletionRequest,
        bytes: ByteArray,
    ): InstallationDeletionHttpResult {
        currentCoroutineContext().ensureActive()
        if (isClosed || !request.binding.work.isCurrent()) return request.failed(Failure.CLOSED)
        return client
            .preparePost(endpoint.deletionUrl.toString()) {
                headers {
                    append(HttpHeaders.Accept, "application/json, application/problem+json")
                    append(HttpHeaders.AcceptEncoding, "identity")
                    append(HttpHeaders.CacheControl, "no-store, no-transform")
                    append(Policy.IDEMPOTENCY_HEADER, request.key)
                }
                setBody(ByteArrayContent(bytes, ContentType.Application.Json))
            }.execute { response ->
                InstallationDeletionResponse.read(InstallationDeletionBody.read(response, endpoint.deletionUrl), request)
            }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val IO_TIMEOUT_MS = 10_000L
    }
}

private fun InstallationDeletionRequest.failed(reason: Failure): InstallationDeletionHttpResult =
    InstallationDeletionHttpResult.Failed(this, reason)
