package me.manga.kira.data.complaint.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.text.CharacterCodingException
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Closed report/reply/edit/status client borrowing one mutation engine. No arbitrary authenticated work. */
@OptIn(ExperimentalAtomicApi::class)
internal class ComplaintMutationHttp(
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

    suspend fun create(
        request: ComplaintCreateHttpRequest,
        session: ComplaintSessionResponse,
    ): ComplaintCreateHttpResult {
        currentCoroutineContext().ensureActive()
        refusal(request.pending, session)?.let { return ComplaintCreateHttpResult.Failed(request, it) }
        val exchange = exchange(request.route, session, request.pending, request.bodyBytes())
        val result =
            when (exchange) {
                is MutationExchange.Received -> ComplaintMutationResponse.create(exchange.document, request)
                is MutationExchange.Failed -> ComplaintCreateHttpResult.Failed(request, exchange.reason)
            }
        currentCoroutineContext().ensureActive()
        return if (isClosed) ComplaintCreateHttpResult.Failed(request, ComplaintMutationFailure.CLOSED) else result
    }

    suspend fun status(
        request: ComplaintCreateStatusRequest,
        session: ComplaintSessionResponse,
    ): ComplaintCreateStatusHttpResult {
        currentCoroutineContext().ensureActive()
        refusal(request.pending, session)?.let { return ComplaintCreateStatusHttpResult.Failed(request, it) }
        val exchange = exchange(ComplaintMutationRoute.STATUS, session, request.pending, request.bodyBytes())
        val result =
            when (exchange) {
                is MutationExchange.Received -> ComplaintMutationResponse.status(exchange.document, request)
                is MutationExchange.Failed -> ComplaintCreateStatusHttpResult.Failed(request, exchange.reason)
            }
        currentCoroutineContext().ensureActive()
        return if (isClosed) {
            ComplaintCreateStatusHttpResult.Failed(request, ComplaintMutationFailure.CLOSED)
        } else {
            result
        }
    }

    suspend fun edit(
        request: ComplaintEditHttpRequest,
        session: ComplaintSessionResponse,
    ): ComplaintEditHttpResult {
        currentCoroutineContext().ensureActive()
        refusal(request.pending, session)?.let { return ComplaintEditHttpResult.Failed(request, it) }
        val exchange = exchange(ComplaintMutationRoute.EDIT, session, request.pending, request.bodyBytes())
        val result =
            when (exchange) {
                is MutationExchange.Received -> ComplaintEditResponse.edit(exchange.document, request)
                is MutationExchange.Failed -> ComplaintEditHttpResult.Failed(request, exchange.reason)
            }
        currentCoroutineContext().ensureActive()
        return if (isClosed) ComplaintEditHttpResult.Failed(request, ComplaintMutationFailure.CLOSED) else result
    }

    suspend fun editStatus(
        request: ComplaintEditStatusRequest,
        session: ComplaintSessionResponse,
    ): ComplaintEditStatusHttpResult {
        currentCoroutineContext().ensureActive()
        refusal(request.pending, session)?.let { return ComplaintEditStatusHttpResult.Failed(request, it) }
        val exchange = exchange(ComplaintMutationRoute.STATUS, session, request.pending, request.bodyBytes())
        val result =
            when (exchange) {
                is MutationExchange.Received -> ComplaintEditResponse.status(exchange.document, request)
                is MutationExchange.Failed -> ComplaintEditStatusHttpResult.Failed(request, exchange.reason)
            }
        currentCoroutineContext().ensureActive()
        return if (isClosed) ComplaintEditStatusHttpResult.Failed(request, ComplaintMutationFailure.CLOSED) else result
    }

    /** Cancels this client's work only. The composition root retains engine ownership. */
    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            try {
                client.coroutineContext.cancel()
            } finally {
                client.close()
            }
        }
    }

    private fun refusal(
        pending: PendingComplaintRecord,
        session: ComplaintSessionResponse,
    ): ComplaintMutationFailure? =
        when {
            isClosed -> ComplaintMutationFailure.CLOSED
            !pending.binding.matchesMutationSession(session) -> ComplaintMutationFailure.INVALIDATED
            else -> null
        }

    // This private exchange takes a closed route and owned bytes, never an authenticated callback or caller URL.
    private suspend fun exchange(
        route: ComplaintMutationRoute,
        session: ComplaintSessionResponse,
        pending: PendingComplaintRecord,
        bytes: ByteArray,
    ): MutationExchange =
        try {
            currentCoroutineContext().ensureActive()
            val refusal = refusal(pending, session)
            when {
                refusal != null -> MutationExchange.Failed(refusal)
                bytes.size > Policy.MAX_REQUEST_BYTES -> MutationExchange.Failed(ComplaintMutationFailure.RESPONSE)
                else ->
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) { dispatch(route, session, pending, bytes) }
                        ?: MutationExchange.Failed(ComplaintMutationFailure.TIMEOUT)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            MutationExchange.Failed(ComplaintMutationFailure.TIMEOUT)
        } catch (_: InvalidComplaintHistory) {
            MutationExchange.Failed(ComplaintMutationFailure.RESPONSE)
        } catch (_: CharacterCodingException) {
            MutationExchange.Failed(ComplaintMutationFailure.RESPONSE)
        } catch (_: Exception) {
            val reason = if (isClosed) ComplaintMutationFailure.CLOSED else ComplaintMutationFailure.TRANSPORT
            MutationExchange.Failed(reason)
        } finally {
            bytes.fill(0)
        }

    private suspend fun dispatch(
        route: ComplaintMutationRoute,
        session: ComplaintSessionResponse,
        pending: PendingComplaintRecord,
        bytes: ByteArray,
    ): MutationExchange.Received {
        val url = route.url(endpoint, pending)
        return client
            .prepareRequest(url.toString()) {
                method = route.method
                headers {
                    append(HttpHeaders.Accept, "application/json, application/problem+json")
                    append(HttpHeaders.AcceptEncoding, "identity")
                    append(HttpHeaders.CacheControl, "no-store, no-transform")
                    append(HttpHeaders.Authorization, session.authorizationValue())
                    if (route != ComplaintMutationRoute.STATUS) {
                        append(Policy.IDEMPOTENCY_HEADER, pending.request.key)
                    }
                    if (route == ComplaintMutationRoute.EDIT) {
                        append(HttpHeaders.IfMatch, checkNotNull(pending.request.action.canonicalPrecondition()))
                    }
                }
                setBody(ByteArrayContent(bytes, ContentType.Application.Json))
            }.execute { MutationExchange.Received(ComplaintMutationBody.read(it, url, route)) }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val IO_TIMEOUT_MS = 10_000L
    }
}

private sealed interface MutationExchange {
    class Received(
        val document: ComplaintMutationDocument,
    ) : MutationExchange

    class Failed(
        val reason: ComplaintMutationFailure,
    ) : MutationExchange
}
