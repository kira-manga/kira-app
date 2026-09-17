package me.manga.kira.data.complaint.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComplaintHistoryPagesTest {
    @Test
    fun genuineEmptyAndTwoFullPagesPlusDefensiveTerminalAreCompleteSnapshots() {
        val empty = ComplaintHistoryPages()
        assertTrue(empty.accept(decodedHistory()))
        assertTrue(empty.complete)
        assertTrue(empty.snapshot().items.isEmpty())
        val pages = ComplaintHistoryPages()
        assertTrue(pages.accept(decodedHistory((100 downTo 51).map { historyItem(it) }, cursor = "v1.first.mac")))
        assertFalse(pages.complete)
        assertTrue(pages.accept(decodedHistory((50 downTo 1).map { historyItem(it) }, cursor = "v1.second.mac")))
        assertFalse(pages.complete)
        assertTrue(pages.accept(decodedHistory()))
        assertTrue(pages.complete)
        assertEquals(100, pages.snapshot().items.size)
        assertFalse(pages.accept(decodedHistory()))
    }

    @Test
    fun repeatedCursorLaterNoticesDuplicateIdsAndAscendingOrderFailClosed() {
        val invalidSeconds = listOf(
            decodedHistory(listOf(historyItem(9)), cursor = "v1.same.mac"),
            decodedHistory(listOf(historyItem(9)), notices = listOf(historyNotice())),
            decodedHistory(listOf(historyItem(10))),
            decodedHistory(listOf(historyItem(11))),
            decodedHistory(listOf(historyItem(9, createdAt = "2026-09-17T08:09:10Z"))),
        )
        for (second in invalidSeconds) {
            val pages = ComplaintHistoryPages()
            assertTrue(pages.accept(decodedHistory(listOf(historyItem(10)), cursor = "v1.same.mac")))
            assertFalse(pages.accept(second))
            assertFalse(pages.complete)
        }
        val loop = ComplaintHistoryPages()
        assertFalse(loop.accept(decodedHistory(cursor = "v1.empty.mac")))
    }

    @Test
    fun thirdPopulatedPageAndOneHundredAndFirstOwnerRowCannotBePublished() {
        val small = ComplaintHistoryPages()
        assertTrue(small.accept(decodedHistory(listOf(historyItem(3)), cursor = "v1.a.mac")))
        assertTrue(small.accept(decodedHistory(listOf(historyItem(2)), cursor = "v1.b.mac")))
        assertFalse(small.accept(decodedHistory(listOf(historyItem(1)))))
        val full = ComplaintHistoryPages()
        assertTrue(full.accept(decodedHistory((101 downTo 52).map { historyItem(it) }, cursor = "v1.a.mac")))
        assertTrue(full.accept(decodedHistory((51 downTo 2).map { historyItem(it) }, cursor = "v1.b.mac")))
        assertFalse(full.accept(decodedHistory(listOf(historyItem(1)))))
        assertFalse(full.complete)
    }

    @Test
    fun unrecognizedItemsShareCanonicalIdentityAndTimestampOrderingWithoutContentFields() {
        val pages = ComplaintHistoryPages()
        assertTrue(pages.accept(decodedHistory(listOf(historyItem(2, kind = "FUTURE")), cursor = "v1.a.mac")))
        assertTrue(pages.accept(decodedHistory(listOf(historyItem(1)))))
        assertEquals(listOf(historyId(2), historyId(1)), pages.snapshot().items.map { it.id })
        val duplicate = ComplaintHistoryPages()
        assertTrue(duplicate.accept(decodedHistory(listOf(historyItem(2)), cursor = "v1.a.mac")))
        assertFalse(duplicate.accept(decodedHistory(listOf(historyItem(2, kind = "FUTURE")))))
    }
}
