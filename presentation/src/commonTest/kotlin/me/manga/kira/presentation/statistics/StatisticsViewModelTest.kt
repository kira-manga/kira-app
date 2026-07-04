package me.manga.kira.presentation.statistics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.statistics.ReadingStatistics
import me.manga.kira.domain.repository.ReadingStatisticsRepository
import me.manga.kira.domain.usecase.statistics.ObserveReadingStatisticsUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the [StatisticsViewModel] projection (backlog T1):
 *  - each upstream [ReadingStatistics] snapshot lands verbatim in [StatisticsState] and clears
 *    the spinner — including the TYPED read-minutes wire (2026-07 backlog L15: raw Int, no
 *    pre-formatted "Xh Ym" string),
 *  - the #17 `.catch {}` degrades an upstream throw to a rendered (zeroed) screen instead of
 *    crashing the `launchIn` collector.
 */
class StatisticsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeReadingStatisticsRepository(
        private val upstream: Flow<ReadingStatistics>,
    ) : ReadingStatisticsRepository {
        override fun observe(): Flow<ReadingStatistics> = upstream
    }

    private fun viewModel(upstream: Flow<ReadingStatistics>) =
        StatisticsViewModel(ObserveReadingStatisticsUseCase(FakeReadingStatisticsRepository(upstream)))

    @Test
    fun emission_projectsAllEightNumbers_andClearsLoading() = runTest {
        val upstream = MutableSharedFlow<ReadingStatistics>(replay = 1).apply {
            tryEmit(
                ReadingStatistics(
                    inLibrary = 12,
                    readMinutes = 447, // 7h 27m — :ui formats; the state must carry the raw Int
                    entriesStarted = 5,
                    entriesCompleted = 2,
                    chaptersTotal = 340,
                    chaptersRead = 120,
                    chaptersDownloaded = 33,
                    chaptersBookmarked = 4,
                ),
            )
        }
        val vm = viewModel(upstream)

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(12, state.inLibrary)
        assertEquals(447, state.readMinutes)
        assertEquals(5, state.entriesStarted)
        assertEquals(2, state.entriesCompleted)
        assertEquals(340, state.chaptersTotal)
        assertEquals(120, state.chaptersRead)
        assertEquals(33, state.chaptersDownloaded)
        assertEquals(4, state.chaptersBookmarked)
    }

    @Test
    fun upstreamThrow_clearsSpinner_andKeepsZeroedValues() = runTest {
        val vm = viewModel(flow { throw RuntimeException("driver-level boom") })

        val state = vm.state.value
        assertFalse(state.isLoading, "#17: the catch must clear the spinner so the screen renders")
        assertEquals(0, state.readMinutes, "degrades to the zeroed defaults, no crash")
        assertEquals(0, state.inLibrary)
    }

    @Test
    fun subsequentEmission_replacesTheSnapshot() = runTest {
        val upstream = MutableSharedFlow<ReadingStatistics>(replay = 1)
        val vm = viewModel(upstream)
        assertTrue(vm.state.value.isLoading, "no emission yet — spinner still up")

        upstream.tryEmit(snapshot(readMinutes = 10, chaptersRead = 1))
        assertEquals(10, vm.state.value.readMinutes)

        upstream.tryEmit(snapshot(readMinutes = 75, chaptersRead = 2))
        assertEquals(75, vm.state.value.readMinutes, "reactive re-emit replaces the snapshot")
        assertEquals(2, vm.state.value.chaptersRead)
    }

    private fun snapshot(readMinutes: Int, chaptersRead: Int) = ReadingStatistics(
        inLibrary = 1,
        readMinutes = readMinutes,
        entriesStarted = 0,
        entriesCompleted = 0,
        chaptersTotal = 0,
        chaptersRead = chaptersRead,
        chaptersDownloaded = 0,
        chaptersBookmarked = 0,
    )
}
