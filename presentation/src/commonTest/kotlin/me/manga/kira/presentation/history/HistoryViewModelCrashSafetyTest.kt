package me.manga.kira.presentation.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.history.HistoryEntry
import me.manga.kira.domain.repository.HistoryRepository
import me.manga.kira.domain.usecase.history.DeleteAllHistoryUseCase
import me.manga.kira.domain.usecase.history.DeleteHistoryEntryUseCase
import me.manga.kira.domain.usecase.history.ObserveHistoryUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Crash-safety tests for [HistoryViewModel] (audit #17 + #29).
 *
 * - #17: a throw from the upstream Room history flow must NOT crash `viewModelScope` — the VM's
 *   `.catch {}` clears the spinner and the screen degrades to its empty state.
 * - #29: a throw from a fire-and-forget mutation (clear-all) must route to the MVI safety net
 *   ([me.manga.kira.presentation.mvi.MviViewModel.onUnhandledError]) via `launchSafely`, NOT
 *   escape `viewModelScope`. The generic routing is proven in `MviViewModelSafetyNetTest`; this pins
 *   that THIS VM's delete intents are actually wrapped — a regression to a bare
 *   `viewModelScope.launch { … }` would stop routing the throw to the hook and this test would fail.
 */
class HistoryViewModelCrashSafetyTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeHistoryRepository(
        private val historyFlow: Flow<List<HistoryEntry>> = flowOf(emptyList()),
        private val onMutate: () -> Unit = {},
    ) : HistoryRepository {
        override fun observeHistory(): Flow<List<HistoryEntry>> = historyFlow
        override suspend fun deleteEntry(entry: HistoryEntry) = onMutate()
        override suspend fun deleteAll() = onMutate()
        override suspend fun record(manga: Manga, chapter: Chapter) = Unit
    }

    /** Subclass that records the safety-net hook so a test can assert a throw was contained. */
    private class TestHistoryViewModel(repo: HistoryRepository) : HistoryViewModel(
        ObserveHistoryUseCase(repo),
        DeleteHistoryEntryUseCase(repo),
        DeleteAllHistoryUseCase(repo),
    ) {
        var captured: Throwable? = null
            private set

        override fun onUnhandledError(throwable: Throwable, intent: HistoryIntent?) {
            captured = throwable
        }
    }

    @Test
    fun throwingHistoryFlow_doesNotCrash_degradesToEmptyState() = runTest {
        // #17: the upstream Room flow throws → catch{} clears isLoading and leaves the list empty.
        val repo = FakeHistoryRepository(historyFlow = flow { throw RuntimeException("room boom") })
        val vm = TestHistoryViewModel(repo)

        val state = vm.state.value
        assertFalse(state.isLoading, "catch{} must clear the spinner on an upstream throw")
        assertTrue(state.items.isEmpty(), "screen degrades to the empty list rather than crashing")
        assertEquals(null, vm.captured, "an upstream-flow throw is handled by catch{}, not the safety net")
    }

    @Test
    fun throwingClearAll_routesToSafetyNet_notScopeEscape() = runTest {
        // #29: a fire-and-forget mutation that throws must route to onUnhandledError via launchSafely.
        val boom = IllegalStateException("delete boom")
        val repo = FakeHistoryRepository(onMutate = { throw boom })
        val vm = TestHistoryViewModel(repo)

        vm.submit(HistoryIntent.OnDeleteAll)

        assertEquals(boom, vm.captured, "clear-all throw must route to the safety net, not escape viewModelScope")
    }
}
