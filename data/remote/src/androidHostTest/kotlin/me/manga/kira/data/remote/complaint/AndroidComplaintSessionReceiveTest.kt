package me.manga.kira.data.remote.complaint

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidComplaintSessionReceiveTest {
    @Test
    fun exactChunkedLimitSucceedsAndOneMoreByteCannotBecomeTruncatedSuccess() =
        runBlocking {
            AndroidSessionEngineFixture().use { fixture ->
                val exact = "x".repeat(ComplaintSessionReceiveBudget.MAX_BYTES)
                fixture.server.enqueue(MockResponse.Builder().chunkedBody(exact, CHUNK_BYTES).build())
                assertEquals(exact, fixture.exchange().body)
                fixture.server.enqueue(MockResponse.Builder().chunkedBody(exact + "y", CHUNK_BYTES).build())
                assertFails { fixture.exchange() }
                fixture.server.enqueue(MockResponse(body = "recovered"))
                assertEquals("recovered", fixture.exchange().body)
                assertEquals(EXACT_OVERRUN_RECOVERY_REQUESTS, fixture.server.requestCount)
            }
        }

    @Test
    fun nonIdentityEncodingAndDeclaredOverrunFailBeforeBodyAcceptance() =
        runBlocking {
            AndroidSessionEngineFixture().use { fixture ->
                fixture.server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Encoding", "gzip")
                        .body("plain")
                        .build(),
                )
                assertFails { fixture.exchange() }
                fixture.server.enqueue(
                    MockResponse
                        .Builder()
                        .body("short")
                        .setHeader("Content-Length", ComplaintSessionReceiveBudget.MAX_BYTES + 1)
                        .onResponseEnd(SocketEffect.CloseSocket())
                        .build(),
                )
                assertFails { fixture.exchange() }
                assertEquals(2, fixture.server.requestCount)
            }
        }

    @Test
    fun earlyNativeEofCannotValidateAFalseDeclaredLength() =
        runBlocking {
            AndroidSessionEngineFixture().use { fixture ->
                fixture.server.enqueue(
                    MockResponse
                        .Builder()
                        .body("short")
                        .setHeader("Content-Length", FALSE_DECLARED_BYTES)
                        .onResponseEnd(SocketEffect.CloseSocket())
                        .build(),
                )
                assertFails { fixture.exchange() }
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun receiveCounterRejectsUnsignedOverflowWithoutMutatingAcceptedBytes() {
        val budget = assertNotNull(ComplaintSessionReceiveBudget.checked(emptyList(), emptyList(), emptyList()))
        assertTrue(budget.accept(1u))
        assertFalse(budget.accept(ULong.MAX_VALUE))
        assertEquals(1, budget.receivedBytes)
        assertTrue(budget.accept((ComplaintSessionReceiveBudget.MAX_BYTES - 1).toULong()))
        assertFalse(budget.accept(1u))
        assertEquals(ComplaintSessionReceiveBudget.MAX_BYTES, budget.receivedBytes)
    }

    private companion object {
        const val EXACT_OVERRUN_RECOVERY_REQUESTS = 3
        const val CHUNK_BYTES = 1_024
        const val FALSE_DECLARED_BYTES = 100
    }
}
