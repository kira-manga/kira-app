package me.manga.kira.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.filters_pfix_language
import me.manga.kira.ui.generated.resources.filters_pfix_status
import me.manga.kira.ui.generated.resources.filters_pfix_type
import me.manga.kira.ui.generated.resources.search_genres
import me.manga.kira.ui.generated.resources.search_pfix_filter_collapsed
import me.manga.kira.ui.generated.resources.search_pfix_filter_expanded
import me.manga.kira.ui.generated.resources.search_pfix_order_by
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
internal fun FilterSection(
    filter: SourceFilter,
    selected: List<String>,
    drafts: SearchFilterDrafts,
    onChange: (List<String>) -> Unit,
    onApplyDrafts: (Map<String, String>) -> Unit,
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
        FilterControlType.TEXT, FilterControlType.NUMBER -> TextSection(filter, drafts, onApplyDrafts)
    }
}

/** Localized standard ids; custom filters keep their config labels. */
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

/** Immediate chip selection, with the existing independently scrolling/collapsible option list. */
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun ChipFlowSection(
    filter: SourceFilter,
    selected: Set<String>,
    multiSelect: Boolean,
    onChange: (List<String>) -> Unit,
) {
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
            ChipOptions(filter, selected, multiSelect, onChange)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun ChipOptions(
    filter: SourceFilter,
    selected: Set<String>,
    multiSelect: Boolean,
    onChange: (List<String>) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        filter.options.forEach { option ->
            val isSelected = option.value in selected
            FilterChip(
                selected = isSelected,
                onClick = { onChange(nextChipSelection(filter, selected, option.value, multiSelect)) },
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

private fun nextChipSelection(
    filter: SourceFilter,
    selected: Set<String>,
    value: String,
    multiSelect: Boolean,
): List<String> =
    when {
        multiSelect ->
            filter.options.map { it.value }.filter {
                if (it == value) value !in selected else it in selected
            }
        value in selected -> emptyList()
        else -> listOf(value)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun DropdownSection(
    filter: SourceFilter,
    selectedValue: String?,
    onChange: (List<String>) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel = filter.options.firstOrNull { it.value == selectedValue }?.label ?: filter.options.first().label
    Text(text = sectionTitle(filter), style = MaterialTheme.typography.titleMedium)
    ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownOptions(filter) { value ->
                onChange(listOf(value))
                menuOpen = false
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun DropdownOptions(
    filter: SourceFilter,
    onSelect: (String) -> Unit,
) {
    filter.options.forEach { option ->
        DropdownMenuItem(text = { Text(option.label) }, onClick = { onSelect(option.value) })
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun ToggleSection(
    filter: SourceFilter,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
) {
    val checked = (selected.firstOrNull() ?: filter.defaultValues.firstOrNull()) == "true"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = LocalSpacing.current.sm),
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

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun TextSection(
    filter: SourceFilter,
    drafts: SearchFilterDrafts,
    onApplyDrafts: (Map<String, String>) -> Unit,
) {
    Text(text = sectionTitle(filter), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = drafts.value(filter.id),
        onValueChange = { drafts.edit(filter.id, it) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (filter.type == FilterControlType.NUMBER) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { applyDrafts(drafts, onApplyDrafts, filter.id) }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onHeaderClick: () -> Unit,
) {
    val expansionStateDescription =
        stringResource(
            if (expanded) Res.string.search_pfix_filter_expanded else Res.string.search_pfix_filter_collapsed,
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onHeaderClick() }
                .semantics { stateDescription = expansionStateDescription }
                .padding(vertical = LocalSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
        )
    }
}

private const val DROPDOWN_MAX_OPTIONS = 8
