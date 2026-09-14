package me.manga.kira.ui.reader

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import kotlinx.coroutines.runBlocking
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.presentation.reader.ReaderIntent
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.np_reader_no_next_chapter
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ReaderEmptyBoundaryTest {
    @Test
    fun skippedStateAloneRefreshesTheVisibleTargetAndDisablesTheTerminalBoundary() =
        runReaderChromeTest {
            val base = loadedReaderState(ReadingMode.WEBTOON)
            val second = Chapter("2", "Empty chapter", "chapter/2", null, false, false)
            val third = Chapter("3", "Readable chapter", "chapter/3", null, false, false)
            val initial =
                base.copy(
                    pages = base.pages.take(1),
                    pageChapters = base.pageChapters.take(1),
                    chapters = listOfNotNull(base.chapter, second, third),
                )
            reset(initial)
            test.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(1)
            replace(initial.copy(skippedChapterUrls = setOf(second.url)))
            test.onNodeWithText("Empty chapter", substring = true).assertDoesNotExist()
            test.runOnIdle { intents.clear() }
            test.onNodeWithText("Readable chapter", substring = true).assertIsEnabled().performClick()
            test.runOnIdle { assertEquals<List<ReaderIntent>>(listOf(ReaderIntent.OnAppendNextChapter), intents) }
            replace(initial.copy(skippedChapterUrls = setOf(second.url, third.url)))
            val terminal = runBlocking { getString(Res.string.np_reader_no_next_chapter) }
            test.onNodeWithText(terminal).assertIsNotEnabled()
        }
}
