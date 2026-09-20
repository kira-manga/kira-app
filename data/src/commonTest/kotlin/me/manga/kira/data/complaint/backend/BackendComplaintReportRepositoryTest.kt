package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class BackendComplaintReportRepositoryTest {
    @Test
    fun preparationCapturesOnceOutsideCoordinatorWithoutWritingOrDispatching() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            val captures = mutableListOf<String>()
            val inputs =
                probeInputs { stage ->
                    assertUnlocked(fixture)
                    captures += stage
                }
            val consumer = fixture.consumer(inputs)
            try {
                val draft = ComplaintReportDraft(subject = "  private subject  ", body = "  private\r\nbody  ")
                val handle = assertIs<ReportLiveHandle>(consumer.preparedConsumerReport(draft))
                assertEquals("private subject", handle.request.subject)
                assertEquals("private\nbody", handle.request.body)
                assertEquals("version", handle.request.metadata.appVersion)
                assertEquals(listOf("identifiers", "metadata"), captures)
                assertTrue(
                    fixture.storage.faults.mutations
                        .isEmpty(),
                )
                assertTrue(fixture.sessionRequests.isEmpty() && fixture.requests.isEmpty())
                for (
                rendered in listOf(
                    draft.toString(),
                    handle.toString(),
                    inputs.toString(),
                    handle.request.metadata.toString(),
                )
                ) {
                    assertFalse(
                        rendered.contains("private") || rendered.contains("version") || rendered.contains("maker"),
                    )
                }
            } finally {
                consumer.close()
                fixture.close()
            }
        }

    @Test
    fun missingInstallationCannotAllocateInputsOrBootstrapThroughPreparation() =
        runTest {
            val fixture = ComplaintReportFixture(this, storage = InstallationCoordinatorFixture())
            val consumer =
                fixture.consumer(
                    ComplaintReportInputs({ error("unexpected IDs") }, { error("unexpected metadata") }),
                )
            try {
                val result =
                    consumer
                        .prepare(ComplaintReportDraft(subject = "Subject", body = "Report body"))
                        .reportSuccess()
                assertEquals(
                    ComplaintReportBlock.MISSING,
                    assertIs<ComplaintReportPreparation.Blocked>(result).failure.block,
                )
                assertTrue(
                    fixture.storage.faults.mutations
                        .isEmpty(),
                )
                assertTrue(fixture.sessionRequests.isEmpty() && fixture.requests.isEmpty())
            } finally {
                consumer.close()
                fixture.close()
            }
        }

    @Test
    fun ambiguousRetryKeepsExactLiveRequestIdsAndCapturedMetadata() =
        runTest {
            val fixture = ambiguousFixture()
            val captures = mutableListOf<String>()
            var version = "captured"
            val inputs =
                probeInputs(
                    metadata = { ComplaintReportMetadataInput(version, "os", "maker", "model") },
                ) { captures += it }
            val consumer = fixture.consumer(inputs)
            try {
                val handle = assertIs<ReportLiveHandle>(consumer.preparedConsumerReport())
                val request = handle.request
                version = "changed"
                assertIs<ComplaintReportAttempt.Unresolved>(consumer.submit(handle).reportSuccess().attempt)
                assertIs<ComplaintReportAttempt.Completed>(consumer.retry(handle).reportSuccess())
                assertSame(request, handle.request)
                assertEquals(listOf("identifiers", "metadata"), captures)
                assertEquals("captured", request.metadata.appVersion)
                assertEquals(fixture.sentBodies.first(), fixture.sentBodies.last())
                assertEquals(
                    listOf(Fixtures.KEY, null, Fixtures.KEY),
                    fixture.requests.map { it.headers[Policy.IDEMPOTENCY_HEADER] },
                )
                assertTrue(fixture.sentBodies[1].contains(Fixtures.KEY))
                assertEquals(3, fixture.requests.size)
            } finally {
                consumer.close()
                fixture.close()
            }
        }

    @Test
    fun foreignLiveHandleIsRejectedBeforeDispatchWithoutConsumingItsFirstSubmission() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            val owner = fixture.consumer()
            val foreign = fixture.consumer()
            try {
                val handle = owner.preparedConsumerReport()
                assertIs<AppResult.Failure>(foreign.submit(handle))
                assertTrue(fixture.requests.isEmpty() && fixture.sessionRequests.isEmpty())
                assertIs<ComplaintReportAttempt.Completed>(owner.submit(handle).reportSuccess().attempt)
                assertIs<AppResult.Failure>(owner.submit(handle))
                assertIs<AppResult.Failure>(owner.retry(handle))
                assertEquals(1, fixture.requests.size)
            } finally {
                owner.close()
                foreign.close()
                fixture.close()
            }
        }

    @Test
    fun stalePreparedLiveIdentityCannotDispatchAfterConsentResetOrRecordReplacement() =
        runTest {
            for (change in listOf("consent-cancel", "reset", "record")) assertStaleLive(change)
        }

    @Test
    fun knownAppliedReceiptRemainsUnresolvedThroughCleanupAndLaterStatusFailure() =
        runTest {
            val fixture = statusFailureFixture()
            val consumer = fixture.consumer()
            fixture.storage.faults.failAt(Step.PENDING_DELETE_BEFORE)
            try {
                val handle = consumer.preparedConsumerReport()
                val first = assertIs<ComplaintReportAttempt.Unresolved>(consumer.submit(handle).reportSuccess().attempt)
                val applied = assertIs<ComplaintReportApplication.Applied>(first.knownApplication)
                assertEquals(Fixtures.OTHER_ID, applied.id)
                assertNotNull(first.pending)
                val retry = assertIs<ComplaintReportAttempt.Unresolved>(consumer.retry(handle).reportSuccess())
                assertSame(applied, retry.knownApplication)
                assertEquals(1, fixture.storage.pending.slots.size)
                assertEquals(2, fixture.requests.size)
            } finally {
                consumer.close()
                fixture.close()
            }
        }
}

