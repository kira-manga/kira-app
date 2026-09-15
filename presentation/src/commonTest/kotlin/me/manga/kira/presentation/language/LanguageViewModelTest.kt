package me.manga.kira.presentation.language

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import kotlin.test.assertTrue

/**
 * Locks the [LanguageViewModel] picker + request-dialog contract (backlog T1):
 *  - the supported list is populated synchronously at construction; `isLoading` clears on the
 *    first selected-code emission,
 *  - [LanguageIntent.OnSelectLanguage] dispatches the repository write (the selected-row
 *    indicator then tracks the upstream re-emit),
 *  - the request flow pins the [FeedbackRepository] wire (type=LANGUAGES + "Languages" subject),
 *    success closes the dialog + emits [LanguageEffect.RequestSubmitted], failure keeps the
 *    dialog open with a non-leaking error flag and the draft preserved, and the in-flight guard
 *    prevents duplicate requests or replacement of the pending draft.
 */
class LanguageViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

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

    /** Feedback repo whose submit can be gated open (in-flight) and resolved on demand. */
    private class GatedFeedbackRepository(
        var result: Result<Unit> = Result.success(Unit),
    ) : FeedbackRepository {
        val submissions = mutableListOf<Triple<ComplaintType, String, String>>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun submit(type: ComplaintType, subject: String, body: String): Result<Unit> {
            submissions += Triple(type, subject, body)
            gate?.await()
            return result
        }
    }

    private fun viewModel(
        languages: RecordingLanguageRepository = RecordingLanguageRepository(),
        feedback: GatedFeedbackRepository = GatedFeedbackRepository(),
    ) = LanguageViewModel(
        GetSupportedLanguagesUseCase(languages),
        ObserveSelectedLanguageUseCase(languages),
        SetLanguageUseCase(languages),
        SendLanguageRequestUseCase(feedback),
    )

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
}
