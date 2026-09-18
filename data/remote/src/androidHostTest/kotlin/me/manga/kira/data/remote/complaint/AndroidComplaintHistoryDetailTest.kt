package me.manga.kira.data.remote.complaint

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
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

class AndroidComplaintHistoryDetailTest {
    @Test
    fun actualHistoryEngineUsesExactQuerylessDetailGetWithoutAmbientOrMutationHeaders() =
        runBlocking {
            AndroidHistoryEngineFixture(basePath = "/base_1/v2").use { fixture ->
                fixture.server.enqueue(detailWireResponse("{}"))
                assertEquals("{}", fixture.get(Url("${fixture.url}/$DETAIL_ID")).body)
                val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("GET", request.method)
                assertEquals("/base_1/v2/api/v1/complaints/$DETAIL_ID", request.target)
                assertEquals(HISTORY_TEST_AUTHORIZATION, request.headers["Authorization"])
                assertEquals("identity", request.headers["Accept-Encoding"])
                assertEquals(0L, request.bodySize)
                listOf(
                    "Cookie",
                    "Proxy-Authorization",
                    "Content-Type",
                    "X-Kira-Idempotency-Key",
                    "If-Match",
                    "If-None-Match",
                    "If-Modified-Since",
                    "If-Unmodified-Since",
                    "If-Range",
                ).forEach { assertNull(request.headers[it]) }
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun detailQueryAliasesConditionalsMutationKeysAndBodiesAreRefusedBeforeHttp() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                fixture.server.enqueue(detailWireResponse("{}"))
                listOf(
                    "If-Match",
                    "If-None-Match",
                    "If-Modified-Since",
                    "If-Unmodified-Since",
                    "If-Range",
                    "X-Kira-Idempotency-Key",
                ).forEach { name -> fixture.rejectDetail { headers.append(name, "synthetic") } }
                fixture.rejectDetail { url("${fixture.url}/$DETAIL_ID?limit=50") }
                fixture.rejectDetail { url("${fixture.url}/$DETAIL_ID/replies") }
                fixture.rejectDetail { url("${fixture.url}/${DETAIL_ID.uppercase()}") }
                fixture.rejectDetail { method = HttpMethod.Post }
                fixture.rejectDetail { setBody(ByteArrayContent("{}".encodeToByteArray(), ContentType.Application.Json)) }
                assertEquals("{}", fixture.get(Url("${fixture.url}/$DETAIL_ID")).body)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun actualNativeReceiveSeparatesDetailJsonThirtyTwoKiBFromEveryProblemSixteenKiB() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                for ((status, cap) in listOf(200 to 32 * 1_024, 404 to 16 * 1_024)) {
                    val exact = "x".repeat(cap)
                    fixture.server.enqueue(detailWireResponse(exact, status, chunked = true))
                    assertEquals(exact, fixture.get(Url("${fixture.url}/$DETAIL_ID")).body)
                    fixture.server.enqueue(detailWireResponse(exact + "y", status, chunked = true))
                    assertFails { fixture.get(Url("${fixture.url}/$DETAIL_ID")) }
                    fixture.server.enqueue(detailWireResponse("{}"))
                    assertEquals("{}", fixture.get(Url("${fixture.url}/$DETAIL_ID")).body)
                }
                assertEquals(6, fixture.server.requestCount)
            }
        }

    @Test
    fun declaredOverflowAndWrongMediaCannotBorrowTheListBudget() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                fixture.server.enqueue(detailWireResponse("x".repeat(32 * 1_024 + 1)))
                assertFails { fixture.get(Url("${fixture.url}/$DETAIL_ID")) }
                fixture.server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "text/plain")
                        .body("x".repeat(16 * 1_024 + 1))
                        .build(),
                )
                assertFails { fixture.get(Url("${fixture.url}/$DETAIL_ID")) }
                fixture.server.enqueue(detailWireResponse("x".repeat(32 * 1_024 + 1)))
                assertEquals(32 * 1_024 + 1, fixture.get().body.length)
                assertEquals(3, fixture.server.requestCount)
            }
        }

    @Test
    fun detailGetRefusesRaw503And421BeforeAutomaticFollowUp() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                listOf(503, 421).forEachIndexed { index, status ->
                    fixture.server.enqueue(
                        MockResponse
                            .Builder()
                            .code(status)
                            .addHeader("Retry-After", "0")
                            .body("terminal")
                            .build(),
                    )
                    fixture.server.enqueue(detailWireResponse("{}"))
                    assertFails { fixture.get(Url("${fixture.url}/$DETAIL_ID")) }
                    assertEquals(index * 2 + 1, fixture.server.requestCount)
                    assertEquals("{}", fixture.get(Url("${fixture.url}/$DETAIL_ID")).body)
                    assertEquals(index * 2 + 2, fixture.server.requestCount)
                }
            }
        }

    private suspend fun AndroidHistoryEngineFixture.rejectDetail(change: HttpRequestBuilder.() -> Unit) {
        // Fresh URL builder: replacing a list URL with a queryless URL can retain the old Ktor query.
        val detailUrl = "$url/$DETAIL_ID"
        val request =
            HttpRequestBuilder().apply {
                url(detailUrl)
                method = HttpMethod.Get
                historyTestHeaders()
                change()
            }
        assertFails { client.prepareRequest(request).execute { } }
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val DETAIL_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}

private fun detailWireResponse(
    body: String,
    status: Int = 200,
    chunked: Boolean = false,
): MockResponse =
    MockResponse
        .Builder()
        .apply {
            code(status)
            addHeader("Content-Type", if (status == 200) "application/json" else "application/problem+json")
            if (chunked) chunkedBody(body, 8_192) else body(body)
        }.build()
