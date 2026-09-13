package me.manga.kira.ui.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.presentation.library.LibraryIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SLIDER_MIDPOINT_ITEMS_PER_ROW = 4
private const val LAST_DISPLAY_SWITCH_INDEX = 4

@OptIn(ExperimentalTestApi::class)
class LibraryOptionsSheetReachabilityTest {
    @Test
    fun compactDoubleTextDisplayControlsRemainReachable() =
        runLibraryOptionsSheetTest { fixture ->
            fixture.selectTab(LibraryOptionsTab.DISPLAY)
            assertTrue(fixture.scrollRange().maxValue > 0f, "The compact case must really overflow")
            fixture.swipeBodyUp()
            assertTrue(fixture.scrollRange().value > 0f, "A real content swipe must scroll, not dismiss the sheet")
            fixture.expectOnly()

            // A center-track pointer tap selects the middle of the production 0..8 Slider.
            fixture.activate(fixture.nodes.slider())
            fixture.expectOnly(LibraryIntent.OnItemsPerRowChange(SLIDER_MIDPOINT_ITEMS_PER_ROW))
            val toggles =
                listOf(
                    LibraryIntent.OnToggleShowDetails(false),
                    LibraryIntent.OnToggleShowSource(false),
                    LibraryIntent.OnToggleShowCount(false),
                    LibraryIntent.OnToggleShowButtons(false),
                    LibraryIntent.OnToggleShowTabs(false),
                )
            toggles.forEachIndexed { index, expected ->
                fixture.activate(fixture.nodes.displaySwitch(index))
                fixture.nodes.displaySwitch(index).assertIsOff()
                fixture.expectOnly(expected)
            }
        }

    @Test
    fun compactDoubleTextFilterAndSortControlsRemainReachable() =
        runLibraryOptionsSheetTest { fixture ->
            LibraryFilter.entries.forEach { option ->
                fixture.activate(fixture.nodes.filterChip(option))
                fixture.expectOnly(LibraryIntent.OnFilterChange(option))
            }
            fixture.selectTab(LibraryOptionsTab.SORT)
            fixture.activate(fixture.nodes.directionSwitch())
            fixture.nodes.directionSwitch().assertIsOff()
            fixture.expectOnly(LibraryIntent.OnSortDirectionToggle)
            LibrarySort.entries.forEach { option ->
                fixture.activate(fixture.nodes.sortChip(option))
                fixture.expectOnly(LibraryIntent.OnSortChange(option))
            }
        }

    @Test
    fun shorterTabNativelyClampsThePreviousScrollOffset() =
        runLibraryOptionsSheetTest { fixture ->
            fixture.selectTab(LibraryOptionsTab.DISPLAY)
            fixture.reveal(fixture.nodes.displaySwitch(LAST_DISPLAY_SWITCH_INDEX))
            val longRange = fixture.scrollRange()
            assertTrue(longRange.value > 0f)

            fixture.selectTab(LibraryOptionsTab.FILTER)
            val shortRange = fixture.scrollRange()
            assertTrue(shortRange.maxValue < longRange.maxValue)
            assertEquals(0f, shortRange.maxValue, "The shorter filter body fits this scene")
            assertEquals(0f, shortRange.value, "Native ScrollState must discard the now-impossible offset")
            // No explicit scroll/reset after a tab change: these controls must already be visible.
            fixture.nodes.bounds(fixture.nodes.filterChip(LibraryFilter.ALL))
            fixture.selectTab(LibraryOptionsTab.SORT)
            fixture.nodes.bounds(fixture.nodes.directionSwitch())
            fixture.selectTab(LibraryOptionsTab.DISPLAY)
            fixture.nodes.bounds(fixture.nodes.slider())
            fixture.expectOnly()
        }

    @Test
    fun portraitKeepsNaturalSizingAndRealModalDismissal() =
        runLibraryOptionsSheetTest(size = libraryOptionsPortraitSize, fontScale = 1f) { fixture ->
            assertEquals(0f, fixture.scrollRange().maxValue)
            LibraryFilter.entries.forEach { fixture.nodes.bounds(fixture.nodes.filterChip(it)) }
            fixture.selectTab(LibraryOptionsTab.DISPLAY)
            assertEquals(0f, fixture.scrollRange().maxValue)
            fixture.nodes.bounds(fixture.nodes.slider())
            repeat(LIBRARY_DISPLAY_SWITCH_COUNT) {
                fixture.nodes.bounds(fixture.nodes.displaySwitch(it))
            }
            assertTrue(fixture.nodes.sheetBounds().height < libraryOptionsPortraitSize.height)
            fixture.expectOnly()

            fixture.dismissUsingScrim()
            assertEquals(1, fixture.dismissCount)
            fixture.reopen()
            fixture.dismissUsingBack()
            assertEquals(2, fixture.dismissCount)
        }
}
