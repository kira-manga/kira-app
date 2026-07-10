package me.manga.kira.domain.model.filters

/**
 * The ordered, render-ready projection of one source's advanced search filters (config-driven
 * filters, 2026-07 — docs/sources/CONFIG_DRIVEN_FILTERS_PLAN.md). Pure display/selection model:
 * it deliberately carries NO request-mapping information — how a selection reaches the wire is the
 * source engine's concern, never the UI's. Produced for generic sources from their validated
 * config stanza, and for legacy sources from the `sortTypes`/`allGenres` adapter in `:data`, so
 * `:presentation`/`:ui` render exactly one filter model for both worlds.
 */
data class SourceFilter(
    /** Stable identity. Standard ids: `genres`, `sort`, `status`, `language`, `type`. */
    val id: String,
    /** Display label; the UI may localize the header for standard ids. */
    val label: String,
    val type: FilterControlType,
    /** Empty for [FilterControlType.TOGGLE]/[FilterControlType.TEXT]/[FilterControlType.NUMBER]. */
    val options: List<FilterOption> = emptyList(),
    /** Values pre-selected when no user selection exists (and restored on reset). */
    val defaultValues: List<String> = emptyList(),
    val required: Boolean = false,
    /** ALL conditions must hold for the filter to be rendered (and sent). */
    val visibleWhen: List<FilterCondition> = emptyList(),
    /** Set when this multiselect is the exclusion counterpart of another filter. */
    val excludeOf: String? = null,
)

enum class FilterControlType { SELECT, MULTISELECT, TOGGLE, TEXT, NUMBER }

/** One selectable option. [value] is the stable backend value; [label] is display-only. */
data class FilterOption(
    val value: String,
    val label: String,
)

/** Holds when the referenced filter's effective value intersects [anyOf]. */
data class FilterCondition(
    val filterId: String,
    val anyOf: List<String>,
)

/**
 * The user's current filter selections, keyed by filter id. Values are backend option values
 * (select/multiselect), `"true"`/`"false"` (toggle), or free text (text/number). Unknown ids and
 * unknown option values are dropped defensively at request-composition time — stale state can
 * never inject arbitrary parameters.
 */
data class FilterSelections(
    val byId: Map<String, List<String>> = emptyMap(),
) {
    fun isEmpty(): Boolean = byId.all { (_, values) -> values.isEmpty() }

    companion object {
        val EMPTY = FilterSelections()
    }
}
