package me.manga.kira.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import me.manga.kira.domain.model.library.LibraryDisplay
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.model.library.SortDirection
import me.manga.kira.presentation.library.LibraryIntent
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.auto_text
import me.manga.kira.ui.generated.resources.items_count_format
import me.manga.kira.ui.generated.resources.items_per_row_label
import me.manga.kira.ui.generated.resources.items_plural
import me.manga.kira.ui.generated.resources.items_singular
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_display
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_filter
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_sort
import me.manga.kira.ui.generated.resources.sort_by_label
import me.manga.kira.ui.generated.resources.sort_direction_ascending
import me.manga.kira.ui.generated.resources.sort_direction_descending
import me.manga.kira.ui.generated.resources.sort_direction_label
import me.manga.kira.ui.generated.resources.sort_options_title
import me.manga.kira.ui.generated.resources.show_buttons
import me.manga.kira.ui.generated.resources.show_items_count
import me.manga.kira.ui.generated.resources.show_items_details
import me.manga.kira.ui.generated.resources.show_items_source
import me.manga.kira.ui.generated.resources.show_tabs_all_likes_etc
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Tabbed Filter / Sort / Display options bottom sheet (Phase 11.ui.UP-6).
 *
 * Restores the native app's single tabbed `ModalBottomSheet` that consolidated the three
 * grid-presentation control axes into one surface. Before UP-6 the rework scattered these into
 * three top-bar `DropdownMenu` anchors (Filter / Sort / Density) plus a separate Display
 * `AlertDialog` — four entry points crowding the top-bar actions row. This collapses them to one
 * [KiraIcons.Tune] button → one sheet with three tabs.
 *
 * Pure projection: reads the current axis values and dispatches the same `LibraryIntent` variants
 * the old scattered menus did (`OnFilterChange`, `OnSortChange`, `OnSortDirectionToggle`,
 * `OnGridDensityChange`, `OnToggleShow*`) — no new intents, no behaviour change, every flip still
 * commits synchronously through the reducer. Tab selection lives in sheet-local `remember`
 * (UI ephemera, not MVI state — same posture as the dropdowns it replaces).
 *
 * `ModalBottomSheet` is an experimental Material 3 API; the opt-in is justified here because the
 * whole point of UP-6 is restoring the *bottom sheet* (unlike [DisplayOptionsDialog]'s prior
 * AlertDialog, where bottom-sheet visual parity was explicitly not required).
 *
 * Reuses the label helpers [librarySortLabel] / [libraryFilterLabel] / [gridDensityLabel]
 * (LibraryScreen.kt, same package) so the option text stays in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryOptionsSheet(
    filter: LibraryFilter,
    sort: LibrarySort,
    sortDirection: SortDirection,
    itemsPerRow: Int,
    display: LibraryDisplay,
    onIntent: (LibraryIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SecondaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(Res.string.library_bottom_sheet_tab_filter)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(Res.string.library_bottom_sheet_tab_sort)) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text(stringResource(Res.string.library_bottom_sheet_tab_display)) },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            when (selectedTab) {
                0 -> FilterChipsRow(
                    filter = filter,
                    onIntent = onIntent,
                )
                1 -> SortOptionsSection(
                    sort = sort,
                    sortDirection = sortDirection,
                    onIntent = onIntent,
                )
                else -> {
                    // Library parity fix (audit p1/library finding 2): the native
                    // DisplayOptionsSection exposes a continuous items-per-row Slider (0..8,
                    // 0 = Auto) — NOT the 3-value density enum the rework had substituted. This
                    // restores the native control verbatim (native DisplayOptionsSection.kt:35-60).
                    ItemsPerRowSlider(
                        count = itemsPerRow,
                        onCountChange = { onIntent(LibraryIntent.OnItemsPerRowChange(it)) },
                    )
                    Spacer(Modifier.height(24.dp))
                    // P2 parity fix (audit p2/library, "Display options sheet content"): native
                    // DisplayOptionsSection.kt:64-68 prefixes EACH SwitchItem with a HorizontalDivider
                    // (and closes the list with a trailing Divider). The rework had a single divider
                    // after the slider. Each ToggleRow below now leads with its own divider.
                    ToggleRow(
                        label = stringResource(Res.string.show_items_details),
                        checked = display.showDetails,
                        onCheckedChange = { onIntent(LibraryIntent.OnToggleShowDetails(it)) },
                    )
                    ToggleRow(
                        label = stringResource(Res.string.show_items_source),
                        checked = display.showSource,
                        onCheckedChange = { onIntent(LibraryIntent.OnToggleShowSource(it)) },
                    )
                    ToggleRow(
                        label = stringResource(Res.string.show_items_count),
                        checked = display.showCount,
                        onCheckedChange = { onIntent(LibraryIntent.OnToggleShowCount(it)) },
                    )
                    ToggleRow(
                        label = stringResource(Res.string.show_buttons),
                        checked = display.showButtons,
                        onCheckedChange = { onIntent(LibraryIntent.OnToggleShowButtons(it)) },
                    )
                    ToggleRow(
                        label = stringResource(Res.string.show_tabs_all_likes_etc),
                        checked = display.showTabs,
                        onCheckedChange = { onIntent(LibraryIntent.OnToggleShowTabs(it)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Filter tab (P2 parity fix, audit p2/library "Filter options sheet presentation"): a [FlowRow] of
 * pill-shaped [FilterChip]s, one per [LibraryFilter]. Mirrors native `FilterChipsRow.kt:28-91`
 * verbatim — each chip is `RoundedCornerShape(16dp)`, `defaultMinSize(minHeight = 32dp)`,
 * `padding(horizontal = 4dp)`, with a leading [Done] icon (onPrimary tint) + primary container +
 * onPrimary label + primary border when selected. The rework had rendered filters as a vertical
 * list of full-width selectable rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipsRow(
    filter: LibraryFilter,
    onIntent: (LibraryIntent) -> Unit,
) {
    FlowRow(modifier = Modifier.fillMaxWidth()) {
        LibraryFilter.entries.forEach { option ->
            LibraryFilterChip(
                label = libraryFilterLabel(option),
                selected = option == filter,
                onClick = { onIntent(LibraryIntent.OnFilterChange(option)) },
            )
        }
    }
}

/**
 * A single filter [FilterChip] (P2 parity, native `FilterChipsRow.kt:50-91`): leading [Done] icon
 * (onPrimary) when selected, `RoundedCornerShape(16dp)`, `defaultMinSize(minHeight = 32dp)`,
 * `padding(horizontal = 4dp)`, primary border + primary container + onPrimary label when selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else {
            null
        },
        modifier = Modifier
            .defaultMinSize(minHeight = 32.dp)
            .padding(horizontal = 4.dp),
        shape = RoundedCornerShape(16.dp),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            },
        ),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/**
 * Sort tab (P2 parity fix, audit p2/library "Sort options sheet layout"): mirrors native
 * `SortOptionsSection.kt:44-104` verbatim:
 *  - a `titleLarge` "Sort Options" header (bottom 12dp),
 *  - a "Sort Direction" Row: a [SwapVert] icon (20dp) + "Sort Direction" label on the left, an
 *    "Ascending"/"Descending" label + a [Switch] on the right (`checked = ascending`),
 *  - a 16dp [Spacer],
 *  - a `titleMedium` "Sort by" label (bottom 8dp),
 *  - a [FlowRow] of plain [FilterChip]s (`RoundedCornerShape(16dp)`, `padding(horizontal = 4dp)`),
 *    one per [LibrarySort].
 *
 * The rework had rendered sort types as full-width selectable rows + a single arrow-icon tap row
 * for direction (no Switch, no SwapVert, no headers, no chips). The `Switch` maps the
 * platform-neutral [SortDirection] (ASCENDING/DESCENDING) onto native's `isAscending` boolean and
 * dispatches the existing [LibraryIntent.OnSortDirectionToggle] (a flip) on change.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SortOptionsSection(
    sort: LibrarySort,
    sortDirection: SortDirection,
    onIntent: (LibraryIntent) -> Unit,
) {
    val ascending = sortDirection == SortDirection.ASCENDING
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.sort_options_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SwapVert,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.sort_direction_label))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ascending) {
                        stringResource(Res.string.sort_direction_ascending)
                    } else {
                        stringResource(Res.string.sort_direction_descending)
                    },
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = ascending,
                    onCheckedChange = { onIntent(LibraryIntent.OnSortDirectionToggle) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.sort_by_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            LibrarySort.entries.forEach { option ->
                FilterChip(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    selected = option == sort,
                    onClick = { onIntent(LibraryIntent.OnSortChange(option)) },
                    shape = RoundedCornerShape(16.dp),
                    label = { Text(librarySortLabel(option)) },
                )
            }
        }
    }
}

/**
 * One display-toggle row — a leading [HorizontalDivider] (P2 parity: native
 * `DisplayOptionsSection.kt:65` prefixes every SwitchItem with a divider), then a row with the
 * [label] on the left and a [Switch] on the right.
 */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val spacing = LocalSpacing.current
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Items-per-row Slider (Library parity fix, audit p1/library finding 2). Restores the native
 * `DisplayOptionsSection.kt:35-60` control verbatim:
 *  - "Items per row:" SemiBold label.
 *  - A continuous [Slider] over `0f..8f` with `steps = 7` (9 discrete positions: 0..8).
 *  - The value caption below: "Auto" (italic) when [count] is `0`, else "N item" / "N items"
 *    via the `items_count_format` "%1$d %2$s" template + singular/plural noun.
 *
 * `0 = Auto` → the grid uses the adaptive cell; `1..8` pin the grid to that many fixed columns.
 * [onCountChange] receives the rounded slider position and the caller dispatches
 * `LibraryIntent.OnItemsPerRowChange`.
 */
@Composable
private fun ItemsPerRowSlider(
    count: Int,
    onCountChange: (Int) -> Unit,
) {
    Text(
        text = stringResource(Res.string.items_per_row_label),
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Slider(
        value = count.toFloat(),
        onValueChange = { onCountChange(it.roundToInt()) },
        valueRange = 0f..8f,
        steps = 7,
    )
    val caption = if (count == 0) {
        stringResource(Res.string.auto_text)
    } else {
        stringResource(
            Res.string.items_count_format,
            count,
            if (count > 1) stringResource(Res.string.items_plural) else stringResource(Res.string.items_singular),
        )
    }
    Text(
        text = caption,
        fontStyle = if (count == 0) FontStyle.Italic else FontStyle.Normal,
    )
}
