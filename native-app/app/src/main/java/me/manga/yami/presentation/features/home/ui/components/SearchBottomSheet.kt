package me.manga.yamiapk.presentation.features.home.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBottomSheet(
    showSheet: Boolean,
    onDismiss: () -> Unit,

    // Sort dropdown
    selectedSort: String,
    onSortSelected: (String, String) -> Unit,

    // Selected genre tag
    selectedGenre: String,
    onGenreClicked: (String) -> Unit,

    // Options
    allSortOptions: Set<String>,
    genres: Set<String>
) {
    if (!showSheet) return

    var genresExpanded by remember { mutableStateOf(true) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = backgroundColor,
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .padding(16.dp)
        ) {

            if (allSortOptions.isEmpty() && genres.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp), // or any height you like
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.search_filters_not_ready),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
                return@ModalBottomSheet
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.filter_sort_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding( 8.dp)
            )

            // Genres Section
            SectionHeader(
                title = stringResource(R.string.genres),
                expanded = genresExpanded,
                onHeaderClick = { genresExpanded = !genresExpanded }
            )
            AnimatedVisibility(visible = genresExpanded) {
                // Scrollable FlowRow container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                ) {
                    FlowRow{
                        genres.sorted().forEach { genre ->
                            FilterChip(
                                selected = genre == selectedGenre,
                                onClick = { onGenreClicked(genre) },
                                label = { Text(genre) },
                                leadingIcon = {
                                    if (genre == selectedGenre) Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null
                                    )
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = DividerDefaults.Thickness,
                color = DividerDefaults.color
            )
            // Sort Section
            Text(
                text = stringResource(R.string.order_by),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding( 8.dp)
            )
            ExposedDropdownMenuBox(
                expanded = sortMenuOpen,
                onExpandedChange = { sortMenuOpen = !sortMenuOpen }
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    value = selectedSort,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = sortMenuOpen
                        )
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                        unfocusedContainerColor = backgroundColor
                    )
                )
                ExposedDropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false }
                ) {
                    allSortOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onSortSelected(option,selectedGenre)
                                sortMenuOpen = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(stringResource(R.string.apply_filters))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}


@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onHeaderClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onHeaderClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null
        )
    }
}