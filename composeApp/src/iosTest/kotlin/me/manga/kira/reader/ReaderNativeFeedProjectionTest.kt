package me.manga.kira.reader

import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.presentation.reader.ReaderState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReaderNativeFeedProjectionTest {
    private val chapters = (1..3).map { Chapter("$it", "Chapter $it", "chapter/$it", null, false, false) }
    private val state =
        ReaderState(
            chapter = chapters[0],
            chapters = chapters,
            pages = listOf(Page("page/1", emptyMap())),
            pageChapters = listOf(chapters[0].url),
            loadedChapterUrls = listOf(chapters[0].url),
        )

    @Test
    fun emptyAppendInvalidatesNativeBoundaryEvenWhenAllPageListsAreIdentical() {
        val projector = ReaderNativeFeedProjection()
        val first = projector.project(state)
        val skipped = projector.project(state.copy(skippedChapterUrls = setOf(chapters[1].url)))
        assertEquals("Chapter 2", first.rows.last().nextLabel)
        assertEquals("Chapter 3", skipped.rows.last().nextLabel)
        assertEquals(first.revision + 1, skipped.revision)
        assertSame(first.pages, skipped.pages)
        val terminal = projector.project(state.copy(skippedChapterUrls = chapters.drop(1).map { it.url }.toSet()))
        assertNull(terminal.rows.last().nextLabel)
        assertEquals(skipped.revision + 1, terminal.revision)
    }

    @Test
    fun scrollAndChromeChangesReuseTheProjectionButRecoveryRefreshesTheLabel() {
        val projector = ReaderNativeFeedProjection()
        val skippedState = state.copy(skippedChapterUrls = setOf(chapters[1].url))
        val skipped = projector.project(skippedState)
        assertSame(skipped, projector.project(skippedState.copy(isUiVisible = false)))
        val recovered = projector.project(state)
        assertEquals("Chapter 2", recovered.rows.last().nextLabel)
        assertEquals(skipped.revision + 1, recovered.revision)
        assertEquals(listOf(0, -1), recovered.rows.map { it.pageIndex })
    }
}
