package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

/** Actual fixed client with the existing MockEngine fixture, not native or durable-recovery qualification. */
class ComplaintReplyMutationTest {
    @Test
    fun replyFactoriesAndFixedPostsBindTheExactParentAndOrderedNoProseStatusTuple() =
        runTest {
            val reply = mobileReplyRequest()
            val pending = mutationPending(reply)
            val create = assertNotNull(ComplaintCreateHttpRequest.checked(reply, pending))
            val status = assertNotNull(ComplaintCreateStatusRequest.checked(pending))
            assertEquals(ComplaintMutationRoute.REPLY, create.route)
            assertReplyFactoryBinding(reply, pending)
            val f = ComplaintMutationFixture(this)
            try {
                val direct = assertIs<ComplaintCreateHttpResult.Applied>(f.http.create(create, f.session))
                val receipt = assertIs<ComplaintCreateStatusHttpResult.Applied>(f.http.status(status, f.session))
                assertSame(create, direct.request)
                assertSame(status, receipt.request)
                assertReplyWire(f, reply, pending)
                assertEquals(2, f.requests.size)
            } finally {
                f.close()
            }
        }

    @Test
    fun replyAcknowledgementsRequireNewChildVersionOneEvenWhenAHigherVersionHasAMatchingTag() {
        val reply = mobileReplyRequest()
        val pending = mutationPending(reply)
        val create = assertNotNull(ComplaintCreateHttpRequest.checked(reply, pending))
        val status = assertNotNull(ComplaintCreateStatusRequest.checked(pending))
        val id = reply.identity.clientId.canonical
        for (version in listOf(1L, 2L, Long.MAX_VALUE)) {
            val document =
                ComplaintMutationDocument(
                    201,
                    mutationAck(version),
                    "${Policy.CREATE_PATH}/$id",
                    "\"complaint-$id-v$version\"",
                )
            val direct = ComplaintMutationResponse.create(document, create)
            val receipt =
                ComplaintMutationResponse.status(
                    ComplaintMutationDocument(200, mutationApplied(version), null, null),
                    status,
                )
            assertSame(create, direct.request)
            assertSame(status, receipt.request)
            if (version == 1L) {
                assertEquals(1L, assertIs<ComplaintCreateHttpResult.Applied>(direct).acknowledgement.version)
                assertEquals(1L, assertIs<ComplaintCreateStatusHttpResult.Applied>(receipt).acknowledgement.version)
            } else {
                assertMutationFailure(ComplaintMutationFailure.RESPONSE, direct)
                assertMutationFailure(ComplaintMutationFailure.RESPONSE, receipt)
            }
        }
        val parent = reply.parentId
        assertMutationFailure(
            ComplaintMutationFailure.RESPONSE,
            ComplaintMutationResponse.create(
                ComplaintMutationDocument(
                    201,
                    mutationAck(id = parent),
                    "${Policy.CREATE_PATH}/$parent",
                    "\"complaint-$parent-v1\"",
                ),
                create,
            ),
        )
        assertMutationFailure(
            ComplaintMutationFailure.RESPONSE,
            ComplaintMutationResponse.status(
                ComplaintMutationDocument(200, mutationApplied(id = parent), null, null),
                status,
            ),
        )
        assertReportPositiveVersionsStillDecode()
    }

