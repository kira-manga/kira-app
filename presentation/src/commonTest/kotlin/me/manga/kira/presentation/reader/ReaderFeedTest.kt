package me.manga.kira.presentation.reader

import me.manga.kira.presentation.testing.readerChapter
import me.manga.kira.presentation.testing.readerPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #5 continuous reader — the page/feed index mapping the owner asked to test heavily:
 * [buildReaderFeed] boundary positions, `pageToFeed`/`feedToPage` consistency, and the
 * per-chapter slider scope ([ReaderState.activeChapterPageIndices]).
 */
class ReaderFeedTest {

    private val chA = readerChapter("1") // url ch/1
    private val chB = readerChapter("2") // url ch/2
    private val chC = readerChapter("3") // url ch/3
    private val chapters = listOf(chA, chB, chC)

    @Test
    fun singleChapter_imagesPlusOneTerminalBoundary_nextIsNextChapter() {
        val pages = listOf(readerPage("p0"), readerPage("p1"))
        val feed = buildReaderFeed(pages, listOf(chA.url, chA.url), chapters, chA)

        assertEquals(3, feed.items.size)
        assertTrue(feed.items[0] is ReaderFeedItem.Image)
        assertTrue(feed.items[1] is ReaderFeedItem.Image)
        val b = feed.items[2] as ReaderFeedItem.Boundary
        assertEquals(chA, b.finishedChapter)
        assertEquals(chB, b.nextChapter, "boundary after chapter A points to the next chapter B")
    }

    @Test
    fun lastChapter_terminalBoundaryHasNoNext() {
        val pages = listOf(readerPage("z0"), readerPage("z1"))
        val feed = buildReaderFeed(pages, listOf(chC.url, chC.url), chapters, chC)

        val b = feed.items.last() as ReaderFeedItem.Boundary
        assertEquals(chC, b.finishedChapter)
        assertNull(b.nextChapter, "the last chapter's boundary is terminal (no next chapter)")
    }

    @Test
    fun twoAppendedChapters_imageRunsSplitByBoundaries() {
        val pages = listOf(readerPage("a"), readerPage("b"), readerPage("c"), readerPage("d"))
        val pageChapters = listOf(chA.url, chA.url, chB.url, chB.url)
        val feed = buildReaderFeed(pages, pageChapters, chapters, chA)

        // Img(a),Img(b),Boundary(A->B),Img(c),Img(d),Boundary(B->C)
        assertEquals(6, feed.items.size)
        val mid = feed.items[2] as ReaderFeedItem.Boundary
        assertEquals(chA, mid.finishedChapter)
        assertEquals(chB, mid.nextChapter)
        val tail = feed.items[5] as ReaderFeedItem.Boundary
        assertEquals(chB, tail.finishedChapter)
        assertEquals(chC, tail.nextChapter)
        // Images carry their absolute page index.
        assertEquals(2, (feed.items[3] as ReaderFeedItem.Image).pageIndex)
    }

    @Test
    fun emptyPages_emptyFeed() {
        val feed = buildReaderFeed(emptyList(), emptyList(), chapters, chA)
        assertTrue(feed.items.isEmpty())
        assertTrue(feed.pageToFeed.isEmpty())
        assertTrue(feed.feedToPage.isEmpty())
    }

    @Test
    fun untaggedPages_fallBackToAnchorChapterForTheBoundary() {
        val pages = listOf(readerPage("a"), readerPage("b"), readerPage("c"))
        // pageChapters empty (single chapter, pre-tag): one run, boundary uses the anchor chapter.
        val feed = buildReaderFeed(pages, emptyList(), chapters, chB)

        assertEquals(4, feed.items.size)
        val b = feed.items.last() as ReaderFeedItem.Boundary
        assertEquals(chB, b.finishedChapter)
        assertEquals(chC, b.nextChapter, "anchor chB -> next is chC")
    }

    @Test
    fun maps_roundTripForImages_andBoundaryCarriesPreviousImagePage() {
        val pages = listOf(readerPage("a"), readerPage("b"), readerPage("c"), readerPage("d"))
        val pageChapters = listOf(chA.url, chA.url, chB.url, chB.url)
        val feed = buildReaderFeed(pages, pageChapters, chapters, chA)

        // feedToPage[pageToFeed[i]] == i for every page.
        for (i in pages.indices) {
            assertEquals(i, feed.feedToPage[feed.pageToFeed[i]], "round-trip for page $i")
        }
        // The A->B boundary sits at feed position 2 and reports A's last page (index 1).
        assertTrue(feed.items[2] is ReaderFeedItem.Boundary)
        assertEquals(1, feed.feedToPage[2], "boundary reports the previous image's page index")
        // The terminal boundary at feed position 5 reports B's last page (index 3).
        assertEquals(3, feed.feedToPage[5])
    }

    @Test
    fun activeChapterPageIndices_scopeToActiveChapter_andMatchHudNumber() {
        val base = ReaderState(
            pages = listOf(readerPage("a"), readerPage("b"), readerPage("c"), readerPage("d")),
            pageChapters = listOf(chA.url, chA.url, chB.url, chB.url),
            chapter = chA,
            chapters = chapters,
            currentPageIndex = 0,
        )
        assertEquals(listOf(0, 1), base.activeChapterPageIndices, "active = chapter A")

        val inB = base.copy(currentPageIndex = 2)
        assertEquals(listOf(2, 3), inB.activeChapterPageIndices, "active = chapter B (contiguous tail run)")
        // The slider value (position within the active chapter) lines up with the HUD number.
        assertEquals(
            inB.activeChapterPageNumber - 1,
            inB.activeChapterPageIndices.indexOf(inB.currentPageIndex),
        )
    }

    @Test
    fun activeChapterPageIndices_untaggedFeed_isAllPages() {
        val state = ReaderState(
            pages = listOf(readerPage("a"), readerPage("b"), readerPage("c")),
            pageChapters = emptyList(),
            chapter = chA,
            chapters = chapters,
            currentPageIndex = 1,
        )
        assertEquals(listOf(0, 1, 2), state.activeChapterPageIndices)
    }
}
