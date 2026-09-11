package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.catalog
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.padding
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.writePadding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KtorRemoteSourceCatalogStreamingTest {
    @Test
    fun both_caps_accept_exact_limit_utf8_with_absent_or_understated_length() =
        runTest {
            for (route in CatalogRoute.entries) {
                for (length in listOf(null, 1)) {
                    withStream(route, length, write = { body ->
                        writePadding(body, route.limit - 2)
                        body.writeFully(byteArrayOf(0xc3.toByte()))
                        yield() // Split the final multibyte character across available chunks.
                        body.writeFully(byteArrayOf(0xa9.toByte()))
                    }) { remote, _, _, _ ->
                        val payload = route.payload(remote)
                        assertEquals(route.limit - 1, payload.length)
                        assertTrue(payload.startsWith(" ") && payload.endsWith("é"))
                    }
                }
            }
        }

    @Test
    fun both_caps_reject_limit_plus_one_even_at_eof_with_absent_or_understated_length() =
        runTest {
            for (route in CatalogRoute.entries) {
                for (length in listOf(null, 1)) {
                    withStream(route, length, write = { writePadding(it, route.limit + 1) }) { remote, _, _, _ ->
                        assertOversize(route, remote)
                    }
                }
            }
        }

    @Test
    fun excess_byte_rejects_without_awaiting_another_byte_or_eof() =
        runTest {
            for (route in CatalogRoute.entries) {
                val sentExcess = CompletableDeferred<Unit>()
                withStream(route, write = { body ->
                    writePadding(body, route.limit + 1)
                    sentExcess.complete(Unit)
                    awaitCancellation() // Never send another byte or EOF before the assertion.
                }) { remote, body, producer, _ ->
                    assertOversize(route, remote)
                    assertTrue(sentExcess.isCompleted)
                    assertFalse(producer.isCompleted)
                    assertTrue(body.isClosedForWrite, "scoped rejection must cancel the still-open body")
                }
            }
        }

    @Test
    fun continuing_chunked_producer_is_stopped_before_its_remainder_is_drained() =
        runTest {
            for (route in CatalogRoute.entries) {
                // Exceeds ByteChannel's read-ahead capacity; produced bytes are NOT a read counter.
                val total = route.limit + 4 * 1024 * 1024
                var produced = 0
                withStream(route, write = { body ->
                    while (produced < total) {
                        body.writeFully(padding)
                        produced += padding.size
                    }
                }) { remote, body, producer, client ->
                    assertOversize(route, remote)
                    assertTrue(body.isClosedForWrite)
                    producer.join()
                    assertTrue(produced < total, "must not consume the entire continuing response")
                    // Normal close must complete its children before the fixture's forced cleanup.
                    client.close()
                    client.coroutineContext.job.join()
                    assertTrue(client.coroutineContext.job.isCompleted)
                }
            }
        }

    private suspend fun assertOversize(
        route: CatalogRoute,
        remote: KtorRemoteSourceCatalog,
    ) {
        val failure = assertFailsWith<IllegalStateException> { route.payload(remote) }
        // A test timeout/cancellation is not an oversize pass.
        assertEquals("${route.label} exceeds the configured size limit", failure.message)
    }

    private suspend fun TestScope.withStream(
        route: CatalogRoute,
        length: Int? = null,
        write: suspend (ByteChannel) -> Unit,
        block: suspend (KtorRemoteSourceCatalog, ByteChannel, Job, HttpClient) -> Unit,
    ) {
        val body = ByteChannel(autoFlush = true)
        withCatalogClient({ respond(body, headers = route.headers(length)) }) { client ->
            val producer =
                launch {
                    try {
                        write(body)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        if (!body.isClosedForWrite) throw failure
                    } finally {
                        body.close()
                    }
                }
            try {
                block(catalog(client), body, producer, client)
            } finally {
                body.cancel()
                withContext(NonCancellable) { producer.cancelAndJoin() }
            }
        }
    }
}
