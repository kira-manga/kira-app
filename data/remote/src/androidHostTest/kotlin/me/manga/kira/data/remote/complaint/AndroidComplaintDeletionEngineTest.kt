package me.manga.kira.data.remote.complaint

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

class AndroidComplaintDeletionEngineTest {
    @Test
    fun factoryRejectsSessionHistoryAndAmbiguousTargetsBeforeAllocatingNativeResources() {
        listOf(
            "https://example.invalid/api/v1/installations/session",
            "https://example.invalid/api/v1/complaints",
            "https://example.invalid/bad/../api/v1/installations/delete-all",
            "https://user@example.invalid/api/v1/installations/delete-all",
            "https://example.invalid/api/v1/installations/delete-all?",
        ).forEach { value ->
            val owner = createAndroidComplaintDeletionEngineOwner(Url(value))
            try {
                assertNull(owner)
            } finally {
                owner?.close()
            }
        }
    }

    @Test
    fun nativePostKeepsExactPrefixBodyLimitAndKeyWithoutBearerOrOtherCredentials() =
        runBlocking {
            AndroidDeletionEngineFixture("/base_1/v2").use { fixture ->
                fixture.server.enqueue(MockResponse.Builder().code(NO_CONTENT).build())
                assertEquals(NO_CONTENT, fixture.exchange(Policy.MAX_REQUEST_BYTES).status)
                val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/base_1/v2${Policy.PATH}", request.target)
                assertEquals(Policy.MAX_REQUEST_BYTES.toLong(), request.bodySize)
                assertEquals(DELETION_TEST_KEY, request.headers[Policy.IDEMPOTENCY_HEADER])
                assertEquals("identity", request.headers["Accept-Encoding"])
                assertEquals("ktor-client", request.headers["User-Agent"])
                listOf(
                    "Authorization",
                    "Cookie",
                    "Proxy-Authorization",
                    "If-Match",
                    "Content-Encoding",
                    "Transfer-Encoding",
                ).forEach { assertNull(request.headers[it]) }
            }
        }

    @Test
    fun changedMethodTargetKeyAndOversizedRequestHaveZeroNativeDispatch() =
        runBlocking {
            AndroidDeletionEngineFixture().use { fixture ->
                fixture.server.enqueue(MockResponse.Builder().code(NO_CONTENT).build())
                fixture.reject { method = HttpMethod.Get }
                fixture.reject { method = HttpMethod.Delete }
                fixture.reject { url("${fixture.url}?key=synthetic") }
                fixture.reject { url(fixture.url.toString().replace("delete-all", "session")) }
                fixture.reject { headers.remove(Policy.IDEMPOTENCY_HEADER) }
                fixture.reject { headers.append(Policy.IDEMPOTENCY_HEADER, DELETION_TEST_KEY) }
                fixture.reject { headers.append("Authorization", "Bearer synthetic") }
                fixture.reject { headers.append("Cookie", "synthetic=value") }
                fixture.reject { headers.append("Content-Encoding", "gzip") }
                fixture.reject { headers.append("User-Agent", "synthetic") }
                assertFails { fixture.exchange(Policy.MAX_REQUEST_BYTES + 1) }
                assertEquals(0, fixture.server.requestCount)
                assertEquals(NO_CONTENT, fixture.exchange().status)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun retryAfterZeroAndRedirectsCannotReplayTheOneShotBodyOrReachAnotherRoute() =
        runBlocking {
            AndroidDeletionEngineFixture().use { fixture ->
                fixture.server.enqueue(retryableDeletionResponse())
                fixture.server.enqueue(MockResponse.Builder().code(NO_CONTENT).build())
                assertEquals(SERVICE_UNAVAILABLE, fixture.exchange().status)
                assertEquals(1, fixture.server.requestCount)
                assertEquals(NO_CONTENT, fixture.exchange().status)
                fixture.server.enqueue(
                    MockResponse.Builder()
                        .code(TEMPORARY_REDIRECT)
                        .addHeader("Location", fixture.server.url("/other"))
                        .build(),
                )
                fixture.server.enqueue(MockResponse.Builder().code(NO_CONTENT).build())
                assertFails { fixture.exchange() }
                assertEquals(REDIRECT_REQUEST_COUNT, fixture.server.requestCount)
                assertEquals(NO_CONTENT, fixture.exchange().status)
                assertEquals(FINAL_REQUEST_COUNT, fixture.server.requestCount)
            }
        }

    private suspend fun AndroidDeletionEngineFixture.reject(change: HttpRequestBuilder.() -> Unit) {
        assertFails { exchange(change = change) }
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val NO_CONTENT = 204
        const val TEMPORARY_REDIRECT = 307
        const val REDIRECT_REQUEST_COUNT = 3
        const val FINAL_REQUEST_COUNT = 4
    }
}

private const val SERVICE_UNAVAILABLE = 503

private fun retryableDeletionResponse(): MockResponse =
    MockResponse.Builder()
        .code(SERVICE_UNAVAILABLE)
        .addHeader("Retry-After", "0")
        .addHeader("Content-Type", "application/problem+json")
        .body("synthetic")
        .build()
