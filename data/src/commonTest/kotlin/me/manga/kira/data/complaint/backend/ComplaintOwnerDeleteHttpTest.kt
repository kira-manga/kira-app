package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.client.utils.EmptyContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class ComplaintOwnerDeleteHttpTest {
    private val direct = mobileOwnerDeleteHttpRequest()
    private val status = mobileOwnerDeleteStatusRequest()
    private val session = mutationSession()

    @Test
    fun exactBodylessDeleteAndMetadataOnlyStatusUseSeparateHeadersAndSuccessCells() =
        runTest {
            val f = mobileOwnerDeleteReportFixture()
            try {
                val deleted = assertIs<ComplaintOwnerDeleteHttpResult.Applied>(f.http.ownerDelete(direct, session))
                val result = f.http.ownerDeleteStatus(status, session)
                val observed = assertIs<ComplaintOwnerDeleteStatusHttpResult.Applied>(result)
                assertSame(direct, deleted.request)
                assertSame(status, observed.request)
                assertOwnerDeleteWire(f)
                assertOwnerDeleteStatusWire(f, status)
                assertEquals(2, f.requests.size)
            } finally {
                f.close()
            }
        }

    @Test
    fun direct401429503AndAllClosedDeleteErrorsAreObservationsNotAutomaticReplay() =
        runTest {
            val cells =
                listOf(
                    HttpStatusCode.Unauthorized to "UNAUTHORIZED",
                    HttpStatusCode.TooManyRequests to "RATE_LIMITED",
                    HttpStatusCode.ServiceUnavailable to "SERVICE_UNAVAILABLE",
                ) + ComplaintOwnerDeleteRejection.entries.map { HttpStatusCode.fromValue(it.status) to it.name }
            for ((code, problem) in cells) {
                val f =
                    ComplaintReportFixture(
                        this,
                        mutationHandler = { respond(mutationProblem(code, problem), code, mutationHeaders(code)) },
                    )
                try {
                    val result = assertIs<ComplaintOwnerDeleteHttpResult.HttpFailure>(f.http.ownerDelete(direct, session))
                    assertSame(direct, result.request)
                    assertEquals(code.value, result.status)
                    assertEquals(1, f.requests.size)
                } finally {
                    f.close()
                }
            }
        }

    @Test
    fun allFourSessionTupleMismatchesAndCloseBlockDeleteAndStatusBeforeIo() =
        runTest {
            val f = mobileOwnerDeleteReportFixture()
            try {
                val records =
                    listOf(
                        Fixtures.record(version = 2),
                        Fixtures.record(generation = 2),
                        Fixtures.record(Fixtures.material(id = Fixtures.OTHER_ID)),
                        Fixtures.record(Fixtures.material(scope = MOBILE_EDIT_SCOPE)),
                    )
                for (record in records) assertBothFail(f, mutationSession(record), ComplaintMutationFailure.INVALIDATED)
                f.http.close()
                assertBothFail(f, session, ComplaintMutationFailure.CLOSED)
                assertTrue(f.requests.isEmpty())
            } finally {
                f.close()
            }
        }

    private suspend fun assertBothFail(
        f: ComplaintReportFixture,
        current: ComplaintSessionResponse,
        reason: ComplaintMutationFailure,
    ) {
        val deleted = f.http.ownerDelete(direct, current)
        assertEquals(reason, assertIs<ComplaintOwnerDeleteHttpResult.Failed>(deleted).reason)
        val observed = f.http.ownerDeleteStatus(status, current)
        assertEquals(reason, assertIs<ComplaintOwnerDeleteStatusHttpResult.Failed>(observed).reason)
    }
}

private fun assertOwnerDeleteWire(f: ComplaintReportFixture) {
    val deletion = f.requests[0]
    assertEquals(HttpMethod.Delete, deletion.method)
    assertEquals("$SESSION_BASE_URL${Policy.CREATE_PATH}/$MOBILE_EDIT_ID", deletion.url.toString())
    assertSame(EmptyContent, deletion.body)
    assertEquals("", f.sentBodies[0])
    assertEquals(listOf(Fixtures.KEY), deletion.headers.getAll(Policy.IDEMPOTENCY_HEADER))
    assertEquals(listOf("\"complaint-$MOBILE_EDIT_ID-v7\""), deletion.headers.getAll(HttpHeaders.IfMatch))
    assertEquals("application/json, application/problem+json", deletion.headers[HttpHeaders.Accept])
    assertEquals("identity", deletion.headers[HttpHeaders.AcceptEncoding])
    assertEquals("no-store, no-transform", deletion.headers[HttpHeaders.CacheControl])
    assertEquals(listOf(mutationSession().authorizationValue()), deletion.headers.getAll(HttpHeaders.Authorization))
    assertNull(deletion.headers[HttpHeaders.ContentType])
    assertNull(deletion.headers[HttpHeaders.ContentEncoding])
    assertNull(deletion.headers[HttpHeaders.TransferEncoding])
    assertTrue(deletion.headers.getAll(HttpHeaders.ContentLength) in listOf(null, listOf("0")))
}

private fun assertOwnerDeleteStatusWire(f: ComplaintReportFixture, status: ComplaintOwnerDeleteStatusRequest) {
    val lookup = f.requests[1]
    assertEquals(HttpMethod.Post, lookup.method)
    assertEquals("$SESSION_BASE_URL${Policy.STATUS_PATH}", lookup.url.toString())
    assertNull(lookup.headers[HttpHeaders.IfMatch])
    assertNull(lookup.headers[Policy.IDEMPOTENCY_HEADER])
    val body = assertIs<JsonObject>(Json.parseToJsonElement(f.sentBodies[1]))
    assertEquals(setOf("operation", "key", "targetIds", "fingerprint"), body.keys)
    assertEquals("OWNER_DELETE", body.historyString("operation"))
    assertEquals(Fixtures.KEY, body.historyString("key"))
    assertEquals("[\"$MOBILE_EDIT_ID\"]", body["targetIds"].toString())
    assertEquals(status.pending.request.fingerprint.encoded, body.historyString("fingerprint"))
}
