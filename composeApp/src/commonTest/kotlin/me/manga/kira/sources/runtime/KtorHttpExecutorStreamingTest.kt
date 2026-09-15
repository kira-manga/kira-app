package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.SerializationException
import me.manga.kira.sources.contracts.SourceHttpMethod
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.padding
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.writePadding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KtorHttpExecutorStreamingTest {
    @Test
    fun exact_byte_limit_accepts_split_utf8_with_absent_understated_or_exact_length() =
        runTest {
            for (length in listOf(null, 1, RESPONSE_LIMIT)) {
                withStream(length, write = { body ->
                    writePadding(body, RESPONSE_LIMIT - 2)
                    body.writeFully(byteArrayOf(0xc3.toByte()))
                    yield()
                    body.writeFully(byteArrayOf(0xa9.toByte()))
                }) { executor, body, _, _ ->
                    val response = executor.execute(SourceRequest(URL))
                    assertEquals(RESPONSE_LIMIT - 1, response.body.length)
                    assertTrue(response.body.endsWith("é"))
                    assertTrue(body.isClosedForRead)
                }
            }
        }

    @Test
    fun all_methods_reject_one_extra_byte_at_eof_with_absent_or_understated_length() =
        runTest {
            for (method in SourceHttpMethod.entries) {
                for (length in listOf(null, 1)) {
                    withStream(length, write = { writePadding(it, RESPONSE_LIMIT + 1) }) { executor, _, _, _ ->
                        assertOversize(executor, method)
                    }
                }
            }
        }

    @Test
    fun first_extra_byte_rejects_without_waiting_for_another_byte_or_eof() =
        runTest {
            val sentExcess = CompletableDeferred<Unit>()
            withStream(write = { body ->
                writePadding(body, RESPONSE_LIMIT + 1)
                sentExcess.complete(Unit)
                awaitCancellation()
            }) { executor, body, producer, _ ->
                assertOversize(executor)
                assertTrue(sentExcess.isCompleted)
                assertFalse(producer.isCompleted)
                assertTrue(body.isClosedForWrite)
            }
        }

    @Test
    fun rejection_stops_a_continuing_producer_and_client_close_finishes_without_forced_cleanup() =
        runTest {
            val total = RESPONSE_LIMIT * 2
            var produced = 0
            withStream(write = { body ->
                while (produced < total) {
                    body.writeFully(padding)
                    produced += padding.size
                }
            }) { executor, body, producer, client ->
                assertOversize(executor)
                assertTrue(body.isClosedForWrite)
                producer.join()
                // ByteChannel can read ahead; produced is not a consumer byte counter.
                assertTrue(produced < total, "must reject before draining the unbounded remainder")
                client.close()
                client.coroutineContext.job.join()
                assertTrue(client.coroutineContext.job.isCompleted)
            }
        }

    @Test
    fun caller_cancellation_while_awaiting_body_propagates_and_closes_the_channel() =
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
            withCatalogClient({ respond(observed) }) { client ->
                assertCallerCancellation(KtorHttpExecutor(client), reading, body)
            }
        }

    private suspend fun assertCallerCancellation(
        executor: KtorHttpExecutor,
        reading: CompletableDeferred<Unit>,
        body: ByteChannel,
    ) = coroutineScope {
        val cancelled = CompletableDeferred<CancellationException>()
        val request =
            launch {
                try {
                    executor.execute(SourceRequest(URL))
                } catch (cause: CancellationException) {
                    cancelled.complete(cause)
                    throw cause
                }
            }
        try {
            reading.await()
            request.cancel(CancellationException("fixture caller cancellation"))
            request.join()
            assertTrue(cancelled.isCompleted, "cancellation must not become a response or typed failure")
            assertEquals("fixture caller cancellation", cancelled.await().message)
            assertTrue(body.isClosedForWrite)
        } finally {
            body.cancel()
            withContext(NonCancellable) { request.cancelAndJoin() }
        }
    }

    private suspend fun assertOversize(
        executor: KtorHttpExecutor,
        method: SourceHttpMethod = SourceHttpMethod.GET,
    ) {
        val failure = assertFailsWith<SerializationException> { executor.execute(SourceRequest(URL, method)) }
        assertEquals("generic source response exceeds the configured size limit", failure.message)
    }

    private suspend fun TestScope.withStream(
        length: Int? = null,
        write: suspend (ByteChannel) -> Unit,
        block: suspend (KtorHttpExecutor, ByteChannel, Job, HttpClient) -> Unit,
    ) {
        val body = ByteChannel(autoFlush = true)
        val headers = Headers.build { length?.let { append(HttpHeaders.ContentLength, it.toString()) } }
        withCatalogClient({ respond(body, headers = headers) }) { client ->
            val producer = launchProducer(body, write)
            try {
                block(KtorHttpExecutor(client), body, producer, client)
            } finally {
                body.cancel()
                withContext(NonCancellable) { producer.cancelAndJoin() }
            }
        }
    }

    private fun TestScope.launchProducer(
        body: ByteChannel,
        write: suspend (ByteChannel) -> Unit,
    ): Job =
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

    private companion object {
        const val RESPONSE_LIMIT = 4 * 1024 * 1024
        const val URL = "https://source.test/latest"
    }
}
