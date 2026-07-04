package me.manga.kira.domain.usecase.home

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.repository.HomeFeedRepository

/**
 * Fetch the active source's popular carousel items (Epic H1b).
 *
 * Forwards the typed [AppResult] from [HomeFeedRepository.fetchFeatured] verbatim (DIP §6).
 */
class FetchFeaturedUseCase(
    private val repository: HomeFeedRepository,
) {
    suspend operator fun invoke(): AppResult<List<FeaturedManga>> = repository.fetchFeatured()
}
