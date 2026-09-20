package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.client.utils.EmptyContent
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import me.manga.kira.domain.model.feedback.ComplaintLiveEdit
import me.manga.kira.domain.model.feedback.ComplaintLiveOwnerDelete
import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteDraft
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class BackendComplaintOwnerDeleteRepositoryTest {
    @Test
    fun preparationCapturesOneKeyOutsideTheCoordinatorWithoutProseMetadataIdsOrWrites() =
        runTest {
            val f = mobileOwnerDeleteReportFixture()
            var keys = 0
            val consumer =
                f.consumer(
                    mobileEditInputs {
                        assertMobileEditSupplierUnlocked(f)
                        keys++
                        Fixtures.KEY
                    },
                )
            try {
                val draft = ComplaintOwnerDeleteDraft(mobileEditRow())
                val live = assertIs<OwnerDeleteLiveHandle>(consumer.preparedConsumerOwnerDelete(draft.target))
                assertEquals(MOBILE_EDIT_ID, live.request.targetId)
                assertEquals("\"complaint-$MOBILE_EDIT_ID-v7\"", live.request.precondition)
                assertEquals(Fixtures.SCOPE, live.request.dataScopeId)
                assertEquals(Fixtures.KEY, live.request.key.canonical)
                assertEquals(1, keys)
                for (rendered in listOf(draft.toString(), live.toString(), live.request.toString())) {
                    assertFalse(rendered.contains("Original") || rendered.contains(MOBILE_EDIT_ID))
                }
                f.assertMobileEditUntouched()
            } finally {
                consumer.close()
                f.close()
            }
        }

    @Test
    fun noticeUnknownRowsAndMalformedTargetsOrPreconditionsAreRefusedBeforeStorageOrSuppliers() =
        runTest {
            val f = mobileOwnerDeleteReportFixture()
            val consumer = f.consumer(mobileEditInputs { error("invalid target cannot allocate a key") })
            try {
                for (row in invalidOwnerDeleteTargets()) {
                    val result = consumer.prepare(ComplaintOwnerDeleteDraft(row)).reportSuccess()
                    assertEquals(
                        ComplaintReportBlock.INVALID_CANDIDATE,
                        assertIs<ComplaintOwnerDeletePreparation.Blocked>(result).failure.block,
                    )
                }
                assertTrue(f.storage.faults.trace.isEmpty())
                f.assertMobileEditUntouched()
            } finally {
                consumer.close()
                f.close()
            }
        }

    @Test
    fun absentLockedCorruptCleanupDeletionResetAndConsentNeverCaptureInputsOrEnroll() =
        runTest {
            for (state in listOf("missing", "locked", "corrupt", "cleanup", "deletion", "reset", "consent")) {
                val f = mobileOwnerDeleteReportFixture()
                f.installEditPreparationBlock(state)
                val consumer = f.consumer(mobileEditInputs { error("blocked identity cannot allocate a key") })
                try {
                    val result = consumer.prepare(ComplaintOwnerDeleteDraft(mobileEditRow())).reportSuccess()
                    assertIs<ComplaintOwnerDeletePreparation.Blocked>(result, state)
                    f.assertMobileEditUntouched()
                } finally {
                    consumer.close()
                    f.close()
                }
            }
        }

    @Test
    fun missingKeySupplierDoesNotFallBackToCreationInputs() =
        runTest {
            val f = mobileOwnerDeleteReportFixture()
            val consumer = f.consumer(ComplaintReportInputs({ error("no content ID fallback") }, { error("no metadata") }))
            try {
                val result = consumer.prepare(ComplaintOwnerDeleteDraft(mobileEditRow())).reportSuccess()
                assertEquals(
                    ComplaintReportBlock.INVALID_CANDIDATE,
                    assertIs<ComplaintOwnerDeletePreparation.Blocked>(result).failure.block,
                )
                f.assertMobileEditUntouched()
            } finally {
                consumer.close()
                f.close()
            }
        }

    @Test
    fun foreignForgedCrossOperationAndConsumedHandlesCannotAcquireDeletionAuthority() =
        runTest {
            val f = mobileOwnerDeleteReportFixture()
            val owner = f.consumer(mobileEditInputs())
            val foreign = f.consumer(mobileEditInputs())
            try {
                val live = owner.preparedConsumerOwnerDelete()
                assertFalse(live is ComplaintLiveEdit || live is ComplaintLiveReport || live is ComplaintLiveReply)
                assertIs<AppResult.Failure>(foreign.submit(live))
                assertIs<AppResult.Failure>(foreign.retry(live))
                owner.assertDeleteForgeriesRejected()
                f.assertMobileEditUntouched()
                val completed = assertIs<ComplaintReportAttempt.Completed>(owner.submit(live).reportSuccess().attempt)
                val application = assertIs<ComplaintReportApplication.OwnerDelete>(completed.application)
                assertSame(ComplaintOwnerDeleteApplication.Applied, application.application)
                assertIs<AppResult.Failure>(owner.submit(live))
                assertIs<AppResult.Failure>(owner.retry(live))
                owner.close()
                assertIs<AppResult.Failure>(owner.prepare(ComplaintOwnerDeleteDraft(mobileEditRow())))
                assertEquals(1, f.requests.size)
            } finally {
                owner.close()
                foreign.close()
                f.close()
            }
        }

    @Test
    fun keyCaptureCannotRecaptureAuthorityAfterConsentResetOrCredentialReplacement() =
        runTest {
            for (change in listOf("consent", "reset", "record")) assertOwnerDeleteCaptureFence(change)
        }

    @Test
    fun firstBodylessDeleteStillFollowsBothDurableReadbacksAndKeepsInstallationCredentials() =
        runTest {
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            val deletion = mobileOwnerDeleteRequest(mobileEditTarget(version = Long.MAX_VALUE))
            val f =
                ComplaintReportFixture(
                    this,
                    storage,
                    mutationHandler = { request ->
                        storage.assertOwnerDeleteDurableProofs(deletion)
                        assertSame(EmptyContent, request.body)
                        respond("", HttpStatusCode.NoContent, mobileOwnerDeleteHeaders())
                    },
                )
            try {
                val completed = assertIs<ReportAttempt.Completed>(f.repository.submit(deletion).reportSuccess().attempt)
                assertSame(deletion, completed.liveReport)
                val application = assertIs<ReportActionState.OwnerDelete>(completed.application)
                assertSame(ComplaintOwnerDeleteActionState.Applied, application.application)
                assertTrue(storage.pending.slots.isEmpty())
                assertTrue(Fixtures.record().sameAs(f.coordinator.admit().success().record))
                assertEquals(1, f.requests.size)
            } finally {
                f.close()
            }
        }
}

