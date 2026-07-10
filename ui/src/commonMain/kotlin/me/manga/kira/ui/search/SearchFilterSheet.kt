package me.manga.kira.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.filters_pfix_language
import me.manga.kira.ui.generated.resources.filters_pfix_reset
import me.manga.kira.ui.generated.resources.filters_pfix_status
import me.manga.kira.ui.generated.resources.filters_pfix_type
import me.manga.kira.ui.generated.resources.search_genres
import me.manga.kira.ui.generated.resources.search_pfix_apply_filters
import me.manga.kira.ui.generated.resources.search_pfix_filter_sort_title
import me.manga.kira.ui.generated.resources.search_pfix_filters_not_ready
import me.manga.kira.ui.generated.resources.search_pfix_order_by
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Search filter bottom sheet — the GENERIC ordered-descriptor renderer (config-driven filters,
 * 2026-07; supersedes the hardcoded genres+sort layout of Epic H4c).
 *
 * Renders the active source's [SourceFilter] list IN DECLARED ORDER, dispatching by control type
 * only — never by source api. Standard ids (`genres`/`sort`/`status`/`language`/`type`) get
 * localized section titles; custom filters show their config label verbatim. Presentation
 * heuristics (dropdown vs chip flow) key on control type + option count — the JSON stays
 * UI-agnostic.
 *
 * **Immediate-apply (native parity, F1)**: every value change dispatches [onFilterChange] the
 * instant it happens, with the sheet staying OPEN so results update live behind it (text/number
 * fields commit on the IME action instead — a per-keystroke search would thrash). The bottom
 * button is a plain "close the sheet" affordance and runs no search; Reset restores the declared
 * defaults via [onResetFilters].
 *
 * Stateless w.r.t. MVI: available [filters] + current [selections] come from state and every
 * change round-trips as an intent. When the source declares no filters, a 200dp centered "filters
 * not ready" panel renders and the sheet returns early (native parity, S-4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFilterSheet(
    filters: List<SourceFilter>,
    selections: Map<String, List<String>>,
    onFilterChange: (filterId: String, values: List<String>) -> Unit,
    onResetFilters: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // S-4 (native parity): a source with no advanced filters renders a prominent 200dp
        // centered "filters not ready" panel and returns early — nothing to apply.
        if (filters.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.search_pfix_filters_not_ready),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = spacing.lg),
                )
            }
            return@ModalBottomSheet
        }

        val visibleFilters = filters.filter { isVisible(it, filters, selections) }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.search_pfix_filter_sort_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onResetFilters) {
                    Text(stringResource(Res.string.filters_pfix_reset))
                }
            }

            visibleFilters.forEachIndexed { index, filter ->
                if (index > 0) HorizontalDivider()
                FilterSection(
                    filter = filter,
                    selected = selections[filter.id].orEmpty(),
                    onChange = { values -> onFilterChange(filter.id, values) },
                )
            }

            // Native parity (F1): the bottom button is a plain "close the sheet" affordance —
            // selections have already been applied live above.
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.search_pfix_apply_filters))
            }
        }
    }
}

/**
 * Sheet-side visibility: a filter renders only while ALL its `visibleWhen` conditions hold against
 * the referenced filters' effective values (selection, else defaults; an untouched toggle counts
 * as `false`). Mirrors the engine's composition rule, so what the user sees is what the request
 * sends.
 */
private fun isVisible(
    filter: SourceFilter,
    all: List<SourceFilter>,
    selections: Map<String, List<String>>,
): Boolean =
    filter.visibleWhen.all { condition ->
        val referenced = all.firstOrNull { it.id == condition.filterId } ?: return@all false
        val effective =
            selections[condition.filterId]
                ?: referenced.defaultValues.ifEmpty {
                    if (referenced.type == FilterControlType.TOGGLE) listOf("false") else emptyList()
                }
        effective.any { it in condition.anyOf }
    }

/** One filter section, dispatched purely on [FilterControlType]. */
@Composable
private fun FilterSection(
    filter: SourceFilter,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
) {
    when (filter.type) {
        FilterControlType.SELECT ->
            if (filter.options.size > DROPDOWN_MAX_OPTIONS) {
                ChipFlowSection(filter, selected.toSet(), multiSelect = false, onChange = onChange)
            } else {
                DropdownSection(filter, selected.firstOrNull(), onChange = onChange)
            }
        FilterControlType.MULTISELECT ->
            ChipFlowSection(filter, selected.toSet(), multiSelect = true, onChange = onChange)
        FilterControlType.TOGGLE -> ToggleSection(filter, selected, onChange)
        FilterControlType.TEXT -> TextSection(filter, selected.firstOrNull().orEmpty(), numeric = false, onChange)
        FilterControlType.NUMBER -> TextSection(filter, selected.firstOrNull().orEmpty(), numeric = true, onChange)
    }
}

