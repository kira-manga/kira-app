package me.manga.kira.presentation.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.library.BulkRemoveFromLibraryUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryCategoryUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryDisplayUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryFilterUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryGridDensityUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryItemsPerRowUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryLastUpdatedUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRefreshResultUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRefreshUseCase
import me.manga.kira.domain.usecase.library.ObserveLibrarySortDirectionUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRandomSeedUseCase
import me.manga.kira.domain.usecase.library.ObserveLibrarySortUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.RefreshLibraryUseCase
import me.manga.kira.domain.usecase.library.SetLibraryCategoryUseCase
import me.manga.kira.domain.usecase.library.SetLibraryFilterUseCase
import me.manga.kira.domain.usecase.library.SetLibraryGridDensityUseCase
import me.manga.kira.domain.usecase.library.SetLibraryItemsPerRowUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowButtonsUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowCountUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowDetailsUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowSourceUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowTabsUseCase
import me.manga.kira.domain.usecase.library.SetLibrarySortDirectionUseCase
import me.manga.kira.domain.usecase.library.SetLibraryRandomSeedUseCase
import me.manga.kira.domain.usecase.library.SetLibrarySortUseCase
import me.manga.kira.domain.usecase.library.ToggleMangaLikedUseCase
import me.manga.kira.domain.usecase.library.ToggleMangaWatchingNowUseCase
import me.manga.kira.domain.usecase.settings.ObserveSettingsUseCase
import me.manga.kira.presentation.testing.FakeDownloadsRepository
import me.manga.kira.presentation.testing.FakeSettingsRepository
import me.manga.kira.presentation.testing.FakeLibraryPrefsRepository
import me.manga.kira.presentation.testing.FakeLibraryRefreshRepository
import me.manga.kira.presentation.testing.FakeLibraryRepository
import me.manga.kira.presentation.testing.sampleLibraryManga
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural tests for `LibraryViewModel.applyView` — the rework's richest pure logic
 * (search -> category -> filter -> sort -> reverse). Driven through the real MVI surface
 * (`submit(intent)`) against hand fakes, asserting the projected `state.items`.
 *
 * `viewModelScope` resolves to `Dispatchers.Main`, so an `UnconfinedTestDispatcher` is installed
 * as Main (eager execution) for the duration of each test. The VM injects no DispatcherProvider,
 * so this is the only viable deterministic harness (confirmed by scoping).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelApplyViewTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // Three rows with deliberately distinct fields; seeded in non-sorted order to prove sorting.
    private val alpha = sampleLibraryManga(
        title = "Alpha", unreadCount = 0, totalChapters = 5, hasDownloads = true,
        bookmarkedCount = 0, isLiked = true, addedAtEpochMillis = 300, lastReadAtEpochMillis = 100,
        lastOpenedAtEpochMillis = 200,
    )
    private val bravo = sampleLibraryManga(
        title = "Bravo", unreadCount = 3, totalChapters = 3, hasDownloads = false,
        bookmarkedCount = 2, isLiked = false, addedAtEpochMillis = 100, lastReadAtEpochMillis = 50,
        lastOpenedAtEpochMillis = 300,
    )
    private val charlie = sampleLibraryManga(
        title = "Charlie", unreadCount = 1, totalChapters = 4, hasDownloads = true,
        bookmarkedCount = 0, isLiked = false, addedAtEpochMillis = 200, lastReadAtEpochMillis = null,
        lastOpenedAtEpochMillis = 100,
    )

    private fun buildEntered(settings: FakeSettingsRepository = FakeSettingsRepository()): LibraryViewModel {
        val library = FakeLibraryRepository().apply { emitLibrary(listOf(charlie, alpha, bravo)) }
        val prefs = FakeLibraryPrefsRepository()
        val refresh = FakeLibraryRefreshRepository()
        val downloads = FakeDownloadsRepository()
        val vm = LibraryViewModel(
            ObserveLibraryUseCase(library),
            BulkRemoveFromLibraryUseCase(library),
            RefreshLibraryUseCase(refresh),
            ObserveLibraryRefreshUseCase(refresh),
            ObserveLibraryRefreshResultUseCase(refresh),
            ObserveLibrarySortUseCase(prefs),
            SetLibrarySortUseCase(prefs),
            ObserveLibrarySortDirectionUseCase(prefs),
            SetLibrarySortDirectionUseCase(prefs),
            ObserveLibraryFilterUseCase(prefs),
            SetLibraryFilterUseCase(prefs),
            ObserveLibraryGridDensityUseCase(prefs),
            SetLibraryGridDensityUseCase(prefs),
            ObserveLibraryItemsPerRowUseCase(prefs),
            SetLibraryItemsPerRowUseCase(prefs),
            ObserveLibraryCategoryUseCase(prefs),
            SetLibraryCategoryUseCase(prefs),
            ObserveLibraryLastUpdatedUseCase(prefs),
            ObserveLibraryDisplayUseCase(prefs),
            SetLibraryShowSourceUseCase(prefs),
            SetLibraryShowCountUseCase(prefs),
            SetLibraryShowDetailsUseCase(prefs),
            SetLibraryShowButtonsUseCase(prefs),
            SetLibraryShowTabsUseCase(prefs),
            ObserveDownloadsUseCase(downloads),
            ToggleMangaLikedUseCase(library),
            ToggleMangaWatchingNowUseCase(library),
            ObserveSettingsUseCase(settings),
            ObserveLibraryRandomSeedUseCase(prefs),
            SetLibraryRandomSeedUseCase(prefs),
        )
        vm.submit(LibraryIntent.OnEnter)
        return vm
    }

    private fun LibraryViewModel.titles(): List<String> = state.value.items.map { it.manga.title }

    @Test
    fun default_projection_is_alphabetical_ascending() = runTest {
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), buildEntered().titles())
    }

    @Test
    fun filter_unread_keeps_only_rows_with_unread_chapters() = runTest {
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnFilterChange(LibraryFilter.UNREAD))
        assertEquals(listOf("Bravo", "Charlie"), vm.titles())
    }

    @Test
    fun global_downloaded_only_setting_overrides_filter_chip() = runTest {
        val settings = FakeSettingsRepository()
        val vm = buildEntered(settings)
        // Baseline: ALL filter shows every row.
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), vm.titles())

        // Flip the global "Downloaded only" Settings toggle ON → only manga with downloads
        // (Alpha + Charlie have hasDownloads=true; Bravo does not).
        settings.downloadedOnly.value = true
        assertEquals(listOf("Alpha", "Charlie"), vm.titles())

        // It OVERRIDES the filter chip (native parity): selecting UNREAD — which alone would drop
        // Alpha (unread=0) — still yields the downloaded set regardless of the chosen FilterType.
        vm.submit(LibraryIntent.OnFilterChange(LibraryFilter.UNREAD))
        assertEquals(listOf("Alpha", "Charlie"), vm.titles())

        // Toggling it back OFF restores the (now UNREAD-filtered) view.
        settings.downloadedOnly.value = false
        assertEquals(listOf("Bravo", "Charlie"), vm.titles())
    }

    @Test
    fun filter_downloaded_keeps_only_rows_with_downloads() = runTest {
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnFilterChange(LibraryFilter.DOWNLOADED))
        assertEquals(listOf("Alpha", "Charlie"), vm.titles())
    }

    @Test
    fun filter_completed_keeps_only_fully_read_rows() = runTest {
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnFilterChange(LibraryFilter.COMPLETED))
        assertEquals(listOf("Alpha"), vm.titles())
    }

    @Test
    fun filter_bookmarked_keeps_only_rows_with_bookmarks() = runTest {
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnFilterChange(LibraryFilter.BOOKMARKED))
        assertEquals(listOf("Bravo"), vm.titles())
    }

    @Test
    fun category_liked_keeps_only_liked_rows() = runTest {
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnCategoryChange(LibraryCategory.LIKED))
        assertEquals(listOf("Alpha"), vm.titles())
    }

    @Test
    fun sort_unread_count_ascending() = runTest {
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnSortChange(LibrarySort.UNREAD_COUNT))
        assertEquals(listOf("Alpha", "Charlie", "Bravo"), vm.titles())
    }

    @Test
    fun sort_date_added_ascending() = runTest {
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnSortChange(LibrarySort.DATE_ADDED))
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), vm.titles())
    }

    @Test
    fun sort_last_read_orders_by_manga_last_open_time() = runTest {
        // Native parity: LAST_READ orders by the manga's last-OPEN time (lastOpenedAt), not by
        // chapter read dates. charlie=100, alpha=200, bravo=300 -> ascending by last-open.
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnSortChange(LibrarySort.LAST_READ))
        assertEquals(listOf("Charlie", "Alpha", "Bravo"), vm.titles())
    }

    @Test
    fun sort_direction_descending_reverses_the_alphabetical_order() = runTest {
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnSortDirectionToggle) // default ASCENDING -> DESCENDING
        assertEquals(listOf("Charlie", "Bravo", "Alpha"), vm.titles())
    }

    @Test
    fun search_filters_by_title_case_insensitively() = runTest {
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnSearchQueryChange("brav"))
        assertEquals(listOf("Bravo"), vm.titles())
    }

    @Test
    fun category_and_filter_compose_then_sort() = runTest {
        // charlie: unread=1 (UNREAD passes), isLiked=false; bravo: unread=3, isLiked=false;
        // alpha: unread=0 (fails UNREAD). Category NAN (all) + filter UNREAD -> [bravo, charlie]
        // sorted alphabetically.
        val vm = buildEntered()
        vm.submit(LibraryIntent.OnFilterChange(LibraryFilter.UNREAD))
        vm.submit(LibraryIntent.OnSortChange(LibrarySort.UNREAD_COUNT))
        assertEquals(listOf("Charlie", "Bravo"), vm.titles())
    }
}
