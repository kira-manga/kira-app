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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
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
class SettingsFeedbackEntryViewModelTest {
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
    fun fixedRequestModesSurviveRetryNewDraftAndLocalResetWithoutEditableCategoryOrSubject() =
        runTest {
            for ((entry, type) in fixedEntries()) {
                val fake = SettingsFeedbackRepositoryFake()
                val vm = model(fake, entry = entry)
                val expected = ComplaintReportDraft(type, "private fixed subject", "private body")
                assertFixedFields(vm, expected)
                assertFalse(entry.toString().contains("private"))
                vm.submit(SettingsFeedbackIntent.Submit)
                vm.submit(SettingsFeedbackIntent.Retry)
                assertEquals(expected, fake.drafts.single())
                assertSame(fake.live, fake.retried.single())
                fake.recovery = fake.preparedRecovery()
                vm.submit(SettingsFeedbackIntent.NewDraft)
                assertFixedReset(vm, entry, expected)
                vm.submit(SettingsFeedbackIntent.RequestRecovery(fake.pending))
                vm.submit(SettingsFeedbackIntent.ConfirmRecovery)
                assertIs<SettingsFeedbackResult.LocalResetCompleted>(vm.state.value.result)
                assertFixedReset(vm, entry, expected)
                vm.submit(SettingsFeedbackIntent.NewDraft)
                assertFixedReset(vm, entry, expected)
                assertEquals(1, fake.drafts.size)
                assertEquals(1, fake.submitted.size)
            }
        }

