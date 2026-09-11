package me.manga.kira.sources.runtime

import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.catalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtorRemoteSourceCatalogCleanupTest {
    @Test
    fun early_status_content_type_metadata_and_declared_size_failures_cancel_open_bodies() =
        runTest {
            for (route in CatalogRoute.entries) {
                for (case in earlyFailures(route)) {
                    val body = ByteChannel()
                    try {
                        withCatalogClient({ respond(body, case.status, case.headers) }) { client ->
                            val failure = assertFails { route.payload(catalog(client)) }
                            assertEquals(case.message, failure.message)
                            assertTrue(body.isClosedForWrite)
                        }
                    } finally {
                        body.cancel()
                    }
                }
            }
        }

    @Test
    fun conditional_304_closes_an_open_body_without_a_ktor_cache_entry() =
        runTest {
            val body = ByteChannel()
            try {
                withCatalogClient({ respond(body, HttpStatusCode.NotModified) }) { client ->
                    assertIs<SourceCatalogManifestResult.NotModified>(catalog(client).fetchManifest("a".repeat(64)))
                    assertTrue(body.isClosedForWrite)
                }
            } finally {
                body.cancel()
            }
        }

    @Test
    fun an_errored_channel_is_not_treated_as_clean_eof() =
        runTest {
            val cause = IllegalStateException("fixture read failure")
            for (route in CatalogRoute.entries) {
                val body = ByteChannel().apply { cancel(cause) }
                withCatalogClient({ respond(body, headers = route.headers()) }) { client ->
                    val failure = assertFails { route.payload(catalog(client)) }
                    assertFalse(failure is CancellationException)
                    assertTrue(generateSequence(failure) { it.cause }.any { it.message == cause.message })
                    assertTrue(body.isClosedForRead)
                }
            }
        }

    @Test
    fun incomplete_utf8_is_rejected_instead_of_replaced() =
        runTest {
            for (route in CatalogRoute.entries) {
                val body = ByteReadChannel(byteArrayOf(0xc3.toByte()))
                withCatalogClient({ respond(body, headers = route.headers()) }) { client ->
                    val failure = assertFails { route.payload(catalog(client)) }
                    assertFalse(failure is CancellationException)
                    assertTrue(body.isClosedForRead)
                }
            }
        }

    @Test
    fun caller_cancellation_during_body_suspension_propagates_and_closes_the_body() =
        runTest {
            val body = ByteChannel()
            val reading = CompletableDeferred<Unit>()
            val observed =
                object : ByteReadChannel by body {
                    override suspend fun awaitContent(min: Int): Boolean {
                        reading.complete(Unit)
                        return body.awaitContent(min)
                    }
                }
            withCatalogClient({ respond(observed, headers = CatalogRoute.SOURCE.headers()) }) { client ->
                assertCallerCancellation(catalog(client), reading, body)
            }
        }

    private suspend fun assertCallerCancellation(
        remote: KtorRemoteSourceCatalog,
        reading: CompletableDeferred<Unit>,
        body: ByteChannel,
    ) = coroutineScope {
        val cancelled = CompletableDeferred<CancellationException>()
        val request =
            launch {
                try {
                    remote.fetchSource(CatalogTransportTestFixtures.entry())
                } catch (cause: CancellationException) {
                    cancelled.complete(cause)
                    throw cause
                }
            }
        try {
            reading.await()
            request.cancel(CancellationException("fixture caller cancellation"))
            request.join()
            assertTrue(cancelled.isCompleted, "the transport must not turn cancellation into a return value")
            assertEquals("fixture caller cancellation", cancelled.await().message)
            assertTrue(body.isClosedForWrite)
        } finally {
            body.cancel()
            withContext(NonCancellable) { request.cancelAndJoin() }
        }
    }

    private data class EarlyFailure(
        val status: HttpStatusCode,
        val headers: Headers,
        val message: String,
    )

    private fun earlyFailures(route: CatalogRoute): List<EarlyFailure> =
        listOf(
            EarlyFailure(HttpStatusCode.BadGateway, route.headers(), "${route.label} returned HTTP 502"),
            EarlyFailure(
                HttpStatusCode.OK,
                Headers.build {
                    route.headers().forEach { name, values -> appendAll(name, values) }
                    set(HttpHeaders.ContentType, "text/html")
                },
                "source-catalog response must be application/json",
            ),
            EarlyFailure(
                HttpStatusCode.OK,
                Headers.build {
                    route.headers().forEach { name, values -> appendAll(name, values) }
                    remove(HttpHeaders.ETag)
                },
                "response is missing ETag",
            ),
            EarlyFailure(HttpStatusCode.OK, route.headers(route.limit + 1), "Check failed."),
        )
}
