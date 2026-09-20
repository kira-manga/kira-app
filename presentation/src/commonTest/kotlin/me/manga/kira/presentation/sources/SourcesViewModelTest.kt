package me.manga.kira.presentation.sources

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import me.manga.kira.domain.model.sources.Source
import me.manga.kira.domain.model.sources.SourceAccessState
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.domain.repository.SourceAccessRepository
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Source controls plus three grouped legacy request cases: exact-body Retry, queued/started
 * retirement and buffered-effect consumer reattachment. No Compose/remount/snackbar UI proof.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourcesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val models = mutableListOf<SourcesViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

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

    private class FakeSourceAccessRepository(activated: Boolean) : SourceAccessRepository {
        override val state = MutableStateFlow(
            if (activated) SourceAccessState.ACTIVATED else SourceAccessState.LOCKED,
        )

        override suspend fun activatePermanently(): Boolean = false
    }

    /** Feedback repo whose submit can be gated open (in-flight) and resolved on demand. */
    private class GatedFeedbackRepository(
        var result: Result<Unit> = Result.success(Unit),
    ) : FeedbackRepository {
        val submissions = mutableListOf<Triple<ComplaintType, String, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        var returned = 0
        var operation: Job? = null
        override suspend fun submit(type: ComplaintType, subject: String, body: String): Result<Unit> {
            submissions += Triple(type, subject, body)
            operation = currentCoroutineContext().job
            if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
            returned++
            return result
        }
    }

    private fun viewModel(
        upstream: Flow<List<Source>> = MutableSharedFlow(replay = 1),
        sources: RecordingSourcesRepository = RecordingSourcesRepository(upstream),
        feedback: GatedFeedbackRepository = GatedFeedbackRepository(),
        activated: Boolean = true,
    ) = SourcesViewModel(
        ObserveSourcesUseCase(sources),
        SetSourceEnabledUseCase(sources, FakeSourceAccessRepository(activated)),
        SetLanguageEnabledUseCase(sources, FakeSourceAccessRepository(activated)),
        SubmitFeedbackUseCase(feedback),
        EnableDefaultLanguageSourcesUseCase(sources, FakeSourceAccessRepository(activated)),
    ).also { models += it }

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
    fun locked_state_drops_toggle_intents_without_modifying_sources() = runTest {
        val upstream = MutableSharedFlow<List<Source>>(replay = 1)
        val sources = RecordingSourcesRepository(upstream)
        val vm = viewModel(upstream = upstream, sources = sources, activated = false)

        vm.submit(SourcesIntent.OnToggleSource(source("Azora"), enabled = false))
        vm.submit(SourcesIntent.OnToggleLanguage("ar", enabled = true))
        vm.submit(SourcesIntent.OnSeedDefaultLanguage("ar"))

        assertTrue(sources.calls.isEmpty())
    }

    @Test
    fun complaintSubmit_success_closesDialog_emitsPayloadFreeSubmitted() = runTest {
        val feedback = GatedFeedbackRepository(result = Result.failure(RuntimeException("synthetic failure")))
        val vm = viewModel(feedback = feedback)
        val effects = mutableListOf<SourcesEffect>()
        backgroundScope.launch(dispatcher) { vm.screenEffects.collect { effects += it } }
        val submit = SourcesIntent.OnSubmitComplaint(body = "please add site X", subject = "Add Manga Site")
        vm.submit(SourcesIntent.OnOpenComplaintDialog)
        vm.submit(submit)
        assertTrue(vm.state.value.complaintDialogOpen, "failure preserves the dialog")
        assertFalse(vm.state.value.isSubmittingComplaint, "the guard resets so legacy Retry can run")
        val failed = assertIs<SourcesEffect.RequestFailed>(effects.single())
        assertEquals(submit.body, failed.body)
        feedback.result = Result.success(Unit)
        // Same intent/body path as Sources Retry, not an automated snackbar click.
        vm.submit(submit.copy(body = failed.body))
        val expected = Triple(ComplaintType.SITES_ADD, submit.subject, submit.body)
        assertEquals(
            listOf(expected, expected),
            feedback.submissions,
            "initial submit and Retry forward the pinned type, localized subject and exact original body",
        )
        assertFalse(vm.state.value.complaintDialogOpen, "success closes the dialog")
        assertFalse(vm.state.value.isSubmittingComplaint)
        assertFalse(vm.state.value.legacyRequestRetired)
        assertEquals(listOf(failed, SourcesEffect.RequestSubmitted), effects)
    }

    @Test
    fun complaintSubmit_failure_keepsDialogOpen_emitsRequestFailedWithExactBody() = runTest {
        val feedback = GatedFeedbackRepository(result = Result.failure(RuntimeException("firestore boom")))
        val upstream = MutableStateFlow(emptyList<Source>())
        val sources = RecordingSourcesRepository(upstream)
        val vm = viewModel(upstream, sources, feedback)
        val submit = SourcesIntent.OnSubmitComplaint(body = "my typed request", subject = "Add Manga Site")
        val savedOpen = { vm.submit(SourcesIntent.OnOpenComplaintDialog) }
        val savedRetry = { vm.submit(submit) }
        savedOpen()
        savedRetry()
        runCurrent() // Complete failure before any collector: the real effect is buffered.
        assertTrue(vm.state.value.complaintDialogOpen, "failure preserves the dialog (typed text kept)")
        assertFalse(vm.state.value.isSubmittingComplaint, "the guard flag resets so Retry can run")
        vm.retireLegacyRequest()
        vm.retireLegacyRequest()
        savedOpen()
        savedRetry()
        vm.submit(SourcesIntent.OnDismissComplaintDialog)
        repeat(2) {
            val effects = mutableListOf<SourcesEffect>()
            val collector = backgroundScope.launch(dispatcher) { vm.screenEffects.collect { effects += it } }
            runCurrent()
            assertTrue(collector.isActive)
            assertTrue(effects.isEmpty(), "retirement drops buffered feedback across consumer reattachment")
            collector.cancel()
        }
        upstream.value = listOf(source("Azora"))
        vm.submit(SourcesIntent.OnToggleSource(source("Azora"), enabled = false))
        vm.submit(SourcesIntent.OnToggleLanguage("ar", enabled = true))
        assertEquals(upstream.value, vm.state.value.items)
        assertEquals(listOf("source:Azora:false", "language:ar:true"), sources.calls)
        assertRetired(vm)
        assertEquals(1, feedback.submissions.size, "saved legacy actions cannot start another request")
        val fresh = viewModel()
        val freshEffects = mutableListOf<SourcesEffect>()
        backgroundScope.launch(dispatcher) { fresh.screenEffects.collect { freshEffects += it } }
        fresh.submit(submit)
        assertFalse(fresh.state.value.legacyRequestRetired)
        assertEquals(listOf<SourcesEffect>(SourcesEffect.RequestSubmitted), freshEffects)
    }

    @Test
    fun complaintSubmit_reEntryGuard_doubleTapCreatesOneSubmission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val submit = SourcesIntent.OnSubmitComplaint(body = "b", subject = "s")
        for (retireOnBusy in listOf(false, true)) {
            val feedback = GatedFeedbackRepository()
            val vm = viewModel(feedback = feedback)
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.state.collect { if (retireOnBusy && it.isSubmittingComplaint) vm.retireLegacyRequest() }
            }
            vm.submit(SourcesIntent.OnOpenComplaintDialog)
            vm.submit(submit)
            if (!retireOnBusy) vm.retireLegacyRequest()
            runCurrent()
            observer.cancel()
            assertRetired(vm)
            assertTrue(feedback.submissions.isEmpty(), "retired before intent or inner provider launch")
        }
        for (result in listOf(Result.success(Unit), Result.failure(RuntimeException("synthetic failure")))) {
            val release = CompletableDeferred<Unit>()
            val feedback = GatedFeedbackRepository(result).apply {
                gate = release
                ignoreCancellation = true
            }
            val vm = viewModel(feedback = feedback)
            val effects = mutableListOf<SourcesEffect>()
            // Raw stream: consumer filtering must not hide a late producer emission.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
            try {
                vm.submit(SourcesIntent.OnOpenComplaintDialog)
                vm.submit(submit)
                vm.submit(submit)
                runCurrent()
                assertEquals(1, feedback.submissions.size, "the in-flight guard drops the second tap")
                vm.submit(SourcesIntent.OnDismissComplaintDialog)
                runCurrent()
                assertTrue(vm.state.value.isSubmittingComplaint)
                assertTrue(vm.state.value.complaintDialogOpen, "busy dismissal stays blocked")
                vm.retireLegacyRequest()
                assertRetired(vm)
                assertTrue(assertNotNull(feedback.operation).isCancelled)
                release.complete(Unit)
                runCurrent()
                assertEquals(1, feedback.returned, "provider deliberately returns despite cancellation")
                assertRetired(vm)
                assertTrue(effects.isEmpty(), "late success/failure is silent at the raw producer")
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
    }

    private fun assertRetired(vm: SourcesViewModel) {
        assertTrue(vm.state.value.legacyRequestRetired)
        assertFalse(vm.state.value.complaintDialogOpen)
        assertFalse(vm.state.value.isSubmittingComplaint)
    }
}
