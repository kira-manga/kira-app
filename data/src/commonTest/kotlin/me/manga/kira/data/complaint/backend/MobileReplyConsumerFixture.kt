package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

internal fun mobileReplyProbeInputs(
    version: () -> String? = { null },
    capture: (String) -> Unit,
): ComplaintReportInputs =
    ComplaintReportInputs(
        {
            capture("identifiers")
            ComplaintReportIdentifiers(Fixtures.OTHER_ID, Fixtures.KEY)
        },
        {
            capture("metadata")
            ComplaintReportMetadataInput(version(), " current OS ", "current maker", "current model")
        },
    )

internal fun mobileReplyNoInputs(): ComplaintReportInputs =
    ComplaintReportInputs({ error("no recovered identifiers") }, { error("no recovered metadata") })

internal fun TestScope.assertMobileReplySupplierUnlocked(fixture: ComplaintReportFixture) {
    val probe = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.beginReconciliation().success() }
    try {
        assertTrue(probe.isCompleted, "reply suppliers must not run under the coordinator mutex")
    } finally {
        probe.cancel()
    }
}

internal fun ComplaintReportFixture.assertMobileReplyUntouched() {
    assertTrue(storage.faults.mutations.isEmpty())
    assertTrue(requests.isEmpty())
    assertTrue(sessionRequests.isEmpty())
}

internal fun TestScope.mobileReplyAmbiguousFixture(): ComplaintReportFixture {
    var creates = 0
    return ComplaintReportFixture(this, mutationHandler = { request ->
        if (request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
            notFoundOrCreated(request)
        } else if (++creates == 1) {
            val unavailable = HttpStatusCode.ServiceUnavailable
            respond(historyProblem(unavailable), unavailable, mutationHeaders(unavailable))
        } else {
            notFoundOrCreated(request)
        }
    })
}

internal suspend fun TestScope.assertMobileReplyPreparationBlocked(
    parent: String,
    client: String,
    key: String,
) {
    val fixture = ComplaintReportFixture(this)
    val consumer =
        fixture.consumer(
            ComplaintReportInputs(
                { ComplaintReportIdentifiers(client, key) },
                { ComplaintReportMetadataInput(null, "", "", "") },
            ),
        )
    try {
        val result = consumer.prepare(ComplaintReplyDraft(parent, "x")).reportSuccess()
        assertEquals(
            ComplaintReportBlock.INVALID_CANDIDATE,
            assertIs<ComplaintReplyPreparation.Blocked>(result).failure.block,
        )
        fixture.assertMobileReplyUntouched()
    } finally {
        consumer.close()
        fixture.close()
    }
}

internal suspend fun assertMobileReplyForgeriesRejected(owner: BackendComplaintReportRepository) {
    val forged = object : ComplaintLiveReply, ComplaintLiveReport {}
    val reply: ComplaintLiveReply = forged
    val report: ComplaintLiveReport = forged
    assertIs<AppResult.Failure>(owner.submit(reply))
    assertIs<AppResult.Failure>(owner.retry(reply))
    assertIs<AppResult.Failure>(owner.submit(report))
    assertIs<AppResult.Failure>(owner.retry(report))
}
