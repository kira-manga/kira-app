package me.manga.kira.presentation.settings.feedback.edit

import kotlinx.coroutines.CompletableDeferred
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
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintEditReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportField
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportRejection
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
@Suppress("TooManyFunctions") // Six risk tests use short phase/assertion helpers rather than oversized test bodies.
class BackendComplaintEditViewModelTest {
    private val fixtures = mutableListOf<BackendComplaintEditViewModelFixture>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun openingIsInertAndRejectsUnknownKindsStatusesAndTypes() =
        runTest {
            val ordinary = fixture()
            ordinary.model.submit(BackendComplaintEditIntent.Retry)
            assertTrue(ordinary.model.state.value.editable)
            assertNoCalls(ordinary.repository)
            val literalUnknown = fixture(editRow(status = ComplaintHistoryStatus.Known(ComplaintStatus.UNKNOWN)))
            assertTrue(literalUnknown.model.state.value.editable)
            val unknown =
                listOf(
                    UnknownComplaintItem(EDIT_ID, "private_future_kind", EDIT_TIME, EDIT_TIME),
                    editRow(status = ComplaintHistoryStatus.Unrecognized),
                    editRow(type = ComplaintHistoryType.Unrecognized),
                )
            unknown.forEach { row ->
                val rejected = fixture(row)
                rejected.model.fillEditDraft()
                rejected.model.submit(BackendComplaintEditIntent.Submit)
                rejected.model.submit(BackendComplaintEditIntent.Retry)
                assertEquals(BackendComplaintEditState(), rejected.model.state.value)
                assertNoCalls(rejected.repository)
            }
        }

    @Test
    fun recognizedContentKeepsItsOriginalTargetTagAndShape() =
        runTest {
            EditRowShape.entries.forEach { shape ->
                val row = editRow(shape)
                val fixture = fixture(row)
                fixture.model.fillEditDraft()
                fixture.model.submit(BackendComplaintEditIntent.Submit)
                val captured = fixture.repository.drafts.single()
                assertSame(row, captured.target)
                assertEquals("\"complaint-$EDIT_ID-v7\"", row.fields.actionTag)
                assertEquals(7L, row.fields.version)
                assertEquals(EDIT_ORIGINAL_BODY, row.fields.body)
                assertEquals(EDIT_PRIVATE_BODY, captured.body)
                val expectedSubject = if (shape == EditRowShape.NOTICE_REPLY) null else EDIT_PRIVATE_SUBJECT
                assertEquals(expectedSubject, captured.subject)
                assertEquals(expectedSubject, fixture.model.state.value.draft.subject)
                fixture.model.submit(BackendComplaintEditIntent.Retry)
                assertSame(fixture.repository.submitted.single(), fixture.repository.retried.single())
                assertEquals(1, fixture.repository.drafts.size)
            }
        }

    @Test
    fun invalidAndBlockedPreparationKeepDraftWithoutReachingSubmission() =
        runTest {
            val fixture = fixture()
            val fake = fixture.repository
            fixture.model.fillEditDraft()
            val invalid = ComplaintEditPreparation.Invalid(ComplaintReportField.BODY, ComplaintReportRejection.TOO_SHORT)
            val missing = ComplaintReportFailure(AppError.Auth.NotSignedIn(), ComplaintReportBlock.MISSING)
            val refusals: List<AppResult<ComplaintEditPreparation>> =
                listOf(
                    AppResult.Success(invalid),
                    AppResult.Success(ComplaintEditPreparation.Blocked(missing)),
                    AppResult.Failure(AppError.Storage.Io()),
                )
            refusals.forEach { result ->
                fake.onPrepare = { result }
                fixture.model.submit(BackendComplaintEditIntent.Submit)
                fixture.model.submit(BackendComplaintEditIntent.Retry)
                assertTrue(fixture.model.state.value.editable)
                assertEquals(EDIT_PRIVATE_BODY, fixture.model.state.value.draft.body)
                assertTrue(fake.submitted.isEmpty() && fake.retried.isEmpty())
            }
            assertEquals(3, fake.drafts.size)
            assertIs<AppError.Storage.Io>(fixture.model.state.value.result.failure?.error)
        }

