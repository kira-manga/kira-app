package me.manga.kira.presentation.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
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
import kotlin.test.assertNull

/**
 * Regression tests for [UpdatesIntent.OnRetry] (backlog M1).
 *
 * The VM's `.catch {}` TERMINATES the observe collector on an upstream throw, so `loadError`
 * could previously never clear until the screen was re-entered — the feed was dead with no
 * recovery affordance. OnRetry must relaunch a FRESH collector (cancel-before-relaunch) so:
 *  - a transient failure recovers to the live list on retry,
 *  - a persistent failure re-renders the error state (not a stuck spinner),
 *  - repeated retries never leave two collectors racing writes into `items`.
 */
class UpdatesViewModelRetryTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val entry = UpdateEntry(
        id = 1L,
        api = "TestSource",
        language = "en",
        mangaId = 10L,
        mangaTitle = "Manga",
        mangaImageUrl = "",
        mangaUrl = "https://example/m/1",
        chapterId = 100L,
        chapterNumber = "5",
        chapterUrl = "https://example/c/5",
        notificationDate = LocalDate(2026, 7, 1),
        isRead = false,
        isDownloaded = false,
        localImagePaths = emptyList(),
    )

    /** Repo whose observe flow throws for the first [failingAttempts] subscriptions, then emits. */
    private class FlakyUpdatesRepository(
        private val failingAttempts: Int,
        private val success: List<UpdateEntry>,
    ) : UpdatesRepository {
        var attempts = 0
            private set

        override fun observeUpdates(): Flow<List<UpdateEntry>> = flow {
            attempts++
            if (attempts <= failingAttempts) throw RuntimeException("boom #$attempts")
            emit(success)
        }

        override suspend fun markAsRead(entry: UpdateEntry) = Unit
        override suspend fun markAllAsRead() = Unit
        override suspend fun deleteEntry(entry: UpdateEntry) = Unit
        override suspend fun restoreEntry(entry: UpdateEntry) = Unit
        override suspend fun deleteAll() = Unit
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

    private fun viewModel(repo: UpdatesRepository) = UpdatesViewModel(
        ObserveUpdatesUseCase(repo),
        MarkUpdateAsReadUseCase(repo),
        MarkAllUpdatesAsReadUseCase(repo),
        DeleteUpdateEntryUseCase(repo),
        RestoreUpdateEntryUseCase(repo),
        DeleteAllUpdatesUseCase(repo),
        EnqueueDownloadUseCase(NoopDownloadsActionRepository),
        ObserveDownloadsUseCase(EmptyDownloadsRepository),
    )

    @Test
    fun retryAfterTransientFailure_resubscribes_andRecoversTheList() = runTest {
        val repo = FlakyUpdatesRepository(failingAttempts = 1, success = listOf(entry))
        val vm = viewModel(repo)

        // Init subscription failed → the collector is dead with the error projected.
        assertNotNull(vm.state.value.loadError, "precondition: the first subscription must have failed")
        assertEquals(1, repo.attempts)

        vm.submit(UpdatesIntent.OnRetry)

        assertEquals(2, repo.attempts, "OnRetry must launch a FRESH subscription (the caught flow is dead)")
        val state = vm.state.value
        assertNull(state.loadError, "a successful retry clears the error")
        assertFalse(state.isLoading, "the fresh emission clears the retry spinner")
        assertEquals(listOf(entry), state.items, "the recovered feed renders the upstream rows")
    }

    @Test
    fun retryAgainstPersistentFailure_rendersTheErrorAgain_notAStuckSpinner() = runTest {
        val repo = FlakyUpdatesRepository(failingAttempts = Int.MAX_VALUE, success = emptyList())
        val vm = viewModel(repo)

        vm.submit(UpdatesIntent.OnRetry)

        val state = vm.state.value
        assertEquals(2, repo.attempts)
        assertNotNull(state.loadError, "a failing retry re-projects the error")
        assertFalse(state.isLoading, "catch{} must clear the retry spinner on the re-throw")
    }

    @Test
    fun repeatedRetries_keepExactlyOneCollector() = runTest {
        val repo = FlakyUpdatesRepository(failingAttempts = 2, success = listOf(entry))
        val vm = viewModel(repo)

        vm.submit(UpdatesIntent.OnRetry) // attempt 2 — still failing
        vm.submit(UpdatesIntent.OnRetry) // attempt 3 — succeeds

        assertEquals(3, repo.attempts, "each retry = exactly one new subscription (cancel-before-relaunch)")
        assertEquals(listOf(entry), vm.state.value.items)
        assertNull(vm.state.value.loadError)
    }
}
