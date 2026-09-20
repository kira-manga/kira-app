package me.manga.kira.presentation.settings.feedback.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.presentation.complaint.DETAIL_A
import me.manga.kira.presentation.complaint.DETAIL_TIME
import me.manga.kira.presentation.complaint.ownedDetail
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Four behavioral tests share narrow assertions for the same operation contract.
class BackendComplaintDeleteViewModelTest {
    private val fixtures = mutableListOf<BackendComplaintDeleteFixture>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun constructorAndRetryAreInertAndUnrecognizedTargetsCannotConfirm() =
        runTest {
            val row = assertIs<ComplaintOwnerRow.Report>(ownedDetail().item)
            val normal = fixture(row)
            normal.model.submit(BackendComplaintDeleteIntent.Retry)
            assertTrue(normal.model.state.value.canConfirm)
            assertEquals(row.subject, normal.model.state.value.preview.subject)
            assertEquals(row.fields.body, normal.model.state.value.preview.body)
            assertTrue(normal.repository.drafts.isEmpty())
            assertEquals("BackendComplaintDeleteState(redacted)", normal.model.state.value.toString())
            assertEquals("BackendComplaintDeletePreview(redacted)", normal.model.state.value.preview.toString())
            val notice = fixture(ComplaintOwnerRow.NoticeReply(row.fields, "secret.notice.key", DETAIL_A))
            assertTrue(notice.model.state.value.canConfirm)
            assertNull(notice.model.state.value.preview.subject)
            assertEquals(row.fields.body, notice.model.state.value.preview.body)
            assertUnknownTargetsRefused(row)
        }

    @Test
    fun explicitConfirmationLocksOriginalTargetAndRetryUsesTheIdenticalLiveDeletion() =
        runTest {
            val row = ownedDetail().item
            val fixture = fixture(row)
            val barriers = DeleteAttemptBarriers(fixture.repository)
            try {
                assertConfirmationLocked(fixture, row)
                barriers.prepared.complete(Unit)
                runCurrent()
                fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
                assertSame(fixture.repository.live, fixture.repository.submitted.single())
                barriers.submitted.complete(Unit)
                runCurrent()
                fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
                assertTrue(fixture.model.state.value.canRetry)
                assertIdenticalRetry(fixture, barriers)
            } finally {
                barriers.release()
                runCurrent()
            }
        }

    @Test
    fun missingOrDeletionPendingPreparationNeverDispatchesOrEnablesRetry() =
        runTest {
            for (block in listOf(ComplaintReportBlock.MISSING, ComplaintReportBlock.REMOTE_DELETION_PENDING)) {
                val fixture = fixture()
                val failure = ComplaintReportFailure(AppError.Network.Http(409), block)
                fixture.repository.onPrepare = { AppResult.Success(ComplaintOwnerDeletePreparation.Blocked(failure)) }
                fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
                assertSame(failure, fixture.model.state.value.result.failure)
                assertTrue(fixture.model.state.value.canConfirm)
                assertFalse(fixture.model.state.value.canRetry)
                assertTrue(fixture.repository.submitted.isEmpty())
                fixture.model.submit(BackendComplaintDeleteIntent.Retry)
                assertTrue(fixture.repository.retried.isEmpty())
                assertEquals(1, fixture.repository.drafts.size)
            }
        }

    @Test
    fun directConflictAndKnownDeleteReceiptsSurviveLaterErrorsButForeignReceiptsNeverCompleteDeletion() =
        runTest {
            assertDirectConflictRetained()
            val receipts =
                listOf(
                    ComplaintOwnerDeleteApplication.Applied,
                    ComplaintOwnerDeleteApplication.Rejected(ComplaintOwnerDeleteReceiptRejection.PRECONDITION_FAILED),
                )
            for (receipt in receipts) assertKnownReceiptRetained(receipt)
        }

    private fun assertUnknownTargetsRefused(row: ComplaintOwnerRow.Report) {
        val unknowns =
            listOf(
                UnknownComplaintItem(DETAIL_A, "future.private.kind", DETAIL_TIME, DETAIL_TIME),
                ComplaintOwnerRow.Report(row.fields, ComplaintHistoryType.Unrecognized, "private subject"),
            )
        for (target in unknowns) {
            val unknown = fixture(target)
            unknown.model.submit(BackendComplaintDeleteIntent.Confirm)
            assertFalse(unknown.model.state.value.hasTarget)
            assertTrue(unknown.repository.drafts.isEmpty())
            assertTrue(unknown.repository.submitted.isEmpty())
        }
    }

