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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintRecoveryPrompt
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
class SettingsInstallationRecoveryViewModelTest {
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
    fun explicitRecoveryModesRequireFreshWarningAndConfirmationWithoutPreparingOrSending() =
        runTest {
            for ((kind, request) in localRecoveryRequests()) {
                val fake = SettingsFeedbackRepositoryFake()
                val history = SettingsFeedbackHistoryFake()
                val entry = SettingsFeedbackEntry.SourceRequest("fixed subject")
                val vm = model(fake, history, entry)
                val effects = mutableListOf<SettingsFeedbackEffect>()
                backgroundScope.launch(dispatcher) { vm.effects.collect { effects += it } }
                vm.submit(SettingsFeedbackIntent.ChangeBody("retained body"))
                vm.submit(SettingsFeedbackIntent.ConfirmRecovery)
                assertTrue(fake.confirmed.isEmpty())
                assertWarningGuards(vm, fake, request, kind)
                assertEquals(listOf<SettingsFeedbackEffect>(SettingsFeedbackEffect.ConfirmLocalReset), effects)
                vm.submit(SettingsFeedbackIntent.CancelRecovery)
                assertSame(fake.nextPrompt, fake.dismissed.single())
                assertEquals("retained body", vm.state.value.draft.body)
                assertFalse(vm.state.value.confirmationPending)
                fake.nextPrompt = SettingsTestRecoveryPrompt()
                vm.submit(request)
                vm.submit(SettingsFeedbackIntent.ConfirmRecovery)
                vm.submit(SettingsFeedbackIntent.ConfirmRecovery)
                assertSame(fake.nextPrompt, fake.confirmed.single())
                assertRecoveryCompleted(vm, entry, kind)
                assertEquals(2, effects.size)
                assertNoRecoveryReportActions(fake, history)
            }
        }

