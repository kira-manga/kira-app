package me.manga.kira.ui.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.presentation.library.LibraryIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            fixture.activate(fixture.slider())
            fixture.expectOnly(LibraryIntent.OnItemsPerRowChange(4))
            val toggles =
                listOf(
                    LibraryIntent.OnToggleShowDetails(false),
                    LibraryIntent.OnToggleShowSource(false),
                    LibraryIntent.OnToggleShowCount(false),
                    LibraryIntent.OnToggleShowButtons(false),
                    LibraryIntent.OnToggleShowTabs(false),
                )
            toggles.forEachIndexed { index, expected ->
                fixture.activate(fixture.displaySwitch(index))
                fixture.displaySwitch(index).assertIsOff()
                fixture.expectOnly(expected)
            }
        }

    @Test
    fun compactDoubleTextFilterAndSortControlsRemainReachable() =
        runLibraryOptionsSheetTest { fixture ->
            LibraryFilter.entries.forEach { option ->
                fixture.activate(fixture.filterChip(option))
                fixture.expectOnly(LibraryIntent.OnFilterChange(option))
            }
            fixture.selectTab(LibraryOptionsTab.SORT)
            fixture.activate(fixture.directionSwitch())
            fixture.directionSwitch().assertIsOff()
            fixture.expectOnly(LibraryIntent.OnSortDirectionToggle)
            LibrarySort.entries.forEach { option ->
                fixture.activate(fixture.sortChip(option))
                fixture.expectOnly(LibraryIntent.OnSortChange(option))
            }
        }

    @Test
    fun shorterTabNativelyClampsThePreviousScrollOffset() =
        runLibraryOptionsSheetTest { fixture ->
            fixture.selectTab(LibraryOptionsTab.DISPLAY)
            fixture.reveal(fixture.displaySwitch(4))
            val longRange = fixture.scrollRange()
            assertTrue(longRange.value > 0f)

            fixture.selectTab(LibraryOptionsTab.FILTER)
            val shortRange = fixture.scrollRange()
            assertTrue(shortRange.maxValue < longRange.maxValue)
            assertEquals(0f, shortRange.maxValue, "The shorter filter body fits this scene")
            assertEquals(0f, shortRange.value, "Native ScrollState must discard the now-impossible offset")
            // No explicit scroll/reset after a tab change: these controls must already be visible.
            fixture.bounds(fixture.filterChip(LibraryFilter.ALL))
            fixture.selectTab(LibraryOptionsTab.SORT)
            fixture.bounds(fixture.directionSwitch())
            fixture.selectTab(LibraryOptionsTab.DISPLAY)
            fixture.bounds(fixture.slider())
            fixture.expectOnly()
        }

    @Test
    fun portraitKeepsNaturalSizingAndRealModalDismissal() =
        runLibraryOptionsSheetTest(size = libraryOptionsPortraitSize, fontScale = 1f) { fixture ->
            assertEquals(0f, fixture.scrollRange().maxValue)
            LibraryFilter.entries.forEach { fixture.bounds(fixture.filterChip(it)) }
            fixture.selectTab(LibraryOptionsTab.DISPLAY)
            assertEquals(0f, fixture.scrollRange().maxValue)
            fixture.bounds(fixture.slider())
            repeat(5) { fixture.bounds(fixture.displaySwitch(it)) }
            assertTrue(fixture.sheetBounds().height < libraryOptionsPortraitSize.height)
            fixture.expectOnly()

            fixture.dismissUsingScrim()
            assertEquals(1, fixture.dismissCount)
            fixture.reopen()
            fixture.dismissUsingBack()
            assertEquals(2, fixture.dismissCount)
        }
}
