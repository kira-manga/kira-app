package me.manga.kira.data.remote.complaint

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AndroidComplaintEnrollmentReceiveTest {
    @Test
    fun bothRoutesAcceptExactChunkedLimitAndRejectOneByteOverWithoutTruncatedSuccess() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                val exact = "x".repeat(ComplaintSessionReceiveBudget.MAX_BYTES)
                listOf(false, true).forEach { enrollment ->
                    fixture.server.enqueue(MockResponse.Builder().chunkedBody(exact, CHUNK_BYTES).build())
                    assertEquals(exact, fixture.receive(enrollment).body)
                    fixture.server.enqueue(MockResponse.Builder().chunkedBody(exact + "y", CHUNK_BYTES).build())
                    assertFails { fixture.receive(enrollment) }
                    fixture.server.enqueue(MockResponse(body = "recovered"))
                    assertEquals("recovered", fixture.receive(enrollment).body)
                }
                assertEquals(BOTH_ROUTES_THREE_EXCHANGES, fixture.server.requestCount)
            }
        }

    @Test
    fun bothRoutesRejectEncodingAndDeclaredOverrunThenRecover() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                listOf(false, true).forEach { enrollment ->
                    fixture.server.enqueue(
                        MockResponse
                            .Builder()
                            .addHeader("Content-Encoding", "gzip")
                            .body("plain")
                            .build(),
                    )
                    assertFails { fixture.receive(enrollment) }
                    fixture.enqueueFalseDeclaredLength(ComplaintSessionReceiveBudget.MAX_BYTES + 1)
                    assertFails { fixture.receive(enrollment) }
                    fixture.server.enqueue(MockResponse(body = "recovered"))
                    assertEquals("recovered", fixture.receive(enrollment).body)
                }
                assertEquals(BOTH_ROUTES_THREE_EXCHANGES, fixture.server.requestCount)
            }
        }

    @Test
    fun bothRoutesRejectPrematureEofInsteadOfAcceptingTheDeclaredLength() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                listOf(false, true).forEach { enrollment ->
                    fixture.enqueueFalseDeclaredLength(FALSE_DECLARED_BYTES)
                    assertFails { fixture.receive(enrollment) }
                    fixture.server.enqueue(MockResponse(body = "recovered"))
                    assertEquals("recovered", fixture.receive(enrollment).body)
                }
                assertEquals(BOTH_ROUTES_TWO_EXCHANGES, fixture.server.requestCount)
            }
        }

    private suspend fun AndroidSessionEngineFixture.receive(enrollment: Boolean): SessionEngineResponse =
        if (enrollment) enroll() else bootstrap()

    private fun AndroidSessionEngineFixture.enqueueFalseDeclaredLength(length: Int) {
        server.enqueue(
            MockResponse
                .Builder()
                .body("short")
                .setHeader("Content-Length", length)
                .onResponseEnd(SocketEffect.CloseSocket())
                .build(),
        )
    }

    private companion object {
        const val CHUNK_BYTES = 1_024
        const val FALSE_DECLARED_BYTES = 100
        const val BOTH_ROUTES_THREE_EXCHANGES = 6
        const val BOTH_ROUTES_TWO_EXCHANGES = 4
    }
}
