package me.manga.kira.data.remote.complaint

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

class AndroidComplaintMutationReceiveTest {
    @Test
    fun create201JsonUsesThirtyTwoKibibytesButStatusAndProblemsStopAtSixteen() =
        runBlocking {
            AndroidMutationEngineFixture().use { fixture ->
                val cases =
                    listOf(
                        Boundary(
                            ComplaintMutationRoute.CREATE,
                            CREATED,
                            "application/json",
                            Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES,
                        ),
                        Boundary(
                            ComplaintMutationRoute.STATUS,
                            OK,
                            "application/json",
                            Policy.MAX_STATUS_OR_PROBLEM_BYTES,
                        ),
                        Boundary(
                            ComplaintMutationRoute.CREATE,
                            CONFLICT,
                            "application/problem+json",
                            Policy.MAX_STATUS_OR_PROBLEM_BYTES,
                        ),
                        Boundary(
                            ComplaintMutationRoute.CREATE,
                            CREATED,
                            "application/problem+json",
                            Policy.MAX_STATUS_OR_PROBLEM_BYTES,
                        ),
                    )
                cases.forEach { case ->
                    fixture.enqueue(case, case.cap)
                    assertEquals(case.cap, fixture.exchange(case.route).body.length)
                    fixture.enqueue(case, case.cap + 1)
                    assertFails { fixture.exchange(case.route) }
                }
                fixture.server.enqueue(MockResponse(body = "recovered"))
                assertEquals("recovered", fixture.exchange().body)
            }
        }

    private fun AndroidMutationEngineFixture.enqueue(
        case: Boundary,
        size: Int,
    ) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(case.status)
                .addHeader("Content-Type", case.media)
                .chunkedBody("x".repeat(size), CHUNK_BYTES)
                .build(),
        )
    }

    private data class Boundary(
        val route: ComplaintMutationRoute,
        val status: Int,
        val media: String,
        val cap: Int,
    )

    private companion object {
        const val CREATED = 201
        const val OK = 200
        const val CONFLICT = 409
        const val CHUNK_BYTES = 8_192
    }
}
