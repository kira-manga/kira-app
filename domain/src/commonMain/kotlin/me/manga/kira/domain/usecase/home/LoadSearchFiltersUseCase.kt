package me.manga.kira.domain.usecase.home

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.repository.HomeFeedRepository

/**
 * Load the active source's ORDERED advanced-filter descriptors for the search filter sheet
 * (config-driven filters, 2026-07 — previously the flat legacy sortTypes/genres pair).
 *
 * Thin pass-through over [HomeFeedRepository.loadSourceFilters] (DIP §6) — carries the typed
 * [AppResult] so a failed active-source resolution surfaces as a handled failure at the
 * presentation boundary rather than an uncaught throw.
 */
class LoadSearchFiltersUseCase(
    private val repository: HomeFeedRepository,
) {
    suspend operator fun invoke(): AppResult<List<SourceFilter>> = repository.loadSourceFilters()
}
