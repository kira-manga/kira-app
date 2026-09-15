package me.manga.kira.presentation.reader

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.presentation.testing.ReaderTestEnv
import me.manga.kira.presentation.testing.readerChapter
import me.manga.kira.presentation.testing.readerManga
import me.manga.kira.presentation.testing.readerTestEnv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * App43: duplicate lifecycle events preserve the first start and each foreground span ends once.
 * End's persistence may suspend after consuming a span; its later completion must not clear a new
 * active span. Platform delivery itself remains a physical-device check, not a claim of these tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelReadingSessionTest {

    private val dispatcher = StandardTestDispatcher()
    private val environments = mutableListOf<ReaderTestEnv>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        environments.forEach { it.vm.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun newEnv(): ReaderTestEnv =
        readerTestEnv(chapterList = listOf(readerChapter("1"), readerChapter("2")))
            .also { environments += it }

    @Test
    fun eachResumePausePair_bracketsOneSession() = runTest {
        val env = newEnv()

        // Two foreground spans (e.g. read, background, return, read).
        env.vm.submit(ReaderIntent.OnScreenResumed)
        env.vm.submit(ReaderIntent.OnScreenPaused)
        env.vm.submit(ReaderIntent.OnScreenResumed)
        env.vm.submit(ReaderIntent.OnScreenPaused)
        runCurrent()

        assertEquals(2, env.readingSession.beginCount, "one begin per resume")
        assertEquals(2, env.readingSession.endCount, "one end per pause — the backgrounded gap is not billed")
        assertEquals(listOf("begin", "end", "begin", "end"), env.readingSession.calls)
    }

    @Test
    fun duplicateResumePreservesFirstBegin_andDuplicatePauseDoesNotWriteAgain() = runTest {
        val env = newEnv()
        env.vm.submit(ReaderIntent.OnScreenResumed)
        runCurrent()
        env.vm.submit(ReaderIntent.OnScreenResumed)
        env.vm.submit(ReaderIntent.OnScreenResumed)
        runCurrent()
        assertEquals(listOf("begin"), env.readingSession.calls, "do not reset the raw start timestamp")

        env.vm.submit(ReaderIntent.OnScreenPaused)
        env.vm.submit(ReaderIntent.OnScreenPaused)
        runCurrent()
        assertEquals(listOf("begin", "end"), env.readingSession.calls)
    }

    @Test
    fun pauseBeforeAnyResume_doesNotReachRepository() = runTest {
        val env = newEnv()
        env.vm.submit(ReaderIntent.OnScreenPaused)
        env.vm.submit(ReaderIntent.OnScreenPaused)
        runCurrent()
        assertEquals(emptyList(), env.readingSession.calls)
    }

    @Test
    fun composeStyleStartResumePauseStopAndDispose_coalesceIntoForegroundSpans() = runTest {
        val env = newEnv()
        // The unchanged Compose observer maps START and RESUME to resume, PAUSE and STOP to pause.
        // This verifies those intent duplicates, not delivery by the platform lifecycle owner.
        repeat(2) {
            env.vm.submit(ReaderIntent.OnScreenResumed)
            env.vm.submit(ReaderIntent.OnScreenResumed)
            env.vm.submit(ReaderIntent.OnScreenPaused)
            env.vm.submit(ReaderIntent.OnScreenPaused)
        }
        env.vm.submit(ReaderIntent.OnScreenPaused) // final disposal after the last pause/stop
        runCurrent()
        assertEquals(listOf("begin", "end", "begin", "end"), env.readingSession.calls)
    }

    @Test
    fun priorEndCompletingAfterResume_doesNotClearNewActiveSpan() = runTest {
        val env = newEnv()
        val persistence = CompletableDeferred<Unit>()
        env.readingSession.endCompletion = persistence
        env.vm.submit(ReaderIntent.OnScreenResumed)
        env.vm.submit(ReaderIntent.OnScreenPaused)
        runCurrent()
        assertEquals(0, env.readingSession.completedEndCount)

        env.vm.submit(ReaderIntent.OnScreenResumed)
        runCurrent()
        assertEquals(listOf("begin", "end", "begin"), env.readingSession.calls)
        persistence.complete(Unit)
        runCurrent()
        assertEquals(1, env.readingSession.completedEndCount, "new resume must not cancel the old write")

        env.vm.submit(ReaderIntent.OnScreenResumed) // duplicate in the still-active second span
        env.vm.submit(ReaderIntent.OnScreenPaused)
        runCurrent()
        assertEquals(listOf("begin", "end", "begin", "end"), env.readingSession.calls)
        assertEquals(2, env.readingSession.completedEndCount)
    }

    @Test
    fun multiplePendingEnds_preserveLaterEdgesAndCompleteTheirWrites() = runTest {
        val env = newEnv()
        val persistence = CompletableDeferred<Unit>()
        env.readingSession.endCompletion = persistence
        repeat(2) {
            env.vm.submit(ReaderIntent.OnScreenResumed)
            env.vm.submit(ReaderIntent.OnScreenPaused)
        }
        env.vm.submit(ReaderIntent.OnScreenResumed)
        runCurrent()
        assertEquals(3, env.readingSession.beginCount)
        assertEquals(2, env.readingSession.endCount)
        assertEquals(0, env.readingSession.completedEndCount)

        persistence.complete(Unit)
        runCurrent()
        env.vm.submit(ReaderIntent.OnScreenResumed)
        env.vm.submit(ReaderIntent.OnScreenPaused)
        runCurrent()
        assertEquals(List(3) { listOf("begin", "end") }.flatten(), env.readingSession.calls)
        assertEquals(3, env.readingSession.completedEndCount)
    }

    @Test
    fun enteringAnotherChapter_doesNotRestartForegroundSpan() = runTest {
        val env = newEnv()
        env.vm.submit(ReaderIntent.OnScreenResumed)
        env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("1")))
        runCurrent()
        env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("2")))
        runCurrent()
        assertEquals("ch/2", env.vm.state.value.chapter?.url)
        assertEquals(listOf("begin"), env.readingSession.calls)

        env.vm.submit(ReaderIntent.OnScreenPaused)
        runCurrent()
        assertEquals(listOf("begin", "end"), env.readingSession.calls)
    }
}
