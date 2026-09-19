package me.manga.kira.presentation.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.domain.repository.ReadingModeRepository
import me.manga.kira.domain.usecase.feedback.SubmitFeedbackUseCase
import me.manga.kira.domain.usecase.reader.ObserveReadingModeUseCase
import me.manga.kira.domain.usecase.reader.SetReadingModeUseCase
import me.manga.kira.domain.usecase.settings.ClearCacheUseCase
import me.manga.kira.domain.usecase.settings.ClearCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.CompressExistingDownloadsUseCase
import me.manga.kira.domain.usecase.settings.ObserveCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.ObserveSettingsUseCase
import me.manga.kira.domain.usecase.settings.StopCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.UpdateSettingsToggleUseCase
import me.manga.kira.presentation.testing.FakeSettingsRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CBZ reset regressions and retained legacy feedback ownership. Effect-consumer reattachment is
 * tested here, not Compose remounts, dialog drafts, snackbar rendering or candidate navigation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val feedbackIntent =
        SettingsIntent.OnSubmitFeedback(ComplaintType.TECHNICAL, "Synthetic category", "Synthetic body")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private class FakeReadingModeRepository : ReadingModeRepository {
        override fun observe(): Flow<ReadingMode> = flowOf(ReadingMode.DEFAULT)
        override suspend fun set(mode: ReadingMode) = Unit
    }

    private class FakeFeedbackRepository(
        val complete: suspend () -> Result<Unit> = { Result.success(Unit) },
    ) : FeedbackRepository {
        val requests = mutableListOf<SettingsIntent.OnSubmitFeedback>()
        var operation: Job? = null

        override suspend fun submit(type: ComplaintType, subject: String, body: String): Result<Unit> {
            requests += SettingsIntent.OnSubmitFeedback(type, subject, body)
            operation = currentCoroutineContext().job
            return complete()
        }
    }

    private fun vm(
        settings: FakeSettingsRepository,
        feedback: FakeFeedbackRepository = FakeFeedbackRepository(),
    ): SettingsViewModel {
        val reading = FakeReadingModeRepository()
        val store = ViewModelStore().also { stores += it }
        val factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    ObserveSettingsUseCase(settings),
                    ObserveReadingModeUseCase(reading),
                    UpdateSettingsToggleUseCase(settings),
                    ClearCacheUseCase(settings),
                    SubmitFeedbackUseCase(feedback),
                    SetReadingModeUseCase(reading),
                    CompressExistingDownloadsUseCase(settings),
                    ObserveCbzConversionUseCase(settings),
                    StopCbzConversionUseCase(settings),
                    ClearCbzConversionUseCase(settings),
                )
            }
        }
        return ViewModelProvider.create(store, factory)[SettingsViewModel::class]
    }

    @Test
    fun dismissFromTerminalState_resetsStateAndClearsUnderlyingFlow() = runTest {
        val settings = FakeSettingsRepository()
        // Seed a TERMINAL snapshot (run finished) BEFORE the VM subscribes — the init collector
        // projects it, mimicking a recreated VM that finds the hot flow at a terminal value.
        settings.conversionProgress.value = CbzConversionProgress(
            isConverting = false,
            totalChapters = 3,
            convertedChapters = 3,
            successMessage = "done",
        )
        val vm = vm(settings)
        assertEquals("done", vm.state.value.cbzConversion.successMessage, "terminal snapshot projected into state")

        vm.submit(SettingsIntent.OnDismissConversionDialog)

        assertEquals(CbzConversionProgress(), vm.state.value.cbzConversion, "state reset to idle on dismiss")
        assertEquals(1, settings.clearConversionCalls, "underlying progress flow cleared so it can't replay (#14)")
    }

    @Test
    fun dismissWhileConverting_isBlocked() = runTest {
        val settings = FakeSettingsRepository()
        settings.conversionProgress.value = CbzConversionProgress(
            isConverting = true,
            totalChapters = 5,
            convertedChapters = 2,
        )
        val vm = vm(settings)
        assertTrue(vm.state.value.cbzConversion.isConverting, "converting snapshot projected into state")

        vm.submit(SettingsIntent.OnDismissConversionDialog)

        assertTrue(vm.state.value.cbzConversion.isConverting, "dismiss ignored while a run is in flight")
        assertEquals(0, settings.clearConversionCalls, "in-converting guard blocks the clear")
        assertFalse(vm.state.value.cbzConversion.convertedChapters == 0, "live progress untouched")
    }

    @Test
    fun unretiredOwnerKeepsLegacyOpenSubmitSuccessAndFailureBehavior() = runTest {
        for (success in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            val feedback = FakeFeedbackRepository {
                release.await()
                feedbackResult(success)
            }
            val model = vm(FakeSettingsRepository(), feedback)
            val effects = mutableListOf<SettingsEffect>()
            backgroundScope.launch(dispatcher) { model.effects.collect { effects += it } }
            try {
                model.submit(SettingsIntent.OnOpenFeedbackDialog)
                model.submit(feedbackIntent)
                model.submit(SettingsIntent.OnDismissFeedbackDialog)
                model.submit(feedbackIntent.copy(body = "replacement"))
                runCurrent()
                assertTrue(model.state.value.feedbackDialogOpen)
                assertTrue(model.state.value.isSubmittingFeedback)
                assertEquals(listOf(feedbackIntent), feedback.requests)
                release.complete(Unit)
                runCurrent()
                assertEquals(listOf<SettingsEffect>(SettingsEffect.FeedbackResult(success)), effects)
                assertFalse(model.state.value.isSubmittingFeedback)
                assertEquals(!success, model.state.value.feedbackDialogOpen)
                assertFalse(model.state.value.legacyFeedbackRetired)
                model.submit(SettingsIntent.OnDismissFeedbackDialog)
                assertFalse(model.state.value.feedbackDialogOpen)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun retirementFencesQueuedProviderStartAndCancelsOwnedJobWithoutTrustingLateCompletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val queuedFeedback = FakeFeedbackRepository()
        val queued = vm(FakeSettingsRepository(), queuedFeedback)
        queued.submit(SettingsIntent.OnOpenFeedbackDialog)
        queued.submit(feedbackIntent)
        queued.retireLegacyFeedback()
        runCurrent()
        assertRetired(queued)
        assertTrue(queuedFeedback.requests.isEmpty())

        val beforeStartFeedback = FakeFeedbackRepository()
        val beforeStart = vm(FakeSettingsRepository(), beforeStartFeedback)
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            beforeStart.state.collect { if (it.isSubmittingFeedback) beforeStart.retireLegacyFeedback() }
        }
        beforeStart.submit(SettingsIntent.OnOpenFeedbackDialog)
        beforeStart.submit(feedbackIntent)
        runCurrent()
        observer.cancel()
        assertRetired(beforeStart)
        assertTrue(beforeStartFeedback.requests.isEmpty(), "retired between busy state and inner provider launch")

        for (success in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            var returned = false
            val feedback = FakeFeedbackRepository {
                withContext(NonCancellable) { release.await() }
                returned = true
                feedbackResult(success)
            }
            val model = vm(FakeSettingsRepository(), feedback)
            val effects = mutableListOf<SettingsEffect>()
            // Raw producer stream: consumer-side filtering must not hide a late producer emission.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { effects += it } }
            try {
                model.submit(SettingsIntent.OnOpenFeedbackDialog)
                model.submit(feedbackIntent)
                runCurrent()
                assertTrue(model.state.value.isSubmittingFeedback)
                val owned = assertNotNull(feedback.operation)
                model.retireLegacyFeedback()
                model.retireLegacyFeedback()
                assertRetired(model)
                assertTrue(owned.isCancelled, "retirement best-effort cancels the retained operation")
                model.submit(SettingsIntent.OnOpenFeedbackDialog)
                model.submit(feedbackIntent)
                release.complete(Unit)
                runCurrent()
                assertTrue(returned, "provider deliberately returns despite cancellation")
                assertRetired(model)
                assertEquals(listOf(feedbackIntent), feedback.requests)
                assertTrue(effects.isEmpty(), "late success and failure must be silent at the producer")
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
    }

    @Test
    fun bufferedResultsAndSavedLegacyActionsStayRetiredAcrossEffectConsumerReattachment() = runTest {
        val settings = FakeSettingsRepository()
        val feedback = FakeFeedbackRepository { feedbackResult(false) }
        val model = vm(settings, feedback)
        val savedRetry = { model.submit(SettingsIntent.OnOpenFeedbackDialog) }
        val savedSubmit = { model.submit(feedbackIntent) }
        savedRetry()
        savedSubmit()
        runCurrent() // Failure completes with no collector: its untagged result is actually queued.
        assertEquals(listOf(feedbackIntent), feedback.requests)
        assertTrue(model.state.value.feedbackDialogOpen)
        assertFalse(model.state.value.isSubmittingFeedback)
        model.retireLegacyFeedback()
        model.retireLegacyFeedback()
        savedRetry()
        savedSubmit()
        settings.downloadedOnly.value = true
        settings.conversionProgress.value = CbzConversionProgress(successMessage = "done")
        runCurrent()
        assertRetired(model)
        assertTrue(model.state.value.downloadedOnly)
        assertEquals("done", model.state.value.cbzConversion.successMessage)
        assertEquals(listOf(feedbackIntent), feedback.requests)
        for (destination in listOf(SettingsDestination.ABOUT, SettingsDestination.LANGUAGE)) {
            val effects = mutableListOf<SettingsEffect>()
            val collector = backgroundScope.launch(dispatcher) { model.screenEffects.collect { effects += it } }
            model.submit(SettingsIntent.OnNavigate(destination))
            runCurrent()
            assertEquals(listOf<SettingsEffect>(SettingsEffect.NavigateTo(destination)), effects)
            collector.cancel()
            assertRetired(model)
        }
        val freshFeedback = FakeFeedbackRepository()
        val fresh = vm(FakeSettingsRepository(), freshFeedback)
        val freshEffects = mutableListOf<SettingsEffect>()
        backgroundScope.launch(dispatcher) { fresh.screenEffects.collect { freshEffects += it } }
        fresh.submit(SettingsIntent.OnOpenFeedbackDialog)
        fresh.submit(feedbackIntent)
        runCurrent()
        assertFalse(fresh.state.value.legacyFeedbackRetired)
        assertEquals(listOf(feedbackIntent), freshFeedback.requests)
        assertEquals(listOf<SettingsEffect>(SettingsEffect.FeedbackResult(true)), freshEffects)
    }

    private fun assertRetired(model: SettingsViewModel) {
        assertTrue(model.state.value.legacyFeedbackRetired)
        assertFalse(model.state.value.feedbackDialogOpen)
        assertFalse(model.state.value.isSubmittingFeedback)
    }

    private fun feedbackResult(success: Boolean): Result<Unit> =
        if (success) Result.success(Unit) else Result.failure(IllegalStateException("synthetic failure"))
}
