package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.feedback.ComplaintLiveOwnerDelete
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteDraft
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.repository.ComplaintOwnerDeleteRepository
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

/** Synthetic single-delete data only; transport, storage, sessions and work use the existing report fixture. */
internal fun mobileOwnerDeleteRequest(
    target: ComplaintEditTarget = mobileEditTarget(),
    key: String = Fixtures.KEY,
    scope: String = Fixtures.SCOPE,
): ComplaintOwnerDeleteRequest = assertNotNull(ComplaintOwnerDeleteRequest.checked(target, key, scope))

internal fun mobileOwnerDeletePending(
    deletion: ComplaintOwnerDeleteRequest = mobileOwnerDeleteRequest(),
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
        assertNotNull(PendingComplaintRequest.checked(deletion.action, deletion.key.canonical, deletion.pendingFingerprint()))
    val time = Instant.parse(SESSION_ISSUED_AT)
    val times = assertNotNull(PendingComplaintTimes.checked(time, time))
    val prepared = PendingComplaintRecord.prepared(tuple, request, times)
    return if (dispatched) assertNotNull(prepared.markedDispatched()) else prepared
}

internal fun mobileOwnerDeleteSlot(
    deletion: ComplaintOwnerDeleteRequest = mobileOwnerDeleteRequest(),
    dispatched: Boolean = true,
): PendingComplaintSlot =
    assertIs<PendingComplaintCodecResult.Value<PendingComplaintSlot>>(
        PendingComplaintRecordCodec.encode(mobileOwnerDeletePending(deletion, dispatched = dispatched)),
    ).value

internal fun mobileOwnerDeleteHttpRequest(
    deletion: ComplaintOwnerDeleteRequest = mobileOwnerDeleteRequest(),
): ComplaintOwnerDeleteHttpRequest =
    assertNotNull(ComplaintOwnerDeleteHttpRequest.checked(deletion, mobileOwnerDeletePending(deletion)))

internal fun mobileOwnerDeleteStatusRequest(): ComplaintOwnerDeleteStatusRequest =
    assertNotNull(ComplaintOwnerDeleteStatusRequest.checked(mobileOwnerDeletePending()))

internal suspend fun ComplaintOwnerDeleteRepository.preparedConsumerOwnerDelete(
    target: ComplaintOwnerRow = mobileEditRow(),
): ComplaintLiveOwnerDelete =
    assertIs<ComplaintOwnerDeletePreparation.Ready>(prepare(ComplaintOwnerDeleteDraft(target)).reportSuccess()).deletion

internal const val MOBILE_OWNER_DELETE_APPLIED = """{"outcome":"APPLIED","originalStatus":204}"""

internal fun mobileOwnerDeleteRejected(
    code: ComplaintOwnerDeleteRejection = ComplaintOwnerDeleteRejection.PRECONDITION_FAILED,
): String = """{"outcome":"REJECTED","originalStatus":${code.status},"problemCode":"${code.name}"}"""

internal fun mobileOwnerDeleteHeaders(change: HeadersBuilder.() -> Unit = {}): Headers =
    Headers.build {
        append(ComplaintBoundedResponse.CONTRACT_HEADER, "1")
        append(HttpHeaders.CacheControl, "no-store, no-transform")
        change()
    }

internal fun TestScope.mobileOwnerDeleteReportFixture(): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = { request ->
            if (request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
                respond(MOBILE_OWNER_DELETE_APPLIED, HttpStatusCode.OK, mutationHeaders())
            } else {
                respond("", HttpStatusCode.NoContent, mobileOwnerDeleteHeaders())
            }
        },
    )

internal fun MockRequestHandleScope.mobileOwnerDeleteNotFoundOrApplied(request: HttpRequestData): HttpResponseData =
    if (request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
        val status = HttpStatusCode.NotFound
        respond(mutationProblem(status, "OPERATION_NOT_FOUND"), status, mutationHeaders(status))
    } else {
        respond("", HttpStatusCode.NoContent, mobileOwnerDeleteHeaders())
    }
