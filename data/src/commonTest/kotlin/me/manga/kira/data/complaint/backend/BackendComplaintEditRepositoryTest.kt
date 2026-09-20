package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintLiveEdit
import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportRejection
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class BackendComplaintEditRepositoryTest {
    @Test
    fun preparationCapturesOnlyOneKeyOutsideTheCoordinatorAndKeepsOnlyNormalizedReplacement() =
        runTest {
            val f = mobileEditReportFixture()
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
                val draft = mobileEditDraft()
                val live = assertIs<EditLiveHandle>(consumer.preparedConsumerEdit(draft))
                assertEquals("Synthetic edit", live.request.subject)
                assertEquals("Line 1\nLine 2", live.request.body)
                assertEquals(MOBILE_EDIT_ID, live.request.target.id)
                assertEquals("\"complaint-$MOBILE_EDIT_ID-v7\"", live.request.target.precondition)
                assertEquals(Fixtures.SCOPE, live.request.dataScopeId)
                assertEquals(1, keys)
                for (rendered in listOf(draft.toString(), live.toString(), live.request.toString())) {
                    assertFalse(rendered.contains("Synthetic") || rendered.contains(MOBILE_EDIT_ID))
                }
                f.assertMobileEditUntouched()
            } finally {
                consumer.close()
                f.close()
            }
        }

    @Test
    fun unrecognizedRowsAndMalformedTargetsOrTagsAreRefusedBeforeAnyStorageOrSupplier() =
        runTest {
            val time = Instant.parse(SESSION_ISSUED_AT)
            val targets =
                listOf(
                    mobileEditRow(mobileEditFields(id = "$MOBILE_EDIT_ID/content")),
                    mobileEditRow(mobileEditFields(version = 0)),
                    mobileEditRow(mobileEditFields(tag = "W/\"complaint-$MOBILE_EDIT_ID-v7\"")),
                    mobileEditRow(mobileEditFields(status = ComplaintHistoryStatus.Unrecognized)),
                    mobileEditRow(type = ComplaintHistoryType.Unrecognized),
                    UnknownComplaintItem(MOBILE_EDIT_ID, "NOTICE", time, time),
                )
            val f = mobileEditReportFixture()
            val consumer = f.consumer(mobileEditInputs { error("invalid target must not allocate a key") })
            try {
                for (target in targets) {
                    val result = consumer.prepare(mobileEditDraft(target)).reportSuccess()
                    assertEquals(
                        ComplaintReportBlock.INVALID_CANDIDATE,
                        assertIs<ComplaintEditPreparation.Blocked>(result).failure.block,
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
    fun missingLockedCorruptCleanupDeletionAndConsentNeverCaptureInputsOrBootstrap() =
        runTest {
            for (state in listOf("missing", "locked", "corrupt", "cleanup", "deletion", "reset", "consent")) {
                val f = mobileEditReportFixture()
                f.installEditPreparationBlock(state)
                val consumer = f.consumer(mobileEditInputs { error("blocked installation must not allocate a key") })
                try {
                    assertIs<ComplaintEditPreparation.Blocked>(
                        consumer.prepare(mobileEditDraft()).reportSuccess(),
                        state,
                    )
                    f.assertMobileEditUntouched()
                } finally {
                    consumer.close()
                    f.close()
                }
            }
        }

    @Test
    fun missingKeySupplierFailsClosedAndInvalidTextKeepsTypedValidationWithoutAnyWrite() =
        runTest {
            val f = mobileEditReportFixture()
            val absent = f.consumer(ComplaintReportInputs({ error("no fallback IDs") }, { error("no diagnostics") }))
            val consumer = f.consumer(mobileEditInputs())
            try {
                val blocked = absent.prepare(mobileEditDraft()).reportSuccess()
                assertEquals(
                    ComplaintReportBlock.INVALID_CANDIDATE,
                    assertIs<ComplaintEditPreparation.Blocked>(blocked).failure.block,
                )
                val invalid = consumer.prepare(mobileEditDraft(body = " \t\n ")).reportSuccess()
                assertEquals(
                    ComplaintReportRejection.REQUIRED,
                    assertIs<ComplaintEditPreparation.Invalid>(invalid).reason,
                )
                f.assertMobileEditUntouched()
            } finally {
                absent.close()
                consumer.close()
                f.close()
            }
        }

    @Test
    fun forgedForeignAndCrossOperationHandlesCannotConsumeTheOriginalAndCloseIsFinal() =
        runTest {
            val f = mobileEditReportFixture()
            val owner = f.consumer(mobileEditInputs())
            val foreign = f.consumer(mobileEditInputs())
            try {
                val live = owner.preparedConsumerEdit()
                assertFalse(live is ComplaintLiveReport || live is ComplaintLiveReply)
                assertIs<AppResult.Failure>(foreign.submit(live))
                assertIs<AppResult.Failure>(foreign.retry(live))
                owner.assertEditForgeriesRejected()
                f.assertMobileEditUntouched()
                assertIs<ComplaintReportAttempt.Completed>(owner.submit(live).reportSuccess().attempt)
                assertIs<AppResult.Failure>(owner.submit(live))
                assertIs<AppResult.Failure>(owner.retry(live))
                owner.close()
                assertIs<AppResult.Failure>(owner.prepare(mobileEditDraft()))
                assertIs<AppResult.Failure>(owner.submit(live))
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
            for (change in listOf("consent", "reset", "record")) assertEditCaptureFence(change)
        }
}

internal suspend fun ComplaintReportFixture.installEditPreparationBlock(state: String) {
    when (state) {
        "missing" -> storage.credentials.removePieces()
        "locked" ->
            storage.credentials.readFailure =
                InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.LOCKED)
        "corrupt" ->
            storage.credentials.readFailure =
                InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.CORRUPT)
        "cleanup" -> storage.credentials.marker = Fixtures.marker(1, CredentialCleanupReason.USER_RESET_CONFIRMED)
        "deletion" ->
            storage.credentials.install(
                Fixtures.record(state = InstallationCredentialState.DELETION_PENDING, key = Fixtures.KEY),
            )
        "reset" -> storage.credentials.install(Fixtures.record(state = InstallationCredentialState.LOCAL_RESET_PENDING))
        "consent" -> coordinator.requestRecovery(RecoveryIntent.Reset(coordinator.admit().success())).success()
    }
}

private suspend fun BackendComplaintReportRepository.assertEditForgeriesRejected() {
    val forged = object : ComplaintLiveEdit, ComplaintLiveReport, ComplaintLiveReply {}
    val edit: ComplaintLiveEdit = forged
    val report: ComplaintLiveReport = forged
    val reply: ComplaintLiveReply = forged
    assertIs<AppResult.Failure>(submit(edit))
    assertIs<AppResult.Failure>(retry(edit))
    assertIs<AppResult.Failure>(submit(report))
    assertIs<AppResult.Failure>(retry(report))
    assertIs<AppResult.Failure>(submit(reply))
    assertIs<AppResult.Failure>(retry(reply))
}

private suspend fun TestScope.assertEditCaptureFence(change: String) {
    val f = mobileEditReportFixture()
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
        val result = consumer.prepare(mobileEditDraft()).reportSuccess()
        assertEquals(
            ComplaintReportBlock.STALE_BINDING,
            assertIs<ComplaintEditPreparation.Blocked>(result).failure.block,
        )
        assertEquals(1, keys)
        assertTrue(f.requests.isEmpty() && f.sessionRequests.isEmpty())
    } finally {
        consumer.close()
        f.close()
    }
}

internal suspend fun ComplaintReportFixture.changeEditOrigin(ordinary: Permit, change: String) {
    if (change == "record") {
        storage.credentials.install(Fixtures.record(version = 2, generation = 2))
    } else {
        val prompt = coordinator.requestRecovery(RecoveryIntent.Reset(ordinary)).success()
        if (change == "reset") {
            coordinator.confirmRecovery(prompt).success()
            storage.credentials.install(Fixtures.record())
        } else {
            coordinator.cancelRecovery(prompt).success()
        }
    }
}
