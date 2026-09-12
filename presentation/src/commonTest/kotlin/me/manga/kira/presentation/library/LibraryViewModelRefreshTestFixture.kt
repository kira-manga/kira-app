package me.manga.kira.presentation.library

import androidx.lifecycle.ViewModelStore
import me.manga.kira.domain.repository.LibraryRefreshRepository
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.library.BulkRemoveFromLibraryUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryCategoryUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryDisplayUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryFilterUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryGridDensityUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryItemsPerRowUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryLastUpdatedUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRandomSeedUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRefreshResultUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRefreshUseCase
import me.manga.kira.domain.usecase.library.ObserveLibrarySortDirectionUseCase
import me.manga.kira.domain.usecase.library.ObserveLibrarySortUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.RefreshLibraryUseCase
import me.manga.kira.domain.usecase.library.SetLibraryCategoryUseCase
import me.manga.kira.domain.usecase.library.SetLibraryFilterUseCase
import me.manga.kira.domain.usecase.library.SetLibraryGridDensityUseCase
import me.manga.kira.domain.usecase.library.SetLibraryItemsPerRowUseCase
import me.manga.kira.domain.usecase.library.SetLibraryRandomSeedUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowButtonsUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowCountUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowDetailsUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowSourceUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowTabsUseCase
import me.manga.kira.domain.usecase.library.SetLibrarySortDirectionUseCase
import me.manga.kira.domain.usecase.library.SetLibrarySortUseCase
import me.manga.kira.domain.usecase.library.ToggleMangaLikedUseCase
import me.manga.kira.domain.usecase.library.ToggleMangaWatchingNowUseCase
import me.manga.kira.domain.usecase.settings.ObserveSettingsUseCase
import me.manga.kira.presentation.testing.FakeDownloadsRepository
import me.manga.kira.presentation.testing.FakeLibraryPrefsRepository
import me.manga.kira.presentation.testing.FakeLibraryRefreshRepository
import me.manga.kira.presentation.testing.FakeLibraryRepository
import me.manga.kira.presentation.testing.FakeSettingsRepository

internal class LibraryViewModelRefreshTestFixture {
    val store = ViewModelStore()
    val library = FakeLibraryRepository()
    val settings = FakeSettingsRepository()
    val refresh = CountingLibraryRefreshRepository()
    private val prefs = FakeLibraryPrefsRepository()

    // Construct only after the test installs Main; the store owns all observer jobs.
    val viewModel by lazy {
        LibraryViewModel(
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
            ObserveDownloadsUseCase(FakeDownloadsRepository()),
            ToggleMangaLikedUseCase(library),
            ToggleMangaWatchingNowUseCase(library),
            ObserveSettingsUseCase(settings),
            ObserveLibraryRandomSeedUseCase(prefs),
            SetLibraryRandomSeedUseCase(prefs),
        ).also { store.put("library-refresh", it) }
    }
}

internal class CountingLibraryRefreshRepository : LibraryRefreshRepository by FakeLibraryRefreshRepository() {
    var calls = 0
        private set

    override fun refresh() {
        calls++
    }
}
