package me.manga.kira.ui.library

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcherOwner
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.model.library.SortDirection
import me.manga.kira.presentation.library.LibraryIntent
import me.manga.kira.presentation.library.LibraryState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_display
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_filter
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_sort
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val libraryOptionsCompactSize = Size(640f, 320f)
internal val libraryOptionsPortraitSize = Size(360f, 800f)

internal enum class LibraryOptionsTab(val title: StringResource) {
    FILTER(Res.string.library_bottom_sheet_tab_filter),
    SORT(Res.string.library_bottom_sheet_tab_sort),
    DISPLAY(Res.string.library_bottom_sheet_tab_display),
}

internal data class LibraryOptionsScrollRange(val value: Float, val maxValue: Float)

@OptIn(ExperimentalTestApi::class)
internal fun runLibraryOptionsSheetTest(
    size: Size = libraryOptionsCompactSize,
    fontScale: Float = 2f,
    block: suspend (LibraryOptionsSheetFixture) -> Unit,
) = runSkikoComposeUiTest(
    size = size,
    density = Density(density = 1f, fontScale = fontScale),
) {
    val fixture = LibraryOptionsSheetFixture(this, size)
    fixture.render()
    block(fixture)
}

@OptIn(ExperimentalTestApi::class)
internal class LibraryOptionsSheetFixture(
    private val ui: ComposeUiTest,
    private val size: Size,
) {
    private var state by mutableStateOf(LibraryState())
    private var shown by mutableStateOf(true)
    private val intents = mutableListOf<LibraryIntent>()
    private val backInput = DirectNavigationEventInput()
    private var tabLabels = emptyMap<LibraryOptionsTab, String>()
    private var filterLabels = emptyMap<LibraryFilter, String>()
    private var sortLabels = emptyMap<LibrarySort, String>()
    var dismissCount = 0
        private set

    fun render() {
        ui.setContent {
            // Use the pinned Skiko host's real navigation dispatcher, not the sheet callback.
            val owner = checkNotNull(LocalLifecycleOwner.current as? NavigationEventDispatcherOwner)
            DisposableEffect(owner) {
                owner.navigationEventDispatcher.addInput(backInput)
                onDispose { owner.navigationEventDispatcher.removeInput(backInput) }
            }
            KiraTheme(darkTheme = false) {
                tabLabels = LibraryOptionsTab.entries.associateWith { stringResource(it.title) }
                filterLabels = LibraryFilter.entries.associateWith { libraryFilterLabel(it) }
                sortLabels = LibrarySort.entries.associateWith { librarySortLabel(it) }
                if (shown) {
                    LibraryOptionsSheet(
                        filter = state.filter,
                        sort = state.sort,
                        sortDirection = state.sortDirection,
                        itemsPerRow = state.itemsPerRow,
                        display = state.display,
                        onIntent = ::accept,
                        onDismiss = {
                            dismissCount++
                            shown = false
                        },
                    )
                }
            }
        }
        ui.waitForIdle()
        val actual = bounds(dialog())
        assertEquals(size.width, actual.width, "The actual dialog must use the controlled scene width")
        assertEquals(size.height, actual.height, "The actual dialog must use the controlled scene height")
    }

    fun selectTab(tab: LibraryOptionsTab) {
        val node = tabNode(tab)
        bounds(node)
        node.performTouchInput { click() }
        ui.waitForIdle()
        tabBounds()
    }

    fun filterChip(filter: LibraryFilter): SemanticsNodeInteraction = chip(filterLabels.getValue(filter))

    fun sortChip(sort: LibrarySort): SemanticsNodeInteraction = chip(sortLabels.getValue(sort))

    // Each real Switch is checked against its distinct intent; no test-only tags or fake rows.
    fun displaySwitch(index: Int): SemanticsNodeInteraction =
        ui.onAllNodes(isToggleable()).assertCountEquals(5)[index]

    fun directionSwitch(): SemanticsNodeInteraction = ui.onNode(isToggleable())

    fun slider(): SemanticsNodeInteraction =
        ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    fun reveal(node: SemanticsNodeInteraction) {
        val before = tabBounds()
        node.performScrollTo()
        ui.waitForIdle()
        bounds(node)
        assertEquals(before, tabBounds(), "Scrolling the body must not move the tabs")
    }

    fun activate(node: SemanticsNodeInteraction) {
        reveal(node)
        node.performTouchInput { click() }
        ui.waitForIdle()
        tabBounds()
    }

    fun swipeBodyUp() {
        val before = tabBounds()
        body().performTouchInput { swipeUp() }
        ui.waitForIdle()
        assertEquals(before, tabBounds(), "Native content scrolling must leave the tabs fixed")
    }

    fun scrollRange(): LibraryOptionsScrollRange {
        val range = body().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return ui.runOnIdle { LibraryOptionsScrollRange(range.value(), range.maxValue()) }
    }

    fun bounds(node: SemanticsNodeInteraction): Rect {
        val rect = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(rect.width > 0f && rect.height > 0f, "Expected nonzero visible control bounds: $rect")
        assertTrue(rect.left >= 0f && rect.top >= 0f, "Control starts outside the actual scene: $rect")
        assertTrue(rect.right <= size.width && rect.bottom <= size.height, "Control exceeds the actual scene: $rect")
        return rect
    }

    fun sheetBounds(): Rect = bounds(ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)))

    fun expectOnly(vararg expected: LibraryIntent) {
        ui.runOnIdle {
            assertEquals(expected.toList(), intents)
            assertEquals(0, dismissCount, "Options interaction must not dismiss the sheet")
            intents.clear()
        }
    }

    fun dismissUsingScrim() {
        val top = sheetBounds().top
        assertTrue(top > 0f, "Portrait content must leave an actual scrim above the naturally sized sheet")
        dialog().performTouchInput { click(Offset(center.x, top / 2f)) }
        ui.waitForIdle()
        dialog().assertDoesNotExist()
    }

    fun reopen() {
        ui.runOnIdle { shown = true }
        ui.waitForIdle()
        bounds(dialog())
    }

    fun dismissUsingBack() {
        ui.runOnIdle { backInput.backCompleted() }
        ui.waitForIdle()
        dialog().assertDoesNotExist()
    }

    private fun tabNode(tab: LibraryOptionsTab): SemanticsNodeInteraction =
        ui.onNode(
            hasText(tabLabels.getValue(tab)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        )

    private fun tabBounds(): List<Rect> = LibraryOptionsTab.entries.map { bounds(tabNode(it)) }

    private fun chip(label: String): SemanticsNodeInteraction = ui.onNode(hasText(label) and hasClickAction())

    private fun dialog(): SemanticsNodeInteraction = ui.onNode(isDialog())

    private fun body(): SemanticsNodeInteraction =
        ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    // A controlled production-sheet host only; this is not a ViewModel/persistence test.
    private fun accept(intent: LibraryIntent) {
        intents += intent
        state =
            when (intent) {
                is LibraryIntent.OnFilterChange -> state.copy(filter = intent.filter)
                is LibraryIntent.OnSortChange -> state.copy(sort = intent.sort)
                LibraryIntent.OnSortDirectionToggle ->
                    state.copy(
                        sortDirection =
                            if (state.sortDirection == SortDirection.ASCENDING) {
                                SortDirection.DESCENDING
                            } else {
                                SortDirection.ASCENDING
                            },
                    )
                is LibraryIntent.OnItemsPerRowChange -> state.copy(itemsPerRow = intent.count)
                is LibraryIntent.OnToggleShowDetails ->
                    state.copy(display = state.display.copy(showDetails = intent.value))
                is LibraryIntent.OnToggleShowSource ->
                    state.copy(display = state.display.copy(showSource = intent.value))
                is LibraryIntent.OnToggleShowCount ->
                    state.copy(display = state.display.copy(showCount = intent.value))
                is LibraryIntent.OnToggleShowButtons ->
                    state.copy(display = state.display.copy(showButtons = intent.value))
                is LibraryIntent.OnToggleShowTabs ->
                    state.copy(display = state.display.copy(showTabs = intent.value))
                else -> error("Unexpected options-sheet intent: $intent")
            }
    }
}
