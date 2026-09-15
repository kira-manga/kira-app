package me.manga.kira.presentation.reader

import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.Page

/**
 * A single row of the continuous reader feed.
 *
 * The reader keeps its pages in one flat [ReaderState.pages] list (page-index space) so the
 * ViewModel reducer and resume/history math stay simple. For RENDERING, the continuous-scroll modes
 * interleave a [Boundary] item after the last page of each chapter — mirroring native's
 * `allReaderItems = ImagePage + NextChapterOverlay` list — so the "next chapter" affordance is an
 * inline row between chapters rather than a floating overlay. This is a pure projection of state:
 * [buildReaderFeed] derives it; nothing here is stored in [ReaderState].
 */
sealed interface ReaderFeedItem {
    /** A page image. [pageIndex] is its absolute index into [ReaderState.pages]. */
    data class Image(val page: Page, val pageIndex: Int) : ReaderFeedItem

    /**
     * The boundary after a chapter's last image. [finishedChapter] is the chapter just read;
     * [nextChapter] is the next chapter in source order not known to be empty, matching append
     * traversal, or `null` when no unskipped chapter remains (terminal "no next chapter" card).
     */
    data class Boundary(val finishedChapter: Chapter?, val nextChapter: Chapter?) : ReaderFeedItem
}

/**
 * Result of [buildReaderFeed]: the interleaved [items] plus the two index maps that translate
 * between page-index space ([ReaderState.pages] / [ReaderState.currentPageIndex]) and feed-index
 * space (LazyColumn item positions). Built in one pass so the maps can never drift from [items].
 *
 * - [pageToFeed]: `pageToFeed[pageIndex]` = the feed position of that page's [ReaderFeedItem.Image].
 *   Used for the initial scroll position and the scrubber-driven `scrollToItem`.
 * - [feedToPage]: `feedToPage[feedPos]` = the page index the UI should report for that feed row. A
 *   [ReaderFeedItem.Boundary] row carries the PREVIOUS image's page index, so while the boundary card
 *   is the top-visible item the active chapter stays the *finished* chapter (no premature flip to the
 *   next chapter); the active chapter flips only once the next chapter's first image is top-visible.
 */
class ReaderFeed(
    val items: List<ReaderFeedItem>,
    val pageToFeed: List<Int>,
    val feedToPage: List<Int>,
)

/**
 * Project the flat page feed into [ReaderFeedItem]s with inline chapter boundaries.
 *
 * Pure and deterministic from its inputs (unit-tested in `ReaderFeedTest`). A boundary is appended
 * after the last page of every chapter run — detected via [pageChapters] (`i == lastIndex` or the
 * next page belongs to a different chapter). [anchorChapter] is used as the finished chapter when
 * [pageChapters] is empty (single chapter not yet tagged).
 */
fun buildReaderFeed(
    pages: List<Page>,
    pageChapters: List<String>,
    chapters: List<Chapter>,
    anchorChapter: Chapter?,
    skippedChapterUrls: Set<String> = emptySet(),
): ReaderFeed {
    if (pages.isEmpty()) return ReaderFeed(emptyList(), emptyList(), emptyList())
    val items = ArrayList<ReaderFeedItem>(pages.size + 4)
    val pageToFeed = IntArray(pages.size)
    val feedToPage = ArrayList<Int>(pages.size + 4)
    for (i in pages.indices) {
        pageToFeed[i] = items.size
        items.add(ReaderFeedItem.Image(pages[i], i))
        feedToPage.add(i)
        val curUrl = pageChapters.getOrNull(i)
        val nextUrl = pageChapters.getOrNull(i + 1)
        val isRunEnd = i == pages.lastIndex || nextUrl != curUrl
        if (isRunEnd) {
            val finished = curUrl?.let { u -> chapters.firstOrNull { it.url == u } } ?: anchorChapter
            val next = nextUnskippedChapter(chapters, finished?.url, skippedChapterUrls)
            items.add(ReaderFeedItem.Boundary(finishedChapter = finished, nextChapter = next))
            // Boundary reports the page it follows (its run's last image), not the next chapter's page.
            feedToPage.add(i)
        }
    }
    return ReaderFeed(items, pageToFeed.toList(), feedToPage)
}

/** Shared source-order traversal for append admission and boundary labels. */
internal fun nextUnskippedChapter(
    chapters: List<Chapter>,
    afterUrl: String?,
    skippedChapterUrls: Set<String>,
): Chapter? {
    val index = chapters.indexOfFirst { it.url == afterUrl }
    if (index < 0) return null
    return chapters.asSequence().drop(index + 1).firstOrNull { it.url !in skippedChapterUrls }
}