    @Test
    fun historySetupRequiresMissingAndExplicitlyFinishesWithoutPreparingOrSubmittingTheRetainedDraft() =
        runTest {
            val fake = SettingsFeedbackRepositoryFake()
            val history = SettingsFeedbackHistoryFake()
            val release = CompletableDeferred<Unit>()
            history.onLoad = {
                release.await()
                history.result
            }
            val vm = model(fake, history, SettingsFeedbackEntry.LanguageRequest("language subject"))
            observeMissingFromSubmit(vm, fake, history)
            try {
                assertSetupBusyGuards(vm, fake, history)
                release.complete(Unit)
                runCurrent()
                assertSetupKeepsDraftWithoutSending(vm, fake, history)
                fake.onPrepare = { AppResult.Success(ComplaintReportPreparation.Ready(fake.live)) }
                vm.submit(SettingsFeedbackIntent.Submit)
                assertEquals(2, fake.drafts.size)
                assertEquals("retained body", fake.drafts.last().body)
                assertEquals(1, fake.submitted.size)
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun historySetupFailureAndCancellationKeepBodyAndNeverAllocateAReportAction() =
        runTest {
            for (cancel in listOf(false, true)) {
                val fake = SettingsFeedbackRepositoryFake()
                fake.recovery = missingRecovery()
                val history = SettingsFeedbackHistoryFake()
                if (cancel) {
                    history.onLoad = { throw CancellationException("synthetic cancellation") }
                } else {
                    history.result = AppResult.Failure(AppError.Network.NoConnectivity())
                }
                val vm = model(fake, history, SettingsFeedbackEntry.SourceRequest("source subject"))
                vm.submit(SettingsFeedbackIntent.ChangeBody("retained body"))
                vm.submit(SettingsFeedbackIntent.SetupHistory)
                val failure = assertIs<SettingsFeedbackResult.Failure>(vm.state.value.result).failure
                if (cancel) {
                    assertIs<AppError.Cancelled>(failure.error)
                } else {
                    assertIs<AppError.Network.NoConnectivity>(failure.error)
                }
                assertEquals("retained body", vm.state.value.draft.body)
                assertTrue(vm.state.value.canSetupHistory)
                assertEquals(1, history.calls)
                assertTrue(fake.drafts.isEmpty() && fake.submitted.isEmpty() && fake.retried.isEmpty())
            }
        }

    @Test
    fun closingSiblingDoesNotCancelSetupAndOwnLateHistoryResultCannotPublishCompletion() =
        runTest {
            val fake = SettingsFeedbackRepositoryFake()
            fake.recovery = missingRecovery()
            val history = SettingsFeedbackHistoryFake()
            val release = CompletableDeferred<Unit>()
            val caller = CompletableDeferred<Job>()
            holdLateHistoryResult(history, release, caller)
            val sibling = model(fake, history)
            val entry = SettingsFeedbackEntry.SourceRequest("source subject")
            val vm = model(fake, history, entry)
            vm.submit(SettingsFeedbackIntent.ChangeBody("private body"))
            try {
                vm.submit(SettingsFeedbackIntent.SetupHistory)
                val operation = caller.await()
                sibling.submit(SettingsFeedbackIntent.Close)
                assertTrue(operation.isActive)
                assertTrue(vm.state.value.busy)
                vm.submit(SettingsFeedbackIntent.Close)
                assertTrue(operation.isCancelled)
                release.complete(Unit)
                runCurrent()
                assertClosedWithoutReport(vm, fake, history, entry)
            } finally {
                release.complete(Unit)
            }
        }

    private fun model(
        fake: SettingsFeedbackRepositoryFake,
        history: SettingsFeedbackHistoryFake = SettingsFeedbackHistoryFake(),
        entry: SettingsFeedbackEntry = SettingsFeedbackEntry.General,
    ): SettingsFeedbackViewModel =
        fake.viewModel(entry, history).also {
            models += it
        }
}

private fun fixedEntries(): List<Pair<SettingsFeedbackEntry, ComplaintType>> =
    listOf(
        SettingsFeedbackEntry.SourceRequest("private fixed subject") to ComplaintType.SITES_ADD,
        SettingsFeedbackEntry.LanguageRequest("private fixed subject") to ComplaintType.LANGUAGES,
    )

private fun assertFixedFields(
    vm: SettingsFeedbackViewModel,
    expected: ComplaintReportDraft,
) {
    vm.submit(SettingsFeedbackIntent.ChangeCategory(ComplaintType.CUSTOM, "replacement"))
    vm.submit(SettingsFeedbackIntent.ChangeSubject("replacement"))
    vm.submit(SettingsFeedbackIntent.ChangeBody("private body"))
    assertEquals(expected, vm.state.value.draft)
}

private fun assertFixedReset(
    vm: SettingsFeedbackViewModel,
    entry: SettingsFeedbackEntry,
    expected: ComplaintReportDraft,
) {
    assertSame(entry, vm.state.value.entry)
    assertEquals(expected.copy(body = ""), vm.state.value.draft)
}

private fun observeMissingFromSubmit(
    vm: SettingsFeedbackViewModel,
    fake: SettingsFeedbackRepositoryFake,
    history: SettingsFeedbackHistoryFake,
) {
    vm.submit(SettingsFeedbackIntent.SetupHistory)
    assertEquals(0, history.calls)
    fake.onPrepare = { AppResult.Success(ComplaintReportPreparation.Blocked(missingFailure())) }
    vm.submit(SettingsFeedbackIntent.Submit)
    vm.submit(SettingsFeedbackIntent.ChangeBody("retained body"))
    assertTrue(vm.state.value.canSetupHistory)
}

private fun assertSetupBusyGuards(
    vm: SettingsFeedbackViewModel,
    fake: SettingsFeedbackRepositoryFake,
    history: SettingsFeedbackHistoryFake,
) {
    vm.submit(SettingsFeedbackIntent.SetupHistory)
    vm.submit(SettingsFeedbackIntent.SetupHistory)
    vm.submit(SettingsFeedbackIntent.Submit)
    vm.submit(SettingsFeedbackIntent.ChangeBody("replacement"))
    assertTrue(vm.state.value.busy)
    assertEquals(1, history.calls)
    assertEquals(1, fake.drafts.size)
    assertTrue(fake.submitted.isEmpty())
}

private fun assertSetupKeepsDraftWithoutSending(
    vm: SettingsFeedbackViewModel,
    fake: SettingsFeedbackRepositoryFake,
    history: SettingsFeedbackHistoryFake,
) {
    assertIs<SettingsFeedbackResult.HistorySetupCompleted>(vm.state.value.result)
    assertEquals("retained body", vm.state.value.draft.body)
    assertTrue(vm.state.value.editable)
    assertFalse(vm.state.value.canSetupHistory)
    assertEquals(1, fake.drafts.size)
    assertTrue(fake.submitted.isEmpty())
    vm.submit(SettingsFeedbackIntent.SetupHistory)
    assertEquals(1, history.calls)
}

private fun holdLateHistoryResult(
    history: SettingsFeedbackHistoryFake,
    release: CompletableDeferred<Unit>,
    caller: CompletableDeferred<Job>,
) {
    history.onLoad = {
        caller.complete(currentCoroutineContext().job)
        withContext(NonCancellable) {
            release.await()
            history.result
        }
    }
}

private fun assertClosedWithoutReport(
    vm: SettingsFeedbackViewModel,
    fake: SettingsFeedbackRepositoryFake,
    history: SettingsFeedbackHistoryFake,
    entry: SettingsFeedbackEntry,
) {
    assertNull(vm.state.value.result)
    assertSame(entry, vm.state.value.entry)
    assertEquals("", vm.state.value.draft.body)
    assertEquals(1, history.calls)
    assertTrue(fake.drafts.isEmpty() && fake.submitted.isEmpty())
    assertTrue(fake.preparedCancelled.isEmpty() && fake.confirmed.isEmpty())
}

private fun missingFailure(): ComplaintReportFailure =
    ComplaintReportFailure(
        AppError.Platform.FeatureUnavailable("complaint_report"),
        ComplaintReportBlock.MISSING,
    )

private fun missingRecovery(): ComplaintReportRecovery =
    ComplaintReportRecovery(
        emptyList(),
        missingFailure(),
    )
