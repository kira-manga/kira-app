package me.manga.kira.presentation.settings.feedback

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.ComplaintInstallationDeletionObservation
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Four domain/use-case/VM boundary tests; no rendered UI automation or platform transport claims. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsInstallationDeletionViewModelTest {
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
    fun remoteAndLocalConsentStaySeparateAndDuplicateConfirmationCannotStartTwice() {
        runTest {
            val reports = SettingsFeedbackRepositoryFake()
            val deletion = SettingsInstallationDeletionFake()
            val vm = model(reports, deletion)
            val release = CompletableDeferred<Unit>()
            deletion.onConfirm = {
                release.await()
                AppResult.Success(ComplaintInstallationDeletionOutcome.Pending())
            }
            try {
                vm.submit(SettingsFeedbackIntent.ChangeBody("retained live body"))
                vm.submit(SettingsFeedbackIntent.Submit)
                vm.submit(SettingsFeedbackIntent.RequestRemoteDeletion)
                vm.submit(SettingsFeedbackIntent.ConfirmRecovery)
                vm.submit(SettingsFeedbackIntent.CancelRecovery)
                assertTrue(vm.state.value.remoteConfirmationPending)
                assertNull(vm.state.value.recoveryKind)
                assertTrue(reports.confirmed.isEmpty() && reports.dismissed.isEmpty())
                vm.submit(SettingsFeedbackIntent.CancelRemoteDeletion)
                assertSame(deletion.nextPrompt, deletion.dismissed.single())
                assertTrue(vm.state.value.canRetry)
                vm.submit(SettingsFeedbackIntent.RequestUnreadableRecovery)
                vm.submit(SettingsFeedbackIntent.ConfirmRemoteDeletion)
                vm.submit(SettingsFeedbackIntent.CancelRemoteDeletion)
                assertEquals(SettingsFeedbackRecoveryKind.UNREADABLE, vm.state.value.recoveryKind)
                assertTrue(deletion.confirmed.isEmpty())
                assertEquals(1, deletion.dismissed.size)
                vm.submit(SettingsFeedbackIntent.CancelRecovery)
                assertSame(reports.nextPrompt, reports.dismissed.single())
                deletion.nextPrompt = SettingsTestDeletionPrompt()
                vm.submit(SettingsFeedbackIntent.RequestRemoteDeletion)
                vm.submit(SettingsFeedbackIntent.ConfirmRemoteDeletion)
                assertTrue(vm.state.value.busy)
                assertIs<SettingsFeedbackDeletionState.Uncertain>(vm.state.value.deletion)
                vm.submit(SettingsFeedbackIntent.ConfirmRemoteDeletion)
                vm.submit(SettingsFeedbackIntent.ConfirmRecovery)
                release.complete(Unit)
                runCurrent()
                assertSame(deletion.nextPrompt, deletion.confirmed.single())
                assertIs<SettingsFeedbackDeletionState.Pending>(vm.state.value.deletion)
                vm.submit(SettingsFeedbackIntent.ConfirmRemoteDeletion)
                vm.submit(SettingsFeedbackIntent.Retry)
                assertEquals(1, deletion.confirmed.size)
                assertTrue(reports.retried.isEmpty() && reports.confirmed.isEmpty())
                assertEquals(0, deletion.continuations)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun pendingFailureAndCanceledConfirmationKeepEveryNormalActionBlockedUntilFreshLocalObservation() {
        runTest {
            for (outcome in DeletionVmOutcome.entries) {
                val reports = SettingsFeedbackRepositoryFake()
                reports.recovery = reports.preparedRecovery()
                val history = SettingsFeedbackHistoryFake()
                val deletion = SettingsInstallationDeletionFake()
                deletion.onConfirm = { outcome.result() }
                val vm = model(reports, deletion, history)
                vm.submit(SettingsFeedbackIntent.ChangeBody("retained body"))
                vm.submit(SettingsFeedbackIntent.Submit)
                vm.submit(SettingsFeedbackIntent.RequestRemoteDeletion)
                vm.submit(SettingsFeedbackIntent.ConfirmRemoteDeletion)
                if (outcome == DeletionVmOutcome.PENDING) {
                    assertIs<SettingsFeedbackDeletionState.Pending>(vm.state.value.deletion)
                } else {
                    assertIs<SettingsFeedbackDeletionState.Uncertain>(vm.state.value.deletion)
                }
                assertNormalFeedbackBlocked(vm, reports, history)
                assertEquals(0, deletion.continuations)
                assertEquals(0, reports.cleanupChecks)
                val barrier = vm.state.value.deletion
                vm.submit(SettingsFeedbackIntent.ResumeCleanup)
                assertSame(barrier, vm.state.value.deletion)
                assertIs<SettingsFeedbackResult.CleanupCheckCompleted>(vm.state.value.result)
                assertNormalFeedbackBlocked(vm, reports, history)
                deletion.observation = AppResult.Failure(AppError.Storage.Io())
                vm.submit(SettingsFeedbackIntent.CheckInstallation)
                assertIs<SettingsFeedbackDeletionState.Uncertain>(vm.state.value.deletion)
                assertNormalFeedbackBlocked(vm, reports, history)
                deletion.observation = AppResult.Success(ComplaintInstallationDeletionObservation.Active)
                vm.submit(SettingsFeedbackIntent.CheckInstallation)
                assertTrue(vm.state.value.editable)
                assertFalse(vm.state.value.canRetry)
                assertEquals("retained body", vm.state.value.draft.body)
                assertEquals(1, reports.drafts.size)
                assertEquals(1, reports.submitted.size)
                assertTrue(reports.retried.isEmpty())
                assertEquals(0, deletion.continuations)
                assertEquals(1, reports.cleanupChecks)
            }
        }
    }

    @Test
    fun openingPendingLocalCleanupOrUnknownPerformsNoReportRecoveryHistoryOrAutomaticContinuation() {
        runTest {
            for (initial in deletionOpeningStates()) {
                val reports = SettingsFeedbackRepositoryFake()
                val history = SettingsFeedbackHistoryFake()
                val deletion = SettingsInstallationDeletionFake()
                val release = CompletableDeferred<Unit>()
                deletion.observation = initial
                deletion.onObserve = {
                    release.await()
                    deletion.observation
                }
                val vm = model(reports, deletion, history)
                try {
                    assertIs<SettingsFeedbackDeletionState.Checking>(vm.state.value.deletion)
                    assertNormalFeedbackBlocked(vm, reports, history)
                    release.complete(Unit)
                    runCurrent()
                    assertNormalFeedbackBlocked(vm, reports, history)
                    assertEquals(0, reports.reconciliations + history.calls + reports.cleanupChecks)
                    assertEquals(0, deletion.requests + deletion.continuations)
                    if (initial.getOrNull() != ComplaintInstallationDeletionObservation.RemoteDeletionPending) {
                        vm.submit(SettingsFeedbackIntent.ContinueRemoteDeletion)
                        assertEquals(0, deletion.continuations)
                        deletion.observation =
                            AppResult.Success(ComplaintInstallationDeletionObservation.RemoteDeletionPending)
                        vm.submit(SettingsFeedbackIntent.CheckInstallation)
                    }
                    assertIs<SettingsFeedbackDeletionState.Pending>(vm.state.value.deletion)
                    assertEquals(0, deletion.continuations)
                    vm.submit(SettingsFeedbackIntent.ContinueRemoteDeletion)
                    assertEquals(1, deletion.continuations)
                    assertEquals(0, reports.reconciliations + history.calls + reports.cleanupChecks)
                    assertNormalFeedbackBlocked(vm, reports, history)
                } finally {
                    release.complete(Unit)
                }
            }
        }
    }

    @Test
    fun closingOwnLatePromptLeavesSiblingAloneAndOnlyProducerOutcomeMeansRemoteCompletion() {
        runTest {
            assertLateRemotePromptOwnership()
            assertDistinctRemoteCompletion()
        }
    }

    private suspend fun TestScope.assertLateRemotePromptOwnership() {
        val reports = SettingsFeedbackRepositoryFake()
        val deletion = SettingsInstallationDeletionFake()
        val release = CompletableDeferred<Unit>()
        val caller = CompletableDeferred<Job>()
        val late = deletion.nextPrompt
        deletion.onRequest = {
            caller.complete(currentCoroutineContext().job)
            withContext(NonCancellable) {
                release.await()
                AppResult.Success(late)
            }
        }
        val sibling = model(reports, deletion)
        val vm = model(reports, deletion)
        try {
            vm.submit(SettingsFeedbackIntent.RequestRemoteDeletion)
            sibling.submit(SettingsFeedbackIntent.Close)
            assertTrue(caller.await().isActive)
            vm.submit(SettingsFeedbackIntent.Close)
            assertTrue(caller.await().isCancelled)
            deletion.nextPrompt = SettingsTestDeletionPrompt()
            deletion.onRequest = { AppResult.Success(deletion.nextPrompt) }
            val surviving = model(reports, deletion)
            surviving.submit(SettingsFeedbackIntent.RequestRemoteDeletion)
            release.complete(Unit)
            runCurrent()
            assertSame(late, deletion.dismissed.single())
            assertTrue(surviving.state.value.remoteConfirmationPending)
            assertFalse(vm.state.value.confirmationPending)
            assertNull(vm.state.value.result)
            assertTrue(deletion.confirmed.isEmpty())
            assertEquals(0, deletion.continuations + reports.cleanupChecks)
        } finally {
            release.complete(Unit)
        }
    }

    private fun assertDistinctRemoteCompletion() {
        val reports = SettingsFeedbackRepositoryFake()
        val deletion = SettingsInstallationDeletionFake()
        deletion.observation = AppResult.Success(ComplaintInstallationDeletionObservation.Missing)
        val local = model(reports, deletion)
        assertIs<SettingsFeedbackDeletionState.Missing>(local.state.value.deletion)
        local.submit(SettingsFeedbackIntent.ResumeCleanup)
        assertIs<SettingsFeedbackResult.CleanupCheckCompleted>(local.state.value.result)
        assertIs<SettingsFeedbackDeletionState.Missing>(local.state.value.deletion)
        local.submit(SettingsFeedbackIntent.RequestDeletionAbandonment)
        local.submit(SettingsFeedbackIntent.ConfirmRecovery)
        assertIs<SettingsFeedbackResult.LocalDeletionAbandoned>(local.state.value.result)
        assertIs<SettingsFeedbackDeletionState.Uncertain>(local.state.value.deletion)
        assertTrue(deletion.confirmed.isEmpty())
        deletion.observation = AppResult.Success(ComplaintInstallationDeletionObservation.Active)
        deletion.onConfirm = { AppResult.Success(ComplaintInstallationDeletionOutcome.Completed) }
        val remote = model(reports, deletion)
        remote.submit(SettingsFeedbackIntent.RequestRemoteDeletion)
        remote.submit(SettingsFeedbackIntent.ConfirmRemoteDeletion)
        assertIs<SettingsFeedbackDeletionState.Completed>(remote.state.value.deletion)
        assertEquals(1, deletion.confirmed.size)
        assertNull(remote.state.value.result)
        assertTrue(remote.state.value.canStartNewDraft)
        assertEquals(0, deletion.continuations)
    }

    private fun model(
        reports: SettingsFeedbackRepositoryFake,
        deletion: SettingsInstallationDeletionFake,
        history: SettingsFeedbackHistoryFake = SettingsFeedbackHistoryFake(),
    ): SettingsFeedbackViewModel = reports.viewModel(history = history, deletion = deletion).also { models += it }
}

private fun assertNormalFeedbackBlocked(
    vm: SettingsFeedbackViewModel,
    reports: SettingsFeedbackRepositoryFake,
    history: SettingsFeedbackHistoryFake,
) {
    val calls = normalFeedbackCalls(reports, history)
    val draft = vm.state.value.draft
    vm.submit(SettingsFeedbackIntent.Submit)
    vm.submit(SettingsFeedbackIntent.Retry)
    vm.submit(SettingsFeedbackIntent.RefreshRecovery)
    vm.submit(SettingsFeedbackIntent.CancelPrepared(reports.pending))
    vm.submit(SettingsFeedbackIntent.RequestRecovery(reports.pending))
    vm.submit(SettingsFeedbackIntent.SetupHistory)
    vm.submit(SettingsFeedbackIntent.NewDraft)
    vm.submit(SettingsFeedbackIntent.ChangeBody("replacement"))
    vm.submit(SettingsFeedbackIntent.ChangeSubject("replacement"))
    vm.submit(SettingsFeedbackIntent.ChangeCategory(ComplaintType.CUSTOM, "replacement"))
    assertEquals(calls, normalFeedbackCalls(reports, history))
    assertEquals(draft, vm.state.value.draft)
    assertFalse(vm.state.value.editable || vm.state.value.canRetry || vm.state.value.canStartNewDraft)
    assertFalse(vm.state.value.canSetupHistory || vm.state.value.canUsePendingActions)
}

private fun normalFeedbackCalls(
    reports: SettingsFeedbackRepositoryFake,
    history: SettingsFeedbackHistoryFake,
): List<Int> =
    listOf(
        reports.drafts.size,
        reports.submitted.size,
        reports.retried.size,
        reports.preparedCancelled.size,
        reports.requested.size,
        reports.reconciliations,
        history.calls,
    )

private enum class DeletionVmOutcome {
    PENDING,
    FAILED,
    CANCELED,
    ;

    fun result(): AppResult<ComplaintInstallationDeletionOutcome> =
        when (this) {
            PENDING -> AppResult.Success(ComplaintInstallationDeletionOutcome.Pending(retryAfterSeconds = 2))
            FAILED -> AppResult.Failure(AppError.Storage.Io())
            CANCELED -> throw CancellationException("Synthetic confirmation cancellation")
        }
}

private fun deletionOpeningStates(): List<AppResult<ComplaintInstallationDeletionObservation>> =
    listOf(
        AppResult.Success(ComplaintInstallationDeletionObservation.RemoteDeletionPending),
        AppResult.Success(ComplaintInstallationDeletionObservation.LocalCleanupRequired),
        AppResult.Failure(AppError.Storage.Io()),
    )
