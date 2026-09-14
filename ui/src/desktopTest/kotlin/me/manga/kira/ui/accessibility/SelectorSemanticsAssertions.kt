package me.manga.kira.ui.accessibility

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertTabSelector(
    labels: List<String>,
    selectedIndex: Int,
    direction: LayoutDirection,
) {
    onAllNodes(selectableGroupMatcher).assertCountEquals(1)
    onAllNodes(tabRoleMatcher).assertCountEquals(labels.size)
    onAllNodes(tabRoleMatcher and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        .assertCountEquals(1)
    onAllNodes(tabRoleMatcher and SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        .assertCountEquals(labels.size - 1)
    labels.forEachIndexed { index, label -> assertSingleTab(label, index == selectedIndex) }
    val centers =
        labels.map {
            onNode(tabMatcher(it))
                .fetchSemanticsNode()
                .boundsInRoot.center.x
        }
    assertTrue(
        centers.zipWithNext().all { (first, second) ->
            if (direction == LayoutDirection.Ltr) first < second else first > second
        },
        "Tab order follows the supplied layout direction",
    )
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.assertSingleTab(
    label: String,
    selected: Boolean,
) {
    onAllNodes(tabMatcher(label)).assertCountEquals(1)
    val tab =
        onNode(tabMatcher(label))
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .assertTextEquals(label)
            .assert(hasAnyAncestor(selectableGroupMatcher))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, selected))
    val raw = rawSubtree(tab)
    assertEquals(1, raw.count { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }, label)
    assertEquals(1, raw.count { it.config.getOrNull(SemanticsProperties.Selected) != null }, label)
    assertEquals(1, raw.count { it.config.getOrNull(SemanticsActions.OnClick) != null }, label)
    assertEquals(1, raw.count { it.config.getOrNull(SemanticsActions.RequestFocus) != null }, label)
}

internal fun tabMatcher(label: String): SemanticsMatcher = hasText(label) and tabRoleMatcher

private val tabRoleMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
private val selectableGroupMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
