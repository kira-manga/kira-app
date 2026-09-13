package me.manga.kira.ui.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.manga.kira.presentation.library.LibraryIntent
import me.manga.kira.ui.accessibility.assertSingleSwitch
import me.manga.kira.ui.accessibility.assertSwitchLabelOrder
import me.manga.kira.ui.accessibility.clickSwitchControl
import me.manga.kira.ui.accessibility.clickSwitchLabel
import me.manga.kira.ui.accessibility.rawSubtree
import me.manga.kira.ui.accessibility.runSharedControlSemanticsTest
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.items_per_row_label
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_display
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_sort
import me.manga.kira.ui.generated.resources.sort_direction_ascending
import me.manga.kira.ui.generated.resources.sort_direction_descending
import me.manga.kira.ui.generated.resources.sort_direction_label
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibraryControlSemanticsTest {
    @Test
    fun englishLabelsAssociateWithDirectionDisplayAndRangeActions() =
        libraryControls(Locale.US, LayoutDirection.Ltr)

    @Test
    fun arabicResourcesAndRtlPreserveNamesStatesAndActions() =
        libraryControls(Locale.forLanguageTag("ar"), LayoutDirection.Rtl)

    private fun libraryControls(locale: Locale, direction: LayoutDirection) = runSharedControlSemanticsTest(locale) {
        val surface = LibraryControlSemanticsFixture()
        surface.render(this, direction)
        awaitIdle()
        assertEquals(
            if (locale.language == "ar") "اتجاه الفرز" else "Sort Direction",
            surface.label(Res.string.sort_direction_label),
        )
        assertEquals(
            if (locale.language == "ar") "العناصر في الصف:" else "Items per row:",
            surface.label(Res.string.items_per_row_label),
        )
        assertEquals(if (locale.language == "ar") "تلقائي" else "Auto", surface.caption(0))
        assertDirectionControl(surface, direction)
        onNodeWithText(surface.label(Res.string.library_bottom_sheet_tab_display)).performClick()
        awaitIdle()
        assertDisplayControls(surface, direction)
        assertSliderActions(surface, direction)
        assertEquals(0, surface.dismissals)
    }

    private suspend fun ComposeUiTest.assertDirectionControl(
        surface: LibraryControlSemanticsFixture,
        direction: LayoutDirection,
    ) {
        val label = surface.label(Res.string.sort_direction_label)
        val ascending = surface.label(Res.string.sort_direction_ascending)
        val descending = surface.label(Res.string.sort_direction_descending)
        onNodeWithText(surface.label(Res.string.library_bottom_sheet_tab_sort)).performClick()
        awaitIdle()
        assertSingleSwitch(label, true, stateDescription = ascending, minimumHeight = 64.dp)
        assertSwitchLabelOrder(label, direction)
        clickSwitchLabel(label)
        awaitIdle()
        assertSingleSwitch(label, false, stateDescription = descending, minimumHeight = 64.dp)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        assertSingleSwitch(label, true, stateDescription = ascending, minimumHeight = 64.dp)
        clickSwitchControl(label, direction)
        awaitIdle()
        assertSingleSwitch(label, false, stateDescription = descending, minimumHeight = 64.dp)
        onAllNodesWithText(descending, useUnmergedTree = true).assertCountEquals(0)
        assertEquals<List<LibraryIntent>>(List(3) { LibraryIntent.OnSortDirectionToggle }, surface.intents)
        runOnIdle { surface.intents.clear() }
    }

    private suspend fun ComposeUiTest.assertDisplayControls(
        surface: LibraryControlSemanticsFixture,
        direction: LayoutDirection,
    ) {
        for ((key, intent) in libraryDisplayToggleCases) {
            val label = surface.label(key)
            assertSingleSwitch(label, true, minimumHeight = 56.dp)
            assertSwitchLabelOrder(label, direction)
            clickSwitchLabel(label)
            awaitIdle()
            assertSingleSwitch(label, false, minimumHeight = 56.dp)
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            awaitIdle()
            assertSingleSwitch(label, true, minimumHeight = 56.dp)
            clickSwitchControl(label, direction)
            awaitIdle()
            assertSingleSwitch(label, false, minimumHeight = 56.dp)
            assertEquals(listOf(intent(false), intent(true), intent(false)), surface.intents)
            runOnIdle { surface.intents.clear() }
        }
    }

    private suspend fun ComposeUiTest.assertSliderActions(
        surface: LibraryControlSemanticsFixture,
        direction: LayoutDirection,
    ) {
        assertRange(surface, 0).performSemanticsAction(SemanticsActions.SetProgress) { it(3.4f) }
        awaitIdle()
        assertRange(surface, 3).performSemanticsAction(SemanticsActions.SetProgress) { it(3.2f) }
        awaitIdle()
        assertEquals<List<LibraryIntent>>(listOf(LibraryIntent.OnItemsPerRowChange(3)), surface.intents)
        assertRange(surface, 3).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        awaitIdle()
        assertRange(surface, 1).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        awaitIdle()
        assertRange(surface, 1).assertIsFocused().performKeyInput {
            pressKey(if (direction == LayoutDirection.Ltr) Key.DirectionRight else Key.DirectionLeft)
        }
        awaitIdle()
        assertRange(surface, 2)
        assertEquals<List<LibraryIntent>>(
            listOf(
                LibraryIntent.OnItemsPerRowChange(3),
                LibraryIntent.OnItemsPerRowChange(1),
                LibraryIntent.OnItemsPerRowChange(2),
            ),
            surface.intents,
        )
        runOnIdle { surface.intents.clear() }
        assertRange(surface, 2).performTouchInput {
            // Tap the maximum end of the existing slider, mirrored independently of resource locale.
            click(Offset(if (direction == LayoutDirection.Ltr) width - 1f else 1f, center.y))
        }
        awaitIdle()
        assertRange(surface, 8)
        assertTrue(surface.intents.isNotEmpty())
        assertTrue(surface.intents.all { it == LibraryIntent.OnItemsPerRowChange(8) })
    }

    private fun ComposeUiTest.assertRange(surface: LibraryControlSemanticsFixture, count: Int): SemanticsNodeInteraction {
        val label = surface.label(Res.string.items_per_row_label)
        val caption = surface.caption(count)
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(1)
        val slider = onNodeWithContentDescription(label)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, caption))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(count.toFloat(), 0f..8f, steps = 7),
                ),
            )
        val raw = rawSubtree(slider)
        assertEquals(1, raw.count { it.config.getOrNull(SemanticsActions.SetProgress) != null })
        assertEquals(1, raw.count { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null })
        onAllNodesWithText(label, useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText(caption, useUnmergedTree = true).assertCountEquals(0)
        return slider
    }
}
