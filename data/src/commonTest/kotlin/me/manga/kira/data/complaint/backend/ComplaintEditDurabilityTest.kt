package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintEditDurabilityTest {
    @Test
    fun firstPatchByteFollowsBothDurableReadbacksAndStoresOnlyTheUnchangedNineteenScalars() =
        runTest {
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            val edit = mobileEditRequest(body = "Private replacement must stay live only")
            val f =
                ComplaintReportFixture(
                    this,
                    storage,
                    mutationHandler = { request ->
                        storage.assertEditDurableProofs(edit)
                        assertEquals(HttpMethod.Patch, request.method)
                        assertEquals(edit.target.precondition, request.headers[HttpHeaders.IfMatch])
                        assertTrue(request.url.encodedPath.endsWith("/$MOBILE_EDIT_ID${Policy.CONTENT_SUFFIX}"))
                        respond(mobileEditAck(), HttpStatusCode.OK, mobileEditHeaders())
                    },
                )
            try {
                val result = f.repository.submit(edit).reportSuccess().attempt
                assertSame(edit, assertIs<ReportAttempt.Completed>(result).liveReport)
                val application = assertIs<ReportActionState.Edit>(result.application)
                val applied = assertIs<ComplaintEditActionState.Applied>(application.application)
                assertEquals(MOBILE_EDIT_ID, applied.id)
                assertEquals(8L, applied.version)
                assertTrue(storage.pending.slots.isEmpty())
                assertEquals(1, f.requests.size)
            } finally {
                f.close()
            }
        }

    @Test
    fun everyPreparedAndMayWriteFailureOrDishonestReadbackStopsEditAndDeleteWithoutDeletingEvidence() =
        runTest {
            for (case in EDIT_WRITE_FAULTS) {
                for (request in listOf(mobileEditRequest(), mobileOwnerDeleteRequest())) {
                    assertOwnerMutationWriteFault(request, case)
                }
            }
        }

    @Test
    fun closeAtEitherDurableTransitionKeepsEditAndDeleteMetadataWithoutDestructiveFinally() =
        runTest {
            for (step in listOf(Step.PENDING_CREATED, Step.PENDING_REPLACED)) {
                for (request in listOf(mobileEditRequest(), mobileOwnerDeleteRequest())) {
                    assertOwnerMutationTransitionClose(request, step)
                }
            }
        }

    @Test
    fun knownEditAckSurvivesUncertainPendingDeletionAndLaterStatusFailureWithoutAnotherPatch() =
        runTest {
            for (step in listOf(Step.PENDING_DELETE_BEFORE, Step.PENDING_DELETED)) {
                assertEditKnownAck(step)
            }
        }
}

