package me.manga.kira.presentation.features.download.domain.clean

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic regression tests for [PageFileNames]. The parsed index decides finalize page ORDER
 * and reconcile on-disk membership, so this locks: every name the three writers produce
 * (`image_$index.$extension`) round-trips to its index, and nothing else in a chapter dir can
 * ever enter the page set.
 */
class PageFileNamesTest {

    @Test
    fun writerFormat_roundTripsToIndex() {
        // Exactly what the writers emit: image_$index.$extension for indices 0..n.
        assertEquals(0, PageFileNames.pageIndexFromName("image_0.webp"))
        assertEquals(5, PageFileNames.pageIndexFromName("image_5.jpg"))
        assertEquals(12, PageFileNames.pageIndexFromName("image_12.png"))
        assertEquals(1234, PageFileNames.pageIndexFromName("image_1234.jpeg"))
    }

    @Test
    fun multiDigitIndices_orderNumerically_notLexically() {
        // The finalize ordering bug this guards: lexical sort would put image_10 before image_2.
        // Parsing to Int and sorting by it must yield numeric page order.
        val names = listOf("image_10.webp", "image_2.webp", "image_1.webp", "image_20.webp")
        val ordered = names
            .mapNotNull { n -> PageFileNames.pageIndexFromName(n)?.let { it to n } }
            .sortedBy { it.first }
            .map { it.second }
        assertEquals(listOf("image_1.webp", "image_2.webp", "image_10.webp", "image_20.webp"), ordered)
    }

    @Test
    fun nonPageFiles_areRejected() {
        // Everything else that legitimately lives in a chapter dir must never parse as a page.
        assertNull(PageFileNames.pageIndexFromName("manifest.json"))
        assertNull(PageFileNames.pageIndexFromName("chapter_5.cbz"))
        assertNull(PageFileNames.pageIndexFromName("chapter_5.cbz.part"))
        assertNull(PageFileNames.pageIndexFromName(".DS_Store"))
    }

    @Test
    fun malformedPageNames_areRejected() {
        assertNull(PageFileNames.pageIndexFromName("image_.webp")) // no index
        assertNull(PageFileNames.pageIndexFromName("image_x.webp")) // non-numeric index
        assertNull(PageFileNames.pageIndexFromName("image_5")) // no extension dot
        assertNull(PageFileNames.pageIndexFromName("image5.webp")) // missing underscore
        assertNull(PageFileNames.pageIndexFromName("IMAGE_5.webp")) // prefix is case-sensitive
    }
}
