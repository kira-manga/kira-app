package me.manga.kira.data.remote.complaint

import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidComplaintHistoryReceiveTest {
    @Test
    fun only200AcceptsTwoMibibytesWhileErrorsStopAtSixteenKibibytes() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                listOf(SUCCESS_STATUS to ComplaintHistoryReceiveBudget.MAX_BYTES, UNAUTHORIZED to ComplaintSessionReceiveBudget.MAX_BYTES)
                    .forEach { (status, cap) ->
                        val exact = "x".repeat(cap)
                        fixture.enqueueChunked(status, exact)
                        assertEquals(exact, fixture.get().body)
                        fixture.enqueueChunked(status, exact + "y")
                        assertFails { fixture.get() }
                        fixture.server.enqueue(MockResponse(body = "recovered"))
                        assertEquals("recovered", fixture.get().body)
                    }
                assertEquals(6, fixture.server.requestCount)
            }
        }

    @Test
    fun declaredBoundsEncodingAndFalseLengthCannotBecomeAcceptedHistory() =
        runBlocking {
            assertFailedChannelCauseIsPreserved()
            AndroidHistoryEngineFixture().use { fixture ->
                listOf(SUCCESS_STATUS to ComplaintHistoryReceiveBudget.MAX_BYTES, UNAUTHORIZED to ComplaintSessionReceiveBudget.MAX_BYTES)
                    .forEach { (status, cap) ->
                        fixture.enqueueDeclared(status, cap + 1)
                        assertFails("declared_overflow_$status") { fixture.get() }
                        fixture.enqueueDeclared(status, FALSE_LENGTH)
                        assertFails("declared_short_$status") { fixture.get() }
                    }
                fixture.server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "gzip").body("plain").build())
                assertFails("unexpected_encoding_gzip") { fixture.get() }
                fixture.server.enqueue(MockResponse(body = "recovered"))
                assertEquals("recovered", fixture.get().body)
            }
        }

    @Test
    fun pausedKtorConsumerStillObservesOverflowFailureThenReleasesItsResponse() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                fixture.enqueueChunked(SUCCESS_STATUS, "x".repeat(ComplaintHistoryReceiveBudget.MAX_BYTES + 1))
                val paused = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val request = async(Dispatchers.Default) { pausedOverflow(fixture, paused, resume) }
                try {
                    withTimeout(SESSION_TEST_TIMEOUT_MS) { paused.await() }
                    resume.complete(Unit)
                    assertNotNull(withTimeout(SESSION_TEST_TIMEOUT_MS) { request.await() })
                    fixture.server.enqueue(MockResponse(body = "recovered"))
                    assertEquals("recovered", fixture.get().body)
                } finally {
                    resume.complete(Unit)
                    request.cancelAndJoin()
                }
            }
        }

    @Test
    fun callerCancellationClosesThePausedLargeResponseBeforeTheNextExplicitGet() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                fixture.enqueueChunked(SUCCESS_STATUS, "x".repeat(ComplaintHistoryReceiveBudget.MAX_BYTES))
                val paused = CompletableDeferred<Unit>()
                val request =
                    async(Dispatchers.Default) {
                        fixture.client.prepareGet(fixture.firstPage.toString()) { historyTestHeaders() }.execute {
                            paused.complete(Unit)
                            awaitCancellation()
                        }
                    }
                try {
                    withTimeout(SESSION_TEST_TIMEOUT_MS) { paused.await() }
                    withTimeout(SESSION_TEST_TIMEOUT_MS) { request.cancelAndJoin() }
                    assertTrue(request.isCancelled)
                    fixture.server.enqueue(MockResponse(body = "recovered"))
                    assertEquals("recovered", fixture.get().body)
                } finally {
                    request.cancelAndJoin()
                }
            }
        }

    private suspend fun assertFailedChannelCauseIsPreserved() {
        val cause = IOException("synthetic history receive failure")
        val channel = ByteChannel()
        channel.cancel(cause)
        val failure = assertFails("already_failed_channel") { historyResponseBytes(channel) }
        assertTrue(generateSequence(failure) { it.cause }.any { it === cause }, "already_failed_channel_preserves_cause")
    }

    private suspend fun pausedOverflow(
        fixture: AndroidHistoryEngineFixture,
        paused: CompletableDeferred<Unit>,
        resume: CompletableDeferred<Unit>,
    ): Throwable? =
        runCatching {
            fixture.client.prepareGet(fixture.firstPage.toString()) { historyTestHeaders() }.execute { response ->
                paused.complete(Unit)
                resume.await()
                historyResponseBytes(response.bodyAsChannel())
            }
        }.exceptionOrNull()

    private fun AndroidHistoryEngineFixture.enqueueChunked(
        status: Int,
        body: String,
    ) {
        server.enqueue(MockResponse.Builder().code(status).chunkedBody(body, CHUNK_BYTES).build())
    }

    private fun AndroidHistoryEngineFixture.enqueueDeclared(
        status: Int,
        length: Int,
    ) {
        server.enqueue(
            MockResponse.Builder().code(status).body("short").setHeader("Content-Length", length)
                .onResponseEnd(SocketEffect.CloseSocket()).build(),
        )
    }

    private companion object {
        const val SUCCESS_STATUS = 200
        const val UNAUTHORIZED = 401
        const val CHUNK_BYTES = 8_192
        const val FALSE_LENGTH = 100
    }
}
