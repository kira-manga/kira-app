package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class ComplaintSessionHttpTest {
    @Test
    fun onlyExplicitHttpsOriginAndExactBodyCarryCredentials() =
        runTest {
            listOf(
                "http://example.invalid",
                "https://user:password@example.invalid",
                "https://example.invalid?x=1",
                "https://example.invalid#fragment",
                "https://example.invalid/a/../b",
                "https://example.invalid/%2e%2e",
                "https://example.invalid//b",
                "https://example.invalid/a%2fb",
                "https://example.invalid\\evil",
                "https://example.invalid/ space",
                "https://example.invalid:0",
                "https://example.invalid:65536",
            ).forEach { assertNull(ComplaintBackendEndpoint.checked(it)) }
            assertNotNull(ComplaintBackendEndpoint.checked("https://example.invalid"))
            assertEquals(
                "$SESSION_BASE_URL/api/v1/installations/session",
                assertNotNull(ComplaintBackendEndpoint.checked("$SESSION_BASE_URL/")).sessionUrl.toString(),
            )
            val fixture = ComplaintSessionFixture(this)
            try {
                assertIs<ComplaintSessionResult.Ready>(fixture.manager.session())
                val request = fixture.engine.requestHistory.single()
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("$SESSION_BASE_URL/api/v1/installations/session", request.url.toString())
                assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
                assertEquals("no-store, no-transform", request.headers[HttpHeaders.CacheControl])
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertEquals(ContentType.Application.Json, request.body.contentType)
                val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                assertEquals(setOf("installationId", "secret", "expectedDataScopeId"), body.keys)
                assertEquals(Fixtures.ID, body.getValue("installationId").jsonPrimitive.content)
                assertEquals(Fixtures.secret, body.getValue("secret").jsonPrimitive.content)
                assertEquals(Fixtures.SCOPE, body.getValue("expectedDataScopeId").jsonPrimitive.content)
                assertFalse(Fixtures.ID in request.url.toString())
                assertFalse(Fixtures.secret in request.headers.toString())
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun redirectsAuthenticationAndServerFailuresNeverReplayOrEraseEvidence() =
        runTest {
            for (code in listOf(301, 307, 401, 403, 404, 409, 410, 429, 500, 503)) {
                val status = HttpStatusCode.fromValue(code)
                val fixture =
                    ComplaintSessionFixture(this) {
                        respond(
                            "{}",
                            status,
                            sessionHeaders(status) { append(HttpHeaders.Location, "https://other.invalid/") },
                        )
                    }
                val slot = sessionPendingSlot(dispatched = true)
                fixture.storage.pending.slots += slot
                try {
                    assertEquals(code, assertIs<ComplaintSessionResult.HttpFailure>(fixture.manager.session()).status)
                    assertEquals(1, fixture.engine.requestHistory.size)
                    fixture.assertPreserved(slots = listOf(slot))
                } finally {
                    fixture.close()
                }
            }
        }

    /** The complete selected-header/framing boundary is kept in one discriminator group. */
    @Test
    fun duplicateMissingConflictingAndInvalidHeadersFailBeforeResponsePublication() =
        runTest {
            val contract = ComplaintBoundedResponse.CONTRACT_HEADER
            val length = sessionResponse().encodeToByteArray().size
            val cases = invalidSessionHeaders(contract, length)
            for (case in cases) {
                val channel = SessionTrackedChannel(ByteReadChannel(sessionResponse().encodeToByteArray()))
                val fixture = ComplaintSessionFixture(this) { respond(channel, case.status, case.headers) }
                try {
                    assertSessionFailure(case.expected, fixture.manager.session())
                    assertTrue(channel.cancelled)
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun actualBytesBoundMissingChunkedAndTruthfulLengthsBeforeStrictUtf8() =
        runTest {
            val exact = sessionResponse().padEnd(ComplaintBoundedResponse.MAX_BYTES).encodeToByteArray()
            val accepted =
                listOf(
                    sessionHeaders(),
                    sessionHeaders { append(HttpHeaders.ContentLength, exact.size.toString()) },
                    sessionHeaders { append(HttpHeaders.TransferEncoding, "chunked") },
                    sessionHeaders {
                        set(HttpHeaders.ContentType, "application/json; charset=\"UTF-8\"")
                        append(HttpHeaders.ContentEncoding, "identity")
                    },
                )
            for (headers in accepted) {
                val channel = SessionTrackedChannel(ByteReadChannel(exact))
                val fixture = ComplaintSessionFixture(this) { respond(channel, HttpStatusCode.OK, headers) }
                try {
                    assertIs<ComplaintSessionResult.Ready>(fixture.manager.session())
                    assertTrue(channel.cancelled)
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
            val rejected =
                listOf(
                    (exact + byteArrayOf(32)) to HttpStatusCode.OK,
                    byteArrayOf(0xC3.toByte(), 0x28) to HttpStatusCode.OK,
                    (exact + byteArrayOf(32)) to HttpStatusCode.Gone,
                )
            for ((body, status) in rejected) {
                val channel = SessionTrackedChannel(ByteReadChannel(body))
                val fixture = ComplaintSessionFixture(this) { respond(channel, status, sessionHeaders(status)) }
                try {
                    assertSessionFailure(
                        if (body.size > exact.size) Failure.TOO_LARGE else Failure.UTF8,
                        fixture.manager.session(),
                    )
                    assertTrue(channel.cancelled)
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
            for (status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound)) {
                for (framing in listOf("missing", "chunked", "declared")) {
                    for (afterPrefix in listOf(false, true)) {
                        assertFailedSessionEofCannotAuthorize(status, framing, afterPrefix)
                    }
                }
            }
        }
}

private class HeaderFailure(
    val expected: Failure,
    val headers: Headers,
    val status: HttpStatusCode = HttpStatusCode.OK,
)

private class SessionTrackedChannel(
    private val delegate: ByteReadChannel,
) : ByteReadChannel by delegate {
    var cancelled = false

    override fun cancel(cause: Throwable?) {
        cancelled = true
        delegate.cancel(cause)
    }
}

private fun invalidSessionHeaders(
    contract: String,
    length: Int,
): List<HeaderFailure> =
    listOf(
        HeaderFailure(Failure.CONTRACT, sessionHeaders { remove(contract) }),
        HeaderFailure(Failure.CONTRACT, sessionHeaders { set(contract, "2") }),
        HeaderFailure(Failure.HEADERS, sessionHeaders { append(contract, "1") }),
        HeaderFailure(Failure.HEADERS, sessionHeaders { remove(HttpHeaders.CacheControl) }),
        HeaderFailure(Failure.HEADERS, sessionHeaders { set(HttpHeaders.CacheControl, "no-store") }),
        HeaderFailure(
            Failure.HEADERS,
            sessionHeaders { set(HttpHeaders.CacheControl, "no-store, no-transform, public") },
        ),
        HeaderFailure(Failure.HEADERS, sessionHeaders { append(HttpHeaders.CacheControl, "no-store, no-transform") }),
        HeaderFailure(Failure.MEDIA, sessionHeaders { remove(HttpHeaders.ContentType) }),
        HeaderFailure(Failure.MEDIA, sessionHeaders { set(HttpHeaders.ContentType, "text/html") }),
        HeaderFailure(
            Failure.MEDIA,
            sessionHeaders { set(HttpHeaders.ContentType, "application/json; charset=utf-16") },
        ),
        HeaderFailure(Failure.HEADERS, sessionHeaders { append(HttpHeaders.ContentType, "application/json") }),
        HeaderFailure(Failure.ENCODING, sessionHeaders { append(HttpHeaders.ContentEncoding, "gzip") }),
        HeaderFailure(Failure.ENCODING, sessionHeaders { append(HttpHeaders.ContentEncoding, "identity, gzip") }),
        HeaderFailure(
            Failure.HEADERS,
            sessionHeaders { appendAll(HttpHeaders.ContentEncoding, listOf("identity", "identity")) },
        ),
        HeaderFailure(Failure.LENGTH, sessionHeaders { append(HttpHeaders.ContentLength, "-1") }),
        HeaderFailure(Failure.LENGTH, sessionHeaders { append(HttpHeaders.ContentLength, "+1") }),
        HeaderFailure(Failure.LENGTH, sessionHeaders { append(HttpHeaders.ContentLength, "$length,$length") }),
        HeaderFailure(Failure.LENGTH, sessionHeaders { append(HttpHeaders.ContentLength, "99999999999999999999999") }),
        HeaderFailure(
            Failure.HEADERS,
            sessionHeaders { appendAll(HttpHeaders.ContentLength, listOf("$length", "$length")) },
        ),
        HeaderFailure(Failure.TOO_LARGE, sessionHeaders { append(HttpHeaders.ContentLength, "16385") }),
        HeaderFailure(Failure.LENGTH, sessionHeaders { append(HttpHeaders.ContentLength, "${length - 1}") }),
        HeaderFailure(Failure.LENGTH, sessionHeaders { append(HttpHeaders.ContentLength, "${length + 1}") }),
        HeaderFailure(Failure.HEADERS, sessionHeaders { append(HttpHeaders.TransferEncoding, "gzip") }),
        HeaderFailure(
            Failure.HEADERS,
            sessionHeaders {
                append(HttpHeaders.ContentLength, "$length")
                append(HttpHeaders.TransferEncoding, "chunked")
            },
        ),
        HeaderFailure(Failure.HEADERS, sessionHeaders { append(HttpHeaders.ETag, "\"not-a-resource\"") }),
        HeaderFailure(
            Failure.HEADERS,
            sessionHeaders(HttpStatusCode.Unauthorized) { remove(HttpHeaders.WWWAuthenticate) },
            HttpStatusCode.Unauthorized,
        ),
    )

private suspend fun TestScope.assertFailedSessionEofCannotAuthorize(
    status: HttpStatusCode,
    framing: String,
    afterPrefix: Boolean,
) {
    val text = if (status == HttpStatusCode.OK) sessionResponse() else historyInstallationNotFoundProblem()
    val bytes = text.encodeToByteArray()
    val channel = ComplaintFailedEofChannel(bytes, afterPrefix)
    val headers =
        sessionHeaders(status) {
            when (framing) {
                "chunked" -> append(HttpHeaders.TransferEncoding, "chunked")
                "declared" -> append(HttpHeaders.ContentLength, bytes.size.toString())
                else -> Unit
            }
        }
    // Use the connected owner consumer so a false strict404 could actually invoke enrollment.
    val fixture = ComplaintHistoryFixture(this, sessionHandler = { respond(channel, status, headers) })
    try {
        val error = assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints()).error
        assertIs<AppError.Network.NoConnectivity>(error)
        assertNull(error.cause)
        assertEquals(afterPrefix, channel.prefixDrained)
        assertTrue(channel.cancelled)
        assertEquals(1, fixture.sessionRequests.size)
        assertTrue(fixture.historyRequests.isEmpty())
        assertTrue(fixture.enrollment.requests.isEmpty())
        assertTrue(
            fixture.enrollment.generator.scopes
                .isEmpty(),
        )
        fixture.assertPreserved()
    } finally {
        fixture.close()
    }
}
