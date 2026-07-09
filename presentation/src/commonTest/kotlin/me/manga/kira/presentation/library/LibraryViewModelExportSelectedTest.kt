package me.manga.kira.presentation.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.repository.MangaKey
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * feature/backup: [LibraryIntent.OnExportSelected] — the long-press multi-select "Export" action
 * must hand the selected [MangaKey]s to the scoped Backup screen via
 * [LibraryEffect.NavigateToBackupExport] and exit selection mode (the handoff consumes the
 * selection, mirroring the delete flow). Same harness as [LibraryViewModelApplyViewTest]; a
 * separate class so that file's baselined lint findings stay at their recorded lines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelExportSelectedTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun buildVm(): LibraryViewModel {
        val library = FakeLibraryRepository()
        val prefs = FakeLibraryPrefsRepository()
        val refresh = FakeLibraryRefreshRepository()
        return LibraryViewModel(
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
            ObserveSettingsUseCase(FakeSettingsRepository()),
            ObserveLibraryRandomSeedUseCase(prefs),
            SetLibraryRandomSeedUseCase(prefs),
        )
    }

    private val alphaKey = MangaKey(api = "api", language = "en", title = "Alpha")
    private val bravoKey = MangaKey(api = "api", language = "en", title = "Bravo")

    @Test
    fun export_selected_hands_the_selection_to_backup_and_exits_selection_mode() =
        runTest {
            val vm = buildVm()
            val effects = mutableListOf<LibraryEffect>()
            val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

            vm.submit(LibraryIntent.OnItemLongClick(alphaKey))
            vm.submit(LibraryIntent.OnSelectionToggle(bravoKey))
            assertTrue(vm.state.value.isInSelectionMode)

            vm.submit(LibraryIntent.OnExportSelected)

            val handoff = effects.filterIsInstance<LibraryEffect.NavigateToBackupExport>().single()
            assertEquals(setOf(alphaKey, bravoKey), handoff.keys.toSet(), "effect carries the whole selection")
            assertTrue(
                vm.state.value.selection
                    .isEmpty(),
                "the handoff consumes the selection, like delete",
            )
            assertFalse(vm.state.value.isInSelectionMode)
            collector.cancel()
        }

    @Test
    fun export_with_empty_selection_is_a_no_op() =
        runTest {
            val vm = buildVm()
            val effects = mutableListOf<LibraryEffect>()
            val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

            vm.submit(LibraryIntent.OnExportSelected)

            assertTrue(effects.isEmpty(), "nothing selected, nothing to hand off")
            collector.cancel()
        }
}
