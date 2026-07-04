package me.manga.kira.domain.model.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * Contract tests for the [PageDownloadProgress] sealed hierarchy.
 *
 * The repository `distinctUntilChanged`es on these values, so structural equality (InProgress over
 * its fraction) and data-object singleton identity are load-bearing for recomposition discipline.
 */
class PageDownloadProgressTest {

    @Test
    fun inProgress_equality_is_by_fraction() {
        assertEquals(PageDownloadProgress.InProgress(0.5f), PageDownloadProgress.InProgress(0.5f))
        assertEquals(PageDownloadProgress.InProgress(null), PageDownloadProgress.InProgress(null))
        assertNotEquals(PageDownloadProgress.InProgress(0.5f), PageDownloadProgress.InProgress(0.25f))
        assertNotEquals(PageDownloadProgress.InProgress(0.5f), PageDownloadProgress.InProgress(null))
    }

    @Test
    fun data_objects_are_singletons_and_pairwise_distinct() {
        assertSame(PageDownloadProgress.Idle, PageDownloadProgress.Idle)
        val objects = listOf(
            PageDownloadProgress.Idle,
            PageDownloadProgress.Started,
            PageDownloadProgress.Decoding,
            PageDownloadProgress.Complete,
            PageDownloadProgress.Failed,
        )
        for (i in objects.indices) {
            for (j in objects.indices) {
                if (i == j) {
                    assertEquals(objects[i], objects[j])
                } else {
                    assertNotEquals(objects[i], objects[j], "${objects[i]} should differ from ${objects[j]}")
                }
            }
        }
    }

    @Test
    fun when_over_the_hierarchy_is_exhaustive() {
        fun label(p: PageDownloadProgress): String = when (p) {
            PageDownloadProgress.Idle -> "idle"
            PageDownloadProgress.Started -> "started"
            is PageDownloadProgress.InProgress -> "inprogress"
            PageDownloadProgress.Decoding -> "decoding"
            PageDownloadProgress.Complete -> "complete"
            PageDownloadProgress.Failed -> "failed"
        }
        assertEquals("idle", label(PageDownloadProgress.Idle))
        assertEquals("inprogress", label(PageDownloadProgress.InProgress(0.1f)))
        assertEquals("failed", label(PageDownloadProgress.Failed))
    }
}
