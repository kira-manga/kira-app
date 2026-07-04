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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.manga.kira.domain.model.home.SearchFilters
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.search_genres
import me.manga.kira.ui.generated.resources.search_pfix_apply_filters
import me.manga.kira.ui.generated.resources.search_pfix_filter_sort_title
import me.manga.kira.ui.generated.resources.search_pfix_filters_not_ready
import me.manga.kira.ui.generated.resources.search_pfix_order_by

/**
 * Search filter bottom sheet (Epic H4c).
 *
 * Faithful port of the legacy
 * `composeApp/.../features/home/ui/components/SearchBottomSheet.kt`: a `ModalBottomSheet` with a
 * genre [FilterChip] FlowRow + a sort `ExposedDropdownMenu` + an Apply button. Reuses the same
 * `ModalBottomSheet` / `FilterChip` FlowRow posture the rework `LibraryOptionsSheet` established.
 *
 * **Immediate-apply (native parity, F1)**: native `SearchBottomSheet` fires a search the instant a
 * genre chip is tapped (`onGenreClicked`) or a sort option is picked (`onSortSelected` →
 * `onSortClick`), with the sheet staying OPEN so results update live behind it. The Apply button is
 * a plain "close the sheet" affordance — in native it is just `onClick = onDismiss` and runs no
 * search. This sheet reproduces that: [onGenreClick] / [onSortSelect] are dispatched on selection
 * (not deferred), and the Apply button calls [onDismiss].
 *
 * Genre selection is **single-select** (GAP-SRCH-07): the legacy `SearchBottomSheet` bound a single
 * `selectedGenre` String with a `Check` leading icon on the chosen chip, and the source
 * `SearchType.GENRES` query accepts only one genre — so tapping a chip replaces the selection (or
 * clears it when re-tapped, emitting `null`) rather than accumulating a set. Sort is single-select
 * via the dropdown.
 *
 * Stateless w.r.t. MVI: takes the available [filters] + the current [selectedSort] / [selectedGenres]
 * (reflected live as the selection changes) and emits each selection immediately. When the source
 * exposes no sort types AND no genres, a 200dp centered "filters not ready" panel renders and the
 * sheet returns early — the Apply button is suppressed (native parity, S-4).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSheet(
    filters: SearchFilters,
    selectedSort: String?,
    selectedGenres: List<String>,
    onGenreClick: (genre: String?) -> Unit,
    onSortSelect: (sort: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Genre is single-select (GAP-SRCH-07): the current selection is the first of the incoming list
    // (for forward-compat with the List-shaped MVI contract). Reflected live from state.
    val selectedGenre = selectedGenres.firstOrNull()
    var sortMenuOpen by remember { mutableStateOf(false) }
    // S-2 (native parity): the Genres section is a collapsible header (native `SectionHeader`
    // chevron); native starts expanded (`genresExpanded = true`).
    var genresExpanded by remember { mutableStateOf(true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // S-4 (native parity): when the active source exposes NO sort types AND NO genres, native
        // renders a prominent 200dp centered "filters not ready" panel and returns early — the
        // Apply button is suppressed entirely (there is nothing to apply). Match
        // `SearchBottomSheet.kt:89-103` (200dp Box + return@ModalBottomSheet).
        if (filters.sortTypes.isEmpty() && filters.genres.isEmpty()) {
            Box(
                modifier = Modifier
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = stringResource(Res.string.search_pfix_filter_sort_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            if (filters.genres.isNotEmpty()) {
                // S-2 (native parity): tappable Genres header with an expand/collapse chevron
                // (native `SectionHeader` KeyboardArrowUp/Down), wrapping an `AnimatedVisibility`
                // FlowRow capped at 300dp + its own scroll (native `heightIn(max = 300.dp)` +
                // `verticalScroll`) so a long genre list does not stretch the whole sheet.
                GenresSectionHeader(
                    title = stringResource(Res.string.search_genres),
                    expanded = genresExpanded,
                    onHeaderClick = { genresExpanded = !genresExpanded },
                )
                AnimatedVisibility(visible = genresExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            filters.genres.forEach { genre ->
                                val selected = genre == selectedGenre
                                FilterChip(
                                    selected = selected,
                                    // Immediate-apply (native parity, F1): tapping a chip fires the
                                    // genre-browse search right away (sheet stays open). Single-select:
                                    // re-tapping the selected chip clears it (emit null); tapping another
                                    // replaces the selection (GAP-SRCH-07).
                                    onClick = { onGenreClick(if (selected) null else genre) },
                                    label = { Text(genre) },
                                    leadingIcon = if (selected) {
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

            if (filters.sortTypes.isNotEmpty()) {
                // S-2 (native parity): native draws a HorizontalDivider between the Genres section
                // and the Order By section (`SearchBottomSheet.kt:149-153`).
                if (filters.genres.isNotEmpty()) {
                    HorizontalDivider()
                }
                Text(
                    text = stringResource(Res.string.search_pfix_order_by),
                    style = MaterialTheme.typography.titleMedium,
                )
                ExposedDropdownMenuBox(
                    expanded = sortMenuOpen,
                    onExpandedChange = { sortMenuOpen = it },
                ) {
                    OutlinedTextField(
                        value = selectedSort ?: filters.sortTypes.first(),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortMenuOpen)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        filters.sortTypes.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    // Immediate-apply (native parity, F1): picking a sort fires
                                    // the sorted search right away; only the dropdown closes,
                                    // the sheet stays open.
                                    onSortSelect(option)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            // Native parity (F1): the Apply button is a plain "close the sheet" affordance — in
            // native `SearchBottomSheet` it is `Button(onClick = onDismiss)` and runs no search;
            // genre/sort selections have already been applied live above.
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
 * S-2 (native parity): a tappable section header with an expand/collapse chevron, faithful to the
 * native `SearchBottomSheet.SectionHeader` (title + KeyboardArrowUp/Down).
 */
@Composable
private fun GenresSectionHeader(
    title: String,
    expanded: Boolean,
    onHeaderClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
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

// region Preview

@Preview
@Composable
private fun SearchFilterSheetPreview() {
    // Note: ModalBottomSheet renders in a popup; this preview exercises the composable's
    // construction path (canned filters) rather than a pixel-accurate sheet render.
    SearchFilterSheet(
        filters = SearchFilters(
            sortTypes = listOf("Latest", "Popular", "A-Z"),
            genres = listOf("Action", "Romance", "Comedy", "Drama", "Fantasy"),
        ),
        selectedSort = "Latest",
        selectedGenres = listOf("Action"),
        onGenreClick = {},
        onSortSelect = {},
        onDismiss = {},
    )
}

// endregion
