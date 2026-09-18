package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.data.complaint.backend.ComplaintCreateRejection.COMPLAINT_CAPACITY_REACHED
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.platform.storage.InstallationCredentialRecord
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

/** Actual fixed client and MockEngine only: no native qualification, authenticated receipt or durable-slot proof. */
internal class ComplaintMutationFixture(
    scope: TestScope,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        if (!request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
            respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
        } else {
            respond(mutationApplied(), HttpStatusCode.OK, mutationHeaders())
        }
    },
) {
    val report = mutationReport()
    val pending = mutationPending(report)
    val create = assertNotNull(ComplaintCreateHttpRequest.checked(report, pending))
    private val reply = mobileReplyRequest()
    private val replyCreate = assertNotNull(ComplaintCreateHttpRequest.checked(reply, mutationPending(reply)))
    val status = assertNotNull(ComplaintCreateStatusRequest.checked(pending))
    val session = mutationSession()
    val requests = mutableListOf<HttpRequestData>()
    val sentBodies = mutableListOf<String>()
    private val engine =
        historyMockEngine(scope) { request ->
            requests += request
            // The production client erases its owned byte array after the call; capture synthetic test text now.
            sentBodies += request.body.toByteArray().decodeToString()
            handler(request)
        }
    val http = ComplaintMutationHttp(assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL)), engine)

    suspend fun call(route: ComplaintMutationRoute): Any =
        when (route) {
            ComplaintMutationRoute.CREATE -> http.create(create, session)
            ComplaintMutationRoute.REPLY -> http.create(replyCreate, session)
            ComplaintMutationRoute.EDIT -> error("Edit has its separate typed fixture")
            ComplaintMutationRoute.STATUS -> http.status(status, session)
        }

    fun close() {
        try {
            http.close()
        } finally {
            engine.close()
        }
    }
}

internal const val MUTATION_OTHER_KEY = "55555555-5555-4555-8555-555555555555"
internal const val MUTATION_OTHER_SCOPE = "66666666-6666-4666-8666-666666666666"

internal val CREATION_AND_STATUS_ROUTES =
    listOf(ComplaintMutationRoute.CREATE, ComplaintMutationRoute.REPLY, ComplaintMutationRoute.STATUS)

internal fun mutationReport(
    key: String = Fixtures.KEY,
    subject: String = "Subject",
    scope: String = Fixtures.SCOPE,
    id: String = Fixtures.OTHER_ID,
    appVersion: String? = null,
): ComplaintReportRequest =
    assertIs<ComplaintReportRequestResult.Accepted>(
        ComplaintReportRequest.normalize(
            assertNotNull(ComplaintReportIdentity.checked(id, key, scope)),
            ComplaintType.TECHNICAL,
            subject,
            "Report body",
            ComplaintReportMetadataInput(appVersion, "", "", ""),
        ),
    ).request

internal fun mutationPending(
    report: ComplaintCreationRequest = mutationReport(),
    record: InstallationCredentialRecord = Fixtures.record(),
    dispatched: Boolean = true,
): PendingComplaintRecord {
    val time = Instant.parse(SESSION_ISSUED_AT)
    val prepared =
        PendingComplaintRecord.prepared(
            mutationBinding(record),
            mutationPendingRequest(report),
            assertNotNull(PendingComplaintTimes.checked(time, time)),
        )
    return if (dispatched) assertNotNull(prepared.markedDispatched()) else prepared
}

private fun mutationBinding(record: InstallationCredentialRecord): PendingComplaintBinding =
    assertNotNull(
        PendingComplaintBinding.checked(
            record.material.installationId,
            record.credentialVersion,
            record.localGeneration,
            record.material.dataScopeId,
        ),
    )

private fun mutationPendingRequest(report: ComplaintCreationRequest): PendingComplaintRequest {
    val action =
        assertNotNull(
            PendingComplaintAction.checked(
                if (report is ComplaintReplyRequest) {
                    PendingComplaintOperation.CREATE_REPLY
                } else {
                    PendingComplaintOperation.CREATE_REPORT
                },
                report.identity.clientId.canonical,
                (report as? ComplaintReplyRequest)?.parentId,
                null,
            ),
        )
    return assertNotNull(
        PendingComplaintRequest.checked(
            action,
            report.identity.key.canonical,
            report.pendingFingerprint(),
        ),
    )
}

internal fun mutationSession(record: InstallationCredentialRecord = Fixtures.record()): ComplaintSessionResponse =
    assertIs<ComplaintSessionResult.Ready>(ComplaintSessionResponse.decode(sessionResponse(record), record)).session

internal fun mutationAck(
    version: Long = 1,
    id: String = Fixtures.OTHER_ID,
): String = """{"id":"$id","version":$version}"""

internal fun mutationApplied(
    version: Long = 1,
    id: String = Fixtures.OTHER_ID,
): String =
    buildJsonObject {
        put("outcome", "APPLIED")
        put("originalStatus", 201)
        put("location", "${Policy.CREATE_PATH}/$id")
        put("etag", "\"complaint-$id-v$version\"")
        put(
            "body",
            buildJsonObject {
                put("id", id)
                put("version", version)
            },
        )
    }.toString()