/** Localized section titles for the standard filter ids; custom filters use their config label. */
@Composable
private fun sectionTitle(filter: SourceFilter): String =
    when (filter.id) {
        "genres" -> stringResource(Res.string.search_genres)
        "sort" -> stringResource(Res.string.search_pfix_order_by)
        "status" -> stringResource(Res.string.filters_pfix_status)
        "language" -> stringResource(Res.string.filters_pfix_language)
        "type" -> stringResource(Res.string.filters_pfix_type)
        else -> filter.label
    }

/**
 * Chip flow (single- or multi-select) with the S-2 collapsible header: a long option list (legacy
 * genre grids reach ~180 entries) collapses behind a chevron and caps at 300dp with its own scroll.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlowSection(
    filter: SourceFilter,
    selected: Set<String>,
    multiSelect: Boolean,
    onChange: (List<String>) -> Unit,
) {
    val spacing = LocalSpacing.current
    var expanded by rememberSaveable(filter.id) { mutableStateOf(true) }

    SectionHeader(
        title = sectionTitle(filter),
        expanded = expanded,
        onHeaderClick = { expanded = !expanded },
    )
    AnimatedVisibility(visible = expanded) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                filter.options.forEach { option ->
                    val isSelected = option.value in selected
                    FilterChip(
                        selected = isSelected,
                        // Immediate-apply (F1). Single-select: tapping replaces the selection and
                        // re-tapping clears it; multi-select: tapping toggles membership, keeping
                        // option-declaration order for determinism.
                        onClick = {
                            val next =
                                when {
                                    multiSelect ->
                                        filter.options.map { it.value }.filter {
                                            if (it == option.value) !isSelected else it in selected
                                        }
                                    isSelected -> emptyList()
                                    else -> listOf(option.value)
                                }
                            onChange(next)
                        },
                        label = { Text(option.label) },
                        leadingIcon =
                            if (isSelected) {
                                { Icon(imageVector = KiraIcons.Check, contentDescription = null) }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSection(
    filter: SourceFilter,
    selectedValue: String?,
    onChange: (List<String>) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel =
        filter.options.firstOrNull { it.value == selectedValue }?.label
            ?: filter.options.first().label

    Text(
        text = sectionTitle(filter),
        style = MaterialTheme.typography.titleMedium,
    )
    ExposedDropdownMenuBox(
        expanded = menuOpen,
        onExpandedChange = { menuOpen = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            filter.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        // Immediate-apply (F1): only the dropdown closes; the sheet stays open.
                        onChange(listOf(option.value))
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ToggleSection(
    filter: SourceFilter,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
) {
    val spacing = LocalSpacing.current
    val checked =
        (selected.firstOrNull() ?: filter.defaultValues.firstOrNull()) == "true"
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = sectionTitle(filter),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = { onChange(listOf(if (it) "true" else "false")) },
        )
    }
}

/** Free text / numeric input; commits on the IME action (a per-keystroke search would thrash). */
@Composable
private fun TextSection(
    filter: SourceFilter,
    committed: String,
    numeric: Boolean,
    onChange: (List<String>) -> Unit,
) {
    var draft by rememberSaveable(filter.id, committed) { mutableStateOf(committed) }
    Text(
        text = sectionTitle(filter),
        style = MaterialTheme.typography.titleMedium,
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onChange(listOf(draft).filter { it.isNotBlank() }) }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * S-2 (native parity): a tappable section header with an expand/collapse chevron, faithful to the
 * native `SearchBottomSheet.SectionHeader` (title + KeyboardArrowUp/Down).
 */
@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onHeaderClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onHeaderClick() }
                .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
        )
    }
}

private const val DROPDOWN_MAX_OPTIONS = 8

// region Preview

@Preview
@Composable
private fun SearchFilterSheetPreview() {
    // Note: ModalBottomSheet renders in a popup; this preview exercises the composable's
    // construction path (canned filters) rather than a pixel-accurate sheet render.
    SearchFilterSheet(
        filters =
            listOf(
                SourceFilter(
                    id = "genres",
                    label = "genres",
                    type = FilterControlType.MULTISELECT,
                    options = listOf("Action", "Romance", "Comedy", "Drama", "Fantasy").map { FilterOption(it, it) },
                ),
                SourceFilter(
                    id = "sort",
                    label = "sort",
                    type = FilterControlType.SELECT,
                    options = listOf("Latest", "Popular", "A-Z").map { FilterOption(it, it) },
                    defaultValues = listOf("Latest"),
                ),
            ),
        selections = mapOf("genres" to listOf("Action"), "sort" to listOf("Latest")),
        onFilterChange = { _, _ -> },
        onResetFilters = {},
        onDismiss = {},
    )
}

// endregion
