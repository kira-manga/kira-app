package me.manga.kira.data.mapper

import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.BaseMangaRepository

/**
 * The legacy→generic filter adapter (config-driven filters, 2026-07): projects a legacy
 * [BaseMangaRepository]'s `allGenres`/`sortTypes` into the SAME ordered [SourceFilter] shape a
 * config-backed source serves from its stanza, so `:presentation`/`:ui` render exactly one filter
 * model. Order (genres, then sort) and single-select genres mirror the pre-generic
 * `SearchFilterSheet` layout; option value == label because legacy repos receive the display
 * strings verbatim.
 */
internal fun BaseMangaRepository.toSourceFilters(): List<SourceFilter> =
    buildList {
        val genres = allGenres.toList()
        if (genres.isNotEmpty()) {
            add(
                SourceFilter(
                    id = "genres",
                    label = "genres",
                    type = FilterControlType.SELECT,
                    options = genres.map { FilterOption(value = it, label = it) },
                ),
            )
        }
        val sorts = sortTypes.toList()
        if (sorts.isNotEmpty()) {
            add(
                SourceFilter(
                    id = "sort",
                    label = "sort",
                    type = FilterControlType.SELECT,
                    options = sorts.map { FilterOption(value = it, label = it) },
                ),
            )
        }
    }

/**
 * The generic→legacy selection translation: maps the standard `sort`/`genres` selections onto the
 * legacy [SearchType] variants with exactly the pre-generic `searchTypeOf` semantics — sort wins
 * over genres, genres comma-join into one string, no selection = plain [SearchType.Normal]. Any
 * other selection id is ignored: a legacy source only ever exposed these two axes, so nothing else
 * can have been selected against it.
 */
internal fun legacySearchTypeOf(
    query: String,
    selections: FilterSelections,
): SearchType {
    val sort = selections.byId["sort"].orEmpty().firstOrNull { it.isNotBlank() }
    val genres =
        selections.byId["genres"]
            .orEmpty()
            .filter { it.isNotBlank() }
            .joinToString(",")
    return when {
        sort != null -> SearchType.SORT(query = query, sortType = sort, genres = genres)
        genres.isNotEmpty() -> SearchType.GENRES(query = query, genres = genres)
        else -> SearchType.Normal(query)
    }
}
