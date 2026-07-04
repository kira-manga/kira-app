package me.manga.kira.presentation.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.presentation.testing.readerChapter
import me.manga.kira.presentation.testing.readerTestEnv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #7 — the reader's lifecycle bracket routes each `OnScreenResumed` to a reading-session begin and
 * each `OnScreenPaused` to an end. With the lifecycle-scoped UI bracket, two foreground spans
 * (resume→pause twice, e.g. background-then-return) must produce exactly two begin/end pairs — NOT
 * one long span that bills the backgrounded gap. The actual ON_RESUME/ON_STOP emission per platform
 * is needs-device-smoke; this asserts the VM reducer routing the bracket depends on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelReadingSessionTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = kotlinx.coroutines.Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    @Test
    fun eachResumePausePair_bracketsOneSession() = runTest {
        val env = readerTestEnv(chapterList = listOf(readerChapter("1")))

        // Two foreground spans (e.g. read, background, return, read).
        env.vm.submit(ReaderIntent.OnScreenResumed)
        env.vm.submit(ReaderIntent.OnScreenPaused)
        env.vm.submit(ReaderIntent.OnScreenResumed)
        env.vm.submit(ReaderIntent.OnScreenPaused)

        assertEquals(2, env.readingSession.beginCount, "one begin per resume")
        assertEquals(2, env.readingSession.endCount, "one end per pause — the backgrounded gap is not billed")
    }
}
