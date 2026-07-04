package me.manga.kira.presentation.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertTrue

/**
 * #5 continuous reader: scroll-to-end APPENDS the next chapter below the current one (keeping the
 * current pages) instead of clearing+jumping. The explicit Next button still clears+jumps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelAppendTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = kotlinx.coroutines.Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    private val chapters = listOf(readerChapter("1"), readerChapter("2"))

    @Test
    fun appendNextChapter_appendsBelow_keepingCurrentPages() = runTest {
        val env = readerTestEnv(chapterList = chapters)
        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("a"), readerPage("b"))))
        env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("1")))
        assertEquals(2, env.vm.state.value.pages.size, "chapter 1 loaded")

        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("c"), readerPage("d"))))
        env.vm.submit(ReaderIntent.OnAppendNextChapter)

        val s = env.vm.state.value
        assertEquals(
            listOf("a", "b", "c", "d"),
            s.pages.map { it.url },
            "next chapter appended BELOW the current pages; current not removed",
        )
        assertEquals(listOf("ch/1", "ch/2"), s.loadedChapterUrls)
        assertEquals(listOf("ch/1", "ch/1", "ch/2", "ch/2"), s.pageChapters)
        assertTrue(env.markRead.marked.contains("ch/1"), "finishing chapter 1 marks it read on append")
    }

    @Test
    fun appendNextChapter_isIdempotentAndNoOpAtEnd() = runTest {
        val env = readerTestEnv(chapterList = chapters)
        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("a"))))
        env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("1")))
        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("c"))))
        env.vm.submit(ReaderIntent.OnAppendNextChapter) // appends ch/2 (the last chapter)
        val afterFirst = env.vm.state.value.pages.map { it.url }

        env.vm.submit(ReaderIntent.OnAppendNextChapter) // no next chapter → no-op

        assertEquals(afterFirst, env.vm.state.value.pages.map { it.url }, "appending past the last chapter is a no-op")
        assertEquals(listOf("a", "c"), afterFirst)
    }

    @Test
    fun explicitNextChapter_clearsAndJumps_notAppend() = runTest {
        val env = readerTestEnv(chapterList = chapters)
        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("a"), readerPage("b"))))
        env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("1")))

        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("c"), readerPage("d"))))
        env.vm.submit(ReaderIntent.OnNextChapter) // explicit button → clear + jump

        val s = env.vm.state.value
        assertEquals(listOf("c", "d"), s.pages.map { it.url }, "explicit Next REPLACES the feed (clear+jump)")
        assertEquals(listOf("ch/2"), s.loadedChapterUrls)
    }

    @Test
    fun activeChapter_andHud_deriveFromVisiblePage_afterAppend() = runTest {
        val env = readerTestEnv(chapterList = chapters)
        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("a"), readerPage("b"))))
        env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("1")))
        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("c"), readerPage("d"))))
        env.vm.submit(ReaderIntent.OnAppendNextChapter)

        // Scroll to the first page of the appended chapter 2 (flat index 2).
        env.vm.submit(ReaderIntent.OnPageChanged(2))

        val s = env.vm.state.value
        assertEquals("ch/2", s.activeChapterUrl, "active chapter follows the visible page across the boundary")
        assertEquals(1, s.currentChapterIndex, "currentChapterIndex is chapter 2's position")
        assertEquals(1, s.activeChapterPageNumber, "HUD shows within-chapter page number (1), not flat (3)")
        assertEquals(2, s.activeChapterPageCount, "HUD total is chapter 2's page count")
    }
}
