package me.manga.kira.reader

import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.presentation.reader.ReaderFeedItem
import me.manga.kira.presentation.reader.ReaderState
import me.manga.kira.presentation.reader.buildReaderFeed

/** Session-local memoization; an empty append changes boundaries without changing any page list. */
internal class ReaderNativeFeedProjection {
    private var pages: List<Page>? = null
    private var pageChapters: List<String>? = null
    private var chapters: List<Chapter>? = null
    private var anchor: Chapter? = null
    private var skipped: Set<String>? = null
    private var value = NativeReaderFeed(emptyList(), emptyList(), 0)

    fun project(state: ReaderState): NativeReaderFeed {
        if (
            pages === state.pages && pageChapters === state.pageChapters && chapters === state.chapters &&
            anchor == state.chapter && skipped == state.skippedChapterUrls
        ) {
            return value
        }
        val feed = buildReaderFeed(state.pages, state.pageChapters, state.chapters, state.chapter, state.skippedChapterUrls)
        value = NativeReaderFeed(
            pages = if (pages === state.pages) value.pages else state.pages.map { IosReaderPage(it.url, it.headers) },
            rows = feed.items.map { it.toNativeRow() },
            revision = value.revision + 1,
        )
        pages = state.pages
        pageChapters = state.pageChapters
        chapters = state.chapters
        anchor = state.chapter
        skipped = state.skippedChapterUrls
        return value
    }
}

internal data class NativeReaderFeed(
    val pages: List<IosReaderPage>,
    val rows: List<IosReaderFeedRow>,
    val revision: Int,
)

private fun ReaderFeedItem.toNativeRow(): IosReaderFeedRow = when (this) {
    is ReaderFeedItem.Image -> IosReaderFeedRow(
        isBoundary = false,
        url = page.url,
        headers = page.headers,
        pageIndex = pageIndex,
        finishedLabel = "",
        nextLabel = null,
    )
    is ReaderFeedItem.Boundary -> IosReaderFeedRow(
        isBoundary = true,
        url = "",
        headers = emptyMap(),
        pageIndex = -1,
        finishedLabel = finishedChapter?.let { it.name.ifBlank { it.number } }.orEmpty(),
        nextLabel = nextChapter?.let { it.name.ifBlank { it.number } },
    )
}
