package me.manga.kira.domain.model.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract test for [LibraryDisplay].
 *
 * All five flags MUST default to `true` so a first-run rework user sees every Library surface,
 * matching the legacy default-true posture (the persistence cell is unwritten on first run).
 */
class LibraryDisplayTest {

    @Test
    fun all_flags_default_to_true_for_first_run_parity() {
        val d = LibraryDisplay()
        assertTrue(d.showSource)
        assertTrue(d.showCount)
        assertTrue(d.showDetails)
        assertTrue(d.showButtons)
        assertTrue(d.showTabs)
    }

    @Test
    fun copy_flips_exactly_one_flag() {
        val d = LibraryDisplay().copy(showTabs = false)
        assertFalse(d.showTabs)
        assertTrue(d.showSource)
        assertTrue(d.showCount)
        assertTrue(d.showDetails)
        assertTrue(d.showButtons)
    }
}
