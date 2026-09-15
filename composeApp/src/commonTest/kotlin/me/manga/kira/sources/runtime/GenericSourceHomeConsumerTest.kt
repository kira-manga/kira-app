package me.manga.kira.sources.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.error.AppError
import me.manga.kira.presentation.home.HomeEffect
import me.manga.kira.presentation.home.HomeIntent
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
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var failProvider = false
            val provider =
                SourceBaseUrlProvider {
                    if (failProvider) {
                        entered.complete(Unit)
                        release.await()
                        throw IllegalStateException("synthetic provider unavailable")
                    }
                    null
                }
            val http = EngineConsumerTestFixtures.homeHttp()
            withEngineConsumerHome(EngineConsumerTestFixtures.registry(http, provider)) { viewModel ->
                val effects = mutableListOf<HomeEffect>()
                val collector =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        viewModel.effects.collect { effects += it }
                    }
                try {
                    viewModel.submit(HomeIntent.OnEnter)
                    runCurrent()
                    assertEquals(listOf("Home title"), viewModel.state.value.feed.map { it.title })
                    assertEquals(listOf("Home title"), viewModel.state.value.featured.map { it.title })
                    assertFalse(viewModel.state.value.isFeedLoading)
                    assertNull(viewModel.state.value.feedError)
                    assertTrue(effects.isEmpty())
                    val initialRequests = http.requestedUrls.toList()
                    assertEquals(
                        listOf(EngineConsumerTestFixtures.HOME_URL, EngineConsumerTestFixtures.FEATURED_URL).sorted(),
                        initialRequests.sorted(),
                    )

                    failProvider = true
                    viewModel.submit(HomeIntent.OnRefresh)
                    runCurrent()
                    assertTrue(entered.isCompleted, "The actual provider must be reached before testing loading")
                    assertTrue(viewModel.state.value.isFeedLoading)
                    assertTrue(viewModel.state.value.isRefreshing)

                    release.complete(Unit)
                    runCurrent()

                    assertFalse(viewModel.state.value.isFeedLoading)
                    assertFalse(viewModel.state.value.isRefreshing)
                    val error = assertIs<AppError.Unexpected>(viewModel.state.value.feedError)
                    assertEquals("generic source error (IllegalStateException)", error.message)
                    assertTrue(effects.any { it is HomeEffect.ShowError && it.error == error })
                    assertEquals(initialRequests, http.requestedUrls, "Failed preparation must not issue HTTP")
                } finally {
                    withContext(NonCancellable) { withTimeout(5_000) { collector.cancelAndJoin() } }
                }
            }
        }
}
