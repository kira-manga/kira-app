package me.manga.kira.presentation.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.SourcesRepository
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.home.FetchFeaturedUseCase
import me.manga.kira.domain.usecase.home.FetchHomeFeedUseCase
import me.manga.kira.domain.usecase.home.FetchMoreHomeFeedUseCase
import me.manga.kira.domain.usecase.home.ObserveActiveTabIndexUseCase
import me.manga.kira.domain.usecase.home.ObserveSiteStateUseCase
import me.manga.kira.domain.usecase.home.ObserveSourceTabsUseCase
import me.manga.kira.domain.usecase.home.SelectSourceTabUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.ToggleInLibraryUseCase
import me.manga.kira.domain.usecase.sources.ClearNewSourcesBadgeUseCase
import me.manga.kira.domain.usecase.sources.ObserveNewSourcesBadgeUseCase
import me.manga.kira.presentation.testing.FakeHomeFeedRepository
import me.manga.kira.presentation.testing.FakeLibraryRepository
import me.manga.kira.presentation.testing.sampleFeedItem
import me.manga.kira.presentation.testing.sampleSourceTab
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioural reducer tests for `HomeViewModel`. Driven through the real MVI `submit(intent)`
 * surface against hand fakes. `UnconfinedTestDispatcher` installed as Main for eager execution
 * (the VM injects no DispatcherProvider — same harness as `LibraryViewModelApplyViewTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var homeRepo: FakeHomeFeedRepository
    private lateinit var libraryRepo: FakeLibraryRepository
    private lateinit var detailsRepo: FakeMangaDetailsRepository
    private lateinit var badgeRepo: FakeSourcesBadgeRepository

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(): HomeViewModel {
        homeRepo = FakeHomeFeedRepository()
        homeRepo.sourceTabs.value = listOf(sampleSourceTab(api = "a"), sampleSourceTab(api = "b"))
        libraryRepo = FakeLibraryRepository()
        detailsRepo = FakeMangaDetailsRepository()
        badgeRepo = FakeSourcesBadgeRepository()
        return HomeViewModel(
            observeSourceTabs = ObserveSourceTabsUseCase(homeRepo),
            observeActiveTabIndex = ObserveActiveTabIndexUseCase(homeRepo),
            observeSiteState = ObserveSiteStateUseCase(homeRepo),
            selectSourceTab = SelectSourceTabUseCase(homeRepo),
            fetchHomeFeed = FetchHomeFeedUseCase(homeRepo),
            fetchMoreHomeFeed = FetchMoreHomeFeedUseCase(homeRepo),
            fetchFeatured = FetchFeaturedUseCase(homeRepo),
            observeLibrary = ObserveLibraryUseCase(libraryRepo),
            toggleInLibrary = ToggleInLibraryUseCase(libraryRepo),
            fetchDetails = FetchMangaDetailsUseCase(detailsRepo),
            observeNewSourcesBadge = ObserveNewSourcesBadgeUseCase(badgeRepo),
            clearNewSourcesBadge = ClearNewSourcesBadgeUseCase(badgeRepo),
        )
    }

    @Test
    fun tabSwitch_resetsFeed_andRefetches() =
        runTest {
            val vm = vm()
            // First source's feed.
            homeRepo.homePages.addLast(AppResult.Success(listOf(sampleFeedItem(title = "FromA"))))
            vm.submit(HomeIntent.OnEnter)
            assertEquals(
                listOf("FromA"),
                vm.state.value.feed
                    .map { it.title },
            )

            // Switching to tab 1 must reset then refetch — queue the new source's feed.
            homeRepo.homePages.addLast(AppResult.Success(listOf(sampleFeedItem(title = "FromB"))))
            vm.submit(HomeIntent.OnTabSelected(1))

            val s = vm.state.value
            assertEquals(1, s.activeTabIndex)
            assertEquals(listOf("FromB"), s.feed.map { it.title }, "feed replaced with new source's items")
            assertEquals(1, s.page, "page cursor reset on tab switch")
            // selectTab + at least one fetchHome for each source.
            assertTrue(homeRepo.calls.contains("selectTab(1)"))
            assertEquals(2, homeRepo.calls.count { it.startsWith("fetchHome") }, "one fetch per source")
        }

    @Test
    fun onEndReached_guardsAgainstDoubleLoad() =
        runTest {
            val vm = vm()
            homeRepo.homePages.addLast(AppResult.Success(listOf(sampleFeedItem(title = "P1"))))
            vm.submit(HomeIntent.OnEnter)
            assertEquals(1, vm.state.value.page)

            // Gate fetchMore so the first page-load stays in-flight (isLoadingNextPage = true) while a
            // second OnEndReached arrives — the only way to exercise the isLoadingNextPage clause of the
            // guard, which the eager Unconfined dispatcher would otherwise never hit (the first load would
            // already have completed before the second submit).
            val gate = CompletableDeferred<Unit>()
            homeRepo.fetchMoreGate = gate
            homeRepo.moreResult = AppResult.Success(listOf(sampleFeedItem(title = "P2")))

            vm.submit(HomeIntent.OnEndReached) // records fetchMore(2), then suspends on the gate
            assertTrue(vm.state.value.isLoadingNextPage, "first page-load is in flight")

            vm.submit(HomeIntent.OnEndReached) // must be dropped by the in-flight guard, no second fetch

            // Only one fetchMore call so far — the second OnEndReached was rejected while loading.
            assertEquals(
                listOf("fetchMore(2)"),
                homeRepo.calls.filter { it.startsWith("fetchMore") },
                "second OnEndReached while a load is in flight must not start another fetch",
            )

            gate.complete(Unit) // let the first load finish

            val s = vm.state.value
            assertEquals(listOf("P1", "P2"), s.feed.map { it.title })
            assertFalse(s.isLoadingNextPage)
            assertEquals(2, s.page, "page advanced exactly once")
            // Still exactly one fetchMore — no duplicate page-2 load resulted from the guarded second call.
            assertEquals(
                listOf("fetchMore(2)"),
                homeRepo.calls.filter { it.startsWith("fetchMore") },
                "no double-load of the same page",
            )
        }

    @Test
    fun onEndReached_noLoadWhenNoMorePages() =
        runTest {
            val vm = vm()
            // Initial fetch returns empty → hasMorePages flips false.
            homeRepo.homePages.addLast(AppResult.Success(emptyList()))
            vm.submit(HomeIntent.OnEnter)
            assertFalse(vm.state.value.hasMorePages)

            vm.submit(HomeIntent.OnEndReached)
            assertEquals(0, homeRepo.calls.count { it.startsWith("fetchMore") }, "no fetchMore when no more pages")
        }

    @Test
    fun tabSwitch_clearsFeaturedCarousel() =
        runTest {
            // #23 — switching source tabs must blank the previous source's featured carousel so it
            // doesn't flash over the new feed. fetchFeaturedFeed only overwrites `featured` on success,
            // so a NEW source whose featured fetch fails would otherwise retain the old carousel.
            val vm = vm()
            homeRepo.featuredResult =
                AppResult.Success(
                    listOf(FeaturedManga(api = "a", language = "en", title = "PopularA", url = "u/a", coverUrl = "")),
                )
            vm.submit(HomeIntent.OnEnter)
            assertEquals(
                listOf("PopularA"),
                vm.state.value.featured
                    .map { it.title },
            )

            // New source's featured fetch fails — without the reset line the old carousel would persist.
            homeRepo.featuredResult = AppResult.Failure(AppError.Network.Http(statusCode = 500))
            vm.submit(HomeIntent.OnTabSelected(1))
            assertTrue(
                vm.state.value.featured
                    .isEmpty(),
                "previous source's carousel cleared on tab switch",
            )
        }

    @Test
    fun saveToggle_add_fetchesChaptersThenPersistsThem() =
        runTest {
            // #2 — saving from Home (ADD) fetches the full chapter list and persists it, so the saved
            // row isn't a 0-chapter row (the false-new-chapter-notification trigger on Android).
            val vm = vm()
            vm.submit(HomeIntent.OnEnter)
            detailsRepo.result =
                AppResult.Success(
                    detailsWith(listOf(testChapter("c/1"), testChapter("c/2"), testChapter("c/3"))),
                )

            vm.submit(HomeIntent.OnSaveToggle(sampleFeedItem(api = "a", title = "Saveable")))

            assertEquals(1, detailsRepo.fetchCount, "ADD fetched the chapter list once")
            assertTrue(
                libraryRepo.calls.any { it == "addToLibrary(Saveable,chapters=3)" },
                "the fetched chapters are persisted with the manga: ${libraryRepo.calls}",
            )
            assertEquals(3, libraryRepo.lastAddedChapters.size)
        }

    @Test
    fun saveToggle_add_fetchFailure_emitsErrorClearsSpinnerAndDoesNotPersist() =
        runTest {
            // #2 — a fetch failure must surface an error, clear the spinner (no stuck savingKeys), and
            // NOT create a half-saved row (flatMap short-circuits before addToLibrary).
            val vm = vm()
            vm.submit(HomeIntent.OnEnter)
            detailsRepo.result = AppResult.Failure(AppError.Network.Http(statusCode = 500))
            val item = sampleFeedItem(api = "a", title = "Failing")

            val effects = mutableListOf<HomeEffect>()
            val job = launch(dispatcher) { vm.effects.collect { effects += it } }
            vm.submit(HomeIntent.OnSaveToggle(item))
            job.cancel()

            assertTrue(effects.any { it is HomeEffect.ShowError }, "fetch failure surfaces an error: $effects")
            assertEquals(
                0,
                libraryRepo.calls.count { it.startsWith("addToLibrary") },
                "no half-saved row on fetch failure",
            )
            assertTrue(
                vm.state.value.savingKeys
                    .none { it.title == "Failing" },
                "savingKeys cleared so the spinner isn't stuck: ${vm.state.value.savingKeys}",
            )
        }

    private fun testChapter(url: String) =
        Chapter(
            number = url.substringAfterLast('/'),
            name = "Chapter $url",
            url = url,
            date = null,
            isDownloaded = false,
            isBookmarked = false,
        )

    private fun detailsWith(chapters: List<Chapter>) =
        MangaDetails(
            api = "a",
            language = "en",
            title = "Saveable",
            url = "https://example.test/Saveable",
            coverUrl = "",
            description = "",
            author = "",
            rating = "",
            status = "",
            genres = emptyList(),
            chapters = chapters,
        )

    private class FakeMangaDetailsRepository : MangaDetailsRepository {
        var result: AppResult<MangaDetails> =
            AppResult.Success(
                MangaDetails("a", "en", "Saveable", "u", "", "", "", "", "", emptyList(), emptyList()),
            )
        var fetchCount = 0
            private set

        override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
            fetchCount++
            return result
        }
    }

    // Audit P1 regression pin (bare-launch → launchSafely): a use case that THROWS (mapper bug,
    // streaming edge — anything not modelled as a failure Result) inside the feed fetch must be
    // absorbed by the MviViewModel safety net. Before the fix the throw escaped `viewModelScope`
    // as an uncaught coroutine exception (a process crash on device; a failed runTest here).
    @Test
    fun throwingFeedFetch_isAbsorbedByTheSafetyNet_andTheVmKeepsWorking() =
        runTest {
            val vm = vm()
            homeRepo.fetchHomeThrows = IllegalStateException("mapper bug")
            vm.submit(HomeIntent.OnEnter)

            // Absorbed: the spinner stays up (the documented degradation for an unmodelled throw —
            // real failures flow through AppResult), and crucially the VM is still alive…
            assertTrue(vm.state.value.isFeedLoading)

            // …and keeps processing intents: a refresh with a healthy repo recovers the feed.
            homeRepo.fetchHomeThrows = null
            homeRepo.homePages.addLast(AppResult.Success(listOf(sampleFeedItem(title = "Recovered"))))
            vm.submit(HomeIntent.OnRefresh)
            assertEquals(
                listOf("Recovered"),
                vm.state.value.feed
                    .map { it.title },
            )
        }

    /** U2: minimal badge-only SourcesRepository fake (other members unused by HomeViewModel). */
    class FakeSourcesBadgeRepository : SourcesRepository {
        val hasNew = kotlinx.coroutines.flow.MutableStateFlow(false)

        override fun observeSources() = kotlinx.coroutines.flow.flowOf(emptyList<me.manga.kira.domain.model.sources.Source>())

        override suspend fun setSourceEnabled(
            api: String,
            enabled: Boolean,
        ) = Unit

        override suspend fun setLanguageEnabled(
            language: String,
            enabled: Boolean,
        ) = Unit

        override suspend fun setLanguageEnabledWithFallback(
            primary: String,
            fallback: String,
            enabled: Boolean,
        ) = Unit

        override fun observeHasNewSources(): kotlinx.coroutines.flow.Flow<Boolean> = hasNew

        override suspend fun setHasNewSources(value: Boolean) {
            hasNew.value = value
        }
    }
}
