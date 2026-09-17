package me.manga.kira.data.complaint.backend

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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

class ComplaintMutationResponseTest {
    @Test
    fun acknowledgementRequiresClosedCanonicalScalarsAndMatchingIdLocationAndEtag() {
        val report = mutationReport()
        val request = assertNotNull(ComplaintCreateHttpRequest.checked(report, mutationPending(report)))
        val id = Fixtures.OTHER_ID
        val location = "${Policy.CREATE_PATH}/$id"
        val tag = "\"complaint-$id-v1\""
        val valid = ComplaintMutationDocument(201, mutationAck(), location, tag)
        val applied = assertIs<ComplaintCreateHttpResult.Applied>(ComplaintMutationResponse.create(valid, request))
        assertSame(request, applied.request)
        val maximum =
            ComplaintMutationDocument(
                201,
                mutationAck(Long.MAX_VALUE),
                location,
                "\"complaint-$id-v${Long.MAX_VALUE}\"",
            )
        val maximumResult =
            assertIs<ComplaintCreateHttpResult.Applied>(ComplaintMutationResponse.create(maximum, request))
        assertEquals(Long.MAX_VALUE, maximumResult.acknowledgement.version)
        val documents =
            invalidAcknowledgementBodies(id).map { ComplaintMutationDocument(201, it, location, tag) } +
                invalidAcknowledgementHeaders(location, tag)
        for (document in documents) {
            val result = ComplaintMutationResponse.create(document, request)
            assertMutationFailure(ComplaintMutationFailure.RESPONSE, result)
            assertSame(request, result.request)
        }
    }

    @Test
    fun statusAcceptsOnlyItsTwoClosedCreateMatricesAndKeepsTheExactNoEchoRequest() {
        val request = assertNotNull(ComplaintCreateStatusRequest.checked(mutationPending()))
        val otherPending = mutationPending(mutationReport(key = MUTATION_OTHER_KEY))
        val other = assertNotNull(ComplaintCreateStatusRequest.checked(otherPending))

        fun decode(text: String): ComplaintCreateStatusHttpResult =
            ComplaintMutationResponse.status(ComplaintMutationDocument(200, text, null, null), request)

        assertSame(request, assertIs<ComplaintCreateStatusHttpResult.Applied>(decode(mutationApplied())).request)
        for (code in ComplaintCreateRejection.entries) {
            val result = assertIs<ComplaintCreateStatusHttpResult.Rejected>(decode(mutationRejected(code)))
            assertSame(request, result.request)
            assertNotSame(other, result.request)
            assertEquals(code, result.code)
        }
        for (text in invalidStatusBodies(mutationRejected(), mutationApplied())) {
            val result = decode(text)
            assertMutationFailure(ComplaintMutationFailure.RESPONSE, result)
            assertSame(request, result.request)
        }
    }

    @Test
    fun requestFactoriesRequireDispatchedCreateAndTheExactImmutableNormalizedTuple() {
        val report = mutationReport()
        val pending = mutationPending(report)
        assertNotNull(ComplaintCreateHttpRequest.checked(report, pending))
        assertNotNull(ComplaintCreateHttpRequest.checked(mutationReport(subject = " \u00a0Subject\t "), pending))
        assertNotNull(ComplaintCreateStatusRequest.checked(pending))
        val prepared = mutationPending(report, dispatched = false)
        assertNull(ComplaintCreateHttpRequest.checked(report, prepared))
        assertNull(ComplaintCreateStatusRequest.checked(prepared))
        val changed =
            listOf(
                mutationReport(key = MUTATION_OTHER_KEY),
                mutationReport(subject = "Different"),
                mutationReport(scope = MUTATION_OTHER_SCOPE),
                mutationReport(id = Fixtures.ID),
                mutationReport(appVersion = ""),
            )
        for (mismatch in changed) assertNull(ComplaintCreateHttpRequest.checked(mismatch, pending))
        val reply = replyPending(pending)
        assertNull(ComplaintCreateHttpRequest.checked(report, reply))
        assertNull(ComplaintCreateStatusRequest.checked(reply))
    }
}

private fun invalidAcknowledgementBodies(id: String): List<String> =
    listOf(
        """{"id":"$id"}""",
        """{"id":"$id","version":1,"actionTag":"ignored"}""",
        """{"id":"$id","\u0069d":"$id","version":1}""",
        """{"id":"$id","version":1,"version":1}""",
        """{"id":"${id.uppercase()}","version":1}""",
        """{"id":"${Fixtures.ID}","version":1}""",
        """{"id":"\ud800","version":1}""",
        """{"id":null,"version":1}""",
        mutationAck() + " null",
        "[${mutationAck()}]",
        "\ufeff${mutationAck()}",
    ) +
        listOf("0", "-1", "1.0", "1e0", "01", "9223372036854775808", "\"1\"", "null").map { version ->
            """{"id":"$id","version":$version}"""
        }

private fun invalidAcknowledgementHeaders(
    location: String,
    tag: String,
): List<ComplaintMutationDocument> =
    listOf(
        ComplaintMutationDocument(200, mutationAck(), location, tag),
        ComplaintMutationDocument(201, mutationAck(), null, tag),
        ComplaintMutationDocument(201, mutationAck(), "$SESSION_BASE_URL$location", tag),
        ComplaintMutationDocument(201, mutationAck(), location, "W/$tag"),
        ComplaintMutationDocument(201, mutationAck(), location, "\"complaint-${Fixtures.OTHER_ID}-v2\""),
    )

