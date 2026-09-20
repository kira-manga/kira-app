package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import me.manga.kira.domain.model.feedback.ComplaintReportReceiptRejection
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt as ConsumerAttempt

class ComplaintEditRecoveryTest {
    @Test
    fun directNotFoundConflictAndPreconditionRemainUncertainUntilRestartedTerminalEditStatus() =
        runTest {
            for (code in ComplaintEditRejection.entries) {
                val storage = InstallationCoordinatorFixture(Fixtures.record())
                assertDirectEditRetained(storage, code)
                assertColdEditRejection(storage, code)
            }
        }

    @Test
    fun mixedRecoveryKeepsCreationEditAndDeleteApplicationsDistinctWithoutRecoveringInputsOrProse() =
        runTest {
            val f = mixedOwnerRecoveryFixture()
            f.storage.pending.slots += mixedOwnerRecoverySlots()
            val consumer = f.consumer(mobileEditInputs { error("metadata recovery must not allocate a key") })
            try {
                val recovery = consumer.reconcile().reportSuccess()
                assertNull(recovery.stopped)
                val applications =
                    recovery.entries().map { assertIs<ConsumerAttempt.Completed>(it.attempt).application }
                assertMixedOwnerApplications(applications)
                assertEquals(6, f.requests.size)
                f.assertOnlyEditStatusRequests()
                assertTrue(f.sentBodies.none { it.contains("\"body\"") || it.contains("\"subject\"") })
                assertTrue(f.storage.pending.slots.isEmpty())
            } finally {
                consumer.close()
                f.close()
            }
        }

    @Test
    fun fullMixedInventoryStillRunsItsBoundedStatusPassBeforeRefusingANewEditOrDelete() =
        runTest {
            for (request in listOf(mobileEditRequest(), mobileOwnerDeleteRequest())) {
                for (count in listOf(15, 16)) assertMixedOwnerCapacity(count, request)
            }
        }

    @Test
    fun coldPreparedEditNeverSendsAndOnlyTheCurrentSharedRecoveryIssuerCanExplicitlyCancelIt() =
        runTest {
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            storage.pending.slots += mobileEditSlot(dispatched = false)
            val old = oldPreparedEditHandle(storage)
            val f = ComplaintReportFixture(this, storage, storage.restart())
            val consumer = f.consumer(mobileEditInputs { error("cold edit cannot reconstruct a key") })
            try {
                assertIs<AppResult.Failure>(consumer.cancelPrepared(old))
                val entry = consumer.reconcile().reportSuccess().entries().single()
                assertEquals(ComplaintReportPhase.PREPARED, entry.pending.phase)
                assertEquals(
                    ComplaintReportBlock.LIVE_REQUEST_REQUIRED,
                    assertIs<ConsumerAttempt.Unresolved>(entry.attempt).failure.block,
                )
                f.assertMobileEditUntouched()
                consumer.cancelPrepared(entry.pending.handle).reportSuccess()
                assertTrue(storage.pending.slots.isEmpty())
                assertTrue(f.requests.isEmpty() && f.sessionRequests.isEmpty())
            } finally {
                consumer.close()
                f.close()
            }
        }
}

