package me.manga.kira.presentation.reader

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.presentation.testing.ReaderTestEnv
import me.manga.kira.presentation.testing.readerChapter
import me.manga.kira.presentation.testing.readerManga
import me.manga.kira.presentation.testing.readerPage
import me.manga.kira.presentation.testing.readerTestEnv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPageProgressOwnershipTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val chapters = listOf(readerChapter("1"), readerChapter("2"))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        stores.forEach { it.clear() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun replacementRevokesUnreportedPagesBeforeResumeSuspendsAndStoreClearRevokesTheRest() =
        runTest(dispatcher) {
            val (env, store) = fixture()
            env.pages.result = flowOf(AppResult.Success(listOf(readerPage("started"), readerPage("unreported"))))
            enter(env)
            val old =
                env.vm.state.value.pageProgressHandles.values
                    .toSet()
            val request = assertNotNull(env.pageProgress.beginAttempt(old.first()))
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            env.readProgress.loadGate = gate
            env.pages.result = flowOf(AppResult.Success(listOf(readerPage("next"))))
            env.vm.submit(ReaderIntent.OnNextChapter)
            runCurrent()
            assertEquals(old, env.pageProgress.cleared.toSet())
            assertTrue(env.pageProgress.activeHandles.isEmpty())
            assertTrue(
                env.vm.state.value.pageProgressHandles
                    .isEmpty(),
            )
            request.report(PageDownloadProgress.Complete)
            runCurrent()
            assertTrue(
                env.vm.state.value.pageProgress
                    .isEmpty(),
            )
            gate.complete(Unit)
            runCurrent()
            assertEquals(setOf("next"), env.vm.state.value.pageProgressHandles.keys)
            store.clear()
            runCurrent()
            assertEquals(env.pageProgress.acquired.toSet(), env.pageProgress.cleared.toSet())
            assertTrue(env.pageProgress.activeHandles.isEmpty())
            assertEquals(0, env.pageProgress.collectorCount)
        }

    @Test
    fun cumulativeSnapshotsAndAppendKeepDuplicateUrlSurvivorsWithoutObserverGaps() =
        runTest(dispatcher) {
            val (env) = fixture()
            val initial = MutableSharedFlow<AppResult<List<Page>>>(replay = 1)
            initial.emit(AppResult.Success(listOf(readerPage("same"), readerPage("same"), readerPage("removed"))))
            env.pages.result = initial
            enter(env)
            val same =
                env.vm.state.value.pageProgressHandles
                    .getValue("same")
            env.pageProgress.report(same, PageDownloadProgress.InProgress(0.4f))
            runCurrent()
            initial.emit(AppResult.Success(listOf(readerPage("same"), readerPage("anchor"))))
            runCurrent()
            assertSame(same, env.vm.state.value.pageProgressHandles["same"])
            assertEquals(listOf("removed"), env.pageProgress.cancelled)
            val appended = MutableSharedFlow<AppResult<List<Page>>>(replay = 1)
            appended.emit(AppResult.Success(listOf(readerPage("same"), readerPage("tail"), readerPage("tail"))))
            env.pages.result = appended
            env.vm.submit(ReaderIntent.OnAppendNextChapter)
            runCurrent()
            val tail =
                env.vm.state.value.pageProgressHandles
                    .getValue("tail")
            assertSame(same, env.vm.state.value.pageProgressHandles["same"])
            assertEquals(3, env.pageProgress.collectorCount)
            assertEquals(4, env.pageProgress.acquired.size)
            appended.emit(AppResult.Success(listOf(readerPage("tail"))))
            runCurrent()
            assertSame(same, env.vm.state.value.pageProgressHandles["same"])
            assertSame(tail, env.vm.state.value.pageProgressHandles["tail"])
            assertEquals(listOf("removed"), env.pageProgress.cancelled)
            assertEquals(PageDownloadProgress.InProgress(0.4f), env.vm.state.value.pageProgress["same"])
        }

    @Test
    fun sameUrlAcrossAtoBtoAUsesNewOwnersAndIdleRemovesTheCurrentValue() =
        runTest(dispatcher) {
            val (env) = fixture()
            env.pages.result = flowOf(AppResult.Success(listOf(readerPage("same"))))
            enter(env)
            val a =
                env.vm.state.value.pageProgressHandles
                    .getValue("same")
            val oldA = assertNotNull(env.pageProgress.beginAttempt(a))
            env.vm.submit(ReaderIntent.OnNextChapter)
            runCurrent()
            val b =
                env.vm.state.value.pageProgressHandles
                    .getValue("same")
            assertNotSame(a, b)
            val oldB = assertNotNull(env.pageProgress.beginAttempt(b))
            runCurrent()
            oldB.report(PageDownloadProgress.Idle)
            runCurrent()
            assertTrue(
                env.vm.state.value.pageProgress
                    .isEmpty(),
            )
            env.vm.submit(ReaderIntent.OnPrevChapter)
            runCurrent()
            val returned =
                env.vm.state.value.pageProgressHandles
                    .getValue("same")
            assertNotSame(a, returned)
            assertNotSame(b, returned)
            oldA.report(PageDownloadProgress.Complete)
            oldB.report(PageDownloadProgress.Failed)
            runCurrent()
            assertTrue(
                env.vm.state.value.pageProgress
                    .isEmpty(),
            )
            env.pageProgress.report(returned, PageDownloadProgress.InProgress(0.8f))
            runCurrent()
            assertEquals(mapOf("same" to PageDownloadProgress.InProgress(0.8f)), env.vm.state.value.pageProgress)
        }

    private fun fixture(): Pair<ReaderTestEnv, ViewModelStore> {
        val env = readerTestEnv(chapters)
        val store = ViewModelStore().apply { put("reader", env.vm) }
        stores += store
        return env to store
    }

    private fun enter(env: ReaderTestEnv) {
        env.vm.submit(ReaderIntent.OnEnter(readerManga(), chapters.first()))
        dispatcher.scheduler.runCurrent()
    }
}
