package me.manga.kira.data.remote.complaint

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

class AndroidComplaintDeletionReceiveTest {
    @Test
    fun acceptedAndNoContentResponsesRejectEveryNonemptyBody() =
        runBlocking {
            AndroidDeletionEngineFixture().use { fixture ->
                for (status in listOf(202, 204)) {
                    fixture.server.enqueue(MockResponse.Builder().code(status).build())
                    assertEquals("", fixture.exchange().body)
                    fixture.server.enqueue(MockResponse.Builder().code(status).body("x").build())
                    assertFails { fixture.exchange() }
                }
                fixture.server.enqueue(MockResponse.Builder().code(202).chunkedBody("x", 1).build())
                assertFails { fixture.exchange() }
            }
        }

    @Test
    fun problemsStopAtSixteenKibibytesBeforeTheLargerDownstreamFixtureReader() =
        runBlocking {
            AndroidDeletionEngineFixture().use { fixture ->
                fixture.enqueueProblem(Policy.MAX_PROBLEM_BYTES)
                assertEquals(Policy.MAX_PROBLEM_BYTES, fixture.exchange().body.length)
                fixture.enqueueProblem(Policy.MAX_PROBLEM_BYTES + 1)
                assertFails { fixture.exchange() }
                fixture.server.enqueue(
                    MockResponse.Builder().code(503).addHeader("Content-Type", "application/problem+json")
                        .addHeader("Content-Encoding", "gzip").body("synthetic").build(),
                )
                assertFails { fixture.exchange() }
                fixture.server.enqueue(MockResponse.Builder().code(204).build())
                assertEquals(204, fixture.exchange().status)
            }
        }

    private fun AndroidDeletionEngineFixture.enqueueProblem(size: Int) {
        server.enqueue(
            MockResponse.Builder().code(503).addHeader("Content-Type", "application/problem+json")
                .chunkedBody("x".repeat(size), CHUNK_BYTES).build(),
        )
    }

    private companion object {
        const val CHUNK_BYTES = 8_192
    }
}
