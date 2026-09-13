package me.manga.kira.ui.library

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
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

private const val COMPACT_WIDTH_PX = 640f
private const val COMPACT_HEIGHT_PX = 320f
private const val PORTRAIT_WIDTH_PX = 360f
private const val PORTRAIT_HEIGHT_PX = 800f

internal val libraryOptionsCompactSize = Size(COMPACT_WIDTH_PX, COMPACT_HEIGHT_PX)
internal val libraryOptionsPortraitSize = Size(PORTRAIT_WIDTH_PX, PORTRAIT_HEIGHT_PX)

internal enum class LibraryOptionsTab(
    val title: StringResource,
) {
    FILTER(Res.string.library_bottom_sheet_tab_filter),
    SORT(Res.string.library_bottom_sheet_tab_sort),
    DISPLAY(Res.string.library_bottom_sheet_tab_display),
}

internal data class LibraryOptionsScrollRange(
    val value: Float,
    val maxValue: Float,
)

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
    val nodes =
        LibraryOptionsNodes(
            ui = ui,
            size = size,
            tabLabels = { tabLabels },
            filterLabels = { filterLabels },
            sortLabels = { sortLabels },
        )
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
        val actual = nodes.bounds(nodes.dialog)
        assertEquals(size.width, actual.width, "The actual dialog must use the controlled scene width")
        assertEquals(size.height, actual.height, "The actual dialog must use the controlled scene height")
    }

    fun selectTab(tab: LibraryOptionsTab) {
        val node = nodes.tabNode(tab)
        nodes.bounds(node)
        node.performTouchInput { click() }
        ui.waitForIdle()
        nodes.tabBounds()
    }

    fun reveal(node: SemanticsNodeInteraction) {
        val before = nodes.tabBounds()
        node.performScrollTo()
        ui.waitForIdle()
        nodes.bounds(node)
        assertEquals(before, nodes.tabBounds(), "Scrolling the body must not move the tabs")
    }

    fun activate(node: SemanticsNodeInteraction) {
        reveal(node)
        node.performTouchInput { click() }
        ui.waitForIdle()
        nodes.tabBounds()
    }

    fun swipeBodyUp() {
        val before = nodes.tabBounds()
        nodes.body().performTouchInput { swipeUp() }
        ui.waitForIdle()
        assertEquals(before, nodes.tabBounds(), "Native content scrolling must leave the tabs fixed")
    }

    fun scrollRange(): LibraryOptionsScrollRange {
        val range = nodes.body().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return ui.runOnIdle { LibraryOptionsScrollRange(range.value(), range.maxValue()) }
    }

    fun expectOnly(vararg expected: LibraryIntent) {
        ui.runOnIdle {
            assertEquals(expected.toList(), intents)
            assertEquals(0, dismissCount, "Options interaction must not dismiss the sheet")
            intents.clear()
        }
    }

    fun dismissUsingScrim() {
        val top = nodes.sheetBounds().top
        assertTrue(top > 0f, "Portrait content must leave an actual scrim above the naturally sized sheet")
        nodes.dialog.performTouchInput { click(Offset(center.x, top / 2f)) }
        ui.waitForIdle()
        nodes.dialog.assertDoesNotExist()
    }

    fun reopen() {
        ui.runOnIdle { shown = true }
        ui.waitForIdle()
        nodes.bounds(nodes.dialog)
    }

    fun dismissUsingBack() {
        ui.runOnIdle { backInput.backCompleted() }
        ui.waitForIdle()
        nodes.dialog.assertDoesNotExist()
    }

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