private suspend fun TestScope.assertDirectEditRetained(
    storage: InstallationCoordinatorFixture,
    code: ComplaintEditRejection,
) {
    val status = HttpStatusCode.fromValue(code.status)
    val f =
        ComplaintReportFixture(
            this,
            storage,
            mutationHandler = {
                respond(mutationProblem(status, code.name), status, mobileEditHeaders(status))
            },
        )
    try {
        val edit = mobileEditRequest(body = "Private text lost when this process closes")
        val attempt = assertIs<ReportAttempt.Unresolved>(f.repository.submit(edit).reportSuccess().attempt)
        assertSame(edit, attempt.liveReport)
        assertNull(attempt.application)
        assertEquals(PendingComplaintState.MAY_HAVE_DISPATCHED, reportRecord(storage.pending.slots.single()).state)
        assertEquals(1, f.requests.size)
        assertTrue(Step.PENDING_DELETE_BEFORE !in storage.faults.trace)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertColdEditRejection(
    storage: InstallationCoordinatorFixture,
    code: ComplaintEditRejection,
) {
    val f =
        ComplaintReportFixture(
            this,
            storage,
            storage.restart(),
            mutationHandler = {
                respond(mobileEditRejected(code), HttpStatusCode.OK, mobileEditHeaders(direct = false))
            },
        )
    try {
        val recovered = f.repository.reconcile().reportSuccess().entries().single()
        val completed = assertIs<ReportAttempt.Completed>(recovered.attempt)
        assertNull(completed.liveReport)
        val edit = assertIs<ReportActionState.Edit>(completed.application)
        assertEquals(code, assertIs<ComplaintEditActionState.Rejected>(edit.application).code)
        assertTrue(storage.pending.slots.isEmpty())
        f.assertOnlyEditStatusRequests()
        assertEquals(1, f.requests.size)
        assertTrue(!f.sentBodies.single().contains("Private text"))
    } finally {
        f.close()
    }
}

private fun TestScope.mixedOwnerRecoveryFixture(): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = { request ->
            val root = assertIs<JsonObject>(Json.parseToJsonElement(request.body.toByteArray().decodeToString()))
            val body =
                when (root.historyString("operation")) {
                    "OWNER_CREATE" -> mutationRejected()
                    "OWNER_REPLY" -> mutationRejected(ComplaintReplyRejection.COMPLAINT_PARENT_NOT_FOUND)
                    "OWNER_EDIT" ->
                        if (root.historyString("key") == Fixtures.KEY) mobileEditApplied() else mobileEditRejected()
                    "OWNER_DELETE" ->
                        if (root.historyString("key") == historyId(12)) {
                            MOBILE_OWNER_DELETE_APPLIED
                        } else {
                            mobileOwnerDeleteRejected()
                        }
                    else -> error("Unexpected operation")
                }
            respond(body, HttpStatusCode.OK, mobileEditHeaders(direct = false))
        },
    )

private fun mixedOwnerRecoverySlots(): List<PendingComplaintSlot> =
    listOf(
        reportSlot(mutationReport(key = historyId(10), id = historyId(20))),
        reportSlot(mobileReplyRequest(key = historyId(11))),
        mobileEditSlot(),
        mobileEditSlot(mobileEditRequest(key = MUTATION_OTHER_KEY)),
        mobileOwnerDeleteSlot(mobileOwnerDeleteRequest(key = historyId(12))),
        mobileOwnerDeleteSlot(mobileOwnerDeleteRequest(key = historyId(13))),
    )

private fun assertMixedOwnerApplications(applications: List<ComplaintReportApplication>) {
    assertEquals(6, applications.size)
    val edits = applications.filterIsInstance<ComplaintReportApplication.Edit>().map { it.application }
    assertEquals(2, edits.size)
    assertEquals(8L, edits.filterIsInstance<ComplaintEditApplication.Applied>().single().version)
    assertEquals(1, edits.filterIsInstance<ComplaintEditApplication.Rejected>().size)
    assertEquals(
        setOf(
            ComplaintReportReceiptRejection.COMPLAINT_CAPACITY_REACHED,
            ComplaintReportReceiptRejection.COMPLAINT_PARENT_NOT_FOUND,
        ),
        applications.filterIsInstance<ComplaintReportApplication.Rejected>().map { it.code }.toSet(),
    )
    val deletions = applications.filterIsInstance<ComplaintReportApplication.OwnerDelete>().map { it.application }
    assertEquals(2, deletions.size)
    assertTrue(ComplaintOwnerDeleteApplication.Applied in deletions)
    assertEquals(
        ComplaintOwnerDeleteReceiptRejection.PRECONDITION_FAILED,
        deletions.filterIsInstance<ComplaintOwnerDeleteApplication.Rejected>().single().code,
    )
}

private fun mixedOwnerSlot(index: Int): PendingComplaintSlot =
    when (index % 4) {
        0 -> reportSlot(mutationReport(key = historyId(index + 100), id = historyId(index + 200)))
        1 -> reportSlot(mobileReplyRequest(key = historyId(index + 100), id = historyId(index + 200)))
        2 -> mobileEditSlot(mobileEditRequest(key = historyId(index + 100)))
        else -> mobileOwnerDeleteSlot(mobileOwnerDeleteRequest(key = historyId(index + 100)))
    }

private suspend fun TestScope.assertMixedOwnerCapacity(count: Int, request: ComplaintOwnerRequest) {
    val f = mixedOwnerCapacityFixture()
    val slots = List(count, ::mixedOwnerSlot)
    f.storage.pending.slots += slots
    try {
        val result = f.repository.submit(request).reportSuccess()
        assertEquals(count, result.recovery.entries().size)
        if (count == 16) {
            assertEquals(Block.PENDING_CAPACITY_REACHED, assertIs<ReportAttempt.Unresolved>(result.attempt).failure.block)
            assertTrue(f.requests.all { it.method == HttpMethod.Post })
        } else {
            assertIs<ReportAttempt.Completed>(result.attempt)
            val method = if (request is ComplaintOwnerDeleteRequest) HttpMethod.Delete else HttpMethod.Patch
            assertEquals(1, f.requests.count { it.method == method })
        }
        assertEquals(16, f.requests.size)
        assertEquals(count, f.storage.pending.slots.size)
        assertTrue(slots.all { old -> f.storage.pending.slots.any(old::sameAs) })
    } finally {
        f.close()
    }
}

private fun TestScope.mixedOwnerCapacityFixture(): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = {
            if (it.method == HttpMethod.Delete) {
                mobileOwnerDeleteNotFoundOrApplied(it)
            } else {
                mobileEditNotFoundOrApplied(it)
            }
        },
    )

private suspend fun TestScope.oldPreparedEditHandle(storage: InstallationCoordinatorFixture): ComplaintPendingReport {
    val f = ComplaintReportFixture(this, storage)
    val consumer = f.consumer(mobileEditInputs { error("cold edit cannot allocate a key") })
    return try {
        consumer.reconcile().reportSuccess().entries().single().pending.handle
    } finally {
        consumer.close()
        f.close()
    }
}

internal fun ComplaintReportFixture.assertOnlyEditStatusRequests() {
    assertTrue(requests.all { it.method == HttpMethod.Post && it.url.encodedPath.endsWith(Policy.STATUS_PATH) })
    assertTrue(
        requests.all { it.headers[HttpHeaders.IfMatch] == null && it.headers[Policy.IDEMPOTENCY_HEADER] == null },
    )
}
