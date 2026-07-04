package me.manga.kira.domain.usecase.home

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.repository.SearchRepository

/**
 * Fan a query out across all enabled repos and stream per-repo results (Epic H1b).
 *
 * Forwards the per-repo result map from [SearchRepository.searchAllRepos] verbatim (DIP §6); map
 * key = source `api`, value = that repo's own [AppResult] (or `null` while it is still loading) so
 * one failure doesn't sink the others.
 */
class SearchAllReposUseCase(
    private val repository: SearchRepository,
) {
    operator fun invoke(query: String): Flow<Map<String, AppResult<List<HomeFeedItem>>?>> =
        repository.searchAllRepos(query)
}
