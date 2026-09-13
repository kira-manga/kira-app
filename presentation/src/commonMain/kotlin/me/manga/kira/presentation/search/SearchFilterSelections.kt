package me.manga.kira.presentation.search

import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.SourceFilter

internal object SearchFilterSelections {
    /**
     * Reconcile declared filters without turning a held empty selection into an absent entry.
     * Retired ids are dropped; absent entries seed nonempty defaults; held values are pruned but
     * keep their key even when nothing remains. An untouched, defaultless filter stays absent.
     */
    fun reconcile(
        filters: List<SourceFilter>,
        held: Map<String, List<String>>,
    ): Map<String, List<String>> =
        buildMap {
            for (filter in filters) {
                val heldValues = held[filter.id]
                val values =
                    if (heldValues == null) filter.defaultValues else prune(filter, heldValues)
                if (heldValues != null || values.isNotEmpty()) put(filter.id, values)
            }
        }

    /** Merge only current text/number drafts; a pruned blank remains an explicit empty entry. */
    fun mergeDrafts(
        filters: List<SourceFilter>,
        held: Map<String, List<String>>,
        drafts: Map<String, String>,
    ): Map<String, List<String>> =
        held +
            buildMap {
                for (filter in filters) {
                    val isTextInput = filter.type == FilterControlType.TEXT || filter.type == FilterControlType.NUMBER
                    val draft = drafts[filter.id]
                    if (isTextInput && draft != null) put(filter.id, prune(filter, listOf(draft)))
                }
            }

    fun prune(
        filter: SourceFilter,
        values: List<String>,
    ): List<String> =
        when (filter.type) {
            FilterControlType.SELECT, FilterControlType.MULTISELECT -> {
                val known = filter.options.map { it.value }.toSet()
                values.filter { it in known }
            }
            FilterControlType.TOGGLE -> values.filter { it == "true" || it == "false" }
            FilterControlType.TEXT, FilterControlType.NUMBER -> values.filter { it.isNotBlank() }
        }
}
