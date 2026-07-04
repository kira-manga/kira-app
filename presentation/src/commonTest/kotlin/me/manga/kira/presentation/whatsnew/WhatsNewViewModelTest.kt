package me.manga.kira.presentation.whatsnew

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.whatsnew.MediaType
import me.manga.kira.domain.model.whatsnew.WhatsNewFeature
import me.manga.kira.domain.repository.WhatsNewRepository
import me.manga.kira.domain.usecase.whatsnew.GetWhatsNewFeaturesUseCase
import me.manga.kira.domain.usecase.whatsnew.MarkWhatsNewSeenUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the [WhatsNewViewModel] one-shot load contract (backlog T1):
 *  - the `init {}` load populates [WhatsNewState.features] and clears the spinner,
 *  - a throwing load is absorbed by `launchSafely` (no crash) and the `finally` still clears
 *    the spinner — the screen degrades to the empty placeholder instead of an infinite spinner,
 *  - [WhatsNewIntent.OnRetry] re-runs the load (recovering after a failure),
 *  - [WhatsNewIntent.OnMarkSeen] hits the repository once; [WhatsNewIntent.OnPageChanged] is a
 *    pure state mutation; [WhatsNewIntent.OnOpenVideo] emits the one-shot effect.
 */
class WhatsNewViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun feature(title: String) = WhatsNewFeature(
        title = title,
        description = "desc",
        mediaType = MediaType.IMAGE,
    )

    private class FakeWhatsNewRepository(
        var behavior: () -> List<WhatsNewFeature>,
    ) : WhatsNewRepository {
        var markSeenCalls = 0
        override suspend fun getFeatures(): List<WhatsNewFeature> = behavior()
        override suspend fun markSeen() {
            markSeenCalls++
        }
    }

    private fun viewModel(repo: FakeWhatsNewRepository) = WhatsNewViewModel(
        GetWhatsNewFeaturesUseCase(repo),
        MarkWhatsNewSeenUseCase(repo),
    )

    @Test
    fun initLoad_populatesFeatures_andClearsLoading() = runTest {
        val repo = FakeWhatsNewRepository(behavior = { listOf(feature("Downloads"), feature("Reader")) })
        val vm = viewModel(repo)

        assertFalse(vm.state.value.isLoading)
        assertEquals(listOf("Downloads", "Reader"), vm.state.value.features.map { it.title })
    }

    @Test
    fun throwingLoad_isAbsorbed_andSpinnerStillClears() = runTest {
        val repo = FakeWhatsNewRepository(behavior = { throw RuntimeException("remote boom") })
        val vm = viewModel(repo)

        assertFalse(vm.state.value.isLoading, "the finally must clear the spinner on a failed load")
        assertTrue(vm.state.value.features.isEmpty(), "degrades to the empty placeholder, no crash")
    }

    @Test
    fun onRetry_afterFailure_recoversWithTheFreshResult() = runTest {
        val repo = FakeWhatsNewRepository(behavior = { throw RuntimeException("first load fails") })
        val vm = viewModel(repo)
        assertTrue(vm.state.value.features.isEmpty())

        repo.behavior = { listOf(feature("Recovered")) }
        vm.submit(WhatsNewIntent.OnRetry)

        assertFalse(vm.state.value.isLoading)
        assertEquals(listOf("Recovered"), vm.state.value.features.map { it.title })
    }

    @Test
    fun onMarkSeen_hitsTheRepositoryOnce() = runTest {
        val repo = FakeWhatsNewRepository(behavior = { emptyList() })
        val vm = viewModel(repo)

        vm.submit(WhatsNewIntent.OnMarkSeen)

        assertEquals(1, repo.markSeenCalls)
    }

    @Test
    fun onPageChanged_updatesCurrentPage() = runTest {
        val vm = viewModel(FakeWhatsNewRepository(behavior = { emptyList() }))

        vm.submit(WhatsNewIntent.OnPageChanged(index = 2))

        assertEquals(2, vm.state.value.currentPage)
    }

    @Test
    fun onOpenVideo_emitsTheOneShotEffect() = runTest {
        val vm = viewModel(FakeWhatsNewRepository(behavior = { emptyList() }))
        val effects = mutableListOf<WhatsNewEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(WhatsNewIntent.OnOpenVideo("https://example.com/v.mp4"))

        assertEquals(listOf<WhatsNewEffect>(WhatsNewEffect.OpenVideo("https://example.com/v.mp4")), effects)
        collector.cancel()
    }
}
