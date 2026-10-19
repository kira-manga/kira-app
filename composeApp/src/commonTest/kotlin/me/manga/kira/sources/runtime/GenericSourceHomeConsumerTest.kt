package me.manga.kira.sources.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.error.AppError
import me.manga.kira.presentation.home.HomeEffect
import me.manga.kira.presentation.home.HomeIntent
import me.manga.kira.presentation.home.HomeViewModel
import me.manga.kira.sources.contracts.SourceBaseUrlProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GenericSourceHomeConsumerTest {
    @Test
    fun providerFailureOnRefresh_clearsLoadingAndSetsTypedError() =
        runTest {
            val gate = ConsumerProviderGate()
            val http = EngineConsumerTestFixtures.homeHttp()
            withEngineConsumerHome(EngineConsumerTestFixtures.registry(http, gate.provider)) { viewModel ->
                exerciseRefresh(viewModel, http, gate)
            }
        }

    private suspend fun TestScope.exerciseRefresh(
        viewModel: HomeViewModel,
        http: EngineConsumerHttp,
        gate: ConsumerProviderGate,
    ) {
        val effects = mutableListOf<HomeEffect>()
        val collector =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.effects.collect { effects += it }
            }
        val scenario = ConsumerHomeRefreshScenario(viewModel, http, gate, effects)
        try {
            scenario.assertInitialLoad()
            scenario.holdFailingRefresh()
            gate.release.complete(Unit)
            scenario.awaitFailure()
            runCurrent()
            scenario.assertFailure()
        } finally {
            withContext(NonCancellable) { withTimeout(5_000) { collector.cancelAndJoin() } }
        }
    }
}

private class ConsumerProviderGate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var fail = false
    val provider =
        SourceBaseUrlProvider {
            if (fail) {
                entered.complete(Unit)
                release.await()
                throw IllegalStateException("synthetic provider unavailable")
            }
            null
        }
}

private class ConsumerHomeRefreshScenario(
    private val viewModel: HomeViewModel,
    private val http: EngineConsumerHttp,
    private val gate: ConsumerProviderGate,
    private val effects: List<HomeEffect>,
) {
    private var initialRequests = emptyList<String>()

    suspend fun assertInitialLoad() {
        viewModel.submit(HomeIntent.OnEnter)
        // Source selection uses a real IO flow; await its result, not one test-scheduler tick.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                viewModel.state.first { it.feed.isNotEmpty() && it.featured.isNotEmpty() && !it.isFeedLoading }
            }
        }
        assertEquals(listOf("Home title"), viewModel.state.value.feed.map { it.title })
        assertEquals(listOf("Home title"), viewModel.state.value.featured.map { it.title })
        assertFalse(viewModel.state.value.isFeedLoading)
        assertNull(viewModel.state.value.feedError)
        assertTrue(effects.isEmpty())
        initialRequests = http.requestedUrls.toList()
        assertEquals(
            listOf(EngineConsumerTestFixtures.HOME_URL, EngineConsumerTestFixtures.FEATURED_URL).sorted(),
            initialRequests.sorted(),
        )
    }

    suspend fun holdFailingRefresh() {
        gate.fail = true
        viewModel.submit(HomeIntent.OnRefresh)
        withContext(Dispatchers.Default) { withTimeout(5_000) { gate.entered.await() } }
        assertTrue(gate.entered.isCompleted, "The actual provider must be reached before testing loading")
        assertTrue(viewModel.state.value.isFeedLoading)
        assertTrue(viewModel.state.value.isRefreshing)
    }

    suspend fun awaitFailure() {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                viewModel.state.first { it.feedError != null && !it.isFeedLoading && !it.isRefreshing }
            }
        }
    }

    fun assertFailure() {
        assertFalse(viewModel.state.value.isFeedLoading)
        assertFalse(viewModel.state.value.isRefreshing)
        val error = assertIs<AppError.Unexpected>(viewModel.state.value.feedError)
        assertEquals("generic source error (IllegalStateException)", error.message)
        assertTrue(effects.any { it is HomeEffect.ShowError && it.error == error })
        assertEquals(initialRequests, http.requestedUrls, "Failed preparation must not issue HTTP")
    }
}
