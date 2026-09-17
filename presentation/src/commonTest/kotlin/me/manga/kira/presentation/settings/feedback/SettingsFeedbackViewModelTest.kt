package me.manga.kira.presentation.settings.feedback

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
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
class SettingsFeedbackViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = mutableListOf<SettingsFeedbackViewModel>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun concurrentSubmitAndEditsCannotReplaceLiveRequestAndRetryKeepsItsHandle() =
        runTest {
            val fake = SettingsFeedbackRepositoryFake()
            val release = CompletableDeferred<Unit>()
            fake.onSubmit = {
                release.await()
                AppResult.Success(ComplaintReportSubmission(fake.unresolved(), fake.recovery))
            }
            val vm = model(fake)
            vm.fillDraft()
            try {
                vm.submit(SettingsFeedbackIntent.Submit)
                vm.submit(SettingsFeedbackIntent.Submit)
                vm.submit(SettingsFeedbackIntent.ChangeBody("replacement"))
                assertTrue(vm.state.value.busy)
                assertEquals(1, fake.submitted.size)
                assertEquals("private report body", vm.state.value.draft.body)
                release.complete(Unit)
                runCurrent()
                vm.submit(SettingsFeedbackIntent.RefreshRecovery)
                vm.submit(SettingsFeedbackIntent.NewDraft)
                assertEquals(1, fake.reconciliations)
                vm.submit(SettingsFeedbackIntent.Retry)
                assertSame(fake.submitted.single(), fake.retried.single())
                assertEquals(1, fake.drafts.size)
                assertTrue(vm.state.value.canStartNewDraft)
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun knownAppliedCleanupFailureIsNotSuccessAndSurvivesLaterRetryFailure() =
        runTest {
            val fake = SettingsFeedbackRepositoryFake()
            val known = ComplaintReportApplication.Applied("synthetic-id", 1)
            fake.onSubmit = { AppResult.Success(ComplaintReportSubmission(fake.unresolved(known), fake.recovery)) }
            fake.onRetry = { AppResult.Failure(AppError.Network.NoConnectivity()) }
            val vm = model(fake)
            vm.fillDraft()
            vm.submit(SettingsFeedbackIntent.Submit)
            vm.submit(SettingsFeedbackIntent.Retry)
            val attempt = assertIs<SettingsFeedbackResult.Attempt>(vm.state.value.result).attempt
            assertSame(known, assertIs<ComplaintReportAttempt.Unresolved>(attempt).knownApplication)
            assertTrue(vm.state.value.canRetry)
            assertFalse(vm.state.value.canStartNewDraft)
            assertFalse(
                vm.state.value
                    .toString()
                    .contains("private"),
            )
            assertFalse(
                vm.state.value.draft
                    .toString()
                    .contains("private"),
            )
            assertFalse(
                SettingsFeedbackIntent
                    .ChangeCategory(ComplaintType.CUSTOM, "private subject")
                    .toString()
                    .contains("private"),
            )
            assertFalse(SettingsFeedbackIntent.ChangeBody("private body").toString().contains("private"))
        }

    @Test
    fun restartingViewModelRecoversMetadataWithoutProseAndOnlyExplicitlyCancelsPrepared() =
        runTest {
            val fake = SettingsFeedbackRepositoryFake()
            val old = model(fake)
            old.fillDraft()
            old.submit(SettingsFeedbackIntent.Submit)
            old.submit(SettingsFeedbackIntent.Close)
            assertTrue(fake.preparedCancelled.isEmpty() && fake.confirmed.isEmpty())
            fake.recovery = fake.preparedRecovery()
            val restarted = model(fake)
            assertEquals("", restarted.state.value.draft.body)
            restarted.submit(SettingsFeedbackIntent.Retry)
            assertTrue(fake.retried.isEmpty())
            assertEquals(1, fake.drafts.size)
            assertEquals(1, fake.submitted.size)
            restarted.submit(SettingsFeedbackIntent.CancelPrepared(fake.pending))
            assertSame(fake.pending, fake.preparedCancelled.single())
            assertIs<SettingsFeedbackResult.PreparedCancelled>(restarted.state.value.result)
            assertEquals(1, fake.submitted.size)
        }

    @Test
    fun unknownUiObservationIsIgnoredAndOnlyExactExplicitConfirmationResetsLocally() =
        runTest {
            val fake = SettingsFeedbackRepositoryFake()
            fake.recovery = fake.preparedRecovery()
            val vm = model(fake)
            val effects = mutableListOf<SettingsFeedbackEffect>()
            backgroundScope.launch(dispatcher) { vm.effects.collect { effects += it } }
            vm.submit(SettingsFeedbackIntent.RequestRecovery(SettingsTestPendingReport()))
            assertTrue(fake.requested.isEmpty())
            vm.submit(SettingsFeedbackIntent.RequestRecovery(fake.pending))
            val first = fake.nextPrompt
            assertTrue(vm.state.value.confirmationPending)
            assertEquals(listOf<SettingsFeedbackEffect>(SettingsFeedbackEffect.ConfirmLocalReset), effects)
            vm.submit(SettingsFeedbackIntent.CancelRecovery)
            assertSame(first, fake.dismissed.single())
            assertTrue(fake.confirmed.isEmpty())
            fake.nextPrompt = SettingsTestRecoveryPrompt()
            vm.submit(SettingsFeedbackIntent.RequestRecovery(fake.pending))
            vm.submit(SettingsFeedbackIntent.ConfirmRecovery)
            assertSame(fake.nextPrompt, fake.confirmed.single())
            assertIs<SettingsFeedbackResult.LocalResetCompleted>(vm.state.value.result)
            assertFalse(vm.state.value.canStartNewDraft)
            assertIs<SettingsFeedbackDeletionState.Uncertain>(vm.state.value.deletion)
            assertFalse(vm.state.value.editable)
            assertTrue(fake.submitted.isEmpty())
        }

    @Test
    fun closeDoesNotCancelAnotherViewModelAndItsOwnLateResultCannotPublishSuccess() =
        runTest {
            val fake = SettingsFeedbackRepositoryFake()
            val release = CompletableDeferred<Unit>()
            var caller: Job? = null
            fake.onSubmit = { lateReportResult(fake, release) { caller = it } }
            val other = model(fake)
            val active = model(fake)
            active.fillDraft()
            try {
                active.submit(SettingsFeedbackIntent.Submit)
                other.submit(SettingsFeedbackIntent.Close)
                assertTrue(assertNotNull(caller).isActive)
                assertTrue(active.state.value.busy)
                active.submit(SettingsFeedbackIntent.Close)
                assertTrue(assertNotNull(caller).isCancelled)
                release.complete(Unit)
                runCurrent()
                assertNull(active.state.value.result)
                assertEquals("", active.state.value.draft.body)
                assertTrue(fake.preparedCancelled.isEmpty() && fake.confirmed.isEmpty())
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun clearingViewModelDuringLatePromptDeliveryDismissesOnlyItsToken() =
        runTest {
            val fake = SettingsFeedbackRepositoryFake()
            fake.recovery = fake.preparedRecovery()
            val release = CompletableDeferred<Unit>()
            fake.onRequest = {
                withContext(NonCancellable) {
                    release.await()
                    AppResult.Success(fake.nextPrompt)
                }
            }
            val vm = model(fake)
            try {
                vm.submit(SettingsFeedbackIntent.RequestRecovery(fake.pending))
                vm.viewModelScope.cancel()
                release.complete(Unit)
                runCurrent()
                assertSame(fake.nextPrompt, fake.dismissed.single())
                assertTrue(fake.preparedCancelled.isEmpty() && fake.confirmed.isEmpty())
                assertFalse(vm.state.value.confirmationPending)
            } finally {
                release.complete(Unit)
            }
        }

    private fun model(fake: SettingsFeedbackRepositoryFake): SettingsFeedbackViewModel =
        fake.viewModel().also {
            models += it
        }
}

private fun SettingsFeedbackViewModel.fillDraft() {
    submit(SettingsFeedbackIntent.ChangeCategory(ComplaintType.TECHNICAL, "private subject"))
    submit(SettingsFeedbackIntent.ChangeBody("private report body"))
}

private suspend fun lateReportResult(
    fake: SettingsFeedbackRepositoryFake,
    release: CompletableDeferred<Unit>,
    observeCaller: (Job) -> Unit,
): AppResult<ComplaintReportSubmission> {
    observeCaller(currentCoroutineContext().job)
    return withContext(NonCancellable) {
        release.await()
        val completed = ComplaintReportAttempt.Completed(ComplaintReportApplication.Applied("id", 1))
        AppResult.Success(ComplaintReportSubmission(completed, fake.recovery))
    }
}
