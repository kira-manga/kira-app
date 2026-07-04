package me.manga.kira.presentation.features.download.domain.clean

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic regression tests for [ResolveAheadRules] — the limited resolve-ahead's target
 * selection. Locks the owner's safety constraints: the window cap (lookahead must never fan out a
 * whole batch), the one-scrape-at-a-time serialization, no duplicate work against manifests or
 * in-flight resolves, and probe laziness (nothing beyond the window is ever even examined).
 */
class ResolveAheadRulesTest {

    private fun select(
        queued: List<Long>,
        window: Int = 3,
        resolving: Set<Long> = emptySet(),
        prefetching: Set<Long> = emptySet(),
        manifested: Set<Long> = emptySet(),
        probed: MutableList<Long> = mutableListOf(),
    ): Long? = ResolveAheadRules.selectNextPrefetch(
        queuedInProcessingOrder = queued,
        window = window,
        resolving = resolving,
        prefetching = prefetching,
        hasManifest = { id -> probed.add(id); id in manifested },
    )

    @Test
    fun windowZero_disablesResolveAhead_withoutProbing() {
        val probed = mutableListOf<Long>()
        assertNull(select(queued = listOf(1L, 2L, 3L), window = 0, probed = probed))
        assertTrue(probed.isEmpty(), "disabled selection must not touch the filesystem probe")
    }

    @Test
    fun inFlightPrefetch_serializesToOneScrapeAtATime() {
        // A non-empty prefetching set selects nothing — combined with the engine's spacing delay
        // and pause-on-failure this is the anti-hammer guarantee.
        val probed = mutableListOf<Long>()
        assertNull(select(queued = listOf(1L, 2L, 3L), prefetching = setOf(9L), probed = probed))
        assertTrue(probed.isEmpty())
    }

    @Test
    fun picksTheFirstUnmanifestedChapter_inProcessingOrder() {
        assertEquals(1L, select(queued = listOf(1L, 2L, 3L, 4L)))
    }

    @Test
    fun skipsChaptersThatAlreadyHaveManifests() {
        assertEquals(3L, select(queued = listOf(1L, 2L, 3L), manifested = setOf(1L, 2L)))
    }

    @Test
    fun skipsChaptersAlreadyBeingResolvedForReal() {
        assertEquals(2L, select(queued = listOf(1L, 2L), resolving = setOf(1L)))
    }

    @Test
    fun neverLooksBeyondTheWindow() {
        // Chapters 1-3 are covered (manifested); chapter 4 is beyond this window of 3 and must be
        // neither selected NOR probed — a 100-chapter batch costs at most `window` probes per top-up.
        val probed = mutableListOf<Long>()
        assertNull(select(queued = listOf(1L, 2L, 3L, 4L, 5L), manifested = setOf(1L, 2L, 3L), probed = probed))
        assertEquals(listOf(1L, 2L, 3L), probed)
    }

    @Test
    fun productionWindowOfSix_coversSixChapters_andNoMore() {
        // The engine ships RESOLVE_AHEAD_WINDOW = 6 (owner-tuned 2026-07-02): with chapters 1-5
        // covered, the 6th is selected; with 1-6 covered, nothing is — and the 7th is never probed.
        val queued = (1L..8L).toList()
        assertEquals(6L, select(queued = queued, window = 6, manifested = setOf(1L, 2L, 3L, 4L, 5L)))
        val probed = mutableListOf<Long>()
        assertNull(select(queued = queued, window = 6, manifested = (1L..6L).toSet(), probed = probed))
        assertEquals((1L..6L).toList(), probed)
    }

    @Test
    fun fullyCoveredWindow_selectsNothing() {
        assertNull(select(queued = listOf(1L, 2L, 3L), manifested = setOf(1L, 2L, 3L)))
    }

    @Test
    fun emptyQueue_selectsNothing() {
        assertNull(select(queued = emptyList()))
    }

    @Test
    fun windowSmallerThanQueue_capsTheCandidates() {
        // Window 1: only the head of the queue is ever a candidate.
        assertNull(select(queued = listOf(1L, 2L), window = 1, manifested = setOf(1L)))
        assertEquals(1L, select(queued = listOf(1L, 2L), window = 1))
    }
}