    @Test
    fun closingSiblingCannotCancelRecoveryAndOwnLatePromptDismissalLeavesNewSiblingTokenAlone() =
        runTest {
            val fake = SettingsFeedbackRepositoryFake()
            val history = SettingsFeedbackHistoryFake()
            val release = CompletableDeferred<Unit>()
            val caller = CompletableDeferred<Job>()
            val late = holdLateUnreadableResult(fake, release, caller)
            val sibling = model(fake, history)
            val active = model(fake, history)
            val effects = mutableListOf<SettingsFeedbackEffect>()
            backgroundScope.launch(dispatcher) { active.effects.collect { effects += it } }
            try {
                active.submit(SettingsFeedbackIntent.RequestUnreadableRecovery)
                sibling.submit(SettingsFeedbackIntent.Close)
                assertTrue(caller.await().isActive)
                assertTrue(active.state.value.busy)
                active.submit(SettingsFeedbackIntent.Close)
                assertTrue(caller.await().isCancelled)
                fake.nextPrompt = SettingsTestRecoveryPrompt()
                val surviving = model(fake, history)
                surviving.submit(SettingsFeedbackIntent.RequestDeletionAbandonment)
                release.complete(Unit)
                runCurrent()
                assertLatePromptDismissal(fake, active, surviving, late, effects)
                assertNoRecoveryReportActions(fake, history, reconciliations = 3)
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun failedOrCancelledConfirmationRetiresItsTokenAndCleanupSuccessIsOnlyANeutralCheck() =
        runTest {
            for (cancel in listOf(false, true)) {
                val fake = SettingsFeedbackRepositoryFake()
                fake.recovery = fake.preparedRecovery()
                fake.onConfirm = {
                    if (cancel) throw CancellationException("synthetic cancellation")
                    AppResult.Failure(AppError.Storage.Io())
                }
                val history = SettingsFeedbackHistoryFake()
                val vm = model(fake, history, SettingsFeedbackEntry.SourceRequest("fixed subject"))
                val effects = mutableListOf<SettingsFeedbackEffect>()
                backgroundScope.launch(dispatcher) { vm.effects.collect { effects += it } }
                vm.submit(SettingsFeedbackIntent.ChangeBody("retained body"))
                vm.submit(SettingsFeedbackIntent.RequestUnreadableRecovery)
                vm.submit(SettingsFeedbackIntent.ConfirmRecovery)
                assertRetiredConfirmation(vm, fake, cancel)
                assertEquals(listOf<SettingsFeedbackEffect>(SettingsFeedbackEffect.ConfirmLocalReset), effects)
                assertNeutralCleanupPreservesDraftAndRecovery(vm, fake)
                fake.nextPrompt = SettingsTestRecoveryPrompt()
                vm.submit(SettingsFeedbackIntent.RequestUnreadableRecovery)
                assertTrue(vm.state.value.confirmationPending)
                assertEquals(2, effects.size)
                assertEquals(2, fake.unreadableRequests)
                vm.submit(SettingsFeedbackIntent.CancelRecovery)
                assertSame(fake.nextPrompt, fake.dismissed.last())
                assertNoRecoveryReportActions(fake, history)
            }
        }

    private fun model(
        fake: SettingsFeedbackRepositoryFake,
        history: SettingsFeedbackHistoryFake,
        entry: SettingsFeedbackEntry = SettingsFeedbackEntry.General,
    ): SettingsFeedbackViewModel =
        fake.viewModel(entry, history).also {
            models += it
        }
}

private fun localRecoveryRequests(): List<Pair<SettingsFeedbackRecoveryKind, SettingsFeedbackIntent>> =
    listOf(
        SettingsFeedbackRecoveryKind.UNREADABLE to SettingsFeedbackIntent.RequestUnreadableRecovery,
        SettingsFeedbackRecoveryKind.ABANDON_DELETION to SettingsFeedbackIntent.RequestDeletionAbandonment,
    )

private fun assertWarningGuards(
    vm: SettingsFeedbackViewModel,
    fake: SettingsFeedbackRepositoryFake,
    request: SettingsFeedbackIntent,
    kind: SettingsFeedbackRecoveryKind,
) {
    vm.submit(request)
    vm.submit(SettingsFeedbackIntent.RequestUnreadableRecovery)
    vm.submit(SettingsFeedbackIntent.RequestDeletionAbandonment)
    vm.submit(SettingsFeedbackIntent.ResumeCleanup)
    vm.submit(SettingsFeedbackIntent.Submit)
    vm.submit(SettingsFeedbackIntent.ChangeBody("replacement"))
    assertEquals(kind, vm.state.value.recoveryKind)
    assertTrue(vm.state.value.confirmationPending)
    assertEquals(if (kind == SettingsFeedbackRecoveryKind.UNREADABLE) 1 else 0, fake.unreadableRequests)
    assertEquals(if (kind == SettingsFeedbackRecoveryKind.ABANDON_DELETION) 1 else 0, fake.abandonmentRequests)
    assertEquals(0, fake.cleanupChecks)
    assertEquals("retained body", vm.state.value.draft.body)
}

private fun assertRecoveryCompleted(
    vm: SettingsFeedbackViewModel,
    entry: SettingsFeedbackEntry,
    kind: SettingsFeedbackRecoveryKind,
) {
    val expected =
        if (kind == SettingsFeedbackRecoveryKind.ABANDON_DELETION) {
            SettingsFeedbackResult.LocalDeletionAbandoned
        } else {
            SettingsFeedbackResult.LocalResetCompleted
        }
    assertSame(expected, vm.state.value.result)
    assertSame(entry, vm.state.value.entry)
    assertEquals(entry.initialDraft(), vm.state.value.draft)
    assertTrue(vm.state.value.canStartNewDraft)
    assertFalse(vm.state.value.confirmationPending)
}

/** Concurrent fake delivery probes VM token ownership, not coordinator consent eligibility. */
private fun holdLateUnreadableResult(
    fake: SettingsFeedbackRepositoryFake,
    release: CompletableDeferred<Unit>,
    caller: CompletableDeferred<Job>,
): ComplaintRecoveryPrompt {
    val prompt = fake.nextPrompt
    fake.onUnreadable = {
        caller.complete(currentCoroutineContext().job)
        withContext(NonCancellable) {
            release.await()
            AppResult.Success(prompt)
        }
    }
    return prompt
}

private fun assertLatePromptDismissal(
    fake: SettingsFeedbackRepositoryFake,
    closed: SettingsFeedbackViewModel,
    surviving: SettingsFeedbackViewModel,
    late: ComplaintRecoveryPrompt,
    effects: List<SettingsFeedbackEffect>,
) {
    assertSame(late, fake.dismissed.single())
    assertTrue(surviving.state.value.confirmationPending)
    assertEquals(SettingsFeedbackRecoveryKind.ABANDON_DELETION, surviving.state.value.recoveryKind)
    assertFalse(closed.state.value.confirmationPending)
    assertNull(closed.state.value.result)
    assertTrue(fake.confirmed.isEmpty())
    assertEquals(listOf<SettingsFeedbackEffect>(SettingsFeedbackEffect.Closed), effects)
}

private fun assertRetiredConfirmation(
    vm: SettingsFeedbackViewModel,
    fake: SettingsFeedbackRepositoryFake,
    cancelled: Boolean,
) {
    val failure = assertIs<SettingsFeedbackResult.Failure>(vm.state.value.result).failure
    if (cancelled) {
        assertIs<AppError.Cancelled>(failure.error)
    } else {
        assertIs<AppError.Storage.Io>(failure.error)
    }
    assertSame(fake.nextPrompt, fake.confirmed.single())
    assertSame(fake.nextPrompt, fake.dismissed.single())
    assertFalse(vm.state.value.confirmationPending)
    assertNull(vm.state.value.recoveryKind)
    assertEquals("retained body", vm.state.value.draft.body)
    vm.submit(SettingsFeedbackIntent.ConfirmRecovery)
    assertEquals(1, fake.confirmed.size)
    assertEquals(1, fake.dismissed.size)
}

private fun assertNeutralCleanupPreservesDraftAndRecovery(
    vm: SettingsFeedbackViewModel,
    fake: SettingsFeedbackRepositoryFake,
) {
    val retained = vm.state.value
    vm.submit(SettingsFeedbackIntent.ResumeCleanup)
    assertEquals(retained.copy(result = SettingsFeedbackResult.CleanupCheckCompleted), vm.state.value)
    assertEquals(1, fake.cleanupChecks)
}

private fun assertNoRecoveryReportActions(
    fake: SettingsFeedbackRepositoryFake,
    history: SettingsFeedbackHistoryFake,
    reconciliations: Int = 1,
) {
    assertTrue(fake.drafts.isEmpty() && fake.submitted.isEmpty() && fake.retried.isEmpty())
    assertTrue(fake.requested.isEmpty() && fake.preparedCancelled.isEmpty())
    assertEquals(0, history.calls)
    assertEquals(reconciliations, fake.reconciliations)
}