    @Test
    fun replyStatusAcceptsOnlyFourClosedRejectionsWithTheirExactOriginalStatusesAndRequestIdentity() {
        val request = assertNotNull(ComplaintCreateStatusRequest.checked(mutationPending(mobileReplyRequest())))
        val other =
            assertNotNull(
                ComplaintCreateStatusRequest.checked(mutationPending(mobileReplyRequest(key = MUTATION_OTHER_KEY))),
            )
        val report = assertNotNull(ComplaintCreateStatusRequest.checked(mutationPending()))

        fun decode(text: String): ComplaintCreateStatusHttpResult =
            ComplaintMutationResponse.status(ComplaintMutationDocument(200, text, null, null), request)

        val cases =
            listOf<Pair<ComplaintCreationRejection, Int>>(
                ComplaintCreateRejection.COMPLAINT_CAPACITY_REACHED to 409,
                ComplaintCreateRejection.COMPLAINT_RESOURCE_ID_REUSED to 409,
                ComplaintReplyRejection.COMPLAINT_PARENT_NOT_FOUND to 404,
                ComplaintReplyRejection.COMPLAINT_DELETION_PENDING to 409,
            )
        for ((code, originalStatus) in cases) {
            assertEquals(originalStatus, code.status)
            val text = mutationRejected(code)
            val result = assertIs<ComplaintCreateStatusHttpResult.Rejected>(decode(text))
            assertSame(request, result.request)
            assertNotSame(other, result.request)
            assertEquals(code, result.code)
            val wrongStatus = if (originalStatus == 404) 409 else 404
            val invalid =
                listOf(
                    text.replace("\"originalStatus\":$originalStatus", "\"originalStatus\":$wrongStatus"),
                    text.dropLast(1) + ",\"body\":null}",
                    text.replace("REJECTED", "IN_PROGRESS"),
                    text.replace(code.wireCode, "COMPLAINT_PARENT_UNAVAILABLE"),
                )
            for (body in invalid) {
                val failure = decode(body)
                assertMutationFailure(ComplaintMutationFailure.RESPONSE, failure)
                assertSame(request, failure.request)
            }
            if (code is ComplaintReplyRejection) {
                val failure = ComplaintMutationResponse.status(ComplaintMutationDocument(200, text, null, null), report)
                assertMutationFailure(ComplaintMutationFailure.RESPONSE, failure)
                assertSame(report, failure.request)
            }
        }
    }

    @Test
    fun replyErrorsRemainBoundNonTerminalWithoutAutomaticRetryAndSessionMismatchCannotDispatch() =
        runTest {
            assertReplyHttpFailure(
                HttpStatusCode.NotFound,
                ComplaintMutationProblem.COMPLAINT_PARENT_NOT_FOUND,
                direct = true,
            )
            assertReplyHttpFailure(
                HttpStatusCode.Conflict,
                ComplaintMutationProblem.COMPLAINT_DELETION_PENDING,
                direct = true,
            )
            assertReplyHttpFailure(
                HttpStatusCode.NotFound,
                ComplaintMutationProblem.OPERATION_NOT_FOUND,
                direct = false,
            )
            assertReplyHttpFailure(HttpStatusCode.Unauthorized, null, direct = false)
            val reply = mobileReplyRequest()
            val pending = mutationPending(reply)
            val create = assertNotNull(ComplaintCreateHttpRequest.checked(reply, pending))
            val status = assertNotNull(ComplaintCreateStatusRequest.checked(pending))
            val f = ComplaintMutationFixture(this)
            try {
                val replacedSession = mutationSession(Fixtures.record(generation = 2))
                val direct = f.http.create(create, replacedSession)
                val lookup = f.http.status(status, replacedSession)
                assertMutationFailure(ComplaintMutationFailure.INVALIDATED, direct)
                assertMutationFailure(ComplaintMutationFailure.INVALIDATED, lookup)
                assertSame(create, direct.request)
                assertSame(status, lookup.request)
                assertTrue(f.requests.isEmpty())
            } finally {
                f.close()
            }
        }
}

private fun assertReplyFactoryBinding(
    reply: ComplaintReplyRequest,
    pending: PendingComplaintRecord,
) {
    assertNotNull(ComplaintCreateHttpRequest.checked(mobileReplyRequest(body = " \u00a0Reply\t "), pending))
    val prepared = mutationPending(reply, dispatched = false)
    assertNull(ComplaintCreateHttpRequest.checked(reply, prepared))
    assertNull(ComplaintCreateStatusRequest.checked(prepared))
    val mismatches =
        listOf<ComplaintCreationRequest>(
            mobileReplyRequest(parentId = Fixtures.ID),
            mobileReplyRequest(id = Fixtures.ID),
            mobileReplyRequest(key = MUTATION_OTHER_KEY),
            mobileReplyRequest(body = "Different"),
            mutationReport(),
        )
    for (mismatch in mismatches) assertNull(ComplaintCreateHttpRequest.checked(mismatch, pending))
    val otherScope = mutationPending(reply, Fixtures.record(Fixtures.material(scope = MUTATION_OTHER_SCOPE)))
    assertNull(ComplaintCreateHttpRequest.checked(reply, otherScope))
    assertNull(ComplaintCreateHttpRequest.checked(reply, mutationPending()))
}

