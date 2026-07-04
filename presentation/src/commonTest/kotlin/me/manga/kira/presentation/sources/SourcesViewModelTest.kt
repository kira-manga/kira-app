package me.manga.kira.presentation.sources

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
import me.manga.kira.domain.model.sources.Source
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.domain.repository.SourcesRepository
import me.manga.kira.domain.usecase.feedback.SubmitFeedbackUseCase
import me.manga.kira.domain.usecase.sources.EnableDefaultLanguageSourcesUseCase
import me.manga.kira.domain.usecase.sources.ObserveSourcesUseCase
import me.manga.kira.domain.usecase.sources.SetLanguageEnabledUseCase
import me.manga.kira.domain.usecase.sources.SetSourceEnabledUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the [SourcesViewModel] toggle fan-out and the complaint-dialog contract (backlog T1):
 *  - source/language toggles dispatch to the matching repository writes,
 *  - complaint submit is re-entry guarded (a double-tap must not create two Firestore docs),
 *  - failure keeps the dialog OPEN (typed text preserved) and emits [SourcesEffect.RequestFailed]
 *    carrying the EXACT body so the snackbar Retry can re-submit it,
 *  - success closes the dialog and emits the payload-free [SourcesEffect.RequestSubmitted].
 */
class SourcesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun source(api: String, language: String = "en", enabled: Boolean = true) =
        Source(api = api, language = language, priority = 0, isEnabled = enabled)

    private class RecordingSourcesRepository(
        private val upstream: Flow<List<Source>>,
    ) : SourcesRepository {
        val calls = mutableListOf<String>()
        override fun observeSources(): Flow<List<Source>> = upstream
        override suspend fun setSourceEnabled(api: String, enabled: Boolean) {
            calls += "source:$api:$enabled"
        }
        override suspend fun setLanguageEnabled(language: String, enabled: Boolean) {
            calls += "language:$language:$enabled"
        }
        override suspend fun setLanguageEnabledWithFallback(primary: String, fallback: String, enabled: Boolean) {
            calls += "fallback:$primary:$fallback:$enabled"
        }
        override fun observeHasNewSources(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
        override suspend fun setHasNewSources(value: Boolean) {
            calls += "badge:$value"
        }
    }

    /** Feedback repo whose submit can be gated open (in-flight) and resolved on demand. */
    private class GatedFeedbackRepository(
        private val result: Result<Unit> = Result.success(Unit),
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
        upstream: Flow<List<Source>> = MutableSharedFlow(replay = 1),
        sources: RecordingSourcesRepository = RecordingSourcesRepository(upstream),
        feedback: GatedFeedbackRepository = GatedFeedbackRepository(),
    ) = SourcesViewModel(
        ObserveSourcesUseCase(sources),
        SetSourceEnabledUseCase(sources),
        SetLanguageEnabledUseCase(sources),
        SubmitFeedbackUseCase(feedback),
        EnableDefaultLanguageSourcesUseCase(sources),
    )

    @Test
    fun observeEmission_populatesItems_andClearsLoading() = runTest {
        val upstream = MutableSharedFlow<List<Source>>(replay = 1).apply {
            tryEmit(listOf(source("Azora"), source("TeamX", language = "ar")))
        }
        val vm = viewModel(upstream = upstream, sources = RecordingSourcesRepository(upstream))

        assertFalse(vm.state.value.isLoading)
        assertEquals(2, vm.state.value.items.size)
    }

    @Test
    fun toggles_dispatchToTheMatchingRepositoryWrite() = runTest {
        val upstream = MutableSharedFlow<List<Source>>(replay = 1)
        val sources = RecordingSourcesRepository(upstream)
        val vm = viewModel(upstream = upstream, sources = sources)

        vm.submit(SourcesIntent.OnToggleSource(source("Azora"), enabled = false))
        vm.submit(SourcesIntent.OnToggleLanguage("ar", enabled = true))

        assertEquals(listOf("source:Azora:false", "language:ar:true"), sources.calls)
    }

    @Test
    fun complaintSubmit_success_closesDialog_emitsPayloadFreeSubmitted() = runTest {
        val feedback = GatedFeedbackRepository()
        val vm = viewModel(feedback = feedback)
        val effects = mutableListOf<SourcesEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(SourcesIntent.OnOpenComplaintDialog)
        vm.submit(SourcesIntent.OnSubmitComplaint(body = "please add site X", subject = "Add Manga Site"))

        assertEquals(
            listOf(Triple(ComplaintType.SITES_ADD, "Add Manga Site", "please add site X")),
            feedback.submissions,
            "the pinned SITES_ADD type + the :ui-localized subject + the typed body reach the repo",
        )
        assertFalse(vm.state.value.complaintDialogOpen, "success closes the dialog")
        assertFalse(vm.state.value.isSubmittingComplaint)
        assertEquals(listOf<SourcesEffect>(SourcesEffect.RequestSubmitted), effects)
        collector.cancel()
    }

    @Test
    fun complaintSubmit_failure_keepsDialogOpen_emitsRequestFailedWithExactBody() = runTest {
        val feedback = GatedFeedbackRepository(result = Result.failure(RuntimeException("firestore boom")))
        val vm = viewModel(feedback = feedback)
        val effects = mutableListOf<SourcesEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(SourcesIntent.OnOpenComplaintDialog)
        vm.submit(SourcesIntent.OnSubmitComplaint(body = "my typed request", subject = "Add Manga Site"))

        assertTrue(vm.state.value.complaintDialogOpen, "failure preserves the dialog (typed text kept)")
        assertFalse(vm.state.value.isSubmittingComplaint, "the guard flag resets so Retry can run")
        assertEquals(
            listOf<SourcesEffect>(SourcesEffect.RequestFailed(body = "my typed request")),
            effects,
            "RequestFailed carries the EXACT body for the snackbar's Retry re-submit",
        )
        collector.cancel()
    }

    @Test
    fun complaintSubmit_reEntryGuard_doubleTapCreatesOneSubmission() = runTest {
        val feedback = GatedFeedbackRepository()
        feedback.gate = CompletableDeferred() // hold the first submit in flight
        val vm = viewModel(feedback = feedback)

        vm.submit(SourcesIntent.OnSubmitComplaint(body = "b", subject = "s"))
        vm.submit(SourcesIntent.OnSubmitComplaint(body = "b", subject = "s")) // double tap

        assertEquals(1, feedback.submissions.size, "the in-flight guard must drop the second tap")
        assertTrue(vm.state.value.isSubmittingComplaint)

        // While in flight, dismiss is also blocked (the user can't lose the pending submission).
        vm.submit(SourcesIntent.OnDismissComplaintDialog)
        // The dialog-open flag was never set in this test (submit direct), so assert via the guard:
        assertTrue(vm.state.value.isSubmittingComplaint)

        feedback.gate?.complete(Unit)
        assertFalse(vm.state.value.isSubmittingComplaint, "flag resets once the gated submit completes")
    }
}
