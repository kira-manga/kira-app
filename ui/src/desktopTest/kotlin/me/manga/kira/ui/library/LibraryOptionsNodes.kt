package me.manga.kira.ui.library

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import kotlin.test.assertTrue

internal const val LIBRARY_DISPLAY_SWITCH_COUNT = 5

@OptIn(ExperimentalTestApi::class)
internal class LibraryOptionsNodes(
    private val ui: ComposeUiTest,
    private val size: Size,
    private val tabLabels: () -> Map<LibraryOptionsTab, String>,
    private val filterLabels: () -> Map<LibraryFilter, String>,
    private val sortLabels: () -> Map<LibrarySort, String>,
) {
    val dialog get() = ui.onNode(isDialog())

    fun filterChip(filter: LibraryFilter): SemanticsNodeInteraction = chip(filterLabels().getValue(filter))

    fun sortChip(sort: LibrarySort): SemanticsNodeInteraction = chip(sortLabels().getValue(sort))

    // Each real Switch is checked against its distinct intent; no test-only tags or fake rows.
    fun displaySwitch(index: Int): SemanticsNodeInteraction =
        ui
            .onAllNodes(isToggleable())
            .assertCountEquals(LIBRARY_DISPLAY_SWITCH_COUNT)[index]

    fun directionSwitch(): SemanticsNodeInteraction = ui.onNode(isToggleable())

    fun slider(): SemanticsNodeInteraction =
        ui.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        )

    fun bounds(node: SemanticsNodeInteraction): Rect {
        val rect = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(rect.width > 0f && rect.height > 0f, "Expected nonzero visible control bounds: $rect")
        assertTrue(rect.left >= 0f && rect.top >= 0f, "Control starts outside the actual scene: $rect")
        assertTrue(rect.right <= size.width && rect.bottom <= size.height, "Control exceeds the actual scene: $rect")
        return rect
    }

    fun sheetBounds(): Rect = bounds(ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)))

    fun tabNode(tab: LibraryOptionsTab): SemanticsNodeInteraction =
        ui.onNode(
            hasText(tabLabels().getValue(tab)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        )

    fun tabBounds(): List<Rect> = LibraryOptionsTab.entries.map { bounds(tabNode(it)) }

    private fun chip(label: String): SemanticsNodeInteraction = ui.onNode(hasText(label) and hasClickAction())

    fun body(): SemanticsNodeInteraction =
        ui.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
}
