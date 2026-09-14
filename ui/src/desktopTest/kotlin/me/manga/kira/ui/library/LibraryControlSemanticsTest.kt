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
import androidx.compose.ui.test.assertHeightIsAtLeast
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
    fun englishLabelsAssociateWithDirectionDisplayAndRangeActions() = libraryControls(Locale.US, LayoutDirection.Ltr)

    @Test
    fun arabicResourcesAndRtlPreserveNamesStatesAndActions() =
        libraryControls(
            Locale.forLanguageTag("ar"),
            LayoutDirection.Rtl,
        )

    private fun libraryControls(
        locale: Locale,
        direction: LayoutDirection,
    ) = runSharedControlSemanticsTest(locale) {
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
        assertSingleSwitch(label, true, stateDescription = ascending).assertHeightIsAtLeast(64.dp)
        assertSwitchLabelOrder(label, direction)
        clickSwitchLabel(label)
        awaitIdle()
        assertSingleSwitch(label, false, stateDescription = descending)
            .assertHeightIsAtLeast(64.dp)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        assertSingleSwitch(label, true, stateDescription = ascending).assertHeightIsAtLeast(64.dp)
        clickSwitchControl(label, direction)
        awaitIdle()
        assertSingleSwitch(label, false, stateDescription = descending).assertHeightIsAtLeast(64.dp)
        onAllNodesWithText(descending, useUnmergedTree = true).assertCountEquals(0)
        assertEquals<List<LibraryIntent>>(
            List(TOGGLE_ACTIVATION_COUNT) { LibraryIntent.OnSortDirectionToggle },
            surface.intents,
        )
        runOnIdle { surface.intents.clear() }
    }

    private suspend fun ComposeUiTest.assertDisplayControls(
        surface: LibraryControlSemanticsFixture,
        direction: LayoutDirection,
    ) {
        for ((key, intent) in libraryDisplayToggleCases) {
            val label = surface.label(key)
            assertSingleSwitch(label, true).assertHeightIsAtLeast(56.dp)
            assertSwitchLabelOrder(label, direction)
            clickSwitchLabel(label)
            awaitIdle()
            assertSingleSwitch(label, false)
                .assertHeightIsAtLeast(56.dp)
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            awaitIdle()
            assertSingleSwitch(label, true).assertHeightIsAtLeast(56.dp)
            clickSwitchControl(label, direction)
            awaitIdle()
            assertSingleSwitch(label, false).assertHeightIsAtLeast(56.dp)
            assertEquals(listOf(intent(false), intent(true), intent(false)), surface.intents)
            runOnIdle { surface.intents.clear() }
        }
    }

    private suspend fun ComposeUiTest.assertSliderActions(
        surface: LibraryControlSemanticsFixture,
        direction: LayoutDirection,
    ) {
        assertRange(surface, 0).performSemanticsAction(SemanticsActions.SetProgress) { it(INITIAL_FRACTIONAL_PROGRESS) }
        awaitIdle()
        assertRange(surface, ROUNDED_ITEMS_PER_ROW)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(SAME_ROUNDED_PROGRESS) }
        awaitIdle()
        assertEquals<List<LibraryIntent>>(
            listOf(LibraryIntent.OnItemsPerRowChange(ROUNDED_ITEMS_PER_ROW)),
            surface.intents,
        )
        assertRange(surface, ROUNDED_ITEMS_PER_ROW).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
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
                LibraryIntent.OnItemsPerRowChange(ROUNDED_ITEMS_PER_ROW),
                LibraryIntent.OnItemsPerRowChange(1),
                LibraryIntent.OnItemsPerRowChange(2),
            ),
            surface.intents,
        )
        runOnIdle { surface.intents.clear() }
        assertRange(surface, 2).performTouchInput {
            // Stay inside the pointer region, beyond Material3's 10dp semantics-only margin.
            // This fixture uses Density(1); mirror the interior maximum endpoint for RTL.
            click(
                Offset(
                    if (direction == LayoutDirection.Ltr) width - SLIDER_POINTER_INSET_PX else SLIDER_POINTER_INSET_PX,
                    center.y,
                ),
            )
        }
        awaitIdle()
        assertRange(surface, MAX_ITEMS_PER_ROW)
        assertTrue(surface.intents.isNotEmpty())
        assertTrue(surface.intents.all { it == LibraryIntent.OnItemsPerRowChange(MAX_ITEMS_PER_ROW) })
    }

    private fun ComposeUiTest.assertRange(
        surface: LibraryControlSemanticsFixture,
        count: Int,
    ): SemanticsNodeInteraction {
        val label = surface.label(Res.string.items_per_row_label)
        val caption = surface.caption(count)
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(1)
        val slider =
            onNodeWithContentDescription(label)
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, caption))
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ProgressBarRangeInfo,
                        ProgressBarRangeInfo(count.toFloat(), 0f..MAX_ITEMS_PER_ROW_PROGRESS, steps = 7),
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

private const val TOGGLE_ACTIVATION_COUNT = 3
private const val INITIAL_FRACTIONAL_PROGRESS = 3.4f
private const val SAME_ROUNDED_PROGRESS = 3.2f
private const val ROUNDED_ITEMS_PER_ROW = 3
private const val SLIDER_POINTER_INSET_PX = 16f
private const val MAX_ITEMS_PER_ROW_PROGRESS = 8f
