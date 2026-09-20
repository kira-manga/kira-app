package me.manga.kira.presentation.settings.feedback.reply

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintReplyViewModelTest {
    private val fixtures = mutableListOf<ComplaintReplyPanelFixture>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.clearAll() }
        Dispatchers.resetMain()
    }

    @Test
    fun prepareSubmitAndRetryKeepExactHandleAndRejectCompetingIntents() =
        runTest {
            val fixture = fixture()
            val gates = List(3) { CompletableDeferred<Unit>() }
            holdEachStage(fixture, gates)
            val vm = fixture.model()
            vm.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
            try {
                vm.submit(ComplaintReplyIntent.Submit)
                assertBusyAndFrozen(vm)
                assertEquals(ComplaintReplyDraft(PARENT_ID, REPLY_BODY), fixture.drafts.single())
                gates[0].complete(Unit)
                runCurrent()
                assertBusyAndFrozen(vm)
                assertSame(fixture.live, fixture.submitted.single())
                gates[1].complete(Unit)
                runCurrent()
                assertTrue(vm.state.value.canRetry)
                vm.submit(ComplaintReplyIntent.Retry)
                assertBusyAndFrozen(vm)
                gates[2].complete(Unit)
                runCurrent()
                assertTerminalAndOriginal(vm, fixture)
            } finally {
                gates.forEach { it.complete(Unit) }
            }
        }

    @Test
    fun closeDuringPrepareOrSubmitFencesLateSuccessAndThrowableWithoutClearingPending() =
        runTest {
            for (prepare in listOf(false, true)) {
                for (throws in listOf(false, true)) {
                    val fixture = fixture()
                    val late = holdLateStage(fixture, prepare, throws)
                    val vm = fixture.model()
                    vm.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
                    vm.submit(ComplaintReplyIntent.Submit)
                    assertCloseDrains(vm, late, ComplaintReplyIntent.Close)
                    assertEquals(if (prepare) 0 else 1, fixture.submitted.size)
                    fixture.assertNoRecoveryMutation()
                }
            }
        }

    @Test
    fun closingSiblingDoesNotCancelAnotherOpeningAndRecoveryHandoffWaitsForOwnDrain() =
        runTest {
            val fixture = fixture()
            val late = holdLateStage(fixture, prepare = false, throws = false)
            val vm = fixture.model()
            val sibling = fixture.model()
            vm.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
            vm.submit(ComplaintReplyIntent.Submit)
            try {
                val caller = late.caller.await()
                sibling.submit(ComplaintReplyIntent.Close)
                assertClosed(sibling)
                assertTrue(caller.isActive)
                assertTrue(vm.state.value.busy)
                assertCloseDrains(vm, late, ComplaintReplyIntent.OpenRecovery)
                fixture.assertNoRecoveryMutation()
                assertEquals(1, fixture.drafts.size)
            } finally {
                late.release.complete(Unit)
            }
        }

    @Test
    fun clearingOwnedStoreClearsTextAndFencesLateThrowableAndFutureIntents() =
        runTest {
            for (prepare in listOf(false, true)) {
                val fixture = fixture()
                val late = holdLateStage(fixture, prepare, throws = true)
                val vm = fixture.model()
                vm.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
                vm.submit(ComplaintReplyIntent.Submit)
                try {
                    vm.effects.test {
                        fixture.clear(vm)
                        late.closing.await()
                        assertClosed(vm)
                        tapCompetingIntents(vm)
                        vm.submit(ComplaintReplyIntent.OpenRecovery)
                        late.release.complete(Unit)
                        runCurrent()
                        assertClosed(vm)
                        expectNoEvents()
                    }
                    fixture.assertNoRecoveryMutation()
                } finally {
                    late.release.complete(Unit)
                }
            }
        }

    @Test
    fun cancelledRetryCannotBeOvertakenWhileItsChildCleanupStillOwnsTheJob() =
        runTest {
            val fixture = fixture()
            val vm = fixture.model()
            vm.submit(ComplaintReplyIntent.ChangeBody(REPLY_BODY))
            vm.submit(ComplaintReplyIntent.Submit)
            val release = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Job>()
            val retry = fixture.onRetry
            fixture.onRetry = { cancelWithChildCleanup(release, cancelled) }
            try {
                vm.submit(ComplaintReplyIntent.Retry)
                val caller = cancelled.await()
                assertTrue(caller.isCancelled && !caller.isCompleted)
                tapCompetingIntents(vm)
                assertEquals(1, fixture.retried.size)
                assertEquals(REPLY_BODY, vm.state.value.body)
                assertEquals(listOf("observe", "reconcile", "prepare", "submit", "retry"), fixture.calls)
                fixture.onRetry = retry
                release.complete(Unit)
                runCurrent()
                assertTrue(caller.isCompleted)
                vm.submit(ComplaintReplyIntent.Retry)
                assertEquals(2, fixture.retried.size)
                assertTrue(fixture.retried.all { it === fixture.live })
            } finally {
                release.complete(Unit)
            }
        }

    private suspend fun TestScope.assertCloseDrains(
        vm: ComplaintReplyViewModel,
        late: LateReplyWork,
        intent: ComplaintReplyIntent,
    ) {
        try {
            vm.effects.test {
                vm.submit(intent)
                late.closing.await()
                assertTrue(late.caller.await().isCancelled)
                assertClosed(vm)
                expectNoEvents()
                vm.submit(ComplaintReplyIntent.Close)
                vm.submit(ComplaintReplyIntent.OpenRecovery)
                tapCompetingIntents(vm)
                late.release.complete(Unit)
                runCurrent()
                assertEquals(closeEffect(intent), awaitItem())
                assertClosed(vm)
                expectNoEvents()
            }
        } finally {
            late.release.complete(Unit)
        }
    }

    private fun fixture(): ComplaintReplyPanelFixture = ComplaintReplyPanelFixture().also { fixtures += it }
}