private suspend fun TestScope.assertOwnerMutationWriteFault(request: ComplaintOwnerRequest, case: String) {
    val f = mobileEditReportFixture()
    f.installWriteFault(case)
    try {
        val attempt = f.repository.submit(request).reportSuccess().attempt
        assertSame(request, assertIs<ReportAttempt.Unresolved>(attempt).liveReport, case)
        assertTrue(f.requests.isEmpty(), case)
        assertTrue(Step.PENDING_DELETE_BEFORE !in f.storage.faults.trace, case)
        assertTrue(Step.CREATE_BEFORE !in f.storage.faults.trace, case)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertOwnerMutationTransitionClose(request: ComplaintOwnerRequest, step: Step) {
    val f = mobileEditReportFixture()
    f.storage.faults.onStep = { observed -> if (step == observed) f.works.close() }
    try {
        assertFailsWith<CancellationException> { f.repository.submit(request) }
        val expected =
            if (step == Step.PENDING_CREATED) PendingComplaintState.PREPARED else PendingComplaintState.MAY_HAVE_DISPATCHED
        assertEquals(expected, reportRecord(f.storage.pending.slots.single()).state)
        assertTrue(f.requests.isEmpty())
        assertTrue(Step.PENDING_DELETE_BEFORE !in f.storage.faults.trace)
        assertIs<AppResult.Failure>(f.repository.submit(request))
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertEditKnownAck(step: Step) {
    val f = editStatusFailureFixture()
    f.storage.faults.failAt(step)
    val consumer = f.consumer(mobileEditInputs())
    try {
        val live = consumer.preparedConsumerEdit()
        val first = assertIs<ComplaintReportAttempt.Unresolved>(consumer.submit(live).reportSuccess().attempt)
        val known = assertIs<ComplaintReportApplication.Edit>(first.knownApplication)
        assertEquals(8L, assertIs<ComplaintEditApplication.Applied>(known.application).version)
        assertNotNull(first.pending)
        val retry = assertIs<ComplaintReportAttempt.Unresolved>(consumer.retry(live).reportSuccess())
        assertSame(known, retry.knownApplication)
        assertEquals(1, f.requests.count { it.method == HttpMethod.Patch })
        assertEquals(if (step == Step.PENDING_DELETE_BEFORE) 2 else 1, f.requests.size)
    } finally {
        consumer.close()
        f.close()
    }
}

private fun TestScope.editStatusFailureFixture(): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = { request ->
            if (request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
                val status = HttpStatusCode.ServiceUnavailable
                respond(historyProblem(status), status, mobileEditHeaders(status))
            } else {
                respond(mobileEditAck(), HttpStatusCode.OK, mobileEditHeaders())
            }
        },
    )

private fun InstallationCoordinatorFixture.assertEditDurableProofs(edit: ComplaintEditRequest) {
    val slot = pending.slots.single()
    val record = reportRecord(slot)
    assertEquals(PendingComplaintState.MAY_HAVE_DISPATCHED, record.state)
    assertEquals(PendingComplaintOperation.EDIT_CONTENT, record.request.action.operation)
    assertEquals(edit.target.precondition, record.request.action.canonicalPrecondition())
    assertEquals(edit.pendingFingerprint().encoded, record.request.fingerprint.encoded)
    val root = assertIs<JsonObject>(Json.parseToJsonElement(slot.bytes().decodeToString()))
    assertEquals(EDIT_PENDING_FIELDS, root.keys)
    assertTrue(root.values.all { it is JsonPrimitive })
    assertEquals(1L, root.number("schemaVersion"))
    assertEquals(7L, root.number("expectedVersion"))
    assertEquals(JsonNull, root["parentId"])
    assertEquals(MOBILE_EDIT_ID, root.historyString("targetId"))
    assertFalse(root.toString().contains(edit.body))
    assertFalse(root.toString().contains(assertNotNull(edit.subject)))
    val trace = faults.trace
    val created = trace.indexOf(Step.PENDING_CREATED)
    val replaced = trace.indexOf(Step.PENDING_REPLACED)
    assertTrue(created >= 0 && replaced > created)
    assertTrue(Step.PENDING_READ in trace.subList(created + 1, replaced))
    assertTrue(Step.PENDING_READ in trace.subList(replaced + 1, trace.size))
}

private val EDIT_WRITE_FAULTS =
    listOf(
        "create-before",
        "create-after",
        "create-readback",
        "create-lie",
        "replace-before",
        "replace-after",
        "replace-readback",
        "replace-lie",
        "unrelated-slot",
    )

private val EDIT_PENDING_FIELDS =
    setOf(
        "schemaVersion",
        "installationId",
        "credentialVersion",
        "localGeneration",
        "dataScopeId",
        "operation",
        "targetId",
        "parentId",
        "idempotencyKey",
        "fingerprintVersion",
        "fingerprint",
        "expectedVersion",
        "createdAtSeconds",
        "createdAtNanos",
        "sessionIssuedAtSeconds",
        "sessionIssuedAtNanos",
        "serverReceiptSafeUntilSeconds",
        "serverReceiptSafeUntilNanos",
        "state",
    )