private fun assertReportPositiveVersionsStillDecode() {
    // Report receipts retain their pre-existing positive-version decoder; reply must not narrow it.
    val report = mutationReport()
    val pending = mutationPending(report)
    val id = report.identity.clientId.canonical
    val direct =
        ComplaintMutationResponse.create(
            ComplaintMutationDocument(201, mutationAck(2), "${Policy.CREATE_PATH}/$id", "\"complaint-$id-v2\""),
            assertNotNull(ComplaintCreateHttpRequest.checked(report, pending)),
        )
    assertEquals(2L, assertIs<ComplaintCreateHttpResult.Applied>(direct).acknowledgement.version)
    val status =
        ComplaintMutationResponse.status(
            ComplaintMutationDocument(200, mutationApplied(2), null, null),
            assertNotNull(ComplaintCreateStatusRequest.checked(pending)),
        )
    assertEquals(2L, assertIs<ComplaintCreateStatusHttpResult.Applied>(status).acknowledgement.version)
}

private fun assertReplyWire(
    f: ComplaintMutationFixture,
    reply: ComplaintReplyRequest,
    pending: PendingComplaintRecord,
) {
    val urls =
        listOf(
            "$SESSION_BASE_URL${Policy.CREATE_PATH}/${reply.parentId}/replies",
            "$SESSION_BASE_URL${Policy.STATUS_PATH}",
        )
    assertEquals(urls, f.requests.map { it.url.toString() })
    for (request in f.requests) {
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(listOf("Bearer $SESSION_TOKEN"), request.headers.getAll(HttpHeaders.Authorization))
        assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
        assertNull(request.headers[HttpHeaders.IfMatch])
    }
    assertEquals(listOf(Fixtures.KEY), f.requests[0].headers.getAll(Policy.IDEMPOTENCY_HEADER))
    assertNull(f.requests[1].headers[Policy.IDEMPOTENCY_HEADER])
    val create = assertIs<JsonObject>(Json.parseToJsonElement(f.sentBodies[0]))
    assertEquals(setOf("id", "body", "metadata"), create.keys)
    assertEquals(reply.identity.clientId.canonical, create.historyString("id"))
    assertEquals(reply.body, create.historyString("body"))
    val metadata = assertIs<JsonObject>(create["metadata"])
    assertEquals(setOf("appVersion", "osVersion", "manufacturer", "deviceModel"), metadata.keys)
    assertSame(JsonNull, metadata["appVersion"])
    for (field in listOf("osVersion", "manufacturer", "deviceModel")) assertEquals("", metadata.historyString(field))
    val status = assertIs<JsonObject>(Json.parseToJsonElement(f.sentBodies[1]))
    assertEquals(setOf("operation", "key", "targetIds", "fingerprint"), status.keys)
    assertEquals("OWNER_REPLY", status.historyString("operation"))
    assertEquals(Fixtures.KEY, status.historyString("key"))
    assertEquals(
        listOf(reply.parentId, reply.identity.clientId.canonical),
        assertIs<JsonArray>(status["targetIds"]).map { it.jsonPrimitive.content },
    )
    assertEquals(pending.request.fingerprint.encoded, status.historyString("fingerprint"))
    assertTrue(f.sentBodies.all { it.encodeToByteArray().size <= Policy.MAX_REQUEST_BYTES })
}

private suspend fun TestScope.assertReplyHttpFailure(
    status: HttpStatusCode,
    problem: ComplaintMutationProblem?,
    direct: Boolean,
) {
    val reply = mobileReplyRequest()
    val pending = mutationPending(reply)
    val create = assertNotNull(ComplaintCreateHttpRequest.checked(reply, pending))
    val lookup = assertNotNull(ComplaintCreateStatusRequest.checked(pending))
    val f =
        ComplaintMutationFixture(this) {
            respond(mutationProblem(status, problem?.name ?: "UNAUTHORIZED"), status, mutationHeaders(status))
        }
    try {
        if (direct) {
            val result = assertIs<ComplaintCreateHttpResult.HttpFailure>(f.http.create(create, f.session))
            assertSame(create, result.request)
            assertEquals(status.value, result.status)
            assertEquals(problem, result.problem)
        } else {
            val result = assertIs<ComplaintCreateStatusHttpResult.HttpFailure>(f.http.status(lookup, f.session))
            assertSame(lookup, result.request)
            assertEquals(status.value, result.status)
            assertEquals(problem, result.problem)
        }
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}
