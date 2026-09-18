package me.manga.kira.presentation.settings.feedback.reply

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportField
import me.manga.kira.domain.model.feedback.ComplaintReportObservation
import me.manga.kira.domain.model.feedback.ComplaintReportReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportRejection
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackDeletionState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintReplyRecoveryTest {
    private val fixtures = mutableListOf<ComplaintReplyPanelFixture>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.clearAll() }
        Dispatchers.resetMain()
    }

    @Test
    fun openingObservesLocallyBeforeMetadataAndAnEmptyPassNeverSendsText() =
        runTest {
            val fixture = fixture()
            val local = CompletableDeferred<Unit>()
            val metadata = CompletableDeferred<Unit>()
            holdOpeningReads(fixture, local, metadata)
            val vm = fixture.model()
            try {
                assertEquals(listOf("observe"), fixture.calls)
                vm.submit(ComplaintReplyIntent.Submit)
                local.complete(Unit)
                runCurrent()
                assertEquals(listOf("observe", "reconcile"), fixture.calls)
                vm.submit(ComplaintReplyIntent.Submit)
                assertTrue(vm.state.value.busy)
                metadata.complete(Unit)
                runCurrent()
                assertTrue(vm.state.value.editable)
                assertTrue(assertNotNull(vm.state.value.recovery).entries().isEmpty())
                assertTrue(fixture.drafts.isEmpty() && fixture.submitted.isEmpty() && fixture.retried.isEmpty())
                fixture.assertNoRecoveryMutation()
            } finally {
                local.complete(Unit)
                metadata.complete(Unit)
            }
        }

    @Test
    fun missingDeletionCleanupAndFailedLocalObservationBlockMutationWithoutSetup() =
        runTest {
            for ((observation, expected) in blockedInstallations()) {
                val fixture = fixture()
                fixture.deletion.observation = observation
                val vm = fixture.model()
                vm.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
                vm.submit(ComplaintReplyIntent.Submit)
                vm.submit(ComplaintReplyIntent.Retry)
                assertEquals(expected, vm.state.value.context.installation)
                assertEquals(listOf("observe"), fixture.calls)
                assertFalse(vm.state.value.canSubmit || vm.state.value.canRetry)
                fixture.assertNoRecoveryMutation()
            }
        }

    @Test
    fun invalidPreparationAllowsCorrectionButRecognizedParentStillDoesNotAuthorizeSubmission() =
        runTest {
            val fixture = fixture()
            fixture.onPrepare = {
                val invalid =
                    ComplaintReplyPreparation.Invalid(ComplaintReportField.BODY, ComplaintReportRejection.REQUIRED)
                AppResult.Success(invalid)
            }
            val vm = fixture.model()
            vm.submit(ComplaintReplyIntent.Submit)
            assertIs<ComplaintReplyResult.Invalid>(vm.state.value.result)
            assertTrue(vm.state.value.editable)
            vm.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
            assertNull(vm.state.value.result)
            fixture.onPrepare = {
                AppResult.Success(ComplaintReplyPreparation.Blocked(blockedReplyFailure(ComplaintReportBlock.MISSING)))
            }
            vm.submit(ComplaintReplyIntent.Submit)
            assertEquals(SettingsFeedbackDeletionState.Missing, vm.state.value.context.installation)
            assertEquals(2, fixture.drafts.size)
            assertTrue(fixture.submitted.isEmpty() && fixture.retried.isEmpty())
            fixture.assertNoRecoveryMutation()
        }

    @Test
    fun aBlockedLiveReplyCannotEnterColdReconciliationOrLoseItsKnownReceipt() =
        runTest {
            val fixture = fixture()
            val known = ComplaintReportApplication.Applied(REPLY_ID, 1)
            fixture.onSubmit = {
                val failure = blockedReplyFailure(ComplaintReportBlock.CLEANUP_REQUIRED)
                val attempt = ComplaintReportAttempt.Unresolved(failure, known)
                AppResult.Success(ComplaintReportSubmission(attempt, fixture.reports.recovery))
            }
            val vm = fixture.model()
            vm.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
            vm.submit(ComplaintReplyIntent.Submit)
            vm.submit(ComplaintReplyIntent.RefreshRecovery)
            vm.submit(ComplaintReplyIntent.Retry)
            vm.submit(ComplaintReplyIntent.ChangeBody("replacement"))
            assertSame(known, unresolved(vm).knownApplication)
            assertFalse(vm.state.value.canRefreshRecovery || vm.state.value.canSubmit || vm.state.value.canRetry)
            assertEquals(REPLY_BODY, vm.state.value.body)
            assertEquals(listOf("observe", "reconcile", "prepare", "submit"), fixture.calls)
        }

    @Test
    fun reopeningRecoversOnlyMetadataAndFailedRefreshDoesNotDiscardPriorObservations() =
        runTest {
            val fixture = fixture()
            val retained = fixture.reports.preparedRecovery()
            fixture.reports.recovery = retained
            val old = fixture.model()
            old.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
            old.submit(ComplaintReplyIntent.Submit)
            old.submit(ComplaintReplyIntent.Close)
            val reopened = fixture.model()
            reopened.submit(ComplaintReplyIntent.Retry)
            fixture.onReconcile = { AppResult.Failure(AppError.Network.NoConnectivity()) }
            reopened.submit(ComplaintReplyIntent.RefreshRecovery)
            assertEquals("", reopened.state.value.body)
            assertSame(retained, reopened.state.value.recovery)
            assertIs<ComplaintReplyResult.Failure>(reopened.state.value.result)
            assertEquals(1, fixture.drafts.size)
            assertEquals(1, fixture.submitted.size)
            assertTrue(fixture.retried.isEmpty())
            fixture.assertNoRecoveryMutation()
        }

    @Test
    fun knownAppliedAndRejectedReceiptsAndExactPendingSurviveFailedCancelledAndThrowingRetry() =
        runTest {
            for (known in knownApplications()) {
                val fixture = fixture()
                val first = fixture.reports.unresolved(known)
                fixture.onSubmit = { AppResult.Success(ComplaintReportSubmission(first, fixture.reports.recovery)) }
                val vm = fixture.model()
                vm.submit(ComplaintReplyIntent.Submit)
                for (fail in failedReplyRetries()) {
                    val attempt = failedRetry(fixture, vm, fail)
                    assertSame(known, attempt.knownApplication)
                    assertSame(first.pending, attempt.pending)
                    assertFalse(vm.state.value.canSubmit || vm.state.value.editable)
                }
                assertEquals(1, fixture.drafts.size)
                assertEquals(1, fixture.submitted.size)
                assertTrue(fixture.retried.all { it === fixture.live })
                fixture.assertNoRecoveryMutation()
            }
        }

    @Test
    fun unresolvedAndExpiredRetryKeepApplicationButNeverInventNewPendingAuthority() =
        runTest {
            for (known in knownApplications()) {
                val fixture = fixture()
                fixture.onSubmit = {
                    val submission = ComplaintReportSubmission(fixture.reports.unresolved(known), fixture.reports.recovery)
                    AppResult.Success(submission)
                }
                val vm = fixture.model()
                vm.submit(ComplaintReplyIntent.Submit)
                val failure = blockedReplyFailure(ComplaintReportBlock.RECEIPT_WINDOW_EXPIRED)
                fixture.onRetry = { AppResult.Success(ComplaintReportAttempt.Unresolved(failure)) }
                vm.submit(ComplaintReplyIntent.Retry)
                assertSame(known, unresolved(vm).knownApplication)
                assertNull(unresolved(vm).pending)
                vm.submit(ComplaintReplyIntent.Submit)
                assertEquals(1, fixture.drafts.size)
                assertSame(fixture.live, fixture.retried.single())
                val completed = ComplaintReportAttempt.Completed(known)
                val preserved = assertIs<ComplaintReportAttempt.Unresolved>(failure.afterReplyAttempt(completed))
                assertSame(known, preserved.knownApplication)
            }
        }

    @Test
    fun mixedRecoveryIsNeverPromotedToThisReplyOrChangedIntoCreationReceipts() =
        runTest {
            val fixture = fixture()
            val pending = assertNotNull(fixture.reports.unresolved().pending)
            val applications =
                knownApplications() + listOf(
                    ComplaintReportApplication.Edit(ComplaintEditApplication.Applied(REPLY_ID, 2)),
                    ComplaintReportApplication.OwnerDelete(ComplaintOwnerDeleteApplication.Applied),
                )
            val observations =
                applications.map { ComplaintReportObservation(pending, ComplaintReportAttempt.Completed(it)) }
            val retained = ComplaintReportRecovery(observations)
            fixture.reports.recovery = retained
            val vm = fixture.model()
            assertSame(retained, vm.state.value.recovery)
            assertNull(vm.state.value.result)
            assertEquals(listOf("observe", "reconcile"), fixture.calls)
            fixture.assertNoRecoveryMutation()
        }

    @Test
    fun unknownNoticeAndUnavailableTargetsStayInertWhileOrdinaryParentsAndDiagnosticsStayBounded() =
        runTest {
            for (detail in blockedReplyDetails()) {
                val target = ComplaintReplyTarget.capture(detail)
                assertNull(target)
                val fixture = fixture()
                val vm = fixture.model(target)
                vm.submit(ComplaintReplyIntent.Submit)
                vm.submit(ComplaintReplyIntent.RefreshRecovery)
                assertFalse(vm.state.value.context.targetAvailable)
                assertTrue(fixture.calls.isEmpty())
            }
            for (detail in readyReplyDetails()) {
                val target = assertNotNull(ComplaintReplyTarget.capture(detail))
                val vm = fixture().model(target)
                vm.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
                val diagnostics =
                    listOf(
                        target.toString(),
                        vm.state.value.toString(),
                        ComplaintReplyIntent.ChangeBody(REPLY_BODY).toString(),
                    )
                assertTrue(diagnostics.none { it.contains("private") || it.contains(PARENT_ID) || it.contains(REPLY_BODY) })
                vm.submit(ComplaintReplyIntent.Submit)
                assertFalse(assertNotNull(vm.state.value.result).toString().contains("private"))
            }
        }

    private fun fixture(): ComplaintReplyPanelFixture = ComplaintReplyPanelFixture().also { fixtures += it }
}

