package me.manga.kira.presentation.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.result.AppResult
import me.manga.kira.presentation.testing.readerChapter
import me.manga.kira.presentation.testing.readerManga
import me.manga.kira.presentation.testing.readerPage
import me.manga.kira.presentation.testing.readerTestEnv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Audit P1 regression pin (bare-launch → launchSafely): the reader's Job-tracked fetch coroutines
 * (`fetchJob`/`appendJob`/`chaptersJob`/`progressJob`) must absorb an unmodelled THROW from a
 * collaborator via the [MviViewModel][me.manga.kira.presentation.mvi.MviViewModel] safety net.
 * Before the fix these were bare `viewModelScope.launch` blocks, so a page-flow throw (streaming
 * source edge, mapper bug — anything not surfaced as a failure [AppResult]) escaped as an uncaught
 * coroutine exception: a process crash on device, a failed `runTest` here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelSafetyNetTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun throwingPageFlow_isAbsorbedByTheSafetyNet_andTheVmKeepsWorking() =
        runTest {
            val env = readerTestEnv(chapterList = listOf(readerChapter("1")))
            env.pages.result = flow { throw IllegalStateException("stream bug") }

            env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("1")))

            // Absorbed AND UI-consistent: the safety net clears the spinner and surfaces the error
            // pane (without it, isLoading would stick true and onRetry's re-entrance guard would
            // dead-lock the screen — retry silently dropped forever).
            val degraded = env.vm.state.value
            assertFalse(degraded.isLoading, "absorbed throw must clear the spinner")
            assertNotNull(degraded.error, "initial-load throw surfaces the error+retry pane")

            // …and the VM is still alive: OnRetry (the dispatch path that forces an explicit re-fetch;
            // OnEnter only processes while chapter == null) with a healthy repo recovers.
            env.pages.result = flowOf(AppResult.Success(listOf(readerPage("p1"))))
            env.vm.submit(ReaderIntent.OnRetry)
            val s = env.vm.state.value
            assertEquals(
                listOf("p1"),
                s.pages.map { it.url },
                "chapter=${s.chapter?.url} isLoading=${s.isLoading} error=${s.error}",
            )
        }
}
