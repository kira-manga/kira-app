package me.manga.kira.presentation.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.domain.repository.DownloadsActionRepository
import me.manga.kira.domain.repository.DownloadsRepository
import me.manga.kira.domain.repository.UpdatesRepository
import me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.updates.DeleteAllUpdatesUseCase
import me.manga.kira.domain.usecase.updates.DeleteUpdateEntryUseCase
import me.manga.kira.domain.usecase.updates.MarkAllUpdatesAsReadUseCase
import me.manga.kira.domain.usecase.updates.MarkUpdateAsReadUseCase
import me.manga.kira.domain.usecase.updates.ObserveUpdatesUseCase
import me.manga.kira.domain.usecase.updates.RestoreUpdateEntryUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Crash-safety tests for [UpdatesViewModel] (audit #17 + #29).
 *
 * - #17: a throw from the upstream Room notifications flow must NOT crash `viewModelScope` — the VM's
 *   `.catch {}` clears the spinner, empties the list, and projects the message into `errorMessage` so
 *   the screen renders an inline error instead of crashing.
 * - #29: a throw from a fire-and-forget mutation (mark-all-read) must route to the MVI safety net
 *   ([me.manga.kira.presentation.mvi.MviViewModel.onUnhandledError]) via `launchSafely`, NOT escape
 *   `viewModelScope`. Pins that THIS VM's mutation intents are wrapped — a regression to a bare
 *   `viewModelScope.launch { … }` would stop routing the throw to the hook and this test would fail.
 */
class UpdatesViewModelCrashSafetyTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeUpdatesRepository(
        private val updatesFlow: Flow<List<UpdateEntry>> = flowOf(emptyList()),
        private val onMutate: () -> Unit = {},
    ) : UpdatesRepository {
        override fun observeUpdates(): Flow<List<UpdateEntry>> = updatesFlow
        override suspend fun markAsRead(entry: UpdateEntry) = onMutate()
        override suspend fun markAllAsRead() = onMutate()
        override suspend fun deleteEntry(entry: UpdateEntry) = onMutate()
        override suspend fun restoreEntry(entry: UpdateEntry) = onMutate()
        override suspend fun deleteAll() = onMutate()
    }

    private object EmptyDownloadsRepository : DownloadsRepository {
        override fun observeAll(): Flow<List<DownloadedChapter>> = flowOf(emptyList())
    }

    private object NoopDownloadsActionRepository : DownloadsActionRepository {
        override suspend fun enqueueDownload(chapterId: Long, mangaTitle: String, api: String) = Result.success(Unit)
        override suspend fun retryDownload(chapterId: Long) = Result.success(Unit)
        override suspend fun cancelDownload(chapterId: Long) = Result.success(Unit)
        override suspend fun cancelRunningDownload(chapterId: Long, mangaId: Long) = Result.success(Unit)
        override suspend fun cancelAllDownloads() = Result.success(Unit)
        override suspend fun deleteDownload(chapterId: Long) = Result.success(Unit)
        override suspend fun deleteDownloadedChapter(chapterId: Long) = Result.success(Unit)
        override suspend fun reconcileInterrupted() = Result.success(Unit)
    }

    /** Subclass that records the safety-net hook so a test can assert a throw was contained. */
    private class TestUpdatesViewModel(repo: UpdatesRepository) : UpdatesViewModel(
        ObserveUpdatesUseCase(repo),
        MarkUpdateAsReadUseCase(repo),
        MarkAllUpdatesAsReadUseCase(repo),
        DeleteUpdateEntryUseCase(repo),
        RestoreUpdateEntryUseCase(repo),
        DeleteAllUpdatesUseCase(repo),
        EnqueueDownloadUseCase(NoopDownloadsActionRepository),
        ObserveDownloadsUseCase(EmptyDownloadsRepository),
    ) {
        var captured: Throwable? = null
            private set

        override fun onUnhandledError(throwable: Throwable, intent: UpdatesIntent?) {
            captured = throwable
        }
    }

    @Test
    fun throwingUpdatesFlow_doesNotCrash_projectsInlineError() = runTest {
        // #17: the upstream Room flow throws → catch{} clears isLoading, empties the list, and sets
        // errorMessage so the screen renders an inline error rather than crashing the collector.
        val repo = FakeUpdatesRepository(updatesFlow = flow { throw RuntimeException("notif boom") })
        val vm = TestUpdatesViewModel(repo)

        val state = vm.state.value
        assertFalse(state.isLoading, "catch{} must clear the spinner on an upstream throw")
        assertTrue(state.items.isEmpty(), "list is emptied on the upstream error")
        assertNotNull(state.loadError, "the throw is projected into loadError for inline rendering")
        assertEquals(null, vm.captured, "an upstream-flow throw is handled by catch{}, not the safety net")
    }

    @Test
    fun throwingMarkAllAsRead_routesToSafetyNet_notScopeEscape() = runTest {
        // #29: a fire-and-forget mutation that throws must route to onUnhandledError via launchSafely.
        val boom = IllegalStateException("mark-all boom")
        val repo = FakeUpdatesRepository(onMutate = { throw boom })
        val vm = TestUpdatesViewModel(repo)

        vm.submit(UpdatesIntent.OnMarkAllAsRead)

        assertEquals(boom, vm.captured, "mark-all throw must route to the safety net, not escape viewModelScope")
    }
}
