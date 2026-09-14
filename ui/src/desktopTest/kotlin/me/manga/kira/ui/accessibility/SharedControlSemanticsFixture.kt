package me.manga.kira.ui.accessibility

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private object SharedControlLocaleLock

/** App34 cases use a real resource locale, not just an RTL layout override. */
@OptIn(ExperimentalTestApi::class)
internal fun runSharedControlSemanticsTest(
    locale: Locale = Locale.US,
    block: suspend ComposeUiTest.() -> Unit,
) = synchronized(SharedControlLocaleLock) {
    val previous = Locale.getDefault()
    try {
        Locale.setDefault(locale)
        runSkikoComposeUiTest(size = Size(CONTROL_SURFACE_WIDTH_PX, CONTROL_SURFACE_HEIGHT_PX), density = Density(1f)) {
            block()
        }
    } finally {
        Locale.setDefault(previous)
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertSingleSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    hint: String? = null,
    stateDescription: String? = null,
): SemanticsNodeInteraction {
    val matcher = switchMatcher(label)
    onAllNodes(matcher).assertCountEquals(1)
    val row =
        onNode(matcher)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    if (hint == null) row.assertTextEquals(label) else row.assertTextEquals(label, hint)
    row.assert(
        SemanticsMatcher.expectValue(
            SemanticsProperties.ToggleableState,
            if (checked) ToggleableState.On else ToggleableState.Off,
        ),
    )
    if (enabled) row.assertIsEnabled() else row.assertIsNotEnabled()
    if (stateDescription != null) {
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, stateDescription))
    }
    // The merged name alone cannot catch an anonymous, independently actionable child Switch.
    val raw = rawSubtree(row)
    assertEquals(1, raw.count { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }, label)
    assertEquals(1, raw.count { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }, label)
    assertEquals(1, raw.count { it.config.getOrNull(SemanticsActions.OnClick) != null }, label)
    return row
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickSwitchLabel(label: String) {
    val row = onNode(switchMatcher(label))
    val labelBounds = rawLabel(row, label).boundsInRoot
    val position = labelBounds.center - row.fetchSemanticsNode().boundsInRoot.topLeft
    row.performTouchInput { click(position) }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickSwitchControl(
    label: String,
    direction: LayoutDirection = LayoutDirection.Ltr,
) {
    // Inside the visual Switch, including the existing 12dp horizontal row padding where present.
    onNode(switchMatcher(label)).performTouchInput {
        click(
            Offset(
                if (direction == LayoutDirection.Ltr) width - SWITCH_TRAILING_INSET_PX else SWITCH_TRAILING_INSET_PX,
                center.y,
            ),
        )
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertSwitchLabelOrder(
    label: String,
    direction: LayoutDirection,
) {
    val row = onNode(switchMatcher(label))
    val rowBounds = row.fetchSemanticsNode().boundsInRoot
    val labelBounds = rawLabel(row, label).boundsInRoot
    if (direction == LayoutDirection.Ltr) {
        assertTrue(
            labelBounds.right <= rowBounds.right - SWITCH_TRAILING_INSET_PX,
            "$label precedes the trailing switch in LTR",
        )
    } else {
        assertTrue(
            labelBounds.left >= rowBounds.left + SWITCH_TRAILING_INSET_PX,
            "$label precedes the trailing switch in RTL",
        )
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.rawSubtree(node: SemanticsNodeInteraction): List<SemanticsNode> {
    val id = node.fetchSemanticsNode().id
    val raw =
        onNode(SemanticsMatcher("same raw node $id") { it.id == id }, useUnmergedTree = true)
            .fetchSemanticsNode()
    return flatten(raw)
}

private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.rawLabel(
    row: SemanticsNodeInteraction,
    label: String,
): SemanticsNode =
    rawSubtree(row).single { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    }

private fun switchMatcher(label: String): SemanticsMatcher =
    hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

private const val CONTROL_SURFACE_WIDTH_PX = 600f
private const val CONTROL_SURFACE_HEIGHT_PX = 1_000f
private const val SWITCH_TRAILING_INSET_PX = 32f
