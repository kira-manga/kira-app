package me.manga.kira.domain.usecase.home

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SearchMode
import me.manga.kira.domain.repository.SearchRepository

/**
 * Search a single source (Epic H1b).
 *
 * Forwards the typed [AppResult] from [SearchRepository.searchSource] verbatim (DIP §6). The
 * [mode] + [sort] + [genres] map onto the legacy `SearchType` variants in the `:data` impl.
 */
class SearchSourceUseCase(
    private val repository: SearchRepository,
) {
    suspend operator fun invoke(
        query: String,
        mode: SearchMode,
        sort: String?,
        genres: List<String>,
    ): AppResult<List<HomeFeedItem>> = repository.searchSource(query, mode, sort, genres)
}
