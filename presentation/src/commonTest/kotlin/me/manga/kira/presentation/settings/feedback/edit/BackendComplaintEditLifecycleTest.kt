package me.manga.kira.presentation.settings.feedback.edit

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
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
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
@Suppress("TooManyFunctions") // Four lifecycle tests share bounded ownership/barrier assertions.
class BackendComplaintEditLifecycleTest {
    private val fixtures = mutableListOf<BackendComplaintEditViewModelFixture>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun closeFencesLateValuesAndFailuresWithoutCancelingAnotherOpening() =
        runTest {
            assertCloseFence(
                LateEditWork {
                    AppResult.Success(ComplaintReportSubmission(completedEdit(), ComplaintReportRecovery(emptyList())))
                },
            )
            assertCloseFence(LateEditWork { throw AssertionError("Synthetic private late failure") })
        }

    @Test
    fun storeClearRetiresDelayedPreparationAndSubmissionAndEveryLaterIntent() =
        runTest {
            assertStoreClear(duringPreparation = true)
            assertStoreClear(duringPreparation = false)
        }

    @Test
    fun currentCancellationPreservesTheSameLiveRetryAndKnownOutcome() =
        runTest {
            for (openRecovery in listOf(false, true)) assertChildCleanupFence(openRecovery)
        }

    @Test
    fun recoveryHandoffDrainsOnlyThisOpeningAndColdReopenCannotRetry() =
        runTest {
            val fixture = fixture()
            val fake = fixture.repository
            val late = LateEditWork { AppResult.Success(fake.submission(completedEdit())) }
            fake.onSubmit = { late.run() }
            val effects = effects(fixture.model)
            try {
                fixture.model.fillEditDraft()
                fixture.model.submit(BackendComplaintEditIntent.Submit)
                fixture.model.submit(BackendComplaintEditIntent.OpenRecovery)
                late.closing.await()
                assertEditRetired(fixture.model)
                assertTrue(effects.isEmpty())
                ignoreLaterIntents(fixture.model)
                late.release.complete(Unit)
                runCurrent()
                assertTrue(late.cleaned)
                assertEquals(listOf<BackendComplaintEditEffect>(BackendComplaintEditEffect.OpenRecovery), effects)
                assertColdCannotRetry(fake)
            } finally {
                late.release.complete(Unit)
            }
        }

