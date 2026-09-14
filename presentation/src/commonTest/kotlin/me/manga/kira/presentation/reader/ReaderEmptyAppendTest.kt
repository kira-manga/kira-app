package me.manga.kira.presentation.reader

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.presentation.testing.ReaderTestEnv
import me.manga.kira.presentation.testing.readerChapter
import me.manga.kira.presentation.testing.readerManga
import me.manga.kira.presentation.testing.readerPage
import me.manga.kira.presentation.testing.readerTestEnv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderEmptyAppendTest {
    private val chapters = listOf(readerChapter("1"), readerChapter("2"), readerChapter("3"))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun emptyMiddleBoundaryNamesTheChapterThatTheNextAppendFetches() =
        withReader { env ->
            env.pages.result = flowOf(AppResult.Success(emptyList()))
            env.vm.submit(ReaderIntent.OnAppendNextChapter)
            val skipped = env.vm.state.value
            assertEquals(setOf(chapters[1].url), skipped.skippedChapterUrls)
            assertEquals(chapters[2], skipped.boundary().nextChapter)
            assertEquals(chapters[0], skipped.boundary().finishedChapter)
            assertEquals(listOf("page/1"), skipped.pages.map { it.url })

            env.pages.result = flowOf(AppResult.Success(listOf(readerPage("page/3"))))
            env.vm.submit(ReaderIntent.OnAppendNextChapter)
            repeat(3) { env.vm.submit(ReaderIntent.OnAppendNextChapter) }
            assertEquals(chapters, env.pages.fetched.map { it.second })
            assertEquals(
                listOf("page/1", "page/3"),
                env.vm.state.value.pages
                    .map { it.url },
            )
            assertNull(
                env.vm.state.value
                    .boundary()
                    .nextChapter,
            )
            assertEquals(chapters[0].url, env.vm.state.value.activeChapterUrl)
        }

    @Test
    fun terminalEmptyIsNotRetriedAndExplicitReplacementResetsTheOutcome() =
        withReader(chapters.take(2)) { env ->
            env.pages.result = flowOf(AppResult.Success(emptyList()))
            env.vm.submit(ReaderIntent.OnAppendNextChapter)
            repeat(3) { env.vm.submit(ReaderIntent.OnAppendNextChapter) }
            assertNull(
                env.vm.state.value
                    .boundary()
                    .nextChapter,
            )
            assertEquals(chapters.take(2), env.pages.fetched.map { it.second })

            env.pages.result = flowOf(AppResult.Success(listOf(readerPage("page/2"))))
            env.vm.submit(ReaderIntent.OnNextChapter)
            assertTrue(
                env.vm.state.value.skippedChapterUrls
                    .isEmpty(),
            )
            assertEquals(listOf(chapters[1].url), env.vm.state.value.loadedChapterUrls)
            assertEquals(
                listOf("page/2"),
                env.vm.state.value.pages
                    .map { it.url },
            )
        }

    @Test
    fun streamingRecoveryClearsSkipWithoutLosingPagesOnLaterEmptyEmissions() =
        withReader { env ->
            val stream = MutableSharedFlow<AppResult<List<Page>>>()
            env.pages.result = stream
            env.vm.submit(ReaderIntent.OnAppendNextChapter)
            stream.emit(AppResult.Success(emptyList()))
            assertEquals(
                chapters[2],
                env.vm.state.value
                    .boundary()
                    .nextChapter,
            )
            repeat(3) { env.vm.submit(ReaderIntent.OnAppendNextChapter) }
            assertEquals(chapters.take(2), env.pages.fetched.map { it.second }, "stream still owns the append")
            stream.emit(AppResult.Success(listOf(readerPage("page/2a"))))
            assertTrue(
                env.vm.state.value.skippedChapterUrls
                    .isEmpty(),
            )
            stream.emit(AppResult.Success(emptyList()))
            assertTrue(
                env.vm.state.value.skippedChapterUrls
                    .isEmpty(),
            )
            assertEquals(
                listOf("page/1", "page/2a"),
                env.vm.state.value.pages
                    .map { it.url },
            )
            stream.emit(AppResult.Success(listOf(readerPage("page/2a"), readerPage("page/2b"))))
            assertEquals(
                listOf("page/1", "page/2a", "page/2b"),
                env.vm.state.value.pages
                    .map { it.url },
            )
        }

    private fun withReader(
        chapterList: List<Chapter> = chapters,
        block: suspend (ReaderTestEnv) -> Unit,
    ) = runTest {
        val env = readerTestEnv(chapterList)
        val store = ViewModelStore().apply { put("reader", env.vm) }
        try {
            env.pages.result = flowOf(AppResult.Success(listOf(readerPage("page/1"))))
            env.vm.submit(ReaderIntent.OnEnter(readerManga(), chapters[0]))
            block(env)
        } finally {
            store.clear()
        }
    }
}

private fun ReaderState.boundary(): ReaderFeedItem.Boundary =
    buildReaderFeed(pages, pageChapters, chapters, chapter, skippedChapterUrls).items.last() as ReaderFeedItem.Boundary
