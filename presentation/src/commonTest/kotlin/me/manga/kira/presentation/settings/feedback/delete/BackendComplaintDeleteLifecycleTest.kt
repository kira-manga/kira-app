package me.manga.kira.presentation.settings.feedback.delete

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackendComplaintDeleteLifecycleTest {
    private val fixtures = mutableListOf<BackendComplaintDeleteFixture>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun closeAndStoreClearFenceLatePreparationSuccessSubmissionSuccessAndThrowable() =
        runTest {
            for (clearStore in listOf(false, true)) {
                assertLatePreparationFenced(clearStore)
                for (throwLate in listOf(false, true)) assertLateSubmissionFenced(clearStore, throwLate)
            }
        }

    @Test
    fun recoveryHandoffWaitsForDrainAndColdOpeningCannotReconstructOrRetryTheDeletion() =
        runTest {
            val fixture = fixture()
            val events = mutableListOf<BackendComplaintDeleteEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.model.effects.collect(events::add) }
            val late = LateDeleteWork { AppResult.Success(fixture.repository.submission(deleteCompleted())) }
            fixture.repository.onSubmit = { late.run() }
            try {
                assertRecoveryDrains(fixture, late, events)
                val reopened = fixture()
                reopened.model.submit(BackendComplaintDeleteIntent.Retry)
                assertTrue(reopened.repository.retried.isEmpty())
                assertTrue(reopened.repository.drafts.isEmpty())
                assertTrue(reopened.model.state.value.canConfirm)
                assertTrue(fixture.repository.retried.isEmpty())
            } finally {
                late.release.complete(Unit)
                runCurrent()
            }
        }

    @Test
    fun currentCancellationPreservesKnownReceiptAndDoesNotPermitReplacementConfirmation() =
        runTest {
            val fixture = fixture()
            val receipt = ComplaintReportApplication.OwnerDelete(ComplaintOwnerDeleteApplication.Applied)
            fixture.repository.onSubmit = { AppResult.Success(fixture.repository.submission(unresolved(receipt))) }
            fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
            fixture.repository.onRetry = { throw CancellationException("synthetic current cancellation") }
            fixture.model.submit(BackendComplaintDeleteIntent.Retry)
            val state = fixture.model.state.value
            assertSame(ComplaintOwnerDeleteApplication.Applied, state.result.receipt)
            assertTrue(state.result.cleanupPending)
            assertIs<AppError.Cancelled>(state.result.failure?.error)
            assertTrue(state.canRetry)
            assertFalse(state.busy)
            fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
            assertEquals(1, fixture.repository.drafts.size)
            assertSame(fixture.repository.live, fixture.repository.retried.single())
        }

    private suspend fun TestScope.assertLatePreparationFenced(clearStore: Boolean) {
        val preparing = fixture()
        val prepared = LateDeleteWork { AppResult.Success(ComplaintOwnerDeletePreparation.Ready(preparing.repository.live)) }
        preparing.repository.onPrepare = { prepared.run() }
        try {
            preparing.model.submit(BackendComplaintDeleteIntent.Confirm)
            if (clearStore) preparing.close() else preparing.model.submit(BackendComplaintDeleteIntent.Close)
            prepared.closing.await()
            assertDeleteClosed(preparing.model)
            assertTrue(preparing.repository.submitted.isEmpty())
            prepared.release.complete(Unit)
            runCurrent()
            assertTrue(prepared.cleaned)
            assertTrue(preparing.repository.submitted.isEmpty())
            assertDeleteClosed(preparing.model)
        } finally {
            prepared.release.complete(Unit)
            runCurrent()
        }
    }

    private suspend fun TestScope.assertLateSubmissionFenced(clearStore: Boolean, throwLate: Boolean) {
        val fixture = fixture()
        val submitted =
            LateDeleteWork<ComplaintReportSubmission> {
                if (throwLate) error("private late deletion response")
                AppResult.Success(fixture.repository.submission(deleteCompleted()))
            }
        fixture.repository.onSubmit = { submitted.run() }
        try {
            fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
            if (clearStore) fixture.close() else fixture.model.submit(BackendComplaintDeleteIntent.Close)
            submitted.closing.await()
            assertDeleteClosed(fixture.model)
            submitted.release.complete(Unit)
            runCurrent()
            assertTrue(submitted.cleaned)
            assertDeleteClosed(fixture.model)
            assertEquals(1, fixture.repository.submitted.size)
            assertTrue(fixture.repository.retried.isEmpty())
        } finally {
            submitted.release.complete(Unit)
            runCurrent()
        }
    }

    private suspend fun TestScope.assertRecoveryDrains(
        fixture: BackendComplaintDeleteFixture,
        late: LateDeleteWork<ComplaintReportSubmission>,
        events: List<BackendComplaintDeleteEffect>,
    ) {
        fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
        fixture.model.submit(BackendComplaintDeleteIntent.OpenRecovery)
        late.closing.await()
        assertDeleteClosed(fixture.model)
        assertTrue(events.isEmpty())
        fixture.model.submit(BackendComplaintDeleteIntent.Close)
        fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
        assertTrue(events.isEmpty())
        late.release.complete(Unit)
        runCurrent()
        assertTrue(late.cleaned)
        assertEquals(listOf(BackendComplaintDeleteEffect.OpenRecovery), events)
    }

    private fun fixture(): BackendComplaintDeleteFixture = BackendComplaintDeleteFixture().also { fixtures += it }
}
