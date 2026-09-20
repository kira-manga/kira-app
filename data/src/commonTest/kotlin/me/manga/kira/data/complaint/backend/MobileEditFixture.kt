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
import kotlinx.coroutines.test.TestScope
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

internal const val MOBILE_EDIT_ID = "123e4567-e89b-52d3-a456-426614174000"
internal const val MOBILE_EDIT_SCOPE = "22222222-2222-4222-8222-222222222222"
internal const val MOBILE_EDIT_KEY = "33333333-3333-4333-8333-333333333333"
internal const val MOBILE_EDIT_VERSION = 7L

internal fun mobileEditTarget(
    id: String = MOBILE_EDIT_ID,
    version: Long = MOBILE_EDIT_VERSION,
    shape: ComplaintEditShape = ComplaintEditShape.SUBJECT_AND_BODY,
    tag: String = "\"complaint-$id-v$version\"",
): ComplaintEditTarget = assertNotNull(ComplaintEditTarget.checked(id, version, tag, shape))

internal fun mobileEditRequest(
    target: ComplaintEditTarget = mobileEditTarget(),
    subject: String? = "Synthetic edit",
    body: String = "Line 1\nLine 2",
    key: String = Fixtures.KEY,
    scope: String = Fixtures.SCOPE,
): ComplaintEditRequest =
    assertIs<ComplaintEditRequestResult.Accepted>(
        ComplaintEditRequest.normalize(target, key, scope, subject, body),
    ).request

internal fun mobileEditPending(
    edit: ComplaintEditRequest = mobileEditRequest(),
    record: InstallationCredentialRecord = Fixtures.record(),
    dispatched: Boolean = true,
): PendingComplaintRecord {
    val tuple =
        assertNotNull(
            PendingComplaintBinding.checked(
                record.material.installationId,
                record.credentialVersion,
                record.localGeneration,
                record.material.dataScopeId,
            ),
        )
    val request =
        assertNotNull(
            PendingComplaintRequest.checked(edit.target.action, edit.key.canonical, edit.pendingFingerprint()),
        )
    val time = Instant.parse(SESSION_ISSUED_AT)
    val times = assertNotNull(PendingComplaintTimes.checked(time, time))
    val prepared = PendingComplaintRecord.prepared(tuple, request, times)
    return if (dispatched) assertNotNull(prepared.markedDispatched()) else prepared
}

internal fun mobileEditSlot(
    edit: ComplaintEditRequest = mobileEditRequest(),
    dispatched: Boolean = true,
): PendingComplaintSlot =
    assertIs<PendingComplaintCodecResult.Value<PendingComplaintSlot>>(
        PendingComplaintRecordCodec.encode(mobileEditPending(edit, dispatched = dispatched)),
    ).value

internal fun mobileEditAck(
    version: Long = MOBILE_EDIT_VERSION + 1,
    id: String = MOBILE_EDIT_ID,
): String = mutationAck(version, id)

internal fun mobileEditApplied(
    version: Long = MOBILE_EDIT_VERSION + 1,
    id: String = MOBILE_EDIT_ID,
): String =
    """{"outcome":"APPLIED","originalStatus":200,"etag":"\"complaint-$id-v$version\"","body":{"id":"$id","version":$version}}"""

internal fun mobileEditRejected(code: ComplaintEditRejection = ComplaintEditRejection.COMPLAINT_NO_CHANGE): String =
    """{"outcome":"REJECTED","originalStatus":${code.status},"problemCode":"${code.name}"}"""

internal fun mobileEditHeaders(
    status: HttpStatusCode = HttpStatusCode.OK,
    direct: Boolean = true,
    change: HeadersBuilder.() -> Unit = {},
): Headers =
    mutationHeaders(status) {
        if (status == HttpStatusCode.OK && direct) append(HttpHeaders.ETag, "\"complaint-$MOBILE_EDIT_ID-v8\"")
        change()
    }

/** Actual fixed mutation client, with only synthetic transport replaced; never an alternate authority. */
internal class ComplaintEditHttpFixture(
    scope: TestScope,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        val status = request.url.encodedPath.endsWith(Policy.STATUS_PATH)
        respond(
            if (status) mobileEditApplied() else mobileEditAck(),
            HttpStatusCode.OK,
            mobileEditHeaders(direct = !status),
        )
    },
) {
    val edit = mobileEditRequest()
    val pending = mobileEditPending(edit)
    val request = assertNotNull(ComplaintEditHttpRequest.checked(edit, pending))
    val status = assertNotNull(ComplaintEditStatusRequest.checked(pending))
    val session = mutationSession()
    val requests = mutableListOf<HttpRequestData>()
    val sentBodies = mutableListOf<String>()
    private val engine =
        historyMockEngine(scope) { request ->
            requests += request
            sentBodies += request.body.toByteArray().decodeToString()
            handler(request)
        }
    val http = ComplaintMutationHttp(assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL)), engine)

    fun close() {
        try {
            http.close()
        } finally {
            engine.close()
        }
    }
}

internal fun TestScope.mobileEditReportFixture(): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = { request ->
            val status = request.url.encodedPath.endsWith(Policy.STATUS_PATH)
            respond(
                if (status) mobileEditApplied() else mobileEditAck(),
                HttpStatusCode.OK,
                mobileEditHeaders(direct = !status),
            )
        },
    )

internal fun MockRequestHandleScope.mobileEditNotFoundOrApplied(request: HttpRequestData): HttpResponseData =
    if (request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
        val status = HttpStatusCode.NotFound
        respond(mutationProblem(status, "OPERATION_NOT_FOUND"), status, mobileEditHeaders(status, direct = false))
    } else {
        respond(mobileEditAck(), HttpStatusCode.OK, mobileEditHeaders())
    }
