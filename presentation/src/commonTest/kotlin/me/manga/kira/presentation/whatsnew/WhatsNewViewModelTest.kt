package me.manga.kira.presentation.whatsnew

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.whatsnew.WhatsNewFeature
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Retains the one-shot load, retry and intent contracts while distinguishing typed failure,
 * successful empty content and cancelled work. Main and runTest share one scheduler; the store
 * owns every ViewModel and is cleared before Main is reset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhatsNewViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fixture = WhatsNewViewModelTestFixture()
    private val store = fixture.store
    private val viewModel = fixture::viewModel

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        try {
            store.clear()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun initLoad_populatesFeatures_andClearsLoading() =
        runTest(dispatcher) {
            val features = listOf(feature("Downloads"), feature("Reader"))
            val repo = FakeWhatsNewRepository { AppResult.Success(features) }
            val vm = viewModel(repo)
            runCurrent()

            assertFalse(vm.state.value.isLoading)
            assertEquals(features, vm.state.value.features)
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.hasLoadedSuccessfully)
            assertEquals(1, repo.loadCalls)
            assertEquals(0, repo.markSeenCalls, "Loading itself must not mark seen")
        }

    @Test
    fun throwingLoad_isAbsorbed_andSpinnerStillClears() =
        runTest(dispatcher) {
            val thrown = IllegalStateException("private remote failure detail")
            val repo = FakeWhatsNewRepository { throw thrown }
            val vm = viewModel(repo)
            runCurrent()

            assertFalse(vm.state.value.isLoading, "the finally must clear the spinner on a failed load")
            assertTrue(
                vm.state.value.features
                    .isEmpty(),
            )
            val error = assertIs<AppError.Unexpected>(vm.state.value.error)
            assertSame(thrown, error.cause)
            assertEquals(thrown::class.simpleName.orEmpty(), error.message)
            assertFalse(vm.state.value.hasLoadedSuccessfully)
            assertEquals(0, repo.markSeenCalls)
        }

    @Test
    fun onRetry_afterFailure_recoversWithTheFreshResult() =
        runTest(dispatcher) {
            val repo = FakeWhatsNewRepository { throw RuntimeException("first load fails") }
            val vm = viewModel(repo)
            runCurrent()
            assertIs<AppError.Unexpected>(vm.state.value.error)
            assertFalse(vm.state.value.hasLoadedSuccessfully)

            val retryResult = CompletableDeferred<AppResult<List<WhatsNewFeature>>>()
            repo.behavior = { retryResult.await() }
            vm.submit(WhatsNewIntent.OnRetry)
            runCurrent()
            assertTrue(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.hasLoadedSuccessfully)

            retryResult.complete(AppResult.Success(listOf(feature("Recovered"))))
            runCurrent()

            assertFalse(vm.state.value.isLoading)
            assertEquals(
                listOf("Recovered"),
                vm.state.value.features
                    .map { it.title },
            )
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.hasLoadedSuccessfully)
            assertEquals(2, repo.loadCalls)
        }

    @Test
    fun onMarkSeen_hitsTheRepositoryOnce() =
        runTest(dispatcher) {
            val repo = FakeWhatsNewRepository()
            val vm = viewModel(repo)

            vm.submit(WhatsNewIntent.OnMarkSeen)
            runCurrent()

            assertEquals(1, repo.markSeenCalls)
        }

    @Test
    fun onPageChanged_updatesCurrentPage() =
        runTest(dispatcher) {
            val vm = viewModel(FakeWhatsNewRepository())

            vm.submit(WhatsNewIntent.OnPageChanged(index = 2))
            runCurrent()

            assertEquals(2, vm.state.value.currentPage)
        }

    @Test
    fun onOpenVideo_emitsTheOneShotEffect() =
        runTest(dispatcher) {
            val vm = viewModel(FakeWhatsNewRepository())
            val effects = mutableListOf<WhatsNewEffect>()
            val collector = launch(dispatcher) { vm.effects.collect { effects += it } }
            try {
                vm.submit(WhatsNewIntent.OnOpenVideo("https://example.com/v.mp4"))
                runCurrent()

                assertEquals(listOf<WhatsNewEffect>(WhatsNewEffect.OpenVideo("https://example.com/v.mp4")), effects)
            } finally {
                collector.cancelAndJoin()
            }
        }

    @Test
    fun typedFailure_andSuccessfulEmpty_areDifferentStates() =
        runTest(dispatcher) {
            val error = AppError.Network.Http(statusCode = 503)
            val failedVm = viewModel(FakeWhatsNewRepository { AppResult.Failure(error) })
            val emptyVm = viewModel(FakeWhatsNewRepository())
            runCurrent()
            val failed = failedVm.state.value
            val empty = emptyVm.state.value

            assertFalse(failed.isLoading)
            assertTrue(failed.features.isEmpty())
            assertSame(error, failed.error)
            assertFalse(failed.hasLoadedSuccessfully)
            assertFalse(empty.isLoading)
            assertTrue(empty.features.isEmpty())
            assertNull(empty.error)
            assertTrue(empty.hasLoadedSuccessfully)
        }

    @Test
    fun retryDuringSuspendedInit_isSingleFlight_andCompletedFailureAllowsRetry() =
        runTest(dispatcher) {
            val initialResult = CompletableDeferred<AppResult<List<WhatsNewFeature>>>()
            val repo = FakeWhatsNewRepository { initialResult.await() }
            val vm = viewModel(repo)
            runCurrent()
            assertTrue(vm.state.value.isLoading)
            assertFalse(vm.state.value.hasLoadedSuccessfully)
            assertNull(vm.state.value.error)

            repeat(3) { vm.submit(WhatsNewIntent.OnRetry) }
            runCurrent()
            assertEquals(1, repo.loadCalls, "The guard must survive the init block")
            val error = AppError.Network.Timeout()
            initialResult.complete(AppResult.Failure(error))
            runCurrent()
            assertSame(error, vm.state.value.error)
            assertFalse(vm.state.value.isLoading)
            assertFalse(vm.state.value.hasLoadedSuccessfully)

            repo.behavior = { AppResult.Success(emptyList()) }
            vm.submit(WhatsNewIntent.OnRetry)
            runCurrent()
            assertEquals(2, repo.loadCalls)
            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.hasLoadedSuccessfully)
            assertEquals(0, repo.markSeenCalls)
        }

    @Test
    fun cancelledLoad_whileMounted_canRetryWithoutMarkingSeen() =
        runTest(dispatcher) {
            val repo = FakeWhatsNewRepository { throw CancellationException("cancel mounted fetch") }
            val vm = viewModel(repo)
            runCurrent()

            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.hasLoadedSuccessfully)
            assertEquals(0, repo.markSeenCalls)

            repo.behavior = { AppResult.Success(listOf(feature("Recovered"))) }
            vm.submit(WhatsNewIntent.OnRetry)
            runCurrent()

            assertEquals(2, repo.loadCalls)
            assertTrue(vm.state.value.hasLoadedSuccessfully)
            assertNull(vm.state.value.error)
            assertEquals(
                listOf("Recovered"),
                vm.state.value.features
                    .map { it.title },
            )
            assertEquals(0, repo.markSeenCalls)
        }

    @Test
    fun cancellingSuspendedLoad_doesNotPublishFailureOrSuccess() =
        runTest(dispatcher) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val repo =
                FakeWhatsNewRepository {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            val vm = viewModel(repo)
            started.await()
            assertTrue(vm.state.value.isLoading)

            store.clear()
            runCurrent()

            assertTrue(cancelled.isCompleted)
            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
            assertTrue(
                vm.state.value.features
                    .isEmpty(),
            )
            assertFalse(vm.state.value.hasLoadedSuccessfully)
            assertEquals(0, repo.markSeenCalls)
        }

    @Test
    fun cancellationUnawareLoad_cannotPublishALateSuccess() =
        runTest(dispatcher) {
            var cancellationObserved = false
            val repo =
                FakeWhatsNewRepository {
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        // Deliberately violate the data boundary to exercise the VM's active-job check.
                        cancellationObserved = true
                        AppResult.Success(listOf(feature("Late result")))
                    }
                }
            val vm = viewModel(repo)
            runCurrent()
            assertTrue(vm.state.value.isLoading)

            store.clear()
            runCurrent()

            assertTrue(cancellationObserved)
            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
            assertTrue(
                vm.state.value.features
                    .isEmpty(),
            )
            assertFalse(vm.state.value.hasLoadedSuccessfully)
            assertEquals(0, repo.markSeenCalls)
        }
}
