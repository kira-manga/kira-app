package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class ComplaintEditHttpTest {
    @Test
    fun exactPatchAndObservationalStatusUseSeparateBodiesHeadersAnd200Results() =
        runTest {
            val f = ComplaintEditHttpFixture(this)
            try {
                val direct = assertIs<ComplaintEditHttpResult.Applied>(f.http.edit(f.request, f.session))
                val status = assertIs<ComplaintEditStatusHttpResult.Applied>(f.http.editStatus(f.status, f.session))
                assertSame(f.request, direct.request)
                assertSame(f.status, status.request)
                assertEditPatchWire(f)
                assertEditStatusWire(f)
                assertEquals(2, f.requests.size)
            } finally {
                f.close()
            }
        }

    @Test
    fun noticeReplyOmitsSubjectRatherThanNullAndMaximumEscapedReplacementStaysBounded() =
        runTest {
            val f = ComplaintEditHttpFixture(this)
            val edit =
                mobileEditRequest(
                    target = mobileEditTarget(shape = ComplaintEditShape.BODY_ONLY),
                    subject = null,
                    body = "x\n\t\"\\y",
                )
            try {
                val request = assertNotNull(ComplaintEditHttpRequest.checked(edit, mobileEditPending(edit)))
                assertIs<ComplaintEditHttpResult.Applied>(f.http.edit(request, f.session))
                val root = assertIs<JsonObject>(Json.parseToJsonElement(f.sentBodies.single()))
                assertEquals(setOf("body"), root.keys)
                assertEquals(edit.body, root.historyString("body"))
                assertMaximumEditRequestBytes()
            } finally {
                f.close()
            }
        }

    @Test
    fun raw401429503AndDirectStalePreconditionNeverAutomaticallyReplay() =
        runTest {
            val cells =
                listOf(
                    HttpStatusCode.Unauthorized to "UNAUTHORIZED",
                    HttpStatusCode.TooManyRequests to "RATE_LIMITED",
                    HttpStatusCode.ServiceUnavailable to "SERVICE_UNAVAILABLE",
                    HttpStatusCode.PreconditionFailed to "PRECONDITION_FAILED",
                    HttpStatusCode.NotFound to "COMPLAINT_NOT_FOUND",
                    HttpStatusCode.Conflict to "COMPLAINT_NO_CHANGE",
                )
            for ((status, code) in cells) {
                val f = ComplaintEditHttpFixture(this) {
                    respond(mutationProblem(status, code), status, mobileEditHeaders(status))
                }
                try {
                    val result = assertIs<ComplaintEditHttpResult.HttpFailure>(f.http.edit(f.request, f.session))
                    assertSame(f.request, result.request)
                    assertEquals(status.value, result.status)
                    assertEquals(1, f.requests.size)
                } finally {
                    f.close()
                }
            }
        }

    @Test
    fun allFourSessionTupleMismatchesAndCloseBlockPatchAndStatusBeforeIo() =
        runTest {
            val f = ComplaintEditHttpFixture(this)
            try {
                val records =
                    listOf(
                        Fixtures.record(version = 2),
                        Fixtures.record(generation = 2),
                        Fixtures.record(Fixtures.material(id = Fixtures.OTHER_ID)),
                        Fixtures.record(Fixtures.material(scope = MOBILE_EDIT_SCOPE)),
                    )
                for (record in records) {
                    f.assertBothEditExchangesFail(mutationSession(record), ComplaintMutationFailure.INVALIDATED)
                }
                f.http.close()
                f.assertBothEditExchangesFail(f.session, ComplaintMutationFailure.CLOSED)
                assertTrue(f.requests.isEmpty())
            } finally {
                f.close()
            }
        }
}

private fun assertEditPatchWire(f: ComplaintEditHttpFixture) {
    val patch = f.requests[0]
    assertEquals(HttpMethod.Patch, patch.method)
    assertEquals("/gateway/api/v1/complaints/$MOBILE_EDIT_ID/content", patch.url.encodedPath)
    assertEquals(listOf("\"complaint-$MOBILE_EDIT_ID-v7\""), patch.headers.getAll(HttpHeaders.IfMatch))
    assertEquals(listOf(Fixtures.KEY), patch.headers.getAll(Policy.IDEMPOTENCY_HEADER))
    assertEquals("identity", patch.headers[HttpHeaders.AcceptEncoding])
    assertEquals("no-store, no-transform", patch.headers[HttpHeaders.CacheControl])
    assertEquals("""{"subject":"Synthetic edit","body":"Line 1\nLine 2"}""", f.sentBodies[0])
}

private fun assertMaximumEditRequestBytes() {
    val maximum = mobileEditRequest(subject = "🙂".repeat(200), body = "🙂".repeat(1000))
    val request = assertNotNull(ComplaintEditHttpRequest.checked(maximum, mobileEditPending(maximum)))
    val bytes = request.bodyBytes()
    assertTrue(bytes.size in 4800..Policy.MAX_REQUEST_BYTES)
    bytes.fill(0)
    assertEquals("🙂".repeat(1000), maximum.body)
}

private suspend fun ComplaintEditHttpFixture.assertBothEditExchangesFail(
    other: ComplaintSessionResponse,
    reason: ComplaintMutationFailure,
) {
    assertEquals(reason, assertIs<ComplaintEditHttpResult.Failed>(http.edit(request, other)).reason)
    assertEquals(reason, assertIs<ComplaintEditStatusHttpResult.Failed>(http.editStatus(status, other)).reason)
}

private fun assertEditStatusWire(f: ComplaintEditHttpFixture) {
    val lookup = f.requests[1]
    assertEquals(HttpMethod.Post, lookup.method)
    assertEquals("/gateway/api/v1/complaint-operations/status", lookup.url.encodedPath)
    assertNull(lookup.headers[HttpHeaders.IfMatch])
    assertNull(lookup.headers[Policy.IDEMPOTENCY_HEADER])
    val root = assertIs<JsonObject>(Json.parseToJsonElement(f.sentBodies[1]))
    assertEquals(setOf("operation", "key", "targetIds", "fingerprint"), root.keys)
    assertEquals("OWNER_EDIT", root.historyString("operation"))
    assertEquals(Fixtures.KEY, root.historyString("key"))
    assertEquals("[\"$MOBILE_EDIT_ID\"]", assertIs<JsonArray>(root["targetIds"]).toString())
    assertEquals(f.pending.request.fingerprint.encoded, root.historyString("fingerprint"))
}
