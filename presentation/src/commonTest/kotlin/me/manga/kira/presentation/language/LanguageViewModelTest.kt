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
 * Picker/request regressions from both composition parents: retained-draft modal failure and
 * explicit retry, one-way producer retirement, and buffered-success/saved-intent ownership.
 * Reattachment here is an effect consumer, not Compose automation; candidate callbacks, dialog
 * local echo, URLs, Back and modal accessibility remain manual UI checks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Preserve both parents' regression sets in their existing shared fixture.
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
        var result: Result<Unit> = Result.success(Unit),
        private val complete: (suspend () -> Result<Unit>)? = null,
    ) : FeedbackRepository {
        val submissions = mutableListOf<Triple<ComplaintType, String, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var operation: Job? = null

        override suspend fun submit(type: ComplaintType, subject: String, body: String): Result<Unit> {
            submissions += Triple(type, subject, body)
            operation = currentCoroutineContext().job
            gate?.await()
            return complete?.invoke() ?: result
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
                assertTrue(model.state.value.requestDialogVisible, "busy dismissal cannot replace the pending dialog")
                assertTrue(model.state.value.requestSubmitting)
                model.submit(LanguageIntent.OnOpenRequestDialog)
                assertTrue(model.state.value.requestDialogVisible)
                assertEquals(requestBody, model.state.value.requestText, "busy reopen retains the pending draft")
                assertTrue(model.state.value.requestSubmitting, "reopen cannot authorize another active producer")
                model.submit(LanguageIntent.OnRequestTextChange("Edited draft while busy"))
                model.submit(LanguageIntent.OnSubmitRequest)
                assertSame(owned, feedback.operation)
                assertEquals(1, feedback.submissions.size)
                assertEquals(requestBody, model.state.value.requestText, "editing is ignored while busy")

                release.complete(Unit)
                runCurrent()
                val expectedEffects: List<LanguageEffect> = if (success) listOf(LanguageEffect.RequestSubmitted) else emptyList()
                assertEquals(expectedEffects, effects, "failure stays in the dialog, never an effect")
                assertEquals(!success, model.state.value.requestFailed)
                assertFalse(model.state.value.requestSubmitting)
                assertFalse(model.state.value.legacyRequestRetired)
                assertEquals(!success, model.state.value.requestDialogVisible)
                assertEquals(if (success) "" else requestBody, model.state.value.requestText)

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
                assertEquals(expectedEffects + expectedEffects, effects)
                assertFalse(model.state.value.requestSubmitting)
                assertEquals(!success, model.state.value.requestFailed)
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
            runCurrent() // No collector: success is buffered; failure stays only in the retained dialog.
            assertTrue(assertNotNull(feedback.operation).isCompleted)
            assertFalse(model.state.value.requestSubmitting)
            assertEquals(!success, model.state.value.requestDialogVisible)
            assertEquals(!success, model.state.value.requestFailed)
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
            assertTrue(effects.isEmpty(), "buffered success and stale intent results cannot reappear")
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

    @Test
    fun supportedList_isPopulatedSynchronously_andLoadingClearsOnFirstCodeEmission() = runTest {
        val languages = RecordingLanguageRepository()
        val vm = viewModel(languages = languages)

        assertEquals(listOf("en", "ar"), vm.state.value.languages.map { it.code }, "sync init read")
        assertTrue(vm.state.value.isLoading, "no selected-code emission yet")

        languages.selectedUpstream.tryEmit("ar")
        assertFalse(vm.state.value.isLoading)
        assertEquals("ar", vm.state.value.selectedCode)
    }

    @Test
    fun onSelectLanguage_dispatchesTheRepositoryWrite() = runTest {
        val languages = RecordingLanguageRepository()
        val vm = viewModel(languages = languages)

        vm.submit(LanguageIntent.OnSelectLanguage("de"))

        assertEquals(listOf("de"), languages.setCalls)
        assertEquals(
            "",
            vm.state.value.selectedCode,
            "mutate-and-re-emit: only the upstream re-emit moves the indicator",
        )
    }

    @Test
    fun submitRequest_success_pinsFeedbackWire_closesDialog_emitsSubmitted() = runTest {
        val feedback = GatedFeedbackRepository()
        val vm = viewModel(feedback = feedback)
        val effects = mutableListOf<LanguageEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(LanguageIntent.OnOpenRequestDialog)
        vm.submit(LanguageIntent.OnRequestTextChange("Please add Polish"))
        vm.submit(LanguageIntent.OnSubmitRequest)

        assertEquals(
            listOf(Triple(ComplaintType.LANGUAGES, "Languages", "Please add Polish")),
            feedback.submissions,
            "the pinned LANGUAGES type + \"Languages\" subject + typed body reach the repo",
        )
        assertFalse(vm.state.value.requestDialogVisible, "success closes the dialog")
        assertEquals("", vm.state.value.requestText, "success clears the buffer")
        assertFalse(vm.state.value.requestSubmitting)
        assertFalse(vm.state.value.requestFailed)
        assertEquals(listOf<LanguageEffect>(LanguageEffect.RequestSubmitted), effects)
        collector.cancel()
    }

    @Test
    fun submitRequest_failure_keepsDialogAndText_setsModalErrorWithoutEffect() = runTest {
        val feedback = GatedFeedbackRepository(result = Result.failure(RuntimeException("firestore boom")))
        val vm = viewModel(feedback = feedback)
        val effects = mutableListOf<LanguageEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(LanguageIntent.OnOpenRequestDialog)
        vm.submit(LanguageIntent.OnRequestTextChange("my typed request"))
        vm.submit(LanguageIntent.OnSubmitRequest)

        assertTrue(vm.state.value.requestDialogVisible, "failure preserves the dialog")
        assertEquals("my typed request", vm.state.value.requestText, "typed text kept for retry")
        assertFalse(vm.state.value.requestSubmitting, "the guard flag resets so retry can run")
        assertTrue(vm.state.value.requestFailed)
        assertTrue(effects.isEmpty(), "failure must not enqueue a snackbar behind the modal")
        vm.retireLegacyRequest()
        assertRetired(vm)
        assertTrue(effects.isEmpty(), "retirement clears the failed dialog without producing an outcome")
        collector.cancel()
    }

    @Test
    fun submitRequest_inFlight_blocksDuplicateDismissReopenAndEdits() = runTest {
        val feedback = GatedFeedbackRepository()
        feedback.gate = CompletableDeferred() // hold the first submit in flight
        val vm = viewModel(feedback = feedback)

        vm.submit(LanguageIntent.OnOpenRequestDialog)
        vm.submit(LanguageIntent.OnRequestTextChange("Please add Polish"))
        vm.submit(LanguageIntent.OnSubmitRequest)
        vm.submit(LanguageIntent.OnSubmitRequest) // double tap
        vm.submit(LanguageIntent.OnDismissRequestDialog)
        vm.submit(LanguageIntent.OnOpenRequestDialog)
        vm.submit(LanguageIntent.OnRequestTextChange("replacement draft"))

        assertEquals(1, feedback.submissions.size, "the in-flight guard must drop the second tap")
        assertTrue(vm.state.value.requestSubmitting)
        assertTrue(vm.state.value.requestDialogVisible, "pending request keeps its dialog")
        assertEquals("Please add Polish", vm.state.value.requestText, "pending draft cannot be replaced")
        assertFalse(vm.state.value.requestFailed)

        feedback.gate?.complete(Unit)
        assertFalse(vm.state.value.requestSubmitting)
        assertFalse(vm.state.value.requestDialogVisible)
    }

    @Test
    fun submitRequest_explicitRetries_clearErrorAndKeepDraft_untilSuccess() = runTest {
        val feedback = GatedFeedbackRepository(result = Result.failure(RuntimeException("request failed")))
        val vm = viewModel(feedback = feedback)
        val effects = mutableListOf<LanguageEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }
        val submission = Triple(ComplaintType.LANGUAGES, "Languages", "Please add Polish")

        vm.submit(LanguageIntent.OnOpenRequestDialog)
        vm.submit(LanguageIntent.OnRequestTextChange(submission.third))
        vm.submit(LanguageIntent.OnSubmitRequest)

        assertTrue(vm.state.value.requestFailed)
        assertEquals(listOf(submission), feedback.submissions, "no automatic retry on failure")
        assertTrue(effects.isEmpty())

        feedback.gate = CompletableDeferred()
        vm.submit(LanguageIntent.OnSubmitRequest) // same retained draft, explicit retry
        vm.submit(LanguageIntent.OnSubmitRequest) // ignored while retry is in flight

        assertTrue(vm.state.value.requestSubmitting)
        assertFalse(vm.state.value.requestFailed, "a new attempt clears the old error")
        assertEquals(submission.third, vm.state.value.requestText)
        assertEquals(listOf(submission, submission), feedback.submissions)

        feedback.gate?.complete(Unit) // a repeated failure remains local too
        assertTrue(vm.state.value.requestFailed)
        assertFalse(vm.state.value.requestSubmitting)
        assertTrue(vm.state.value.requestDialogVisible)
        assertEquals(submission.third, vm.state.value.requestText)
        assertEquals(2, feedback.submissions.size, "failure never retries on its own")
        assertTrue(effects.isEmpty())

        feedback.result = Result.success(Unit)
        feedback.gate = null
        vm.submit(LanguageIntent.OnSubmitRequest)

        assertEquals(listOf(submission, submission, submission), feedback.submissions)
        assertFalse(vm.state.value.requestDialogVisible)
        assertEquals("", vm.state.value.requestText)
        assertFalse(vm.state.value.requestSubmitting)
        assertFalse(vm.state.value.requestFailed)
        assertEquals(listOf<LanguageEffect>(LanguageEffect.RequestSubmitted), effects)
        collector.cancel()
    }

    @Test
    fun requestFailure_preservesEditsAndRepeatedOpen_untilExplicitDismissal() = runTest {
        val feedback = GatedFeedbackRepository(result = Result.failure(RuntimeException("request failed")))
        val vm = viewModel(feedback = feedback)

        vm.submit(LanguageIntent.OnOpenRequestDialog)
        vm.submit(LanguageIntent.OnRequestTextChange("Please add Polish"))
        vm.submit(LanguageIntent.OnSubmitRequest)
        vm.submit(LanguageIntent.OnRequestTextChange("Please add Polish and Czech"))
        vm.submit(LanguageIntent.OnOpenRequestDialog)

        assertTrue(vm.state.value.requestDialogVisible)
        assertTrue(vm.state.value.requestFailed, "editing is not a successful submission")
        assertEquals("Please add Polish and Czech", vm.state.value.requestText)
        assertEquals(1, feedback.submissions.size, "editing or opening is not resubmission")

        vm.submit(LanguageIntent.OnDismissRequestDialog)
        assertFalse(vm.state.value.requestDialogVisible)
        assertFalse(vm.state.value.requestFailed)
        assertEquals("", vm.state.value.requestText)

        vm.submit(LanguageIntent.OnOpenRequestDialog)
        assertTrue(vm.state.value.requestDialogVisible)
        assertFalse(vm.state.value.requestFailed, "a fresh dialog has no stale error")
        assertEquals("", vm.state.value.requestText)
    }

    @Test
    fun hiddenRequestDialog_ignoresTextChangesAndSubmission() = runTest {
        val feedback = GatedFeedbackRepository()
        val vm = viewModel(feedback = feedback)

        vm.submit(LanguageIntent.OnRequestTextChange("Please add Polish"))
        vm.submit(LanguageIntent.OnSubmitRequest)

        assertTrue(feedback.submissions.isEmpty())
        assertFalse(vm.state.value.requestDialogVisible)
        assertFalse(vm.state.value.requestSubmitting)
        assertFalse(vm.state.value.requestFailed)
        assertEquals("", vm.state.value.requestText)
    }

    private fun assertRetired(model: LanguageViewModel) {
        assertTrue(model.state.value.legacyRequestRetired)
        assertFalse(model.state.value.requestDialogVisible)
        assertEquals("", model.state.value.requestText)
        assertFalse(model.state.value.requestSubmitting)
        assertFalse(model.state.value.requestFailed)
    }

    private fun requestResult(success: Boolean): Result<Unit> =
        if (success) Result.success(Unit) else Result.failure(IllegalStateException("synthetic request failure"))
}
