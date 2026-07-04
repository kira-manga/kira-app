package me.manga.kira.domain.usecase.home

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.repository.HomeFeedRepository

/**
 * Fetch the first page of the active source's Home feed (Epic H1b).
 *
 * Forwards the typed [AppResult] from [HomeFeedRepository.fetchHome] verbatim (DIP §6).
 *
 * @param reset true to discard prior pagination state and start from page 1.
 */
class FetchHomeFeedUseCase(
    private val repository: HomeFeedRepository,
) {
    suspend operator fun invoke(reset: Boolean): AppResult<List<HomeFeedItem>> =
        repository.fetchHome(reset)
}