internal fun mutationRejected(code: ComplaintCreationRejection = COMPLAINT_CAPACITY_REACHED): String =
    """{"outcome":"REJECTED","originalStatus":${code.status},"problemCode":"${code.wireCode}"}"""

internal fun mutationProblem(
    status: HttpStatusCode,
    code: String,
): String =
    """{"type":"about:blank","title":"${status.description}","status":${status.value},"errors":[""" +
        """{"code":"$code","message":"Complaint request refused."}]}"""

internal fun mutationHeaders(
    status: HttpStatusCode = HttpStatusCode.OK,
    change: HeadersBuilder.() -> Unit = {},
): Headers =
    Headers.build {
        append(ComplaintBoundedResponse.CONTRACT_HEADER, "1")
        append(HttpHeaders.CacheControl, "no-store, no-transform")
        append(
            HttpHeaders.ContentType,
            if (status == HttpStatusCode.OK || status == HttpStatusCode.Created) {
                "application/json"
            } else {
                "application/problem+json"
            },
        )
        if (status == HttpStatusCode.Created) {
            append(HttpHeaders.Location, "${Policy.CREATE_PATH}/${Fixtures.OTHER_ID}")
            append(HttpHeaders.ETag, "\"complaint-${Fixtures.OTHER_ID}-v1\"")
        }
        if (status == HttpStatusCode.Unauthorized) {
            append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"kira-complaints\"")
        }
        change()
    }

internal fun assertMutationFailure(
    expected: ComplaintMutationFailure,
    actual: Any,
) {
    val reason =
        when (actual) {
            is ComplaintCreateHttpResult.Failed -> actual.reason
            is ComplaintCreateStatusHttpResult.Failed -> actual.reason
            else -> fail("Expected a content-free mutation failure")
        }
    assertEquals(expected, reason)
}

internal suspend fun TestScope.assertMutationBoundary(
    route: ComplaintMutationRoute,
    status: HttpStatusCode,
    text: String,
    maximum: Int,
) {
    for (extra in 0..1) {
        val bytes = (text + " ".repeat(maximum + extra - text.encodeToByteArray().size)).encodeToByteArray()
        val channel = HistoryTrackedChannel(ByteReadChannel(bytes))
        val f = ComplaintMutationFixture(this) { respond(channel, status, mutationHeaders(status)) }
        try {
            val result = f.call(route)
            if (extra != 0) {
                assertMutationFailure(ComplaintMutationFailure.RESPONSE, result)
            } else {
                assertWithinMutationBoundary(result, status, route)
            }
            assertEquals(1, f.requests.size)
            assertTrue(channel.cancelled)
        } finally {
            f.close()
        }
    }
}

private fun assertWithinMutationBoundary(
    result: Any,
    status: HttpStatusCode,
    route: ComplaintMutationRoute,
) {
    when {
        status == HttpStatusCode.Created -> assertIs<ComplaintCreateHttpResult.Applied>(result)
        status == HttpStatusCode.OK -> assertIs<ComplaintCreateStatusHttpResult.Rejected>(result)
        route != ComplaintMutationRoute.STATUS -> assertIs<ComplaintCreateHttpResult.HttpFailure>(result)
        else -> assertIs<ComplaintCreateStatusHttpResult.HttpFailure>(result)
    }
}

internal suspend fun TestScope.assertMutationFailedEof(
    route: ComplaintMutationRoute,
    afterPrefix: Boolean,
) {
    val text = if (route != ComplaintMutationRoute.STATUS) mutationAck() else mutationRejected()
    val bytes = text.encodeToByteArray()
    for (declared in listOf(false, true)) {
        val channel = ComplaintFailedEofChannel(bytes, afterPrefix)
        val headers =
            mutationHeaders(route.success) {
                if (declared) append(HttpHeaders.ContentLength, bytes.size.toString())
            }
        val f = ComplaintMutationFixture(this) { respond(channel, route.success, headers) }
        try {
            assertMutationFailure(ComplaintMutationFailure.TRANSPORT, f.call(route))
            assertEquals(afterPrefix, channel.prefixDrained)
            assertTrue(channel.cancelled)
            assertEquals(1, f.requests.size)
        } finally {
            f.close()
        }
    }
}

internal suspend fun TestScope.assertRejectedMutation(
    bytes: ByteArray,
    headers: Headers,
    status: HttpStatusCode = HttpStatusCode.Created,
    route: ComplaintMutationRoute = ComplaintMutationRoute.CREATE,
) {
    val channel = HistoryTrackedChannel(ByteReadChannel(bytes))
    val f = ComplaintMutationFixture(this) { respond(channel, status, headers) }
    try {
        assertMutationFailure(ComplaintMutationFailure.RESPONSE, f.call(route))
        assertEquals(1, f.requests.size)
        assertTrue(channel.cancelled)
    } finally {
        f.close()
    }
}
