package me.manga.kira.presentation.language

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
import kotlinx.coroutines.flow.MutableSharedFlow
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
import me.manga.kira.domain.model.language.Language
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.domain.repository.LanguageRepository
import me.manga.kira.domain.usecase.feedback.SendLanguageRequestUseCase
import me.manga.kira.domain.usecase.language.GetSupportedLanguagesUseCase
import me.manga.kira.domain.usecase.language.ObserveSelectedLanguageUseCase
import me.manga.kira.domain.usecase.language.SetLanguageUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Three grouped picker/request regressions: original behavior, producer retirement, and buffered
 * effect/saved-intent ownership. Reattachment here is an effect consumer, not a Compose remount;
 * candidate callbacks, dialog local echo, URLs, Back and label-only Retry remain manual UI checks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val requestBody = "Please add Polish"

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private class RecordingLanguageRepository : LanguageRepository {
        val selectedUpstream = MutableSharedFlow<String>(replay = 1)
        val setCalls = mutableListOf<String>()
        override fun observeSelectedLanguageCode(): Flow<String> = selectedUpstream
        override fun getSupportedLanguages(): List<Language> = listOf(
            Language(code = "en", displayName = "English"),
            Language(code = "ar", displayName = "العربية"),
        )
        override suspend fun setLanguage(code: String) {
            setCalls += code
        }
    }

    private class GatedFeedbackRepository(
        private val complete: suspend () -> Result<Unit> = { Result.success(Unit) },
    ) : FeedbackRepository {
        val submissions = mutableListOf<Triple<ComplaintType, String, String>>()
        var operation: Job? = null

        override suspend fun submit(type: ComplaintType, subject: String, body: String): Result<Unit> {
            submissions += Triple(type, subject, body)
            operation = currentCoroutineContext().job
            return complete()
        }
    }

    private fun viewModel(
        languages: RecordingLanguageRepository = RecordingLanguageRepository(),
        feedback: GatedFeedbackRepository = GatedFeedbackRepository(),
    ): LanguageViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = viewModelFactory {
            initializer {
                LanguageViewModel(
                    GetSupportedLanguagesUseCase(languages),
                    ObserveSelectedLanguageUseCase(languages),
                    SetLanguageUseCase(languages),
                    SendLanguageRequestUseCase(feedback),
                )
            }
        }
        return ViewModelProvider.create(store, factory)[LanguageViewModel::class]
    }

    @Test
    fun unretiredOwnerKeepsPickerAndRequestBehaviorIncludingBusyDismissReopen() = runTest {
        val languages = RecordingLanguageRepository()
        val picker = viewModel(languages = languages)
        assertEquals(listOf("en", "ar"), picker.state.value.languages.map { it.code }, "sync supported-list read")
        assertTrue(picker.state.value.isLoading, "no selected-code emission yet")
        picker.submit(LanguageIntent.OnSelectLanguage("de"))
        assertEquals(listOf("de"), languages.setCalls)
        assertEquals("", picker.state.value.selectedCode, "only an upstream re-emit moves the indicator")
        assertTrue(languages.selectedUpstream.tryEmit("ar"))
        assertFalse(picker.state.value.isLoading)
        assertEquals("ar", picker.state.value.selectedCode)

        for (success in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            val feedback = GatedFeedbackRepository {
                release.await()
                requestResult(success)
            }
            val model = viewModel(feedback = feedback)
            val effects = mutableListOf<LanguageEffect>()
            backgroundScope.launch(dispatcher) { model.effects.collect { effects += it } }
            try {
                model.submit(LanguageIntent.OnOpenRequestDialog)
                model.submit(LanguageIntent.OnRequestTextChange(requestBody))
                model.submit(LanguageIntent.OnSubmitRequest)
                model.submit(LanguageIntent.OnSubmitRequest)
                runCurrent()
                val owned = assertNotNull(feedback.operation)
                assertTrue(model.state.value.requestDialogVisible)
                assertTrue(model.state.value.requestSubmitting)
                assertEquals(requestBody, model.state.value.requestText)
                assertEquals(listOf(Triple(ComplaintType.LANGUAGES, "Languages", requestBody)), feedback.submissions)

                model.submit(LanguageIntent.OnDismissRequestDialog)
                assertFalse(model.state.value.requestDialogVisible, "legacy dismissal remains allowed while busy")
                assertTrue(model.state.value.requestSubmitting)
                model.submit(LanguageIntent.OnOpenRequestDialog)
                assertTrue(model.state.value.requestDialogVisible)
                assertEquals("", model.state.value.requestText, "reopen still clears the draft")
                assertTrue(model.state.value.requestSubmitting, "reopen cannot authorize another active producer")
                model.submit(LanguageIntent.OnRequestTextChange("Edited draft while busy"))
                model.submit(LanguageIntent.OnSubmitRequest)
                assertSame(owned, feedback.operation)
                assertEquals(1, feedback.submissions.size)

                release.complete(Unit)
                runCurrent()
                val expectedEffect = if (success) LanguageEffect.RequestSubmitted else LanguageEffect.RequestFailed
                assertEquals(listOf(expectedEffect), effects)
                assertFalse(model.state.value.requestSubmitting)
                assertFalse(model.state.value.legacyRequestRetired)
                assertEquals(!success, model.state.value.requestDialogVisible)
                assertEquals(if (success) "" else "Edited draft while busy", model.state.value.requestText)

                model.submit(LanguageIntent.OnDismissRequestDialog)
                model.submit(LanguageIntent.OnOpenRequestDialog)
                assertTrue(model.state.value.requestDialogVisible, "ordinary reopen after completion stays enabled")
                assertFalse(model.state.value.requestSubmitting)
                assertEquals("", model.state.value.requestText)
                model.submit(LanguageIntent.OnRequestTextChange("Another request"))
                model.submit(LanguageIntent.OnSubmitRequest)
                runCurrent()
                assertEquals(
                    listOf(
                        Triple(ComplaintType.LANGUAGES, "Languages", requestBody),
                        Triple(ComplaintType.LANGUAGES, "Languages", "Another request"),
                    ),
                    feedback.submissions,
                )
                assertEquals(listOf(expectedEffect, expectedEffect), effects)
                assertFalse(model.state.value.requestSubmitting)
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
    }

    @Test
    fun retirementFencesQueuedProviderStartAndCancelsOwnedJobsWithoutTrustingLateResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val queuedFeedback = GatedFeedbackRepository()
        val queued = viewModel(feedback = queuedFeedback)
        queued.submit(LanguageIntent.OnOpenRequestDialog)
        queued.submit(LanguageIntent.OnRequestTextChange(requestBody))
        queued.submit(LanguageIntent.OnSubmitRequest)
        queued.retireLegacyRequest()
        assertRetired(queued)
        runCurrent()
        assertRetired(queued)
        assertTrue(queuedFeedback.submissions.isEmpty())

        val beforeStartFeedback = GatedFeedbackRepository()
        val beforeStart = viewModel(feedback = beforeStartFeedback)
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            beforeStart.state.collect { if (it.requestSubmitting) beforeStart.retireLegacyRequest() }
        }
        beforeStart.submit(LanguageIntent.OnOpenRequestDialog)
        beforeStart.submit(LanguageIntent.OnRequestTextChange(requestBody))
        beforeStart.submit(LanguageIntent.OnSubmitRequest)
        runCurrent()
        observer.cancel()
        assertRetired(beforeStart)
        assertTrue(beforeStartFeedback.submissions.isEmpty(), "retired between busy state and provider launch")

        for (success in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            var returned = false
            val feedback = GatedFeedbackRepository {
                withContext(NonCancellable) { release.await() }
                returned = true
                requestResult(success)
            }
            val model = viewModel(feedback = feedback)
            val effects = mutableListOf<LanguageEffect>()
            // Raw stream: consumer filtering cannot conceal a late producer emission in this check.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { effects += it } }
            try {
                model.submit(LanguageIntent.OnOpenRequestDialog)
                model.submit(LanguageIntent.OnRequestTextChange(requestBody))
                model.submit(LanguageIntent.OnSubmitRequest)
                runCurrent()
                val owned = assertNotNull(feedback.operation)
                assertTrue(model.state.value.requestSubmitting)
                model.submit(LanguageIntent.OnDismissRequestDialog)
                model.submit(LanguageIntent.OnOpenRequestDialog)
                model.submit(LanguageIntent.OnSubmitRequest)
                runCurrent()
                assertSame(owned, feedback.operation, "busy reopen must not orphan the original operation")
                model.retireLegacyRequest()
                model.retireLegacyRequest()
                assertRetired(model)
                assertTrue(owned.isCancelled, "the retained provider job receives best-effort cancellation")
                model.submit(LanguageIntent.OnOpenRequestDialog)
                model.submit(LanguageIntent.OnRequestTextChange("stale draft"))
                model.submit(LanguageIntent.OnSubmitRequest)
                release.complete(Unit)
                runCurrent()
                assertTrue(returned, "the provider deliberately returns after cancellation")
                assertRetired(model)
                assertEquals(listOf(Triple(ComplaintType.LANGUAGES, "Languages", requestBody)), feedback.submissions)
                assertTrue(effects.isEmpty(), "late success and failure stay silent at the producer")
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }

        // Immediate entry can retire before launchSafely has returned the Job to its caller.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        var retireOnEntry: (() -> Unit)? = null
        var returned = false
        val immediateFeedback = GatedFeedbackRepository {
            checkNotNull(retireOnEntry).invoke()
            withContext(NonCancellable) { release.await() }
            returned = true
            Result.success(Unit)
        }
        val immediate = viewModel(feedback = immediateFeedback)
        retireOnEntry = immediate::retireLegacyRequest
        val immediateEffects = mutableListOf<LanguageEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            immediate.effects.collect { immediateEffects += it }
        }
        try {
            immediate.submit(LanguageIntent.OnOpenRequestDialog)
            immediate.submit(LanguageIntent.OnRequestTextChange(requestBody))
            immediate.submit(LanguageIntent.OnSubmitRequest)
            assertRetired(immediate)
            assertTrue(assertNotNull(immediateFeedback.operation).isCancelled, "post-launch cancellation fence")
            release.complete(Unit)
            runCurrent()
            assertTrue(returned)
            assertRetired(immediate)
            assertEquals(1, immediateFeedback.submissions.size)
            assertTrue(immediateEffects.isEmpty())
        } finally {
            release.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun bufferedResultsAndSavedLegacyIntentsStayRetiredAcrossConsumerReattachment() = runTest {
        val languages = RecordingLanguageRepository()
        var nextSuccess = false
        val feedback = GatedFeedbackRepository { requestResult(nextSuccess) }
        val model = viewModel(languages, feedback)
        val screenEffects = model.screenEffects
        assertSame(screenEffects, model.screenEffects, "the screen stream has stable identity")
        val savedOpen = { model.submit(LanguageIntent.OnOpenRequestDialog) }
        val savedTextChange = { model.submit(LanguageIntent.OnRequestTextChange("saved draft")) }
        val savedSubmit = { model.submit(LanguageIntent.OnSubmitRequest) }
        val savedDismiss = { model.submit(LanguageIntent.OnDismissRequestDialog) }
        for (success in listOf(false, true)) {
            nextSuccess = success
            savedOpen()
            savedTextChange()
            savedSubmit()
            runCurrent() // No collector: both real, untagged request outcomes remain buffered.
            assertTrue(assertNotNull(feedback.operation).isCompleted)
            assertFalse(model.state.value.requestSubmitting)
            assertEquals(!success, model.state.value.requestDialogVisible)
            assertEquals(if (success) "" else "saved draft", model.state.value.requestText)
        }
        assertEquals(List(2) { Triple(ComplaintType.LANGUAGES, "Languages", "saved draft") }, feedback.submissions)
        model.retireLegacyRequest()
        model.retireLegacyRequest()
        val retiredState = model.state.value
        savedOpen()
        savedTextChange()
        savedSubmit()
        savedDismiss()
        runCurrent()
        assertEquals(retiredState, model.state.value, "every saved legacy intent is inert after retirement")
        assertRetired(model)

        model.submit(LanguageIntent.OnSelectLanguage("ar"))
        assertEquals(listOf("ar"), languages.setCalls, "retirement must not disable language selection")
        assertEquals("", model.state.value.selectedCode)
        assertTrue(languages.selectedUpstream.tryEmit("ar"))
        runCurrent()
        assertEquals("ar", model.state.value.selectedCode)
        assertFalse(model.state.value.isLoading)
        assertEquals(listOf("en", "ar"), model.state.value.languages.map { it.code })
        repeat(2) {
            val effects = mutableListOf<LanguageEffect>()
            val collector = backgroundScope.launch(dispatcher) { screenEffects.collect { effects += it } }
            savedOpen()
            savedTextChange()
            savedSubmit()
            savedDismiss()
            runCurrent()
            assertTrue(effects.isEmpty(), "buffered success/failure and stale intent results cannot reappear")
            assertEquals(2, feedback.submissions.size)
            collector.cancel()
            assertRetired(model)
        }

        val freshFeedback = GatedFeedbackRepository()
        val fresh = viewModel(feedback = freshFeedback)
        fresh.submit(LanguageIntent.OnOpenRequestDialog)
        fresh.submit(LanguageIntent.OnRequestTextChange(requestBody))
        fresh.submit(LanguageIntent.OnSubmitRequest)
        runCurrent()
        val freshEffects = mutableListOf<LanguageEffect>()
        backgroundScope.launch(dispatcher) { fresh.screenEffects.collect { freshEffects += it } }
        assertFalse(fresh.state.value.legacyRequestRetired, "retirement is VM-local, never a global switch")
        assertEquals(listOf(Triple(ComplaintType.LANGUAGES, "Languages", requestBody)), freshFeedback.submissions)
        assertEquals(listOf<LanguageEffect>(LanguageEffect.RequestSubmitted), freshEffects)
    }

    private fun assertRetired(model: LanguageViewModel) {
        assertTrue(model.state.value.legacyRequestRetired)
        assertFalse(model.state.value.requestDialogVisible)
        assertEquals("", model.state.value.requestText)
        assertFalse(model.state.value.requestSubmitting)
    }

    private fun requestResult(success: Boolean): Result<Unit> =
        if (success) Result.success(Unit) else Result.failure(IllegalStateException("synthetic request failure"))
}
