package me.manga.kira.navigation.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [NotificationRouter] — the cold-start-safe, deliver-once, jam-free deep-link bus.
 */
class NotificationRouterTest {

    private val sample = PushDestination.MangaDetail(api = "azora", url = "u")

    @Test
    fun initialPendingIsNull() {
        assertNull(NotificationRouter().pending.value)
    }

    @Test
    fun submitBeforeCollect_isRetained_coldStart() {
        // Simulates a notification cold-launch: submit happens before the nav host observes; the
        // StateFlow retains the value so a late collector's first emission carries it.
        val router = NotificationRouter()
        router.submit(sample)
        assertEquals(sample, router.pending.value?.destination)
    }

    @Test
    fun consume_clearsPending_soItIsNotActedOnAgain() {
        val router = NotificationRouter()
        router.submit(sample)
        router.consume()
        assertNull(router.pending.value)
    }

    @Test
    fun latestSubmissionWins() {
        val router = NotificationRouter()
        router.submit(sample)
        router.submit(PushDestination.Updates)
        assertEquals(PushDestination.Updates, router.pending.value?.destination)
    }

    @Test
    fun resubmitEqualDestinationAfterConsume_isDistinctValue_noConflationJam() {
        // The jam (#10): submit A -> consume (null) -> submit A must yield a value NOT equal to the
        // first, so the collecting StateFlow re-emits and the host effect restarts, instead of
        // conflating A -> null -> A away and stranding an un-consumed value.
        val router = NotificationRouter()
        router.submit(sample)
        val first = router.pending.value
        router.consume()
        router.submit(sample)
        val second = router.pending.value

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(sample, second.destination)
        assertNotEquals(first, second) // different seq → StateFlow re-emits, LaunchedEffect key changes
    }

    @Test
    fun resubmitEqualTabDestination_isDistinctValue() {
        // Tab destinations are data objects (always structurally equal); the seq must still make
        // consecutive submissions distinct.
        val router = NotificationRouter()
        router.submit(PushDestination.Updates)
        val first = router.pending.value
        router.submit(PushDestination.Updates)
        val second = router.pending.value
        assertNotEquals(first, second)
    }
}
