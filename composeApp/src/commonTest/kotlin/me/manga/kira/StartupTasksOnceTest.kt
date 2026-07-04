package me.manga.kira

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-07 audit — `App()`'s one-shot startup tasks (download reconcile / config sync / app_open)
 * must run once per PROCESS, not once per composition: an Android Activity recreation (rotation)
 * rebuilds the composition and re-fired them, resetting actively-downloading rows to QUEUED and
 * duplicating analytics. [StartupTasksOnce] is the guard.
 */
class StartupTasksOnceTest {
    @BeforeTest
    fun freshProcess() {
        StartupTasksOnce.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        StartupTasksOnce.resetForTests()
    }

    @Test
    fun firstClaimWins_everyLaterCompositionIsRefused() {
        assertTrue(StartupTasksOnce.claim(), "fresh process must run the startup tasks")
        assertFalse(StartupTasksOnce.claim(), "an Activity recreation must NOT re-run them")
        assertFalse(StartupTasksOnce.claim(), "…nor any later recreation")
    }

    @Test
    fun resetRestoresFreshProcessSemantics() {
        assertTrue(StartupTasksOnce.claim())
        StartupTasksOnce.resetForTests()
        assertTrue(StartupTasksOnce.claim(), "a new process starts with a fresh claim")
    }
}
