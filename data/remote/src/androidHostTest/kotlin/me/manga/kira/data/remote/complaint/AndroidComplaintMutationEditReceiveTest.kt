package me.manga.kira.data.remote.complaint

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

@Suppress("MagicNumber") // Route/status/media cells and byte-boundary fixtures.
class AndroidComplaintMutationEditReceiveTest {
    @Test
    fun edit200AloneGetsThirtyTwoKiBWhileErrorsWrongSuccessAndStatusStaySixteenWithNoRetryAfterOverrun() =
        runBlocking {
            AndroidMutationEngineFixture().use { fixture ->
                val cases = responseBoundaries()
                for (case in cases) {
                    for (extra in 0..1) {
                        fixture.server.enqueue(
                            MockResponse
                                .Builder()
                                .code(case.status)
                                .addHeader("Content-Type", case.media)
                                .chunkedBody("x".repeat(case.cap + extra), 8_192)
                                .build(),
                        )
                        if (extra == 0) {
                            val response = fixture.exchange(case.route)
                            assertEquals(case.status, response.status)
                            assertEquals(case.cap, response.body.length)
                        } else {
                            assertFails { fixture.exchange(case.route) }
                        }
                    }
                }
                fixture.server.enqueue(MockResponse(body = "next-explicit-call"))
                assertEquals("next-explicit-call", fixture.exchange(ComplaintMutationRoute.EDIT).body)
                assertEquals(cases.size * 2 + 1, fixture.server.requestCount)
            }
        }

    @Test
    fun editDeclaredOverrunBadMediaCodingFramingAndFailedEofCannotBecomeTruncatedSuccess() =
        runBlocking {
            AndroidMutationEngineFixture().use { fixture ->
                val invalid = invalidEditResponses()
                invalid.forEachIndexed { index, response ->
                    fixture.server.enqueue(response)
                    assertFails { fixture.exchange(ComplaintMutationRoute.EDIT) }
                    assertEquals(index + 1, fixture.server.requestCount)
                }
                fixture.server.enqueue(MockResponse(body = "next-explicit-call"))
                assertEquals("next-explicit-call", fixture.exchange(ComplaintMutationRoute.EDIT).body)
                assertEquals(invalid.size + 1, fixture.server.requestCount)
            }
        }

    private fun responseBoundaries(): List<Boundary> =
        listOf(
            Boundary(ComplaintMutationRoute.EDIT, 200, "application/json", Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES),
            Boundary(ComplaintMutationRoute.EDIT, 412, "application/problem+json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
            Boundary(ComplaintMutationRoute.EDIT, 201, "application/json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
            Boundary(ComplaintMutationRoute.STATUS, 200, "application/json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
        )

    private fun invalidEditResponses(): List<MockResponse> {
        val large = Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES
        val small = Policy.MAX_STATUS_OR_PROBLEM_BYTES
        return listOf(
            MockResponse.Builder().addHeader("Content-Type", "application/json")
                .body("x".repeat(large + 1)).build(),
            MockResponse.Builder().addHeader("Content-Type", "application/problem+json")
                .body("x".repeat(small + 1)).build(),
            MockResponse.Builder().addHeader("Content-Type", "application/json")
                .addHeader("Content-Type", "application/json").body("x".repeat(small + 1)).build(),
            MockResponse.Builder().addHeader("Content-Type", "application/json")
                .addHeader("Content-Encoding", "gzip").body("plain").build(),
            MockResponse.Builder().addHeader("Content-Type", "application/json")
                .body("x").setHeader("Content-Length", "1, 1").onResponseEnd(SocketEffect.CloseSocket()).build(),
            MockResponse.Builder().addHeader("Content-Type", "application/json")
                .body("short").setHeader("Content-Length", large).onResponseEnd(SocketEffect.CloseSocket()).build(),
        )
    }

    private data class Boundary(
        val route: ComplaintMutationRoute,
        val status: Int,
        val media: String,
        val cap: Int,
    )
}
