package me.manga.kira.domain.usecase.home

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.repository.SearchRepository

/**
 * Search a single source with generic filter [FilterSelections] (config-driven filters, 2026-07).
 *
 * Forwards the typed [AppResult] from [SearchRepository.searchSource] verbatim (DIP §6). Routing
 * lives in `:data`: config-backed sources always run the generic engine with the selections;
 * legacy sources have their standard sort/genres selections translated onto the legacy
 * `SearchType`.
 */
class SearchSourceUseCase(
    private val repository: SearchRepository,
) {
    suspend operator fun invoke(
        query: String,
        selections: FilterSelections,
    ): AppResult<List<HomeFeedItem>> = repository.searchSource(query, selections)
}
