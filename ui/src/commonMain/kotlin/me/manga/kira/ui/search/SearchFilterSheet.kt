package me.manga.kira.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.filters_pfix_reset
import me.manga.kira.ui.generated.resources.search_pfix_apply_filters
import me.manga.kira.ui.generated.resources.search_pfix_filter_sort_title
import me.manga.kira.ui.generated.resources.search_pfix_filters_not_ready
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Ordered, source-agnostic filter sheet. Chips/selects/toggles apply immediately through
 * [onFilterChange]; text/number inputs stay local until IME Done (current field only) or Apply
 * (all remaining changed inputs), using one [onApplyDrafts] delta before dismissal.
 * Done keeps the sheet open; Apply dismisses after dispatch, or just dismisses if unchanged.
 *
 * Drafts belong only to this composition: dismissal discards pending edits, Reset shows declared
 * defaults synchronously before [onResetFilters], and hidden-but-declared drafts survive.
 * Visibility still uses committed [selections]. Empty [filters] withhold Apply while not ready.
 *
 * Six explicit inputs/callbacks keep the atomic draft delta separate from immediate changes,
 * Reset and dismissal; a callback bag would obscure these contracts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "ktlint:standard:function-naming", "LongParameterList")
@Composable
fun SearchFilterSheet(
    filters: List<SourceFilter>,
    selections: Map<String, List<String>>,
    onFilterChange: (filterId: String, values: List<String>) -> Unit,
    onApplyDrafts: (Map<String, String>) -> Unit,
    onResetFilters: () -> Unit,
    onDismiss: () -> Unit,
) {
    val drafts = remember { SearchFilterDrafts() }
    drafts.reconcile(filters, selections)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (filters.isEmpty()) {
            FiltersNotReady()
        } else {
            FilterSheetContent {
                FilterSheetHeader {
                    drafts.reset(filters)
                    onResetFilters()
                }
                FilterSections(filters, selections, drafts, onFilterChange, onApplyDrafts)
                ApplyFiltersButton(drafts, onApplyDrafts, onDismiss)
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun FilterSheetContent(content: @Composable ColumnScope.() -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
        content = content,
    )
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun FilterSheetHeader(onResetFilters: () -> Unit) {
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
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun FilterSections(
    filters: List<SourceFilter>,
    selections: Map<String, List<String>>,
    drafts: SearchFilterDrafts,
    onFilterChange: (String, List<String>) -> Unit,
    onApplyDrafts: (Map<String, String>) -> Unit,
) {
    filters.filter { isVisible(it, filters, selections) }.forEachIndexed { index, filter ->
        if (index > 0) HorizontalDivider()
        FilterSection(
            filter = filter,
            selected = selections[filter.id].orEmpty(),
            drafts = drafts,
            onChange = { values -> onFilterChange(filter.id, values) },
            onApplyDrafts = onApplyDrafts,
        )
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun ApplyFiltersButton(
    drafts: SearchFilterDrafts,
    onApplyDrafts: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    Button(
        onClick = {
            applyDrafts(drafts, onApplyDrafts)
            onDismiss()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.search_pfix_apply_filters))
    }
}

internal fun applyDrafts(
    drafts: SearchFilterDrafts,
    onApplyDrafts: (Map<String, String>) -> Unit,
    filterId: String? = null,
) {
    val delta = drafts.consume(filterId)
    if (delta.isNotEmpty()) onApplyDrafts(delta)
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun FiltersNotReady() {
    Box(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.search_pfix_filters_not_ready),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = LocalSpacing.current.lg),
        )
    }
}

/** All visibility conditions use committed selections, else defaults (untouched toggles are false). */
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

// Invoked by Compose preview tooling, not by the application call graph.
@Preview
@Suppress("FunctionNaming", "UnusedPrivateMember", "ktlint:standard:function-naming")
@Composable
private fun SearchFilterSheetPreview() {
    // ModalBottomSheet renders in a popup; this exercises construction, not a pixel-accurate render.
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
        onApplyDrafts = {},
        onResetFilters = {},
        onDismiss = {},
    )
}