private fun invalidStatusBodies(
    rejected: String,
    applied: String,
): List<String> =
    listOf(
        rejected.replace("REJECTED", "IN_PROGRESS"),
        rejected.replace("REJECTED", "AUTHORIZED_DELETE"),
        rejected.replace("409", "422"),
        rejected.replace("COMPLAINT_CAPACITY_REACHED", "COMPLAINT_PARENT_UNAVAILABLE"),
        rejected.replace("COMPLAINT_CAPACITY_REACHED", "PRECONDITION_FAILED"),
        rejected.replace("COMPLAINT_CAPACITY_REACHED", "NO_CHANGE"),
        rejected.dropLast(1) + ",\"body\":null}",
        rejected.dropLast(1) + ",\"location\":null}",
        rejected.dropLast(1) + ",\"fingerprint\":\"ignored\"}",
        rejected.dropLast(1) + ",\"outcome\":\"APPLIED\"}",
        """{"outcome":"REJECTED","originalStatus":409}""",
        """{"outcome":null,"originalStatus":409,"problemCode":"COMPLAINT_CAPACITY_REACHED"}""",
        applied.replace("201", "200"),
        applied.replace("\"version\":1", "\"version\":2"),
        applied.replace(Fixtures.OTHER_ID, Fixtures.ID),
        applied.dropLast(1) + ",\"problemCode\":\"COMPLAINT_CAPACITY_REACHED\"}",
    )

private fun replyPending(pending: PendingComplaintRecord): PendingComplaintRecord {
    val reply =
        assertNotNull(
            PendingComplaintAction.checked(
                PendingComplaintOperation.CREATE_REPLY,
                Fixtures.OTHER_ID,
                Fixtures.ID,
                null,
            ),
        )
    val request =
        assertNotNull(PendingComplaintRequest.checked(reply, pending.request.key, pending.request.fingerprint))
    return assertNotNull(PendingComplaintRecord.prepared(pending.binding, request, pending.times).markedDispatched())
}

/** Closed-wire assertions use bytes captured by MockEngine, not the already-erased client buffer. */
internal fun assertMutationWire(f: ComplaintMutationFixture) {
    val urls = listOf("$SESSION_BASE_URL${Policy.CREATE_PATH}", "$SESSION_BASE_URL${Policy.STATUS_PATH}")
    assertEquals(urls, f.requests.map { it.url.toString() })
    f.requests.forEach(::assertMutationHeaders)
    assertEquals(listOf(Fixtures.KEY), f.requests[0].headers.getAll(Policy.IDEMPOTENCY_HEADER))
    assertNull(f.requests[1].headers[Policy.IDEMPOTENCY_HEADER])
    assertCreateWire(f.sentBodies[0], f.report)
    assertStatusWire(f.sentBodies[1], f.pending)
    assertTrue(f.sentBodies.all { it.encodeToByteArray().size <= Policy.MAX_REQUEST_BYTES })
}

private fun assertMutationHeaders(request: HttpRequestData) {
    val allowed =
        setOf(
            "accept",
            "accept-encoding",
            "cache-control",
            "authorization",
            "content-type",
            "content-length",
            Policy.IDEMPOTENCY_HEADER.lowercase(),
        )
    assertEquals(HttpMethod.Post, request.method)
    assertEquals(listOf("Bearer $SESSION_TOKEN"), request.headers.getAll(HttpHeaders.Authorization))
    assertEquals("application/json, application/problem+json", request.headers[HttpHeaders.Accept])
    assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
    assertEquals("no-store, no-transform", request.headers[HttpHeaders.CacheControl])
    assertEquals("application/json", request.body.contentType.toString())
    assertTrue(request.headers.names().all { it.lowercase() in allowed })
}

private fun assertCreateWire(
    text: String,
    report: ComplaintReportRequest,
) {
    val create = assertIs<JsonObject>(Json.parseToJsonElement(text))
    assertEquals(setOf("id", "type", "subject", "body", "metadata"), create.keys)
    assertEquals(Fixtures.OTHER_ID, create.historyString("id"))
    assertEquals("TECHNICAL", create.historyString("type"))
    assertEquals(report.subject, create.historyString("subject"))
    assertEquals(report.body, create.historyString("body"))
    val metadata = assertIs<JsonObject>(create["metadata"])
    assertEquals(setOf("appVersion", "osVersion", "manufacturer", "deviceModel"), metadata.keys)
    assertSame(JsonNull, metadata["appVersion"])
    assertEquals("", metadata.historyString("osVersion"))
    assertEquals("", metadata.historyString("manufacturer"))
    assertEquals("", metadata.historyString("deviceModel"))
}

private fun assertStatusWire(
    text: String,
    pending: PendingComplaintRecord,
) {
    val status = assertIs<JsonObject>(Json.parseToJsonElement(text))
    assertEquals(setOf("operation", "key", "targetIds", "fingerprint"), status.keys)
    assertEquals("OWNER_CREATE", status.historyString("operation"))
    assertEquals(Fixtures.KEY, status.historyString("key"))
    assertEquals(Fixtures.OTHER_ID, assertIs<JsonArray>(status["targetIds"]).single().jsonPrimitive.content)
    assertEquals(pending.request.fingerprint.encoded, status.historyString("fingerprint"))
}
