package me.manga.kira.presentation.sources

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import kotlin.test.assertTrue

/** Port-call/lifecycle regressions only; atomic persistence is covered by the data-layer Room tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourcesViewModelLanguageToggleTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun rapidLanguageSeedAndSingleIntentsCompleteInControlledAdmissionOrder() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = GatedSourcesRepository(gate)
            val vm = viewModel(repository)
            val store = ViewModelStore().apply { put("sources", vm) }
            try {
                submitConflictingIntents(vm)
                runCurrent()
                assertEquals(expectedCalls, repository.calls)
                assertEquals(expectedCalls.take(1), repository.admitted)
                assertTrue(repository.completedCalls.isEmpty())

                gate.complete(Unit)
                runCurrent()
                assertEquals(expectedCalls, repository.completedCalls)
                assertTrue(repository.jobs.all { it.isCompleted && !it.isCancelled })
            } finally {
                store.clear()
                gate.complete(Unit)
                runCurrent()
            }
        }

    @Test
    fun ordinaryPersistenceFailuresAreContainedForAllThreeMutationPaths() =
        runTest {
            val repository = GatedSourcesRepository().apply { failure = IllegalStateException("write failed") }
            val vm = viewModel(repository)
            val store = ViewModelStore().apply { put("sources", vm) }
            try {
                submitConflictingIntents(vm)
                runCurrent()
                assertEquals(expectedCalls, repository.calls)
                assertTrue(repository.completedCalls.isEmpty())
                assertTrue(
                    repository.jobs.all { it.isCompleted && !it.isCancelled },
                    "launchSafely contains SQL errors",
                )

                repository.failure = null
                vm.submit(SourcesIntent.OnToggleLanguage("(EN)", true))
                runCurrent()
                assertEquals(listOf("language:(EN):true"), repository.completedCalls)
            } finally {
                store.clear()
                runCurrent()
            }
        }

    @Test
    fun cancellationExceptionsPropagateInsteadOfCompletingMutationJobsNormally() =
        runTest {
            val repository = GatedSourcesRepository().apply { failure = CancellationException("cancelled write") }
            val vm = viewModel(repository)
            val store = ViewModelStore().apply { put("sources", vm) }
            try {
                submitConflictingIntents(vm)
                runCurrent()
                assertEquals(expectedCalls, repository.calls)
                assertEquals(expectedCalls, repository.cancelledCalls)
                assertTrue(repository.jobs.all { it.isCancelled })
                assertTrue(repository.completedCalls.isEmpty())
            } finally {
                store.clear()
                runCurrent()
            }
        }

    @Test
    fun clearingTheViewModelCancelsAdmittedAndWaitingCallsBeforeTheyCanWrite() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = GatedSourcesRepository(gate)
            val vm = viewModel(repository)
            val store = ViewModelStore().apply { put("sources", vm) }
            try {
                submitConflictingIntents(vm)
                runCurrent()
                assertEquals(expectedCalls, repository.calls)
                assertEquals(expectedCalls.take(1), repository.admitted)

                store.clear()
                runCurrent()
                assertEquals(expectedCalls.toSet(), repository.cancelledCalls.toSet())
                assertTrue(repository.jobs.all { it.isCancelled })
                gate.complete(Unit)
                vm.submit(SourcesIntent.OnToggleLanguage("(EN)", true))
                runCurrent()
                assertEquals(expectedCalls, repository.calls)
                assertTrue(
                    repository.completedCalls.isEmpty(),
                    "The released pre-write gate must not allow a later write",
                )
            } finally {
                store.clear()
                gate.complete(Unit)
                runCurrent()
            }
        }

    private fun submitConflictingIntents(vm: SourcesViewModel) {
        vm.submit(SourcesIntent.OnToggleLanguage("(EN)", false))
        vm.submit(SourcesIntent.OnSeedDefaultLanguage("zz"))
        vm.submit(SourcesIntent.OnToggleSource(Source("EnglishOne", "(EN)", 0, true), false))
    }

    private fun viewModel(repository: SourcesRepository): SourcesViewModel {
        val access = ActivatedSourceAccess()
        return SourcesViewModel(
            ObserveSourcesUseCase(repository),
            SetSourceEnabledUseCase(repository, access),
            SetLanguageEnabledUseCase(repository, access),
            SubmitFeedbackUseCase(UnusedFeedbackRepository),
            EnableDefaultLanguageSourcesUseCase(repository, access),
        )
    }

    private class ActivatedSourceAccess : SourceAccessRepository {
        override val state = MutableStateFlow(SourceAccessState.ACTIVATED)

        override suspend fun activatePermanently(): Boolean = false
    }

    private object UnusedFeedbackRepository : FeedbackRepository {
        override suspend fun submit(
            type: ComplaintType,
            subject: String,
            body: String,
        ): Result<Unit> = error("Toggle tests must not submit feedback")
    }

    /** A gate BEFORE the simulated write. Already-committed Room writes have different cancellation semantics. */
    private class GatedSourcesRepository(
        private val gate: CompletableDeferred<Unit>? = null,
    ) : SourcesRepository {
        private val admission = Mutex()
        val calls = mutableListOf<String>()
        val admitted = mutableListOf<String>()
        val completedCalls = mutableListOf<String>()
        val cancelledCalls = mutableListOf<String>()
        val jobs = mutableListOf<Job>()
        var failure: Exception? = null

        override fun observeSources(): Flow<List<Source>> = flowOf(emptyList())

        override suspend fun setSourceEnabled(
            api: String,
            enabled: Boolean,
        ) = record("source:$api:$enabled")

        override suspend fun setLanguageEnabled(
            language: String,
            enabled: Boolean,
        ) = record("language:$language:$enabled")

        override suspend fun setLanguageEnabledWithFallback(
            primary: String,
            fallback: String,
            enabled: Boolean,
        ) = record("fallback:$primary:$fallback:$enabled")

        override fun observeHasNewSources(): Flow<Boolean> = flowOf(false)

        override suspend fun setHasNewSources(value: Boolean) = Unit

        private suspend fun record(call: String) {
            calls += call
            jobs += currentCoroutineContext().job
            try {
                admission.withLock {
                    admitted += call
                    gate?.await()
                    failure?.let { throw it }
                    completedCalls += call
                }
            } catch (cancelled: CancellationException) {
                cancelledCalls += call
                throw cancelled
            }
        }
    }

    private companion object {
        val expectedCalls = listOf("language:(EN):false", "fallback:(ZZ):(EN):true", "source:EnglishOne:false")
    }
}