private fun invalidOwnerDeleteTargets(): List<ComplaintOwnerRow> {
    val tag = "\"complaint-$MOBILE_EDIT_ID-v7\""
    val time = Instant.parse(SESSION_ISSUED_AT)
    val tags =
        listOf(
            "W/$tag",
            "$tag,$tag",
            "*",
            tag.replace("v7", "v07"),
            tag.replace("v7", "v9223372036854775808"),
        )
    return tags.map { mobileEditRow(mobileEditFields(tag = it)) } +
        listOf(
            mobileEditRow(mobileEditFields(id = "$MOBILE_EDIT_ID/content")),
            mobileEditRow(mobileEditFields(id = MOBILE_EDIT_ID.uppercase())),
            mobileEditRow(mobileEditFields(version = 0)),
            mobileEditRow(mobileEditFields(tag = tag.replace(MOBILE_EDIT_ID, Fixtures.ID))),
            mobileEditRow(mobileEditFields(status = ComplaintHistoryStatus.Unrecognized)),
            mobileEditRow(type = ComplaintHistoryType.Unrecognized),
            UnknownComplaintItem(MOBILE_EDIT_ID, "NOTICE", time, time),
        )
}

private suspend fun BackendComplaintReportRepository.assertDeleteForgeriesRejected() {
    val forged = object : ComplaintLiveOwnerDelete, ComplaintLiveEdit, ComplaintLiveReport, ComplaintLiveReply {}
    val deletion: ComplaintLiveOwnerDelete = forged
    val edit: ComplaintLiveEdit = forged
    val report: ComplaintLiveReport = forged
    val reply: ComplaintLiveReply = forged
    assertIs<AppResult.Failure>(submit(deletion))
    assertIs<AppResult.Failure>(retry(deletion))
    assertIs<AppResult.Failure>(submit(edit))
    assertIs<AppResult.Failure>(retry(edit))
    assertIs<AppResult.Failure>(submit(report))
    assertIs<AppResult.Failure>(retry(report))
    assertIs<AppResult.Failure>(submit(reply))
    assertIs<AppResult.Failure>(retry(reply))
}

private suspend fun TestScope.assertOwnerDeleteCaptureFence(change: String) {
    val f = mobileOwnerDeleteReportFixture()
    val ordinary = f.coordinator.admit().success()
    var keys = 0
    val consumer =
        f.consumer(
            mobileEditInputs {
                keys++
                val changed = launch(start = CoroutineStart.UNDISPATCHED) { f.changeEditOrigin(ordinary, change) }
                assertTrue(changed.isCompleted)
                Fixtures.KEY
            },
        )
    try {
        val result = consumer.prepare(ComplaintOwnerDeleteDraft(mobileEditRow())).reportSuccess()
        val blocked = assertIs<ComplaintOwnerDeletePreparation.Blocked>(result)
        assertEquals(ComplaintReportBlock.STALE_BINDING, blocked.failure.block)
        assertEquals(1, keys)
        assertTrue(f.requests.isEmpty() && f.sessionRequests.isEmpty())
    } finally {
        consumer.close()
        f.close()
    }
}

private fun InstallationCoordinatorFixture.assertOwnerDeleteDurableProofs(deletion: ComplaintOwnerDeleteRequest) {
    val slot = pending.slots.single()
    val record = reportRecord(slot)
    assertEquals(PendingComplaintState.MAY_HAVE_DISPATCHED, record.state)
    assertEquals(PendingComplaintOperation.DELETE_OWNED, record.request.action.operation)
    assertEquals(deletion.precondition, record.request.action.canonicalPrecondition())
    assertEquals(deletion.pendingFingerprint().encoded, record.request.fingerprint.encoded)
    val root = assertIs<JsonObject>(Json.parseToJsonElement(slot.bytes().decodeToString()))
    assertEquals(19, root.keys.size)
    assertTrue(root.values.all { it is JsonPrimitive })
    assertEquals(1L, root.number("schemaVersion"))
    assertEquals(Long.MAX_VALUE, root.number("expectedVersion"))
    assertEquals(JsonNull, root["parentId"])
    assertEquals(deletion.targetId, root.historyString("targetId"))
    assertTrue(root.keys.none { it in setOf("body", "subject", "actionTag", "authorization", "token") })
    val trace = faults.trace
    val created = trace.indexOf(Step.PENDING_CREATED)
    val replaced = trace.indexOf(Step.PENDING_REPLACED)
    assertTrue(created >= 0 && replaced > created)
    assertTrue(Step.PENDING_READ in trace.subList(created + 1, replaced))
    assertTrue(Step.PENDING_READ in trace.subList(replaced + 1, trace.size))
}
