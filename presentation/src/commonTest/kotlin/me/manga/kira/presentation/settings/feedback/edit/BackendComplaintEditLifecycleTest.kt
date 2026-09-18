package me.manga.kira.presentation.settings.feedback.edit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
            val fixture = fixture()
            val fake = fixture.repository
            val receipt = ComplaintEditApplication.Applied(EDIT_ID, 8)
            fake.onSubmit = {
                AppResult.Success(fake.submission(fake.unresolved(ComplaintReportApplication.Edit(receipt))))
            }
            fixture.model.fillEditDraft()
            fixture.model.submit(BackendComplaintEditIntent.Submit)
            var caller: Job? = null
            fake.onRetry = {
                caller = currentCoroutineContext().job
                throw CancellationException("Synthetic private cancellation")
            }
            fixture.model.submit(BackendComplaintEditIntent.Retry)
            assertTrue(assertNotNull(caller).isCancelled)
            val state = fixture.model.state.value
            assertIs<AppError.Cancelled>(state.result.failure?.error)
            assertNull(state.result.failure?.error?.cause)
            assertSame(receipt, state.result.receipt)
            assertTrue(state.canRetry && state.result.cleanupPending)
            assertEquals(EDIT_PRIVATE_BODY, state.draft.body)
            fake.onRetry = { AppResult.Success(completedEdit(receipt)) }
            fixture.model.submit(BackendComplaintEditIntent.Retry)
            assertTrue(fake.retried.all { it === fake.submitted.single() })
            assertEquals(1, fake.drafts.size)
            assertTrue(fixture.model.state.value.result.completed)
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