    @Test
    fun rapidIntentsCannotReplaceCapturedTextOrCreateASecondLiveEdit() =
        runTest {
            val fixture = fixture()
            val prepared = CompletableDeferred<Unit>()
            val submitted = CompletableDeferred<Unit>()
            pausePrepareAndSubmit(fixture.repository, prepared, submitted)
            try {
                fixture.model.fillEditDraft()
                fixture.model.submit(BackendComplaintEditIntent.Submit)
                assertLockedAgainstReplacement(fixture)
                assertTrue(fixture.repository.submitted.isEmpty())
                prepared.complete(Unit)
                runCurrent()
                assertLockedAgainstReplacement(fixture)
                assertEquals(1, fixture.repository.submitted.size)
                submitted.complete(Unit)
                runCurrent()
                assertSameHandleRetry(fixture)
            } finally {
                prepared.complete(Unit)
                submitted.complete(Unit)
            }
        }

    @Test
    fun directConflictKeepsOriginalDraftAndHandleAcrossLaterFailures() =
        runTest {
            val fixture = fixture()
            val fake = fixture.repository
            fake.onSubmit = { AppResult.Success(fake.submission(fake.unresolved(error = AppError.Network.Http(412)))) }
            fixture.model.fillEditDraft()
            fixture.model.submit(BackendComplaintEditIntent.Submit)
            assertTrue(fixture.model.state.value.result.conflictObserved)
            val failures: List<AppResult<ComplaintReportAttempt>> =
                listOf(
                    AppResult.Failure(AppError.Network.NoConnectivity()),
                    AppResult.Success(fake.unresolved(error = AppError.Network.Http(404))),
                )
            failures.forEach { failure ->
                fake.onRetry = { failure }
                fixture.model.submit(BackendComplaintEditIntent.Retry)
                assertTrue(fixture.model.state.value.result.conflictObserved)
                assertNull(fixture.model.state.value.result.receipt)
                assertLockedAgainstReplacement(fixture)
            }
            assertTrue(fake.retried.all { it === fake.submitted.single() })
            val captured = assertIs<ComplaintOwnerRow.Content>(fake.drafts.single().target)
            assertEquals("\"complaint-$EDIT_ID-v7\"", captured.fields.actionTag)
        }

    @Test
    fun knownReceiptsSurviveCleanupFailuresWithoutAcceptingOtherOperationReceipts() =
        runTest {
            val receipts =
                listOf(
                    ComplaintEditApplication.Applied(EDIT_ID, 8),
                    ComplaintEditApplication.Rejected(ComplaintEditReceiptRejection.PRECONDITION_FAILED),
                )
            receipts.forEach { receipt ->
                val fixture = knownReceiptFixture(receipt)
                assertRetainedReceipt(fixture, receipt)
                retryFailuresAndForeignReceipts(fixture.repository).forEach { result ->
                    fixture.repository.onRetry = { result }
                    fixture.model.submit(BackendComplaintEditIntent.Retry)
                    assertRetainedReceipt(fixture, receipt)
                }
                fixture.repository.onRetry = { AppResult.Success(completedEdit(receipt)) }
                fixture.model.submit(BackendComplaintEditIntent.Retry)
                assertTrue(fixture.model.state.value.result.completed)
                assertFalse(fixture.model.state.value.result.cleanupPending)
                assertFalse(fixture.model.state.value.canRetry || fixture.model.state.value.editable)
                assertEquals(EDIT_PRIVATE_BODY, fixture.model.state.value.draft.body)
                assertEquals(1, fixture.repository.drafts.size)
            }
        }

    private fun fixture(row: ComplaintOwnerRow = editRow()): BackendComplaintEditViewModelFixture =
        BackendComplaintEditViewModelFixture(row).also { fixtures += it }

    private fun assertNoCalls(fake: EditRepositoryFake) {
        assertTrue(fake.drafts.isEmpty() && fake.submitted.isEmpty() && fake.retried.isEmpty())
    }