private fun holdEachStage(fixture: ComplaintReplyPanelFixture, gates: List<CompletableDeferred<Unit>>) {
    val prepare = fixture.onPrepare
    val submit = fixture.onSubmit
    val retry = fixture.onRetry
    fixture.onPrepare = {
        gates[0].await()
        prepare(it)
    }
    fixture.onSubmit = {
        gates[1].await()
        submit(it)
    }
    fixture.onRetry = {
        gates[2].await()
        retry(it)
    }
}

private fun closeEffect(intent: ComplaintReplyIntent): ComplaintReplyEffect =
    if (intent == ComplaintReplyIntent.Close) ComplaintReplyEffect.Closed else ComplaintReplyEffect.OpenSettingsRecovery

private fun holdLateStage(fixture: ComplaintReplyPanelFixture, prepare: Boolean, throws: Boolean): LateReplyWork {
    val late = LateReplyWork()
    if (prepare) {
        fixture.onPrepare = {
            late.finish {
                if (throws) throw AssertionError("synthetic private late preparation failure")
                AppResult.Success(ComplaintReplyPreparation.Ready(fixture.live))
            }
        }
    } else {
        fixture.onSubmit = {
            late.finish {
                if (throws) throw AssertionError("synthetic private late submission failure")
                AppResult.Success(ComplaintReportSubmission(fixture.reports.unresolved(), fixture.reports.recovery))
            }
        }
    }
    return late
}

private fun tapCompetingIntents(vm: ComplaintReplyViewModel) {
    vm.submit(ComplaintReplyIntent.ChangeBody("replacement private body"))
    vm.submit(ComplaintReplyIntent.Submit)
    vm.submit(ComplaintReplyIntent.Retry)
    vm.submit(ComplaintReplyIntent.RefreshRecovery)
}

private fun assertBusyAndFrozen(vm: ComplaintReplyViewModel) {
    assertTrue(vm.state.value.busy)
    tapCompetingIntents(vm)
    assertEquals(REPLY_BODY, vm.state.value.body)
}

private fun assertTerminalAndOriginal(vm: ComplaintReplyViewModel, fixture: ComplaintReplyPanelFixture) {
    assertEquals(ComplaintReplyActivity.TERMINAL, vm.state.value.activity)
    tapCompetingIntents(vm)
    assertEquals(REPLY_BODY, vm.state.value.body)
    assertSame(fixture.live, fixture.retried.single())
    assertEquals(listOf("observe", "reconcile", "prepare", "submit", "retry"), fixture.calls)
    assertFalse(vm.state.value.canRetry || vm.state.value.canSubmit || vm.state.value.editable)
}

private fun assertClosed(vm: ComplaintReplyViewModel) {
    assertTrue(vm.state.value.isClosed)
    assertEquals("", vm.state.value.body)
    assertNull(vm.state.value.result)
    assertNull(vm.state.value.recovery)
    assertFalse(vm.state.value.canSubmit || vm.state.value.canRetry || vm.state.value.canOpenRecovery)
}

private suspend fun cancelWithChildCleanup(
    release: CompletableDeferred<Unit>,
    cancelled: CompletableDeferred<Job>,
): Nothing {
    val context = currentCoroutineContext()
    CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { release.await() }
        }
    }
    cancelled.complete(context.job)
    context.job.cancel()
    throw CancellationException("synthetic canceled retry")
}
