package me.manga.kira.presentation.settings.feedback

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Existing report/deletion fakes; the attached child outlives work's finally, not just its action body. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Two material drain cases share the existing fakes and bounded assertions.
class SettingsFeedbackDrainLifecycleTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun returnedWorkRetainsItsJobUntilChildDrainBeforeEditsAndNewDraft() =
        runTest {
            for (terminal in listOf(false, true)) assertReturnedWorkFence(terminal)
        }

    @Test
    fun cancelledRetryCannotPublishClosedUntilItsAttachedChildFinishes() =
        runTest {
            val fake = SettingsFeedbackRepositoryFake()
            val known = ComplaintReportApplication.Applied("synthetic-id", 1)
            fake.onSubmit = { AppResult.Success(ComplaintReportSubmission(fake.unresolved(known), fake.recovery)) }
            val drain = SettingsChildDrain()
            fake.onRetry = { drain.cancelCaller() }
            val vm = fake.viewModel()
            try {
                runCurrent()
                fillDraft(vm)
                vm.submit(SettingsFeedbackIntent.Submit)
                runCurrent()
                assertCloseWaitsForChild(vm, fake, drain, known)
            } finally {
                drain.release.complete(Unit)
                vm.viewModelScope.cancel()
                runCurrent()
            }
        }

    private suspend fun TestScope.assertReturnedWorkFence(terminal: Boolean) {
        val fake = SettingsFeedbackRepositoryFake()
        val deletion = SettingsInstallationDeletionFake()
        val vm = fake.viewModel(deletion = deletion)
        val drain = SettingsChildDrain()
        val receipt = configureReturnedWork(fake, drain)
        try {
            runCurrent()
            fillDraft(vm)
            vm.submit(if (terminal) SettingsFeedbackIntent.Submit else SettingsFeedbackIntent.ResumeCleanup)
            runCurrent()
            assertEquals(terminal, vm.state.value.canStartNewDraft)
            assertEquals(!terminal, vm.state.value.editable)
            val caller = drain.caller.await()
            caller.cancel()
            runCurrent()
            assertChildStillOwnsJob(drain, caller)
            assertNoCompetingIntents(vm, fake, deletion)
            if (terminal) assertSame(receipt, assertIs<SettingsFeedbackResult.Attempt>(vm.state.value.result).attempt)
            drain.release.complete(Unit)
            runCurrent()
            assertTrue(caller.isCompleted)
            assertHealthyEditing(vm, terminal)
        } finally {
            drain.release.complete(Unit)
            vm.viewModelScope.cancel()
            runCurrent()
        }
    }

    private fun configureReturnedWork(
        fake: SettingsFeedbackRepositoryFake,
        drain: SettingsChildDrain,
    ): ComplaintReportAttempt.Completed {
        val receipt = ComplaintReportAttempt.Completed(ComplaintReportApplication.Applied("synthetic-id", 1))
        fake.onCleanup = {
            drain.attach()
            AppResult.Success(Unit)
        }
        fake.onSubmit = {
            drain.attach()
            AppResult.Success(ComplaintReportSubmission(receipt, fake.recovery))
        }
        return receipt
    }

    private fun TestScope.assertNoCompetingIntents(
        vm: SettingsFeedbackViewModel,
        fake: SettingsFeedbackRepositoryFake,
        deletion: SettingsInstallationDeletionFake,
    ) {
        val retained = vm.state.value
        val workCounts = listOf(fake.drafts.size, fake.submitted.size, fake.cleanupChecks, deletion.observations)
        listOf(
            SettingsFeedbackIntent.ChangeCategory(ComplaintType.CUSTOM, "replacement category"),
            SettingsFeedbackIntent.ChangeSubject("replacement subject"),
            SettingsFeedbackIntent.ChangeBody("replacement body"),
            SettingsFeedbackIntent.NewDraft,
            SettingsFeedbackIntent.CheckInstallation,
            SettingsFeedbackIntent.ResumeCleanup,
            SettingsFeedbackIntent.Submit,
            SettingsFeedbackIntent.Retry,
        ).forEach(vm::submit)
        runCurrent()
        assertSame(retained, vm.state.value)
        assertEquals(
            workCounts,
            listOf(fake.drafts.size, fake.submitted.size, fake.cleanupChecks, deletion.observations),
        )
        assertTrue(fake.retried.isEmpty() && fake.preparedCancelled.isEmpty() && fake.confirmed.isEmpty())
    }

    private suspend fun TestScope.assertCloseWaitsForChild(
        vm: SettingsFeedbackViewModel,
        fake: SettingsFeedbackRepositoryFake,
        drain: SettingsChildDrain,
        known: ComplaintReportApplication,
    ) {
        vm.submit(SettingsFeedbackIntent.Retry)
        runCurrent()
        val caller = drain.caller.await()
        assertChildStillOwnsJob(drain, caller)
        val retained = vm.state.value
        assertPreservedAttempt(retained, fake, known)
        vm.submit(SettingsFeedbackIntent.Retry)
        runCurrent()
        assertEquals(1, fake.retried.size)
        val closed = backgroundScope.async(dispatcher) {
            vm.effects.first { it == SettingsFeedbackEffect.Closed }
            caller.isCompleted
        }
        vm.submit(SettingsFeedbackIntent.Close)
        runCurrent()
        assertFalse(closed.isCompleted)
        assertSame(retained, vm.state.value)
        drain.release.complete(Unit)
        runCurrent()
        assertTrue(closed.await())
        assertNull(vm.state.value.result)
        assertEquals("", vm.state.value.draft.body)
        assertTrue(fake.preparedCancelled.isEmpty() && fake.confirmed.isEmpty())
    }

    private fun assertPreservedAttempt(
        state: SettingsFeedbackState,
        fake: SettingsFeedbackRepositoryFake,
        known: ComplaintReportApplication,
    ) {
        assertFalse(state.busy) // work's finally returned while its canceled caller still owns a child.
        assertTrue(state.canRetry)
        val attempt =
            assertIs<ComplaintReportAttempt.Unresolved>(assertIs<SettingsFeedbackResult.Attempt>(state.result).attempt)
        assertSame(known, attempt.knownApplication)
        assertSame(fake.pending, attempt.pending?.handle)
        assertSame(fake.live, fake.retried.single())
    }

    private fun TestScope.fillDraft(vm: SettingsFeedbackViewModel) {
        vm.submit(SettingsFeedbackIntent.ChangeCategory(ComplaintType.TECHNICAL, "original subject"))
        vm.submit(SettingsFeedbackIntent.ChangeBody("original body"))
        runCurrent()
    }

    private fun TestScope.assertHealthyEditing(vm: SettingsFeedbackViewModel, terminal: Boolean) {
        if (terminal) {
            vm.submit(SettingsFeedbackIntent.NewDraft)
            runCurrent()
            assertEquals("", vm.state.value.draft.body)
        }
        vm.submit(SettingsFeedbackIntent.ChangeBody("fresh body"))
        runCurrent()
        assertEquals("fresh body", vm.state.value.draft.body)
    }

    private fun assertChildStillOwnsJob(drain: SettingsChildDrain, caller: Job) {
        assertTrue(drain.closing.isCompleted && caller.isCancelled)
        assertFalse(caller.isCompleted)
    }
}

/** Same direct-child cancellation shape as the accepted reply/edit/delete lifecycle regressions. */
private class SettingsChildDrain {
    val caller = CompletableDeferred<Job>()
    val closing = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    suspend fun attach() {
        val context = currentCoroutineContext()
        CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    closing.complete(Unit)
                    release.await()
                }
            }
        }
        caller.complete(context.job)
    }

    suspend fun cancelCaller(): Nothing {
        attach()
        currentCoroutineContext().job.cancel()
        throw CancellationException("synthetic cancelled work")
    }
}
