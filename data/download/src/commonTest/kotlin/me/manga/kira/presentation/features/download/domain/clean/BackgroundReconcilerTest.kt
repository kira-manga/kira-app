package me.manga.kira.presentation.features.download.domain.clean

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic tests for [BackgroundReconciler] (background-downloads M3). No I/O — exercises completion,
 * dedupe (on-disk + in-flight), bounded-retry exhaustion, and mixed states.
 */
class BackgroundReconcilerTest {

    private fun manifest(vararg pages: ManifestPage) =
        DownloadManifest(mangaId = 1L, chapterId = 10L, api = "test", pages = pages.toList())

    private fun page(index: Int, attempts: Int = 0) =
        ManifestPage(index = index, url = "https://example/$index.jpg", headers = emptyMap(), attempts = attempts)

    @Test
    fun allPagesOnDisk_isComplete() {
        val plan = BackgroundReconciler.plan(
            manifest(page(0), page(1), page(2)),
            pagesOnDisk = setOf(0, 1, 2),
            inFlightPages = emptySet(),
            maxAttempts = 3,
        )
        assertTrue(plan.isComplete)
        assertTrue(plan.toEnqueue.isEmpty())
        assertNull(plan.failedPageIndex)
    }

    @Test
    fun nothingOnDisk_enqueuesAll() {
        val plan = BackgroundReconciler.plan(
            manifest(page(0), page(1), page(2)),
            pagesOnDisk = emptySet(),
            inFlightPages = emptySet(),
            maxAttempts = 3,
        )
        assertFalse(plan.isComplete)
        assertEquals(listOf(0, 1, 2), plan.toEnqueue)
        assertNull(plan.failedPageIndex)
    }

    @Test
    fun onDiskPagesAreSkipped() {
        val plan = BackgroundReconciler.plan(
            manifest(page(0), page(1), page(2), page(3)),
            pagesOnDisk = setOf(0, 2),
            inFlightPages = emptySet(),
            maxAttempts = 3,
        )
        assertEquals(listOf(1, 3), plan.toEnqueue)
        assertFalse(plan.isComplete)
        assertNull(plan.failedPageIndex)
    }

    @Test
    fun inFlightPagesAreNotReEnqueued() {
        val plan = BackgroundReconciler.plan(
            manifest(page(0), page(1), page(2)),
            pagesOnDisk = emptySet(),
            inFlightPages = setOf(1),
            maxAttempts = 3,
        )
        assertEquals(listOf(0, 2), plan.toEnqueue) // page 1 is in-flight → left alone
        assertFalse(plan.isComplete)
        assertNull(plan.failedPageIndex)
    }

    @Test
    fun retryExhaustedPageFailsChapter() {
        val plan = BackgroundReconciler.plan(
            manifest(page(0), page(1, attempts = 3)),
            pagesOnDisk = setOf(0),
            inFlightPages = emptySet(),
            maxAttempts = 3,
        )
        assertEquals(1, plan.failedPageIndex)
        assertTrue(plan.toEnqueue.isEmpty()) // do not enqueue when the chapter is failing
        assertFalse(plan.isComplete)
    }

    @Test
    fun exhaustedButInFlightDoesNotFail() {
        val plan = BackgroundReconciler.plan(
            manifest(page(0), page(1, attempts = 5)),
            pagesOnDisk = setOf(0),
            inFlightPages = setOf(1),
            maxAttempts = 3,
        )
        assertNull(plan.failedPageIndex) // over budget but still transferring → leave it
        assertTrue(plan.toEnqueue.isEmpty())
        assertFalse(plan.isComplete)
    }

    @Test
    fun underBudgetPageIsRetried() {
        val plan = BackgroundReconciler.plan(
            manifest(page(0, attempts = 2)),
            pagesOnDisk = emptySet(),
            inFlightPages = emptySet(),
            maxAttempts = 3,
        )
        assertEquals(listOf(0), plan.toEnqueue) // attempts(2) < max(3) → retry
        assertNull(plan.failedPageIndex)
        assertFalse(plan.isComplete)
    }

    @Test
    fun emptyManifestIsComplete() {
        val plan = BackgroundReconciler.plan(manifest(), emptySet(), emptySet(), 3)
        assertTrue(plan.isComplete)
        assertTrue(plan.toEnqueue.isEmpty())
        assertNull(plan.failedPageIndex)
    }

    @Test
    fun mixedOnDiskInFlightAndEnqueue() {
        // 0,1 on disk; 2 in-flight; 3,4 still to enqueue.
        val plan = BackgroundReconciler.plan(
            manifest(page(0), page(1), page(2), page(3), page(4)),
            pagesOnDisk = setOf(0, 1),
            inFlightPages = setOf(2),
            maxAttempts = 3,
        )
        assertEquals(listOf(3, 4), plan.toEnqueue)
        assertFalse(plan.isComplete)
        assertNull(plan.failedPageIndex)
    }
}
