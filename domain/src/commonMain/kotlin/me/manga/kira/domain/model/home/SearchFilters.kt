package me.manga.kira.domain.model.home

/**
 * Pure-domain bundle of the filter options a source exposes for search.
 *
 * Sourced from the legacy `BaseMangaRepository.sortTypes` + `allGenres` of the active source. The
 * UI renders these as a sort dropdown + a genre chip grid in the search bottom sheet; both lists
 * are plain source-supplied label strings (opaque to domain), and either may be empty for a
 * source that supports only plain text search.
 */
data class SearchFilters(
    /** Source-supplied sort-mode labels (e.g. "Latest", "Popular"). May be empty. */
    val sortTypes: List<String>,
    /** Source-supplied genre labels for genre filtering. May be empty. */
    val genres: List<String>,
)

/**
 * The mode a search request runs in, covering the three legacy `SearchType` variants
 * (`:shared/.../home/data/SearchType.kt`):
 *  - [NORMAL] — plain text query only (legacy `SearchType.Normal`).
 *  - [SORT] — text query + a chosen sort type + genres (legacy `SearchType.SORT`).
 *  - [GENRES] — text query + chosen genres (legacy `SearchType.GENRES`).
 *
 * The mode selects which of the [SearchFilters] axes a search request carries; the per-source
 * dispatch onto the legacy `fetchSearchDataF(when ...)` lives behind the `:data` impl.
 */
enum class SearchMode {
    NORMAL,
    SORT,
    GENRES,
}
