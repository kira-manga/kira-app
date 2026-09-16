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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class InstallationEnrollmentHttpTest {
    @Test
    fun bootstrapHasNoBodyOrIdentityAndEnrollmentHasExactlyTheDurableFourFields() =
        runTest {
            val fixture = InstallationEnrollmentFixture(this)
            try {
                val result = assertIs<InstallationEnrollmentResult.Ready<Unit>>(fixture.enroll())
                assertEquals(Unit, result.value)
                assertEquals(listOf(HttpMethod.Get, HttpMethod.Post), fixture.requests.map { it.method })
                val bootstrap = fixture.requests.first()
                assertEquals("$SESSION_BASE_URL/api/v1/installations/bootstrap", bootstrap.url.toString())
                assertTrue(bootstrap.body.toByteArray().isEmpty())
                assertNull(bootstrap.body.contentType)
                assertNull(bootstrap.headers[HttpHeaders.ContentType])
                val request = fixture.requests.last()
                assertEquals("$SESSION_BASE_URL/api/v1/installations", request.url.toString())
                assertEquals(ContentType.Application.Json, request.body.contentType)
                val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                assertEquals(setOf("installationId", "secret", "platform", "expectedDataScopeId"), body.keys)
                assertEquals(Fixtures.ID, body.getValue("installationId").jsonPrimitive.content)
                assertEquals(Fixtures.secret, body.getValue("secret").jsonPrimitive.content)
                assertEquals("ANDROID", body.getValue("platform").jsonPrimitive.content)
                assertEquals(Fixtures.SCOPE, body.getValue("expectedDataScopeId").jsonPrimitive.content)
                fixture.requests.forEach {
                    assertEquals("identity", it.headers[HttpHeaders.AcceptEncoding])
                    assertEquals("no-store, no-transform", it.headers[HttpHeaders.CacheControl])
                    assertNull(it.headers[HttpHeaders.Authorization])
                    assertNull(it.headers[HttpHeaders.ProxyAuthorization])
                    assertNull(it.headers[HttpHeaders.Cookie])
                    assertFalse(Fixtures.ID in it.url.toString())
                }
                assertFalse(SESSION_TOKEN in result.toString())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun enrollmentCreatedRequiresExactLocationAndReplayCannotSmuggleDifferentBinding() =
        runTest {
            enrollmentCase()
            enrollmentCase(status = HttpStatusCode.OK)
            enrollmentCase(headers = enrollmentHeaders { remove(HttpHeaders.Location) }, failure = Failure.HEADERS)
            enrollmentCase(
                headers = enrollmentHeaders { set(HttpHeaders.Location, "https://other.invalid/") },
                failure = Failure.HEADERS,
            )
            enrollmentCase(
                status = HttpStatusCode.OK,
                headers = enrollmentHeaders(HttpStatusCode.OK) { append(HttpHeaders.ETag, "x") },
                failure = Failure.HEADERS,
            )
            enrollmentCase(
                body = sessionResponse().replace(Fixtures.ID, Fixtures.OTHER_ID),
                failure = Failure.INVALIDATED,
            )
            enrollmentCase(
                body = sessionResponse().replace("\"credentialVersion\":1", "\"credentialVersion\":2"),
                failure = Failure.INVALIDATED,
            )
            enrollmentCase(body = "{}", failure = Failure.RESPONSE)
        }

    @Test
    fun bootstrapActualBytesAndStrictDecodingFailBeforeEntropyOrStorage() =
        runTest {
            val exact = bootstrapResponse().padEnd(ComplaintBoundedResponse.MAX_BYTES).encodeToByteArray()
            bootstrapCase(exact)
            bootstrapCase(exact, sessionHeaders { append(HttpHeaders.TransferEncoding, "chunked") })
            bootstrapCase(exact + byteArrayOf(32), failure = Failure.TOO_LARGE)
            bootstrapCase(byteArrayOf(0xc3.toByte(), 0x28), failure = Failure.UTF8)
            bootstrapCase("{}".encodeToByteArray(), failure = Failure.RESPONSE)
        }

    @Test
    fun bootstrapHeadersAndEncodingFailBeforeEntropyOrStorage() =
        runTest {
            val body = bootstrapResponse().encodeToByteArray()
            bootstrapCase(body, sessionHeaders { set(ComplaintBoundedResponse.CONTRACT_HEADER, "2") }, Failure.CONTRACT)
            bootstrapCase(
                body,
                sessionHeaders { append(ComplaintBoundedResponse.CONTRACT_HEADER, "1") },
                Failure.HEADERS,
            )
            bootstrapCase(body, sessionHeaders { set(HttpHeaders.CacheControl, "no-store") }, Failure.HEADERS)
            bootstrapCase(body, sessionHeaders { append(HttpHeaders.ContentEncoding, "gzip") }, Failure.ENCODING)
            bootstrapCase(body, sessionHeaders { append(HttpHeaders.ContentLength, "1") }, Failure.LENGTH)
            bootstrapCase(body, sessionHeaders { set(HttpHeaders.ContentType, "text/html") }, Failure.MEDIA)
        }

    @Test
    fun redirectsTerminalErrorsAndOversizedEnrollmentProblemsNeverRetryOrErase() =
        runTest {
            for (code in listOf(301, 307, 403, 409, 410, 429, 500, 503)) {
                val status = HttpStatusCode.fromValue(code)
                val fixture =
                    InstallationEnrollmentFixture(this, enrollmentStorage()) {
                        respond(
                            "{}",
                            status,
                            enrollmentHeaders(status) { append(HttpHeaders.Location, "https://other.invalid/") },
                        )
                    }
                try {
                    assertEquals(code, assertIs<InstallationEnrollmentResult.HttpFailure>(fixture.enroll()).status)
                    assertEquals(1, fixture.requests.size)
                    assertTrue(
                        fixture.storage.faults.mutations
                            .isEmpty(),
                    )
                    assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(Fixtures.record()))
                } finally {
                    fixture.close()
                }
            }
            val fixture =
                InstallationEnrollmentFixture(this, enrollmentStorage()) {
                    respond(
                        " ".repeat(ComplaintBoundedResponse.MAX_BYTES + 1),
                        HttpStatusCode.Gone,
                        enrollmentHeaders(HttpStatusCode.Gone),
                    )
                }
            try {
                assertEquals(Failure.TOO_LARGE, assertIs<InstallationEnrollmentResult.Failed>(fixture.enroll()).reason)
                assertTrue(
                    fixture.storage.faults.mutations
                        .isEmpty(),
                )
            } finally {
                fixture.close()
            }
        }
}

private suspend fun TestScope.enrollmentCase(
    status: HttpStatusCode = HttpStatusCode.Created,
    headers: Headers = enrollmentHeaders(status),
    body: String = sessionResponse(),
    failure: Failure? = null,
) {
    val fixture = InstallationEnrollmentFixture(this, enrollmentStorage()) { respond(body, status, headers) }
    try {
        val result = fixture.enroll()
        if (failure == null) {
            assertIs<InstallationEnrollmentResult.Ready<Unit>>(result)
        } else {
            assertEquals(failure, assertIs<InstallationEnrollmentResult.Failed>(result).reason)
        }
        assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(Fixtures.record()))
        assertTrue(
            fixture.storage.faults.mutations
                .isEmpty(),
        )
        assertTrue(fixture.generator.scopes.isEmpty())
    } finally {
        fixture.close()
    }
}

private suspend fun TestScope.bootstrapCase(
    body: ByteArray,
    headers: Headers = sessionHeaders(),
    failure: Failure? = null,
) {
    val fixture =
        InstallationEnrollmentFixture(this) { request ->
            if (request.method == HttpMethod.Get) {
                respond(ByteReadChannel(body), HttpStatusCode.OK, headers)
            } else {
                respond(sessionResponse(), HttpStatusCode.Created, enrollmentHeaders())
            }
        }
    try {
        val result = fixture.enroll()
        if (failure == null) {
            assertIs<InstallationEnrollmentResult.Ready<Unit>>(result)
        } else {
            assertEquals(failure, assertIs<InstallationEnrollmentResult.Failed>(result).reason)
            assertTrue(fixture.generator.scopes.isEmpty())
            assertTrue(
                fixture.storage.faults.mutations
                    .isEmpty(),
            )
            fixture.storage.assertAbsent()
            assertEquals(1, fixture.requests.size)
        }
    } finally {
        fixture.close()
    }
}
