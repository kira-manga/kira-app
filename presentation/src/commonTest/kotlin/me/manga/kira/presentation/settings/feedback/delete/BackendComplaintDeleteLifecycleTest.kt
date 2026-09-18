package me.manga.kira.presentation.settings.feedback.delete

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
import kotlinx.coroutines.flow.collect
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
import kotlin.test.assertNull
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
            for (openRecovery in listOf(false, true)) assertChildCleanupFence(openRecovery)
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

    private suspend fun TestScope.assertChildCleanupFence(openRecovery: Boolean) {
        val fixture = fixture()
        val fake = fixture.repository
        val receipt = ComplaintOwnerDeleteApplication.Applied
        fake.onSubmit = { AppResult.Success(fake.submission(unresolved(ComplaintReportApplication.OwnerDelete(receipt)))) }
        fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
        val release = CompletableDeferred<Unit>()
        val caller = CompletableDeferred<Job>()
        fake.onRetry = { cancelWithChildCleanup(release, caller) }
        val events = mutableListOf<BackendComplaintDeleteEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.model.effects.collect { events += it } }
        try {
            fixture.model.submit(BackendComplaintDeleteIntent.Retry)
            assertPendingRetryOwned(fixture, caller.await(), receipt)
            if (openRecovery) fixture.model.submit(BackendComplaintDeleteIntent.OpenRecovery)
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

    private fun assertPendingRetryOwned(fixture: BackendComplaintDeleteFixture, caller: Job, receipt: ComplaintOwnerDeleteApplication) {
        val state = fixture.model.state.value
        assertTrue(caller.isCancelled && !caller.isCompleted)
        assertIs<AppError.Cancelled>(state.result.failure?.error)
        assertNull(state.result.failure?.error?.cause)
        assertSame(receipt, state.result.receipt)
        assertTrue(state.canRetry && state.result.cleanupPending)
        assertFalse(state.busy)
        fixture.model.submit(BackendComplaintDeleteIntent.Confirm)
        fixture.model.submit(BackendComplaintDeleteIntent.Retry)
        assertEquals(1, fixture.repository.drafts.size)
        assertSame(fixture.repository.live, fixture.repository.retried.single())
    }

    private fun assertAfterChildDrain(
        fixture: BackendComplaintDeleteFixture,
        openRecovery: Boolean,
        events: List<BackendComplaintDeleteEffect>,
        receipt: ComplaintOwnerDeleteApplication,
    ) {
        if (openRecovery) {
            assertDeleteClosed(fixture.model)
            assertEquals(listOf(BackendComplaintDeleteEffect.OpenRecovery), events)
        } else {
            fixture.repository.onRetry = { AppResult.Success(deleteCompleted(receipt)) }
            fixture.model.submit(BackendComplaintDeleteIntent.Retry)
            assertTrue(fixture.repository.retried.all { it === fixture.repository.submitted.single() })
            assertEquals(1, fixture.repository.drafts.size)
            assertTrue(fixture.model.state.value.result.completed)
        }
    }

    private fun fixture(): BackendComplaintDeleteFixture = BackendComplaintDeleteFixture().also { fixtures += it }
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
