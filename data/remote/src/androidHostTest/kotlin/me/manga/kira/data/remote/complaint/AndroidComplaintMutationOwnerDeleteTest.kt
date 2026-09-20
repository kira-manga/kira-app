package me.manga.kira.data.remote.complaint

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Held Ktor EmptyContent through the existing isolated HTTPS fixture; no receipt-decoder claim. */
@Suppress("MagicNumber") // Closed HTTP cells and actual zero-byte snapshot cases.
class AndroidComplaintMutationOwnerDeleteTest {
    @Test
    fun nativeEmptyContentDeletePreservesTheBareNonV4TargetAndNoRepresentationHeaders() =
        runBlocking {
            AndroidMutationEngineFixture("/base_1/v2").use { fixture ->
                fixture.server.enqueue(emptyAcknowledgement())
                val response = fixture.exchange(ComplaintMutationRoute.OWNER_DELETE)
                assertEquals(204, response.status)
                assertEquals("", response.body)
                val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("DELETE", request.method)
                assertEquals("/base_1/v2/api/v1/complaints/$MUTATION_TEST_PARENT", request.target)
                assertEquals(0L, request.bodySize)
                assertDeleteHeaders(request.headers)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun invalidDeleteBodyMethodTargetKeyOrTagNeverReachesHttp() =
        runBlocking {
            AndroidMutationEngineFixture().use { fixture ->
                fixture.server.enqueue(emptyAcknowledgement())
                val url = "${fixture.createUrl}/$MUTATION_TEST_PARENT"
                invalidDeletes(url).forEach { change ->
                    assertFails { fixture.exchange(ComplaintMutationRoute.OWNER_DELETE, change = change) }
                    assertEquals(0, fixture.server.requestCount)
                }
                assertFails { fixture.exchange(ComplaintMutationRoute.OWNER_DELETE, size = 1) }
                assertEquals(0, fixture.server.requestCount)
                assertEquals(204, fixture.exchange(ComplaintMutationRoute.OWNER_DELETE).status)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun oneShotEmptyDeleteKeeps503And421RawUntilTheNextExplicitStatusCall() =
        runBlocking {
            AndroidMutationEngineFixture().use { fixture ->
                listOf(503, 421).forEachIndexed { index, status ->
                    fixture.server.enqueue(rawProblem(status))
                    fixture.server.enqueue(MockResponse(body = "explicit-status"))
                    assertEquals(status, fixture.exchange(ComplaintMutationRoute.OWNER_DELETE).status)
                    assertEquals(index * 2 + 1, fixture.server.requestCount)
                    assertEquals("explicit-status", fixture.exchange(ComplaintMutationRoute.STATUS).body)
                    assertEquals(index * 2 + 2, fixture.server.requestCount)
                    val deletion = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals("DELETE", deletion.method)
                    assertEquals(0L, deletion.bodySize)
                    assertDeleteHeaders(deletion.headers)
                    val observation = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals("POST", observation.method)
                    assertEquals(Policy.STATUS_PATH, observation.target)
                    assertNull(observation.headers[Policy.IDEMPOTENCY_HEADER])
                    assertNull(observation.headers["If-Match"])
                }
            }
        }

    @Test
    fun verifiedEmptySnapshotIsNullMediaAndOneShotWithoutReinvokingTheOriginalWriter() {
        val original = EmptyDeleteWriter()
        val snapshot = boundedComplaintOwnerDeleteBody(original)
        assertTrue(snapshot.isOneShot())
        assertEquals(0L, snapshot.contentLength())
        assertNull(snapshot.contentType())
        val outgoing = Buffer()
        try {
            repeat(2) { snapshot.writeTo(outgoing) } // Re-reading a snapshot never calls the supplied writer.
            assertEquals(0L, outgoing.size)
            assertEquals(1, original.writes)
        } finally {
            outgoing.clear()
        }
    }

    @Test
    fun emptySnapshotRefusesUnknownPositiveDuplexAndMediaBodiesBeforeWriting() {
        listOf(
            EmptyDeleteWriter(declared = -1),
            EmptyDeleteWriter(declared = 1),
            EmptyDeleteWriter(duplex = true),
            EmptyDeleteWriter(media = "application/json".toMediaType()),
        ).forEach { original ->
            assertFailsWith<IOException> { boundedComplaintOwnerDeleteBody(original) }
            assertEquals(0, original.writes)
        }
    }

    @Test
    fun lyingZeroLengthWriterAndSwallowedOverrunCannotProduceAnEmptySnapshot() {
        listOf(EmptyDeleteWriter(actual = 1), EmptyDeleteWriter(swallowOverrun = true)).forEach { original ->
            assertFailsWith<IOException> { boundedComplaintOwnerDeleteBody(original) }
            assertEquals(1, original.writes)
        }
    }

    private fun assertDeleteHeaders(headers: Headers) {
        assertEquals(listOf(MUTATION_TEST_KEY), headers.values(Policy.IDEMPOTENCY_HEADER))
        assertEquals(listOf(MUTATION_TEST_PRECONDITION), headers.values("If-Match"))
        assertEquals(MUTATION_TEST_AUTHORIZATION, headers["Authorization"])
        assertEquals("application/json, application/problem+json", headers["Accept"])
        assertEquals("identity", headers["Accept-Encoding"])
        assertEquals("no-store, no-transform", headers["Cache-Control"])
        assertEquals("ktor-client", headers["User-Agent"])
        assertTrue(headers.values("Content-Length").let { it.isEmpty() || it == listOf("0") })
        listOf("Content-Type", "Content-Encoding", "Transfer-Encoding", "Cookie", "Proxy-Authorization")
            .forEach { name -> assertNull(headers[name]) }
    }

    private fun invalidDeletes(deleteUrl: String): List<HttpRequestBuilder.() -> Unit> =
        listOf(
            { method = HttpMethod.Post },
            { url("$deleteUrl?version=1") },
            { url("$deleteUrl/content") },
            { url(deleteUrl.replace(MUTATION_TEST_PARENT, MUTATION_TEST_KEY)) },
            { headers.remove(Policy.IDEMPOTENCY_HEADER) },
            { headers[Policy.IDEMPOTENCY_HEADER] = MUTATION_TEST_PARENT },
            { headers.append(Policy.IDEMPOTENCY_HEADER, MUTATION_TEST_KEY) },
            { headers.remove("If-Match") },
            { headers["If-Match"] = "W/$MUTATION_TEST_PRECONDITION" },
            { headers.append("if-match", MUTATION_TEST_PRECONDITION) },
            { headers.append("Content-Encoding", "identity") },
            { headers.append("Transfer-Encoding", "chunked") },
            { setBody(ByteArrayContent(ByteArray(0), ContentType.Application.Json)) },
        )

    private fun emptyAcknowledgement(): MockResponse =
        MockResponse.Builder().code(204).removeHeader("Content-Length").build()

    private fun rawProblem(status: Int): MockResponse =
        MockResponse.Builder().code(status).addHeader("Retry-After", "0").body("problem").build()
}

private class EmptyDeleteWriter(
    private val declared: Long = 0,
    private val actual: Int = 0,
    private val media: MediaType? = null,
    private val duplex: Boolean = false,
    private val swallowOverrun: Boolean = false,
) : RequestBody() {
    var writes = 0
        private set

    override fun contentType(): MediaType? = media

    override fun contentLength(): Long = declared

    override fun isDuplex(): Boolean = duplex

    override fun writeTo(sink: BufferedSink) {
        writes++
        if (!swallowOverrun) {
            sink.write(ByteArray(actual))
        } else {
            try {
                sink.writeByte(0)
                sink.flush()
            } catch (_: IOException) {
                sink.buffer.clear()
            }
        }
    }
}