private fun unresolved(vm: ComplaintReplyViewModel): ComplaintReportAttempt.Unresolved =
    assertIs<ComplaintReportAttempt.Unresolved>(assertIs<ComplaintReplyResult.Attempt>(vm.state.value.result).attempt)

private fun knownApplications(): List<ComplaintReportApplication> =
    listOf(
        ComplaintReportApplication.Applied(REPLY_ID, 1),
        ComplaintReportApplication.Rejected(ComplaintReportReceiptRejection.COMPLAINT_PARENT_NOT_FOUND),
    )

private suspend fun failedRetry(
    fixture: ComplaintReplyPanelFixture,
    vm: ComplaintReplyViewModel,
    fail: suspend () -> AppResult<ComplaintReportAttempt>,
): ComplaintReportAttempt.Unresolved {
    val caller = CompletableDeferred<Job>()
    fixture.onRetry = {
        caller.complete(currentCoroutineContext().job)
        fail()
    }
    vm.submit(ComplaintReplyIntent.Retry)
    val attempt = unresolved(vm)
    val error = attempt.failure.error
    assertNull(error.cause)
    if (error is AppError.Cancelled) assertTrue(caller.await().isCancelled)
    if (error is AppError.Unexpected) assertEquals("complaint_reply_failed", error.message)
    return attempt
}
