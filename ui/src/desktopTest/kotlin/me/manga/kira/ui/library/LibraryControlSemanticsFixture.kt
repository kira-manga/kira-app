package me.manga.kira.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.LayoutDirection
import me.manga.kira.domain.model.library.LibraryDisplay
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.model.library.SortDirection
import me.manga.kira.presentation.library.LibraryIntent
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.auto_text
import me.manga.kira.ui.generated.resources.items_count_format
import me.manga.kira.ui.generated.resources.items_per_row_label
import me.manga.kira.ui.generated.resources.items_plural
import me.manga.kira.ui.generated.resources.items_singular
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_display
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_sort
import me.manga.kira.ui.generated.resources.show_buttons
import me.manga.kira.ui.generated.resources.show_items_count
import me.manga.kira.ui.generated.resources.show_items_details
import me.manga.kira.ui.generated.resources.show_items_source
import me.manga.kira.ui.generated.resources.show_tabs_all_likes_etc
import me.manga.kira.ui.generated.resources.sort_direction_ascending
import me.manga.kira.ui.generated.resources.sort_direction_descending
import me.manga.kira.ui.generated.resources.sort_direction_label
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalTestApi::class)
internal class LibraryControlSemanticsFixture {
    var direction by mutableStateOf(SortDirection.ASCENDING)
    var display by mutableStateOf(LibraryDisplay())
    var count by mutableIntStateOf(0)
    val intents = mutableListOf<LibraryIntent>()
    var dismissals = 0
    private var labels = emptyMap<StringResource, String>()
    private var captions = emptyMap<Int, String>()

    fun label(key: StringResource): String = labels.getValue(key)

    fun caption(count: Int): String = captions.getValue(count)

    fun render(
        ui: ComposeUiTest,
        layoutDirection: LayoutDirection,
    ) {
        ui.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                KiraTheme(darkTheme = false) {
                    labels = labelKeys.associateWith { stringResource(it) }
                    captions =
                        (0..MAX_ITEMS_PER_ROW).associateWith { value ->
                            if (value == 0) {
                                stringResource(Res.string.auto_text)
                            } else {
                                stringResource(
                                    Res.string.items_count_format,
                                    value,
                                    stringResource(
                                        if (value == 1) Res.string.items_singular else Res.string.items_plural,
                                    ),
                                )
                            }
                        }
                    LibraryOptionsSheet(
                        filter = LibraryFilter.ALL,
                        sort = LibrarySort.ALPHABETIC,
                        sortDirection = direction,
                        itemsPerRow = count,
                        display = display,
                        onIntent = ::accept,
                        onDismiss = { dismissals++ },
                    )
                }
            }
        }
    }

    private fun accept(intent: LibraryIntent) {
        intents += intent
        when (intent) {
            LibraryIntent.OnSortDirectionToggle ->
                direction =
                    if (direction == SortDirection.ASCENDING) {
                        SortDirection.DESCENDING
                    } else {
                        SortDirection.ASCENDING
                    }
            is LibraryIntent.OnItemsPerRowChange -> count = intent.count
            is LibraryIntent.OnToggleShowDetails -> display = display.copy(showDetails = intent.value)
            is LibraryIntent.OnToggleShowSource -> display = display.copy(showSource = intent.value)
            is LibraryIntent.OnToggleShowCount -> display = display.copy(showCount = intent.value)
            is LibraryIntent.OnToggleShowButtons -> display = display.copy(showButtons = intent.value)
            is LibraryIntent.OnToggleShowTabs -> display = display.copy(showTabs = intent.value)
            else -> error("Unexpected Library control intent: $intent")
        }
    }
}

internal val libraryDisplayToggleCases: List<Pair<StringResource, (Boolean) -> LibraryIntent>> =
    listOf(
        Res.string.show_items_details to { value: Boolean -> LibraryIntent.OnToggleShowDetails(value) },
        Res.string.show_items_source to { value: Boolean -> LibraryIntent.OnToggleShowSource(value) },
        Res.string.show_items_count to { value: Boolean -> LibraryIntent.OnToggleShowCount(value) },
        Res.string.show_buttons to { value: Boolean -> LibraryIntent.OnToggleShowButtons(value) },
        Res.string.show_tabs_all_likes_etc to { value: Boolean -> LibraryIntent.OnToggleShowTabs(value) },
    )

private val labelKeys =
    listOf(
        Res.string.library_bottom_sheet_tab_sort,
        Res.string.library_bottom_sheet_tab_display,
        Res.string.sort_direction_label,
        Res.string.sort_direction_ascending,
        Res.string.sort_direction_descending,
        Res.string.items_per_row_label,
    ) + libraryDisplayToggleCases.map { it.first }

internal const val MAX_ITEMS_PER_ROW = 8