    private fun pausePrepareAndSubmit(
        fake: EditRepositoryFake,
        prepared: CompletableDeferred<Unit>,
        submitted: CompletableDeferred<Unit>,
    ) {
        fake.onPrepare = {
            prepared.await()
            AppResult.Success(ComplaintEditPreparation.Ready(fake.live))
        }
        fake.onSubmit = {
            submitted.await()
            AppResult.Success(fake.submission(fake.unresolved()))
        }
    }

    private fun assertLockedAgainstReplacement(fixture: BackendComplaintEditViewModelFixture) {
        fixture.model.submit(BackendComplaintEditIntent.ChangeSubject("private replacement subject that must be ignored"))
        fixture.model.submit(BackendComplaintEditIntent.ChangeBody("private replacement that must be ignored"))
        fixture.model.submit(BackendComplaintEditIntent.Submit)
        assertEquals(1, fixture.repository.drafts.size)
        assertEquals(EDIT_PRIVATE_BODY, fixture.model.state.value.draft.body)
        assertEquals(EDIT_PRIVATE_SUBJECT, fixture.model.state.value.draft.subject)
        assertFalse(fixture.model.state.value.editable)
    }

    private suspend fun TestScope.assertSameHandleRetry(fixture: BackendComplaintEditViewModelFixture) {
        val release = CompletableDeferred<Unit>()
        fixture.repository.onRetry = {
            release.await()
            AppResult.Success(completedEdit())
        }
        try {
            fixture.model.submit(BackendComplaintEditIntent.Retry)
            fixture.model.submit(BackendComplaintEditIntent.Retry)
            assertLockedAgainstReplacement(fixture)
            assertSame(fixture.repository.submitted.single(), fixture.repository.retried.single())
            release.complete(Unit)
            runCurrent()
            assertLockedAgainstReplacement(fixture)
            assertFalse(fixture.model.state.value.canRetry)
        } finally {
            release.complete(Unit)
        }
    }

    private fun knownReceiptFixture(receipt: ComplaintEditApplication): BackendComplaintEditViewModelFixture {
        val fixture = fixture()
        val fake = fixture.repository
        fake.recovery = ComplaintReportRecovery(emptyList(), ComplaintReportFailure(AppError.Storage.Io()))
        fake.onSubmit = {
            val attempt = fake.unresolved(ComplaintReportApplication.Edit(receipt), AppError.Storage.Io())
            AppResult.Success(fake.submission(attempt))
        }
        fixture.model.fillEditDraft()
        fixture.model.submit(BackendComplaintEditIntent.Submit)
        return fixture
    }

    private fun assertRetainedReceipt(fixture: BackendComplaintEditViewModelFixture, receipt: ComplaintEditApplication) {
        val state = fixture.model.state.value
        assertSame(receipt, state.result.receipt)
        assertTrue(state.result.cleanupPending && state.result.recoveryObserved && state.canRetry)
        assertFalse(state.result.completed || state.editable)
        assertEquals(receipt is ComplaintEditApplication.Rejected, state.result.conflictObserved)
        assertEquals(EDIT_PRIVATE_BODY, state.draft.body)
        assertFalse(state.toString().contains("private") || state.draft.toString().contains("private"))
        assertFalse(BackendComplaintEditIntent.ChangeBody(EDIT_PRIVATE_BODY).toString().contains("private"))
        assertFalse(BackendComplaintEditIntent.ChangeSubject(EDIT_PRIVATE_SUBJECT).toString().contains("private"))
    }

    private fun retryFailuresAndForeignReceipts(fake: EditRepositoryFake): List<AppResult<ComplaintReportAttempt>> =
        listOf(
            AppResult.Failure(AppError.Network.NoConnectivity()),
            AppResult.Success(fake.unresolved(error = AppError.Network.Http(404))),
            AppResult.Success(ComplaintReportAttempt.Completed(ComplaintReportApplication.Applied(EDIT_PARENT, 1))),
            AppResult.Success(
                ComplaintReportAttempt.Completed(
                    ComplaintReportApplication.OwnerDelete(ComplaintOwnerDeleteApplication.Applied),
                ),
            ),
        )
}
