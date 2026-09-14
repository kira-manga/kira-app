package me.manga.kira.presentation.library

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.presentation.testing.sampleLibraryManga
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelRefreshTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fixture = LibraryViewModelRefreshTestFixture()
    private val savedManga = sampleLibraryManga(title = "Saved manga", totalChapters = 1)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        try {
            fixture.store.clear()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun searchWithNoMatchesStillRefreshesSavedLibrary() =
        assertHiddenLibraryRefreshes { it.viewModel.submit(LibraryIntent.OnSearchQueryChange("unmatched")) }

    @Test
    fun categoryWithNoMatchesStillRefreshesSavedLibrary() =
        assertHiddenLibraryRefreshes { it.viewModel.submit(LibraryIntent.OnCategoryChange(LibraryCategory.LIKED)) }

    @Test
    fun downloadedOnlyWithNoMatchesStillRefreshesSavedLibrary() =
        assertHiddenLibraryRefreshes {
            it.settings.downloadedOnly.value = true
        }

    @Test
    fun unreadFilterWithNoMatchesStillRefreshesSavedLibrary() =
        assertHiddenLibraryRefreshes { it.viewModel.submit(LibraryIntent.OnFilterChange(LibraryFilter.UNREAD)) }

    @Test
    fun downloadedFilterWithNoMatchesStillRefreshesSavedLibrary() =
        assertHiddenLibraryRefreshes { it.viewModel.submit(LibraryIntent.OnFilterChange(LibraryFilter.DOWNLOADED)) }

    @Test
    fun trulyEmptySnapshotEmitsEmptyEffectWithoutRefreshing() =
        runTest(dispatcher) {
            val vm = fixture.viewModel
            vm.submit(LibraryIntent.OnEnter)
            runCurrent()
            assertFalse(vm.state.value.isLoading)
            assertFalse(vm.state.value.hasLibraryItems)
            assertTrue(vm.state.value.isEmpty)
            vm.effects.test {
                vm.submit(LibraryIntent.OnRefresh)
                runCurrent()
                assertEquals(LibraryEffect.ShowEmptyLibraryRefresh, awaitItem())
                assertEquals(0, fixture.refresh.calls)
                expectNoEvents()
            }
        }

    @Test
    fun rawPresenceTracksSnapshotsWhileProjectionStaysEmpty() =
        runTest(dispatcher) {
            val vm = fixture.viewModel
            vm.submit(LibraryIntent.OnFilterChange(LibraryFilter.UNREAD))
            vm.submit(LibraryIntent.OnEnter)
            runCurrent()
            val snapshots = listOf(listOf(savedManga), emptyList(), listOf(savedManga))
            snapshots.forEach { rows ->
                fixture.library.emitLibrary(rows)
                runCurrent()
                assertEquals(rows.isNotEmpty(), vm.state.value.hasLibraryItems)
                assertTrue(vm.state.value.isEmpty)
                assertFalse(vm.state.value.isLoading)
                assertEquals(LibraryFilter.UNREAD, vm.state.value.filter)
            }
        }

    @Test
    fun refreshBeforeObservationRetainsEmptyGuardThenUsesLoadedSnapshot() =
        runTest(dispatcher) {
            fixture.library.emitLibrary(listOf(savedManga))
            val vm = fixture.viewModel
            assertTrue(vm.state.value.isLoading)
            assertFalse(vm.state.value.isEmpty)
            vm.effects.test {
                vm.submit(LibraryIntent.OnRefresh)
                runCurrent()
                assertEquals(LibraryEffect.ShowEmptyLibraryRefresh, awaitItem())
                assertEquals(0, fixture.refresh.calls)
                vm.submit(LibraryIntent.OnEnter)
                runCurrent()
                assertTrue(vm.state.value.hasLibraryItems)
                assertFalse(vm.state.value.isLoading)
                vm.submit(LibraryIntent.OnRefresh)
                runCurrent()
                assertEquals(1, fixture.refresh.calls)
                expectNoEvents()
            }
        }

    private fun assertHiddenLibraryRefreshes(narrow: (LibraryViewModelRefreshTestFixture) -> Unit) =
        runTest(dispatcher) {
            fixture.library.emitLibrary(listOf(savedManga))
            val vm = fixture.viewModel
            vm.submit(LibraryIntent.OnEnter)
            runCurrent()
            assertFalse(vm.state.value.isEmpty)
            narrow(fixture)
            runCurrent()
            val projected = vm.state.value
            assertTrue(projected.isEmpty)
            assertTrue(projected.hasLibraryItems)
            vm.effects.test {
                vm.submit(LibraryIntent.OnRefresh)
                runCurrent()
                assertEquals(1, fixture.refresh.calls)
                expectNoEvents()
            }
            assertEquals(projected, vm.state.value, "Refresh must not clear projection controls")
        }
}
