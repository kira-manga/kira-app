package me.manga.kira.domain.usecase.home

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.home.SearchFilters
import me.manga.kira.domain.repository.HomeFeedRepository

/**
 * Load the active source's sort types + genres for the search filter sheet (Epic H1b).
 *
 * Thin pass-through over [HomeFeedRepository.loadFilters] (DIP §6) — carries the typed
 * [AppResult] so a failed active-source resolution surfaces as a handled failure at the
 * presentation boundary rather than an uncaught throw.
 */
class LoadSearchFiltersUseCase(
    private val repository: HomeFeedRepository,
) {
    suspend operator fun invoke(): AppResult<SearchFilters> = repository.loadFilters()
}
