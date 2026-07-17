package me.manga.kira.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BottomBarVisibilityTest {
    @Test
    fun primary_destinations_show_the_bottom_bar() {
        listOf(
            Screen.Library,
            Screen.Updates,
            Screen.Home,
            Screen.History,
            Screen.Setting,
        ).forEach { screen ->
            assertTrue(shouldShowBottomBar(sequenceOf(screen.route)))
        }
    }

    @Test
    fun a_primary_destination_in_a_nested_hierarchy_shows_the_bottom_bar() {
        assertTrue(
            shouldShowBottomBar(
                sequenceOf("nested.graph", Screen.Home.route),
            ),
        )
    }

    @Test
    fun pushed_and_missing_destinations_hide_the_bottom_bar() {
        assertFalse(shouldShowBottomBar(sequenceOf(Screen.StartReading().route)))
        assertFalse(shouldShowBottomBar(sequenceOf(Screen.Sources.route)))
        assertFalse(shouldShowBottomBar(sequenceOf(null)))
        assertFalse(shouldShowBottomBar(emptySequence()))
    }
}
