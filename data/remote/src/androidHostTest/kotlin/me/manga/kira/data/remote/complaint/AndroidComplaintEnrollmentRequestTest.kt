package me.manga.kira.data.remote.complaint

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AndroidComplaintEnrollmentRequestTest {
    @Test
    fun changedMethodPathOriginAndSessionAreRejectedBeforeHttp() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                fixture.server.enqueue(MockResponse(body = "allowed"))
                val changes: List<HttpRequestBuilder.() -> Unit> =
                    listOf(
                        { method = HttpMethod.Put },
                        { method = HttpMethod.Get },
                        { url(fixture.bootstrapUrl.toString()) },
                        { url("${fixture.url}/session") },
                        { url(fixture.server.url("/sibling/api/v1/installations").toString()) },
                        { url(fixture.url.toString().replace("localhost", "127.0.0.1")) },
                        { url(fixture.url.toString().replace("https://", "http://")) },
                    )
                changes.forEach { fixture.rejectEnrollment(it) }
                fixture.rejectBootstrap("POST on bootstrap") { method = HttpMethod.Post }
                fixture.rejectBootstrap("GET on enrollment") { url(fixture.url.toString()) }
                assertEquals("allowed", fixture.enroll().body)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun bootstrapRejectsBodyContentAndFramingEvenWhenKtorDropsGetBody() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                fixture.server.enqueue(MockResponse(body = "allowed"))
                listOf(
                    "Content-Type" to "application/json",
                    "Transfer-Encoding" to "chunked",
                    "Content-Encoding" to "gzip",
                ).forEach { (name, value) ->
                    fixture.rejectBootstrap("GET $name header") { headers.append(name, value) }
                }
                // Ktor strips Content-Length on an empty GET; nonempty content preserves its length, but not its body.
                fixture.rejectBootstrap("GET derived Content-Length without Content-Type") {
                    setBody(ByteArrayContent("{}".encodeToByteArray(), null))
                }
                fixture.rejectBootstrap("GET JSON body content") {
                    setBody(ByteArrayContent("{}".encodeToByteArray(), ContentType.Application.Json))
                }
                assertEquals("allowed", fixture.bootstrap().body)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun bothRoutesRejectCredentialHeadersAndNonIdentityEncoding() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                fixture.server.enqueue(MockResponse(body = "allowed"))
                listOf(
                    "Cookie" to "synthetic=value",
                    "Authorization" to "Bearer synthetic-test-only",
                    "Proxy-Authorization" to "Basic synthetic-test-only",
                    "Accept-Encoding" to "gzip",
                    "Accept-Encoding" to "identity",
                ).forEach { (name, value) ->
                    fixture.rejectBootstrap { headers.append(name, value) }
                    fixture.rejectEnrollment { headers.append(name, value) }
                }
                fixture.rejectBootstrap { headers.remove("Accept-Encoding") }
                fixture.rejectEnrollment { headers.remove("Accept-Encoding") }
                assertEquals("allowed", fixture.bootstrap().body)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun enrollmentRequiresPositiveKnownBodyLengthAtMostFourKibibytes() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                fixture.server.enqueue(MockResponse(body = "allowed"))
                listOf(ByteArray(0), ByteArray(MAX_REQUEST_BYTES + 1)).forEach { bytes ->
                    fixture.rejectEnrollment { setBody(ByteArrayContent(bytes, ContentType.Application.Json)) }
                }
                fixture.rejectEnrollment { setBody(object : OutgoingContent.NoContent() {}) }
                fixture.rejectEnrollment { setBody(unknownLengthBody()) }
                fixture.client
                    .preparePost(fixture.url.toString()) {
                        headers { append("Accept-Encoding", "identity") }
                        setBody(ByteArrayContent(ByteArray(MAX_REQUEST_BYTES), ContentType.Application.Json))
                    }.execute { assertEquals(HttpStatusCode.OK.value, it.status.value) }
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun oldSessionEngineRefusesBothNewRoutesBeforeHttp() =
        runBlocking {
            AndroidSessionEngineFixture().use { fixture ->
                fixture.server.enqueue(MockResponse(body = "allowed"))
                val enrollmentUrl = Url(fixture.url.toString().removeSuffix("/session"))
                assertFails { enrollmentExchange(fixture.client, enrollmentUrl) }
                assertFails { bootstrapExchange(fixture.client, Url("$enrollmentUrl/bootstrap")) }
                assertEquals(0, fixture.server.requestCount)
                assertEquals("allowed", fixture.exchange().body)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    private suspend fun AndroidSessionEngineFixture.rejectEnrollment(change: HttpRequestBuilder.() -> Unit) {
        assertFails {
            client
                .preparePost(url.toString()) {
                    headers { append("Accept-Encoding", "identity") }
                    setBody(ByteArrayContent(ENROLLMENT_TEST_BODY.encodeToByteArray(), ContentType.Application.Json))
                    change()
                }.execute { }
        }
        assertEquals(0, server.requestCount)
    }

    private suspend fun AndroidSessionEngineFixture.rejectBootstrap(
        case: String = "bootstrap request",
        change: HttpRequestBuilder.() -> Unit,
    ) {
        assertFails(case) {
            client
                .prepareRequest(bootstrapUrl.toString()) {
                    method = HttpMethod.Get
                    headers { append("Accept-Encoding", "identity") }
                    change()
                }.execute { }
        }
        assertEquals(0, server.requestCount)
    }

    private fun unknownLengthBody(): OutgoingContent =
        object : OutgoingContent.ReadChannelContent() {
            override val contentType: ContentType = ContentType.Application.Json

            override fun readFrom(): ByteReadChannel = ByteReadChannel("{}".encodeToByteArray())
        }

    private companion object {
        const val MAX_REQUEST_BYTES = 4 * 1_024
    }
}
