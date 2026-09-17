package me.manga.kira.data.remote.complaint

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidComplaintHistoryRequestTest {
    @Test
    fun realGetUsesExactAuthenticatedPagesWithoutCookiesBodyOrCacheReuse() =
        runBlocking {
            AndroidHistoryEngineFixture(basePath = "/base_1/v2").use { fixture ->
                fixture.server.enqueue(
                    MockResponse.Builder()
                        .addHeader("Cache-Control", "public, max-age=3600")
                        .addHeader("Set-Cookie", "history=synthetic; Path=/; Secure")
                        .body("first").build(),
                )
                fixture.server.enqueue(MockResponse(body = "second"))
                fixture.server.enqueue(MockResponse(body = "fresh"))
                assertEquals("first", fixture.get().body)
                assertEquals("second", fixture.get(Url("${fixture.url}?limit=50&cursor=v1.a.b")).body)
                assertEquals("fresh", fixture.get().body)
                listOf("limit=50", "limit=50&cursor=v1.a.b", "limit=50").forEach { query ->
                    assertRequest(fixture, query)
                }
                assertEquals(3, fixture.server.requestCount)
            }
        }

    @Test
    fun changedOriginMethodRouteAndQueryAreRejectedBeforeHttp() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                fixture.server.enqueue(MockResponse(body = "allowed"))
                assertQuerylessReplacementRetainsQuery(fixture)
                fixture.reject("method_post") { method = HttpMethod.Post }
                fixture.reject("method_head") { method = HttpMethod.Head }
                fixture.reject("missing_query") {
                    url.parameters.clear()
                    assertEquals(fixture.url, url.build(), "missing_query_effective_url")
                }
                fixture.reject("route_suffix") { url("${fixture.url}/other?limit=50") }
                fixture.reject("session_route") {
                    url(fixture.server.url("/api/v1/installations/session?limit=50").toString())
                }
                fixture.reject("changed_host") { url(fixture.firstPage.toString().replace("localhost", "127.0.0.1")) }
                fixture.reject("changed_scheme") { url(fixture.firstPage.toString().replace("https://", "http://")) }
                fixture.reject("duplicate_limit") { url("${fixture.url}?limit=50&limit=50") }
                fixture.reject("extra_key") { url("${fixture.url}?limit=50&extra=1") }
                fixture.reject("empty_cursor") { url("${fixture.url}?limit=50&cursor=") }
                fixture.reject("duplicate_cursor") { url("${fixture.url}?limit=50&cursor=v1.a.b&cursor=v1.a.c") }
                assertEquals("allowed", fixture.get().body)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun noOtherCredentialsEncodingOrGetBodyCanReachTheNativeRequest() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                fixture.server.enqueue(MockResponse(body = "allowed"))
                listOf(
                    "Cookie" to "synthetic=value", "Cookie2" to "synthetic=value",
                    "Proxy-Authorization" to "Basic synthetic", "Authorization" to HISTORY_TEST_AUTHORIZATION,
                    "Accept-Encoding" to "gzip", "Content-Type" to "application/json", "Content-Encoding" to "gzip",
                    "Transfer-Encoding" to "chunked",
                ).forEach { (name, value) -> fixture.reject { headers.append(name, value) } }
                fixture.reject { headers.remove("Authorization") }
                fixture.reject { headers.remove("Accept-Encoding") }
                fixture.reject { setBody(ByteArrayContent("{}".encodeToByteArray(), null)) }
                fixture.reject { setBody(ByteArrayContent("{}".encodeToByteArray(), ContentType.Application.Json)) }
                assertEquals("allowed", fixture.get().body)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    private fun assertQuerylessReplacementRetainsQuery(fixture: AndroidHistoryEngineFixture) {
        val request = HttpRequestBuilder()
        request.url(fixture.firstPage.toString())
        request.url(fixture.url.toString())
        assertEquals(fixture.firstPage, request.url.build(), "original_queryless_replacement_retains_limit")
    }

    private suspend fun AndroidHistoryEngineFixture.reject(
        vector: String = "request",
        change: HttpRequestBuilder.() -> Unit,
    ) {
        val statement =
            client.prepareGet(firstPage.toString()) {
                historyTestHeaders()
                change()
            }
        assertFails(vector) { statement.execute { } }
        assertEquals(0, server.requestCount, vector)
    }

    private fun assertRequest(
        fixture: AndroidHistoryEngineFixture,
        query: String,
    ) {
        val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/base_1/v2/api/v1/complaints?$query", request.target)
        assertEquals(HISTORY_TEST_AUTHORIZATION, request.headers["Authorization"])
        assertEquals("identity", request.headers["Accept-Encoding"])
        assertEquals("no-store, no-transform", request.headers["Cache-Control"])
        assertEquals(0L, request.bodySize)
        listOf("Cookie", "Cookie2", "Proxy-Authorization", "Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding")
            .forEach { assertNull(request.headers[it]) }
    }
}