private fun probeInputs(
    metadata: () -> ComplaintReportMetadataInput = {
        ComplaintReportMetadataInput("  version  ", "os", "maker", "model")
    },
    probe: (String) -> Unit,
): ComplaintReportInputs =
    ComplaintReportInputs(
        {
            probe("identifiers")
            ComplaintReportIdentifiers(Fixtures.OTHER_ID, Fixtures.KEY)
        },
        {
            probe("metadata")
            metadata()
        },
    )

private fun TestScope.ambiguousFixture(): ComplaintReportFixture {
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

private fun TestScope.statusFailureFixture(): ComplaintReportFixture =
    ComplaintReportFixture(this, mutationHandler = { request ->
        if (request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
            val unavailable = HttpStatusCode.ServiceUnavailable
            respond(historyProblem(unavailable), unavailable, mutationHeaders(unavailable))
        } else {
            respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
        }
    })

private fun TestScope.assertUnlocked(fixture: ComplaintReportFixture) {
    val probe = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.beginReconciliation().success() }
    try {
        assertTrue(probe.isCompleted, "supplier must not run while the coordinator mutex is held")
    } finally {
        probe.cancel()
    }
}

private suspend fun TestScope.assertStaleLive(change: String) {
    val fixture = ComplaintReportFixture(this)
    val consumer = fixture.consumer()
    try {
        val handle = consumer.preparedConsumerReport()
        if (change == "record") {
            fixture.storage.credentials.install(Fixtures.record(version = 2, generation = 2))
        } else {
            val ordinary = fixture.coordinator.admit().success()
            val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(ordinary)).success()
            if (change == "reset") {
                fixture.coordinator.confirmRecovery(prompt).success()
                // Synthetic restoration of even the exact old tuple must not revive the prior issuer.
                fixture.storage.credentials.install(Fixtures.record())
            } else {
                fixture.coordinator.cancelRecovery(prompt).success()
            }
        }
        val attempt = assertIs<ComplaintReportAttempt.Unresolved>(consumer.submit(handle).reportSuccess().attempt)
        assertEquals(ComplaintReportBlock.STALE_BINDING, attempt.failure.block, change)
        assertTrue(fixture.sessionRequests.isEmpty() && fixture.requests.isEmpty(), change)
    } finally {
        consumer.close()
        fixture.close()
    }
}
