package me.manga.kira.data.remote.complaint

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Uses the existing isolated HTTPS engine fixture; receipt/schema validation belongs to the data layer. */
@Suppress("MagicNumber") // Exact protocol cells and fixture request counts.
class AndroidComplaintMutationEditTest {
    @Test
    fun nativePatchPreservesExactContentJsonNonV4TargetKeyAndPrecondition() =
        runBlocking {
            AndroidMutationEngineFixture("/base_1/v2").use { fixture ->
                fixture.server.enqueue(MockResponse(body = "acknowledgement"))
                val response =
                    fixture.exchange(ComplaintMutationRoute.EDIT) {
                        setBody(ByteArrayContent(EDIT_BODY.encodeToByteArray(), ContentType.Application.Json))
                    }
                assertEquals(200, response.status)
                assertEquals("acknowledgement", response.body)
                val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("PATCH", request.method)
                assertEquals("/base_1/v2/api/v1/complaints/$MUTATION_TEST_PARENT/content", request.target)
                assertEquals(EDIT_BODY, request.body?.utf8())
                assertEquals(EDIT_BODY.encodeToByteArray().size.toLong(), request.bodySize)
                assertEquals(listOf(MUTATION_TEST_KEY), request.headers.values(Policy.IDEMPOTENCY_HEADER))
                assertEquals(listOf(MUTATION_TEST_PRECONDITION), request.headers.values("If-Match"))
                assertEquals(MUTATION_TEST_AUTHORIZATION, request.headers["Authorization"])
                assertEquals("application/json", request.headers["Content-Type"])
                assertEquals("application/json, application/problem+json", request.headers["Accept"])
                assertEquals("identity", request.headers["Accept-Encoding"])
                assertEquals("no-store, no-transform", request.headers["Cache-Control"])
                assertEquals("ktor-client", request.headers["User-Agent"])
                listOf("Cookie", "Proxy-Authorization", "Content-Encoding", "Transfer-Encoding")
                    .forEach { assertNull(request.headers[it]) }
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun wrongMethodTargetKeyPreconditionAndOversizedEditNeverReachHttpOrWidenOldPostRoutes() =
        runBlocking {
            AndroidMutationEngineFixture().use { fixture ->
                fixture.server.enqueue(MockResponse(body = "allowed"))
                val edit = "${fixture.createUrl}/$MUTATION_TEST_PARENT/content"
                val invalid = invalidEdits(edit)
                for (change in invalid) {
                    assertFails { fixture.exchange(ComplaintMutationRoute.EDIT, change = change) }
                    assertEquals(0, fixture.server.requestCount)
                }
                for (size in listOf(0, Policy.MAX_REQUEST_BYTES + 1)) {
                    assertFails { fixture.exchange(ComplaintMutationRoute.EDIT, size) }
                }
                val oldRoutes =
                    listOf(ComplaintMutationRoute.CREATE, ComplaintMutationRoute.REPLY, ComplaintMutationRoute.STATUS)
                for (route in oldRoutes) {
                    assertFails { fixture.exchange(route) { method = HttpMethod.Patch } }
                    assertFails { fixture.exchange(route) { headers.append("If-Match", MUTATION_TEST_PRECONDITION) } }
                }
                assertEquals(0, fixture.server.requestCount)
                assertEquals("allowed", fixture.exchange(ComplaintMutationRoute.EDIT, Policy.MAX_REQUEST_BYTES).body)
                val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("PATCH", request.method)
                assertEquals(Policy.MAX_REQUEST_BYTES.toLong(), request.bodySize)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun oneShotPatchKeepsRaw503And421WithoutFollowingRedirectsChallengesOrCookies() =
        runBlocking {
            AndroidMutationEngineFixture().use { fixture ->
                listOf(503, 421, 307, 401, 429).forEachIndexed { index, status ->
                    fixture.server.enqueue(terminalResponse(fixture, status))
                    fixture.server.enqueue(MockResponse(body = "next-explicit-call"))
                    assertEquals(status, fixture.exchange(ComplaintMutationRoute.EDIT).status)
                    assertEquals(index * 2 + 1, fixture.server.requestCount)
                    assertEquals("next-explicit-call", fixture.exchange(ComplaintMutationRoute.EDIT).body)
                    assertEquals(index * 2 + 2, fixture.server.requestCount)
                    repeat(2) {
                        val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                        assertEquals("PATCH", request.method)
                        assertEquals("/api/v1/complaints/$MUTATION_TEST_PARENT/content", request.target)
                        assertEquals(MUTATION_TEST_AUTHORIZATION, request.headers["Authorization"])
                        assertEquals(MUTATION_TEST_KEY, request.headers[Policy.IDEMPOTENCY_HEADER])
                        assertEquals(MUTATION_TEST_PRECONDITION, request.headers["If-Match"])
                        assertNull(request.headers["Cookie"])
                        assertNull(request.headers["Proxy-Authorization"])
                    }
                }
            }
        }

    private fun invalidEdits(edit: String): List<HttpRequestBuilder.() -> Unit> =
        listOf(
            { method = HttpMethod.Post },
            { url("$edit?version=1") },
            { url(edit.replace(MUTATION_TEST_PARENT, MUTATION_TEST_PARENT.uppercase())) },
            { url(edit.replace(MUTATION_TEST_PARENT, MUTATION_TEST_KEY)) },
            { url(edit.replace("localhost", "127.0.0.1")) },
            { headers.remove(Policy.IDEMPOTENCY_HEADER) },
            { headers[Policy.IDEMPOTENCY_HEADER] = MUTATION_TEST_PARENT },
            { headers.append(Policy.IDEMPOTENCY_HEADER, MUTATION_TEST_KEY) },
            { headers.remove("If-Match") },
            { headers["If-Match"] = "W/$MUTATION_TEST_PRECONDITION" },
            { headers["If-Match"] = "\"complaint-$MUTATION_TEST_KEY-v1\"" },
            { headers.append("if-match", MUTATION_TEST_PRECONDITION) },
            { headers.append("Content-Encoding", "gzip") },
            { headers.append("Cookie", "synthetic") },
            { setBody(ByteArrayContent(byteArrayOf(1), ContentType.Text.Plain)) },
        )

    private fun terminalResponse(fixture: AndroidMutationEngineFixture, status: Int): MockResponse =
        MockResponse
            .Builder()
            .code(status)
            .addHeader("Retry-After", "0")
            .addHeader("WWW-Authenticate", "Basic realm=\"test\"")
            .addHeader("Location", fixture.server.url("/forbidden"))
            .addHeader("Set-Cookie", "synthetic=value; Secure; Path=/")
            .addHeader("Cache-Control", "public, max-age=60")
            .body("terminal")
            .build()

    private companion object {
        const val EDIT_BODY = "{\"subject\":\"Updated subject\",\"body\":\"Updated body\"}"
    }
}
