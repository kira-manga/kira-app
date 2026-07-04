package me.manga.kira.domain.usecase.home

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.repository.HomeFeedRepository

/**
 * Fetch the next page of the active source's Home feed for infinite scroll (Epic H1b).
 *
 * Forwards the typed [AppResult] from [HomeFeedRepository.fetchMore] verbatim (DIP §6).
 */
class FetchMoreHomeFeedUseCase(
    private val repository: HomeFeedRepository,
) {
    suspend operator fun invoke(page: Int): AppResult<List<HomeFeedItem>> =
        repository.fetchMore(page)
}
