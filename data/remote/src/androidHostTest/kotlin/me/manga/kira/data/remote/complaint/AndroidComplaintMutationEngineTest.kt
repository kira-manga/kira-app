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
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

class AndroidComplaintMutationEngineTest {
    @Test
    fun factoryRejectsTheStatusRouteAndNormalizationCannotAuthorizeANewBase() {
        listOf(
            "https://example.invalid" + Policy.STATUS_PATH,
            "https://example.invalid" + Policy.CREATE_PATH + "?limit=50",
            "https://example.invalid/bad/../api/v1/complaints",
            "https://user@example.invalid/api/v1/complaints",
        ).forEach { value ->
            val owner = createAndroidComplaintMutationEngineOwner(Url(value))
            try {
                assertNull(owner)
            } finally {
                owner?.close()
            }
        }
    }

    @Test
    fun nativePostPreservesBothRoutesAndOnlyCreateHasAKeyAtTheRequestLimit() =
        runBlocking {
            AndroidMutationEngineFixture("/base_1/v2").use { fixture ->
                ComplaintMutationRoute.entries.forEach { route ->
                    fixture.server.enqueue(MockResponse(body = "accepted"))
                    assertEquals("accepted", fixture.exchange(route, Policy.MAX_REQUEST_BYTES).body)
                    val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals("POST", request.method)
                    val path =
                        when (route) {
                            ComplaintMutationRoute.CREATE -> Policy.CREATE_PATH
                            ComplaintMutationRoute.REPLY ->
                                "${Policy.CREATE_PATH}/$MUTATION_TEST_PARENT${Policy.REPLIES_SUFFIX}"
                            ComplaintMutationRoute.STATUS -> Policy.STATUS_PATH
                        }
                    assertEquals("/base_1/v2$path", request.target)
                    assertEquals(Policy.MAX_REQUEST_BYTES.toLong(), request.bodySize)
                    assertEquals(MUTATION_TEST_AUTHORIZATION, request.headers["Authorization"])
                    assertEquals("identity", request.headers["Accept-Encoding"])
                    assertEquals("ktor-client", request.headers["User-Agent"])
                    val key = if (route != ComplaintMutationRoute.STATUS) MUTATION_TEST_KEY else null
                    assertEquals(key, request.headers[Policy.IDEMPOTENCY_HEADER])
                    listOf("If-Match", "Cookie", "Proxy-Authorization", "Content-Encoding", "Transfer-Encoding")
                        .forEach { assertNull(request.headers[it]) }
                }
                assertEquals(ComplaintMutationRoute.entries.size, fixture.server.requestCount)
            }
        }

    @Test
    fun changedMethodTargetQueryKeysAndOversizedRequestsNeverReachHttp() =
        runBlocking {
            AndroidMutationEngineFixture().use { fixture ->
                fixture.server.enqueue(MockResponse(body = "allowed"))
                fixture.reject { method = HttpMethod.Get }
                fixture.reject { method = HttpMethod.Put }
                fixture.reject { url("${fixture.createUrl}?limit=50") }
                fixture.reject { url("${fixture.createUrl}/other") }
                fixture.reject { url(fixture.createUrl.toString().replace("localhost", "127.0.0.1")) }
                fixture.reject { url(fixture.statusUrl.toString()) }
                fixture.reject { headers.append("If-Match", "\"synthetic\"") }
                fixture.reject { headers.append(Policy.IDEMPOTENCY_HEADER, MUTATION_TEST_KEY) }
                fixture.reject { headers.remove(Policy.IDEMPOTENCY_HEADER) }
                fixture.reject { headers.append("Content-Encoding", "gzip") }
                fixture.reject { headers.append("User-Agent", "synthetic") }
                fixture.reject { headers.append("User-Agent", "ktor-client,ktor-client") }
                fixture.reject {
                    headers.append("User-Agent", "ktor-client")
                    headers.append("user-agent", "ktor-client")
                }
                assertFails {
                    fixture.exchange(ComplaintMutationRoute.STATUS) {
                        headers.append(Policy.IDEMPOTENCY_HEADER, MUTATION_TEST_KEY)
                    }
                }
                assertFails { fixture.exchange(size = Policy.MAX_REQUEST_BYTES + 1) }
                assertEquals(0, fixture.server.requestCount)
                assertEquals("allowed", fixture.exchange().body)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    private suspend fun AndroidMutationEngineFixture.reject(change: HttpRequestBuilder.() -> Unit) {
        assertFails { exchange(change = change) }
        assertEquals(0, server.requestCount)
    }
}