    private fun assertConfirmationLocked(fixture: BackendComplaintDeleteFixture, row: ComplaintOwnerRow) {
        fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
        fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
        fixture.model.submit(BackendComplaintDeleteIntent.Retry)
        assertTrue(fixture.model.state.value.busy)
        assertEquals(1, fixture.repository.drafts.size)
        assertSame(row, fixture.repository.drafts.single().target)
    }

    private suspend fun TestScope.assertIdenticalRetry(fixture: BackendComplaintDeleteFixture, barriers: DeleteAttemptBarriers) {
        val repository = fixture.repository
        barriers.holdRetry()
        fixture.model.submit(BackendComplaintDeleteIntent.Retry)
        fixture.model.submit(BackendComplaintDeleteIntent.Retry)
        assertSame(repository.live, repository.retried.single())
        barriers.retried.complete(Unit)
        runCurrent()
        assertSame(ComplaintOwnerDeleteApplication.Applied, fixture.model.state.value.result.receipt)
        assertTrue(fixture.model.state.value.result.completed)
        fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
        fixture.model.submit(BackendComplaintDeleteIntent.Retry)
        assertEquals(1, repository.drafts.size)
        assertEquals(1, repository.submitted.size)
        assertEquals(1, repository.retried.size)
    }

    private fun assertDirectConflictRetained() {
        val conflict = fixture()
        val preview = conflict.model.state.value.preview
        conflict.repository.onSubmit = { AppResult.Failure(AppError.Network.Http(412)) }
        conflict.model.submit(BackendComplaintDeleteIntent.Confirm)
        assertTrue(conflict.model.state.value.result.conflictObserved)
        assertEquals(preview, conflict.model.state.value.preview)
        assertNull(conflict.model.state.value.result.receipt)
        conflict.repository.onRetry = { AppResult.Failure(AppError.Network.Timeout()) }
        conflict.model.submit(BackendComplaintDeleteIntent.Retry)
        assertTrue(conflict.model.state.value.result.conflictObserved)
        assertSame(conflict.repository.live, conflict.repository.retried.single())
    }

    private fun assertKnownReceiptRetained(receipt: ComplaintOwnerDeleteApplication) {
        val fixture = fixture()
        fixture.repository.onSubmit = {
            AppResult.Success(fixture.repository.submission(unresolved(ComplaintReportApplication.OwnerDelete(receipt))))
        }
        fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
        assertSame(receipt, fixture.model.state.value.result.receipt)
        assertTrue(fixture.model.state.value.result.cleanupPending)
        fixture.repository.onRetry = { error("private provider detail") }
        fixture.model.submit(BackendComplaintDeleteIntent.Retry)
        assertSame(receipt, fixture.model.state.value.result.receipt)
        assertTrue(fixture.model.state.value.result.cleanupPending)
        assertIs<AppError.Unexpected>(fixture.model.state.value.result.failure?.error)
        assertForeignReceiptsRejected(fixture, receipt)
        fixture.repository.onRetry = { AppResult.Success(deleteCompleted(receipt)) }
        fixture.model.submit(BackendComplaintDeleteIntent.Retry)
        assertTrue(fixture.model.state.value.result.completed)
        assertFalse(fixture.model.state.value.canRetry)
        assertEquals(receipt is ComplaintOwnerDeleteApplication.Rejected, fixture.model.state.value.result.conflictObserved)
    }

    private fun assertForeignReceiptsRejected(fixture: BackendComplaintDeleteFixture, receipt: ComplaintOwnerDeleteApplication) {
        val foreign =
            listOf(
                ComplaintReportApplication.Applied(DETAIL_A, 2),
                ComplaintReportApplication.Edit(ComplaintEditApplication.Applied(DETAIL_A, 2)),
            )
        for (application in foreign) {
            fixture.repository.onRetry = { AppResult.Success(ComplaintReportAttempt.Completed(application)) }
            fixture.model.submit(BackendComplaintDeleteIntent.Retry)
            val state = fixture.model.state.value
            assertSame(receipt, state.result.receipt)
            assertFalse(state.result.completed)
            assertEquals(ComplaintReportBlock.RECONCILIATION_REQUIRED, state.result.failure?.block)
        }
    }

    private fun fixture(target: ComplaintOwnerRow = ownedDetail().item): BackendComplaintDeleteFixture =
        BackendComplaintDeleteFixture(target).also { fixtures += it }
}
