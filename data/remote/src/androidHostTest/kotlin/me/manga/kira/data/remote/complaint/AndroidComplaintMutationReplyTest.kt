package me.manga.kira.data.remote.complaint

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Existing owned HTTPS fixture only. Bodies exercise native framing, not the higher-level receipt decoder. */
@Suppress("MagicNumber") // Fixed protocol statuses, receive chunks and exact fixture request counts.
class AndroidComplaintMutationReplyTest {
    @Test
    fun nativeReplyPreservesCanonicalNoticeParentAndRefusesMissingKeyPreconditionAndQueryBeforeHttp() =
        runBlocking {
            AndroidMutationEngineFixture("/base_1/v2").use { fixture ->
                val replyUrl = replyPolicyUrl(fixture.createUrl.toString(), REPLY_POLICY_NOTICE)
                fixture.server.enqueue(
                    MockResponse
                        .Builder()
                        .code(201)
                        .addHeader("Content-Type", "application/json")
                        .body("accepted")
                        .build(),
                )
                val invalid =
                    listOf<HttpRequestBuilder.() -> Unit>(
                        { headers.remove(Policy.IDEMPOTENCY_HEADER) },
                        { headers.append("If-Match", "\"synthetic\"") },
                        { method = HttpMethod.Get },
                        { url("$replyUrl?owner=private") },
                    )
                for (change in invalid) {
                    assertFails {
                        fixture.exchange(ComplaintMutationRoute.REPLY) {
                            url(replyUrl)
                            change()
                        }
                    }
                    assertEquals(0, fixture.server.requestCount)
                }
                val response =
                    fixture.exchange(ComplaintMutationRoute.REPLY, Policy.MAX_REQUEST_BYTES) { url(replyUrl) }
                assertEquals(201, response.status)
                assertEquals("accepted", response.body)
                val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/base_1/v2/api/v1/complaints/$REPLY_POLICY_NOTICE/replies", request.target)
                assertEquals(Policy.MAX_REQUEST_BYTES.toLong(), request.bodySize)
                assertEquals(MUTATION_TEST_KEY, request.headers[Policy.IDEMPOTENCY_HEADER])
                assertEquals(MUTATION_TEST_AUTHORIZATION, request.headers["Authorization"])
                assertEquals("identity", request.headers["Accept-Encoding"])
                assertEquals("ktor-client", request.headers["User-Agent"])
                listOf("If-Match", "Cookie", "Proxy-Authorization", "Content-Encoding", "Transfer-Encoding")
                    .forEach { assertNull(request.headers[it]) }
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun nativeReplyAcknowledgementAndParentProblemsKeepTheirDistinctReceiveCapsAndReleaseFailedCalls() =
        runBlocking {
            AndroidMutationEngineFixture().use { fixture ->
                val replyUrl = replyPolicyUrl(fixture.createUrl.toString())
                val cases =
                    listOf(
                        Boundary(201, "application/json", Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES),
                        Boundary(404, "application/problem+json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
                        Boundary(409, "application/problem+json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
                    )
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
                            val response = fixture.exchange(ComplaintMutationRoute.REPLY) { url(replyUrl) }
                            assertEquals(case.status, response.status)
                            assertEquals(case.cap, response.body.length)
                        } else {
                            assertFails { fixture.exchange(ComplaintMutationRoute.REPLY) { url(replyUrl) } }
                        }
                    }
                }
                fixture.server.enqueue(MockResponse(body = "next-explicit-call"))
                assertEquals(
                    "next-explicit-call",
                    fixture.exchange(ComplaintMutationRoute.REPLY) { url(replyUrl) }.body,
                )
                assertEquals(
                    7,
                    fixture.server.requestCount,
                    "No native retry or replacement dispatch after an overflow.",
                )
            }
        }

    private data class Boundary(
        val status: Int,
        val media: String,
        val cap: Int,
    )
}
