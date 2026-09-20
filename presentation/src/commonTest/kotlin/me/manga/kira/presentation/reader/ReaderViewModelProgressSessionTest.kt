package me.manga.kira.presentation.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.ProgressHandle
import me.manga.kira.domain.model.identity.SavedProgressOwner
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.progress.ProgressWriteResult
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.presentation.testing.readerLocator
import me.manga.kira.presentation.testing.readerPage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelProgressSessionTest {
    private val dispatcher = StandardTestDispatcher()
    private val fixtures = mutableListOf<ReaderActiveActionFixture>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        gates.forEach { it.complete(Unit) }
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun pendingPreparationClaimsEntryBeforeDuplicateRouteOrNavigation() = runTest(dispatcher) {
        val env = fixture()
        val chapter = env.chapters.first()
        val locator = readerLocator(env.manga, chapter)
        val gate = gate()
        env.resume.positions[locator] = 1
        env.legacyProgress.beforePrepare = { gate.await() }
        env.dispatch(ReaderIntent.OnEnter(env.manga, chapter))
        assertTrue(env.vm.state.value.isLoading)
        assertEquals(chapter, env.vm.state.value.chapter)
        env.dispatch(ReaderIntent.OnEnter(env.manga.copy(title = "Renamed"), env.chapters[1]))
        env.dispatch(ReaderIntent.OnNextChapter)
        assertEquals(listOf(locator), env.legacyProgress.prepared)
        assertTrue(env.resume.begun.isEmpty())
        assertTrue(env.pages.requested.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(locator), env.resume.begun)
        assertEquals(listOf(env.manga to chapter), env.pages.requested)
        assertEquals(1, env.vm.state.value.currentPageIndex)
        assertTrue(env.resume.saves.isEmpty(), "seeding a position is not a page-change write")
    }

    @Test
    fun latePreparationOrBeginCannotSeedOrFetchAReplacementWork() = runTest(dispatcher) {
        for (duringBegin in listOf(false, true)) {
            val env = fixture()
            val chapter = env.chapters.first()
            val old = readerLocator(env.manga, chapter)
            val other = env.manga.copy(url = "https://x/other")
            val replacement = readerLocator(other, chapter)
            val gate = gate()
            val delay: suspend (ChapterLocator) -> Unit = {
                if (it == old) withContext(NonCancellable) { gate.await() }
            }
            if (duringBegin) env.resume.beforeBegin = delay else env.legacyProgress.beforePrepare = delay
            env.resume.positions[old] = 1
            env.details.lists[other] = env.chapters
            env.dispatch(ReaderIntent.OnEnter(env.manga, chapter))
            env.dispatch(ReaderIntent.OnEnter(other, chapter))
            val current = env.vm.state.value
            gate.complete(Unit)
            runCurrent()
            assertEquals(other, current.manga)
            assertEquals(0, current.currentPageIndex)
            assertEquals(current, env.vm.state.value)
            assertEquals(listOf(other to chapter), env.pages.requested)
            assertEquals(listOf(old, replacement), env.legacyProgress.prepared)
            assertEquals(if (duringBegin) listOf(old, replacement) else listOf(replacement), env.resume.begun)
        }
    }

    @Test
    fun teardownCancelsPreparationAndBeginWithoutDurableClearOrReopen() = runTest(dispatcher) {
        for (duringBegin in listOf(false, true)) {
            val env = fixture()
            val gate = gate()
            var cancelled = false
            val delay: suspend (ChapterLocator) -> Unit = {
                try {
                    gate.await()
                } catch (failure: CancellationException) {
                    cancelled = true
                    throw failure
                }
            }
            if (duringBegin) env.resume.beforeBegin = delay else env.legacyProgress.beforePrepare = delay
            env.dispatch(ReaderIntent.OnEnter(env.manga, env.chapters.first()))
            env.close()
            gate.complete(Unit)
            runCurrent()
            assertTrue(cancelled)
            assertTrue(env.pages.requested.isEmpty())
            assertEquals(if (duringBegin) 1 else 0, env.resume.begun.size)
            assertEquals(1, env.legacyProgress.prepared.size)
            assertTrue(env.resume.saves.isEmpty())
            assertTrue(env.resume.chapterClears.isEmpty())
            assertTrue(env.resume.workClears.isEmpty())
            assertEquals(0, env.legacyProgress.reconciliations)
        }
    }

    @Test
    fun streamingAppendAndScrollBackReuseReturnedHandlesAtRequestedLocators() = runTest(dispatcher) {
        val env = fixture()
        env.dispatch(ReaderIntent.OnEnter(env.manga, env.chapters.first()))
        val anchor = env.resume.snapshots.single().handle
        val requested = readerLocator(env.manga, env.chapters[1])
        val adopted = requested.copy(work = requested.work.copy(url = "https://accepted/alias"), chapterUrl = "alias/ch2")
        val handle = ProgressHandle(adopted, 7, 11)
        env.resume.returnedHandles[requested] = handle
        val stream = MutableSharedFlow<AppResult<List<Page>>>(extraBufferCapacity = 1)
        env.pages.results[requested.chapterUrl] = stream
        env.dispatch(ReaderIntent.OnAppendNextChapter)
        assertTrue(stream.tryEmit(AppResult.Success(listOf(readerPage("c")))))
        runCurrent()
        env.dispatch(ReaderIntent.OnPageChanged(2))
        assertTrue(stream.tryEmit(AppResult.Success(listOf(readerPage("c"), readerPage("d"), readerPage("e")))))
        runCurrent()
        env.dispatch(ReaderIntent.OnPageChanged(4))
        env.dispatch(ReaderIntent.OnPageChanged(1))
        env.dispatch(ReaderIntent.OnAppendNextChapter)
        assertEquals(listOf(anchor.chapter, requested), env.resume.begun)
        assertEquals(env.resume.begun, env.legacyProgress.prepared)
        assertEquals(listOf(handle to 0, handle to 2, anchor to 1), env.resume.saves)
        assertEquals(listOf("ch/1/a", "ch/1/b", "c", "d", "e"), env.vm.state.value.pages.map { it.url })
    }

    @Test
    fun queuedSavesKeepCapturedHandlesAndLatestBackwardPageAcrossReplacement() = runTest(dispatcher) {
        val env = fixture()
        val chapter = env.chapters.first()
        env.pages.results[chapter.url] = flowOf(AppResult.Success(listOf("a", "b", "c").map(::readerPage)))
        env.dispatch(ReaderIntent.OnEnter(env.manga, chapter))
        val old = env.resume.snapshots.single().handle
        val gate = gate()
        env.resume.beforeSave = { handle, index -> if (handle == old && index == 2) gate.await() }
        listOf(2, 1, 0).forEach { env.dispatch(ReaderIntent.OnPageChanged(it)) }
        val other = env.manga.copy(api = "other-source")
        env.details.lists[other] = env.chapters
        env.dispatch(ReaderIntent.OnEnter(other, chapter))
        env.dispatch(ReaderIntent.OnPageChanged(1))
        val replacement = env.resume.snapshots.last().handle
        assertEquals(listOf(old to 2), env.resume.saves, "later writes must not even start while the first is pending")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(old to 2, old to 1, old to 0, replacement to 1), env.resume.written)
        assertEquals(0, env.resume.positions[old.chapter])
        assertEquals(1, env.resume.positions[replacement.chapter])
        assertEquals(listOf(old.chapter, replacement.chapter), env.resume.begun)
    }

    @Test
    fun absenceAndStoredZeroOnlyWriteAfterARealPageChange() = runTest(dispatcher) {
        for (seed in listOf(null, 0)) {
            val env = fixture()
            val locator = readerLocator(env.manga, env.chapters.first())
            if (seed != null) env.resume.positions[locator] = seed
            env.dispatch(ReaderIntent.OnEnter(env.manga, env.chapters.first()))
            assertEquals(seed, env.resume.snapshots.single().pageIndex)
            assertEquals(0, env.vm.state.value.currentPageIndex)
            env.dispatch(ReaderIntent.OnPageChanged(0))
            assertTrue(env.resume.saves.isEmpty())
            env.dispatch(ReaderIntent.OnPageChanged(1))
            env.dispatch(ReaderIntent.OnPageChanged(0))
            assertEquals(listOf(1, 0), env.resume.written.map { it.second })
            assertEquals(0, env.resume.positions[locator])
        }
    }

    @Test
    fun staleHandleDisablesQueuedAndLaterWritesUntilDeliberateNewEntry() = runTest(dispatcher) {
        val env = fixture()
        val locator = readerLocator(env.manga, env.chapters.first())
        val handle = ProgressHandle(locator, 2, 3, SavedProgressOwner(SavedWorkIdentity(7, locator.work), 9))
        env.resume.returnedHandles[locator] = handle
        env.dispatch(ReaderIntent.OnEnter(env.manga, env.chapters.first()))
        val gate = gate()
        env.resume.beforeSave = { _, _ -> gate.await() }
        env.dispatch(ReaderIntent.OnPageChanged(1))
        env.dispatch(ReaderIntent.OnPageChanged(0))
        env.resume.saveResult = AppResult.Success(ProgressWriteResult.STALE)
        gate.complete(Unit)
        runCurrent()
        exerciseNonEntryActions(env)
        assertEquals(listOf(handle to 1), env.resume.saves)
        assertTrue(env.resume.written.isEmpty())
        assertEquals(listOf(locator), env.resume.begun)
        assertEquals(listOf(locator), env.legacyProgress.prepared)
        val reopened = handle.copy(workGeneration = 3, chapterGeneration = 4, retainedOwner = null)
        env.resume.returnedHandles[locator] = reopened
        env.resume.saveResult = AppResult.Success(ProgressWriteResult.WRITTEN)
        env.dispatch(ReaderIntent.OnNextChapter)
        env.dispatch(ReaderIntent.OnPrevChapter)
        env.dispatch(ReaderIntent.OnPageChanged(1))
        assertEquals(listOf(reopened to 1), env.resume.written)
    }

    @Test
    fun typedPreparationOrBeginFailureLeavesReadingUsableWithoutWritableProgress() = runTest(dispatcher) {
        val failures = listOf(false to AppError.Storage.Constraint("owner conflict"), true to AppError.Storage.Io())
        for ((duringBegin, error) in failures) {
            val env = fixture()
            if (duringBegin) env.resume.beginFailure = error else env.legacyProgress.result = AppResult.Failure(error)
            env.dispatch(ReaderIntent.OnEnter(env.manga, env.chapters.first()))
            assertEquals(ReaderEffect.ShowError(error), env.vm.effects.first())
            assertTrue(env.vm.state.value.hasPages)
            assertFalse(env.vm.state.value.isLoading)
            assertNull(env.vm.state.value.error, "the progress error must not hide readable pages")
            env.dispatch(ReaderIntent.OnPageChanged(1))
            env.dispatch(ReaderIntent.OnRetry)
            env.dispatch(ReaderIntent.OnPageChanged(0))
            assertTrue(env.resume.saves.isEmpty())
            assertTrue(env.resume.snapshots.isEmpty())
            assertEquals(if (duringBegin) 1 else 0, env.resume.begun.size)
            assertEquals(1, env.legacyProgress.prepared.size)
            assertTrue(env.resume.readRequests.isEmpty())
        }
    }

    @Test
    fun saveFailureSurfacesWithoutAutomaticRetryOrSessionAcquisition() = runTest(dispatcher) {
        val env = fixture()
        env.dispatch(ReaderIntent.OnEnter(env.manga, env.chapters.first()))
        val handle = env.resume.snapshots.single().handle
        val error = AppError.Storage.Io()
        env.resume.saveResult = AppResult.Failure(error)
        env.dispatch(ReaderIntent.OnPageChanged(1))
        assertEquals(ReaderEffect.ShowError(error), env.vm.effects.first())
        runCurrent()
        assertEquals(listOf(handle to 1), env.resume.saves)
        env.resume.saveResult = AppResult.Success(ProgressWriteResult.WRITTEN)
        env.dispatch(ReaderIntent.OnPageChanged(0))
        assertEquals(listOf(handle to 0), env.resume.written)
        assertEquals(listOf(handle.chapter), env.resume.begun)
    }

    private fun exerciseNonEntryActions(env: ReaderActiveActionFixture) {
        env.dispatch(ReaderIntent.OnRetry)
        env.dispatch(ReaderIntent.OnScreenPaused)
        env.dispatch(ReaderIntent.OnScreenResumed)
        env.dispatch(ReaderIntent.OnEnter(env.manga.copy(title = "Renamed"), env.chapters.first()))
        env.dispatch(ReaderIntent.OnPageChanged(1))
    }

    private fun fixture(): ReaderActiveActionFixture =
        ReaderActiveActionFixture(dispatcher.scheduler).also { fixtures += it }

    private fun gate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates += it }
}
