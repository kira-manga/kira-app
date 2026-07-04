package me.manga.kira.presentation.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.repository.DownloadsActionRepository
import me.manga.kira.domain.repository.DownloadsRepository
import me.manga.kira.domain.usecase.downloads.CancelDownloadUseCase
import me.manga.kira.domain.usecase.downloads.CancelRunningDownloadUseCase
import me.manga.kira.domain.usecase.downloads.DeleteDownloadUseCase
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.downloads.RetryDownloadUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the [DownloadsViewModel] 3-bucket partition (backlog T1) — the native-parity rule the
 * whole Downloads screen renders from — plus the silent-success / generic-failure action posture:
 *  - Active = RUNNING ∪ QUEUED ∪ DOWNLOADED (the iOS bg engine's "transferred, finalize pending"
 *    row); Failed = FAILED; Completed = SUCCESS.
 *  - COMPRESSING appears in NO bucket (native `getDownloadsByState` queries only the four states —
 *    a prior rework put it in Active and was reverted; this test pins the revert).
 *  - Mutation success emits nothing (Room re-emit is the confirmation); failure emits the generic
 *    [DownloadsEffect.ShowActionFailed] (never raw error text).
 */
class DownloadsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun chapter(id: Long, state: DownloadState) = DownloadedChapter(
        chapterId = id,
        mangaId = 1L,
        number = "$id",
        mangaTitle = "Manga",
        state = state,
        progress = 0,
        errorMsg = null,
        url = "https://example/c/$id",
    )

    private class FakeDownloadsRepository(
        private val upstream: Flow<List<DownloadedChapter>>,
    ) : DownloadsRepository {
        override fun observeAll(): Flow<List<DownloadedChapter>> = upstream
    }

    private class RecordingActionRepository(
        private val result: Result<Unit> = Result.success(Unit),
    ) : DownloadsActionRepository {
        val calls = mutableListOf<String>()
        override suspend fun enqueueDownload(chapterId: Long, mangaTitle: String, api: String): Result<Unit> {
            calls += "enqueue:$chapterId"; return result
        }
        override suspend fun retryDownload(chapterId: Long): Result<Unit> {
            calls += "retry:$chapterId"; return result
        }
        override suspend fun cancelDownload(chapterId: Long): Result<Unit> {
            calls += "cancel:$chapterId"; return result
        }
        override suspend fun cancelRunningDownload(chapterId: Long, mangaId: Long): Result<Unit> {
            calls += "cancelRunning:$chapterId:$mangaId"; return result
        }
        override suspend fun cancelAllDownloads(): Result<Unit> {
            calls += "cancelAll"; return result
        }
        override suspend fun deleteDownload(chapterId: Long): Result<Unit> {
            calls += "delete:$chapterId"; return result
        }
        override suspend fun deleteDownloadedChapter(chapterId: Long): Result<Unit> {
            calls += "deleteChapter:$chapterId"; return result
        }
        override suspend fun reconcileInterrupted(): Result<Unit> {
            calls += "reconcile"; return result
        }
    }

    private fun viewModel(
        upstream: Flow<List<DownloadedChapter>>,
        actions: DownloadsActionRepository = RecordingActionRepository(),
    ) = DownloadsViewModel(
        ObserveDownloadsUseCase(FakeDownloadsRepository(upstream)),
        RetryDownloadUseCase(actions),
        CancelDownloadUseCase(actions),
        CancelRunningDownloadUseCase(actions),
        DeleteDownloadUseCase(actions),
    )

    @Test
    fun partition_activeIsRunningQueuedAndDownloaded_compressingInNoBucket() = runTest {
        val running = chapter(1, DownloadState.RUNNING)
        val queued = chapter(2, DownloadState.QUEUED)
        val downloaded = chapter(3, DownloadState.DOWNLOADED)
        val compressing = chapter(4, DownloadState.COMPRESSING)
        val failed = chapter(5, DownloadState.FAILED)
        val success = chapter(6, DownloadState.SUCCESS)
        val upstream = MutableSharedFlow<List<DownloadedChapter>>(replay = 1).apply {
            tryEmit(listOf(running, queued, downloaded, compressing, failed, success))
        }

        val vm = viewModel(upstream)
        val state = vm.state.value

        assertFalse(state.isLoading, "first emission clears the spinner")
        assertEquals(listOf(running, queued, downloaded), state.active, "Active = RUNNING ∪ QUEUED ∪ DOWNLOADED")
        assertEquals(listOf(failed), state.failed)
        assertEquals(listOf(success), state.completed)
        // The forbidden regression: COMPRESSING must appear in NO bucket (native parity).
        val bucketed = state.active + state.failed + state.completed
        assertTrue(bucketed.none { it.chapterId == compressing.chapterId }, "COMPRESSING belongs to no tab")
        // ...but it is still part of the raw snapshot (nothing is silently dropped from `all`).
        assertTrue(state.all.any { it.chapterId == compressing.chapterId })
    }

    @Test
    fun upstreamReEmission_reBucketsRows() = runTest {
        val upstream = MutableSharedFlow<List<DownloadedChapter>>(replay = 1).apply {
            tryEmit(listOf(chapter(1, DownloadState.RUNNING)))
        }
        val vm = viewModel(upstream)
        assertEquals(1, vm.state.value.active.size)

        // The running row finishes: SUCCESS lands in Completed and leaves Active — the Room
        // re-emit is the screen's state change (silent-success posture).
        upstream.tryEmit(listOf(chapter(1, DownloadState.SUCCESS)))

        assertTrue(vm.state.value.active.isEmpty())
        assertEquals(1, vm.state.value.completed.size)
    }

    @Test
    fun upstreamThrow_clearsSpinner_doesNotCrash() = runTest {
        val vm = viewModel(flow { throw RuntimeException("room boom") })
        assertFalse(vm.state.value.isLoading, "#17: catch{} must clear the spinner, not crash the scope")
    }

    @Test
    fun mutationIntents_dispatchToTheMatchingUseCase() = runTest {
        val actions = RecordingActionRepository()
        val vm = viewModel(MutableSharedFlow(replay = 1), actions)
        val target = chapter(7, DownloadState.FAILED)

        vm.submit(DownloadsIntent.OnRetry(target))
        vm.submit(DownloadsIntent.OnCancel(target))
        vm.submit(DownloadsIntent.OnCancelRunning(target))
        vm.submit(DownloadsIntent.OnDelete(target))

        assertEquals(
            listOf("retry:7", "cancel:7", "cancelRunning:7:1", "delete:7"),
            actions.calls,
        )
    }

    @Test
    fun failedMutation_emitsGenericShowActionFailed_successEmitsNothing() = runTest {
        val failing = RecordingActionRepository(result = Result.failure(IllegalStateException("worker boom")))
        val vm = viewModel(MutableSharedFlow(replay = 1), failing)

        val effects = mutableListOf<DownloadsEffect>()
        // Unconfined so the collector subscribes BEFORE submit (runTest's default launch is lazy-scheduled).
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(DownloadsIntent.OnRetry(chapter(8, DownloadState.FAILED)))
        assertEquals(listOf<DownloadsEffect>(DownloadsEffect.ShowActionFailed), effects, "failure → the generic effect (no raw text)")

        val succeeding = RecordingActionRepository()
        val vm2 = viewModel(MutableSharedFlow(replay = 1), succeeding)
        val effects2 = mutableListOf<DownloadsEffect>()
        val collector2 = launch(dispatcher) { vm2.effects.collect { effects2 += it } }
        vm2.submit(DownloadsIntent.OnRetry(chapter(9, DownloadState.FAILED)))
        assertTrue(effects2.isEmpty(), "success is silent — the Room re-emit is the confirmation")

        collector.cancel()
        collector2.cancel()
    }

    @Test
    fun tabSelect_updatesSelectedTabOnly() = runTest {
        val vm = viewModel(MutableSharedFlow(replay = 1))
        vm.submit(DownloadsIntent.OnTabSelect(2))
        assertEquals(2, vm.state.value.selectedTab)
    }
}