    private suspend fun TestScope.assertChildCleanupFence(openRecovery: Boolean) {
        val fixture = fixture()
        val fake = fixture.repository
        val receipt = ComplaintEditApplication.Applied(EDIT_ID, 8)
        fake.onSubmit = { AppResult.Success(fake.submission(fake.unresolved(ComplaintReportApplication.Edit(receipt)))) }
        fixture.model.fillEditDraft()
        fixture.model.submit(BackendComplaintEditIntent.Submit)
        val release = CompletableDeferred<Unit>()
        val caller = CompletableDeferred<Job>()
        fake.onRetry = { cancelWithChildCleanup(release, caller) }
        val events = mutableListOf<BackendComplaintEditEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.model.effects.collect { events += it } }
        try {
            fixture.model.submit(BackendComplaintEditIntent.Retry)
            assertPendingRetryOwned(fixture, caller.await(), receipt)
            if (openRecovery) fixture.model.submit(BackendComplaintEditIntent.OpenRecovery)
            assertTrue(events.isEmpty())
            release.complete(Unit)
            runCurrent()
            assertTrue(caller.await().isCompleted)
            assertAfterChildDrain(fixture, openRecovery, events, receipt)
        } finally {
            release.complete(Unit)
            runCurrent()
        }
    }

    private fun assertPendingRetryOwned(fixture: BackendComplaintEditViewModelFixture, caller: Job, receipt: ComplaintEditApplication) {
        val state = fixture.model.state.value
        assertTrue(caller.isCancelled && !caller.isCompleted)
        assertIs<AppError.Cancelled>(state.result.failure?.error)
        assertNull(state.result.failure?.error?.cause)
        assertSame(receipt, state.result.receipt)
        assertTrue(state.canRetry && state.result.cleanupPending)
        assertFalse(state.busy)
        assertEquals(EDIT_PRIVATE_BODY, state.draft.body)
        fixture.model.submit(BackendComplaintEditIntent.Submit)
        fixture.model.submit(BackendComplaintEditIntent.Retry)
        assertEquals(1, fixture.repository.drafts.size)
        assertSame(fixture.repository.live, fixture.repository.retried.single())
    }

    private fun assertAfterChildDrain(
        fixture: BackendComplaintEditViewModelFixture,
        openRecovery: Boolean,
        events: List<BackendComplaintEditEffect>,
        receipt: ComplaintEditApplication,
    ) {
        if (openRecovery) {
            assertEditRetired(fixture.model)
            assertEquals(listOf(BackendComplaintEditEffect.OpenRecovery), events)
        } else {
            fixture.repository.onRetry = { AppResult.Success(completedEdit(receipt)) }
            fixture.model.submit(BackendComplaintEditIntent.Retry)
            assertTrue(fixture.repository.retried.all { it === fixture.repository.submitted.single() })
            assertEquals(1, fixture.repository.drafts.size)
            assertTrue(fixture.model.state.value.result.completed)
        }
    }

    private fun fixture(fake: EditRepositoryFake = EditRepositoryFake()): BackendComplaintEditViewModelFixture =
        BackendComplaintEditViewModelFixture(repository = fake).also { fixtures += it }

    private fun TestScope.effects(model: BackendComplaintEditViewModel): MutableList<BackendComplaintEditEffect> {
        val effects = mutableListOf<BackendComplaintEditEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { effects += it } }
        return effects
    }

    private suspend fun TestScope.assertCloseFence(late: LateEditWork<ComplaintReportSubmission>) {
        val fake = EditRepositoryFake()
        fake.onSubmit = { late.run() }
        val active = fixture(fake)
        val other = fixture(fake)
        val effects = effects(active.model)
        try {
            active.model.fillEditDraft()
            active.model.submit(BackendComplaintEditIntent.Submit)
            other.model.submit(BackendComplaintEditIntent.Close)
            assertTrue(assertNotNull(late.caller).isActive)
            active.model.submit(BackendComplaintEditIntent.Close)
            late.closing.await()
            assertEditRetired(active.model)
            assertTrue(effects.isEmpty())
            ignoreLaterIntents(active.model)
            late.release.complete(Unit)
            runCurrent()
            assertTrue(late.cleaned)
            assertEditRetired(active.model)
            assertEquals(listOf<BackendComplaintEditEffect>(BackendComplaintEditEffect.Closed), effects)
            assertEquals(1, fake.submitted.size)
        } finally {
            late.release.complete(Unit)
        }
    }

    private suspend fun TestScope.assertStoreClear(duringPreparation: Boolean) {
        val fixture = fixture()
        val fake = fixture.repository
        val late = pausePhase(fake, duringPreparation)
        val effects = effects(fixture.model)
        try {
            fixture.model.fillEditDraft()
            fixture.model.submit(BackendComplaintEditIntent.Submit)
            fixture.close()
            late.closing.await()
            assertEditRetired(fixture.model)
            ignoreLaterIntents(fixture.model)
            late.release.complete(Unit)
            runCurrent()
            assertTrue(late.cleaned)
            assertEditRetired(fixture.model)
            assertTrue(effects.isEmpty() && fake.retried.isEmpty())
            assertEquals(1, fake.drafts.size)
            assertEquals(if (duringPreparation) 0 else 1, fake.submitted.size)
        } finally {
            late.release.complete(Unit)
        }
    }

    private fun pausePhase(fake: EditRepositoryFake, duringPreparation: Boolean): LateEditWork<*> =
        if (duringPreparation) {
            LateEditWork { AppResult.Success(ComplaintEditPreparation.Ready(fake.live)) }
                .also { late -> fake.onPrepare = { late.run() } }
        } else {
            LateEditWork { AppResult.Success(fake.submission(completedEdit())) }
                .also { late -> fake.onSubmit = { late.run() } }
        }

    private fun ignoreLaterIntents(model: BackendComplaintEditViewModel) {
        model.fillEditDraft()
        model.submit(BackendComplaintEditIntent.Submit)
        model.submit(BackendComplaintEditIntent.Retry)
        model.submit(BackendComplaintEditIntent.OpenRecovery)
        model.submit(BackendComplaintEditIntent.Close)
        assertEditRetired(model)
    }

    private fun assertColdCannotRetry(fake: EditRepositoryFake) {
        val cold = fixture(fake)
        cold.model.submit(BackendComplaintEditIntent.Retry)
        assertEquals(EDIT_ORIGINAL_BODY, cold.model.state.value.draft.body)
        assertFalse(cold.model.state.value.canRetry)
        assertEquals(1, fake.drafts.size)
        assertEquals(1, fake.submitted.size)
        assertTrue(fake.retried.isEmpty())
    }
}

/** Reproduces a canceled caller that has returned while its attached child is still draining. */
private suspend fun cancelWithChildCleanup(release: CompletableDeferred<Unit>, caller: CompletableDeferred<Job>): Nothing {
    val context = currentCoroutineContext()
    CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { release.await() }
        }
    }
    caller.complete(context.job)
    context.job.cancel()
    throw CancellationException("synthetic canceled operation")
}
