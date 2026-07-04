package me.manga.kira.domain.usecase.home

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.repository.HomeFeedRepository

/**
 * Observe the per-source [SiteState] for a given source (Epic H1b).
 *
 * Drives the Home maintenance/stopped/adult gating. Thin pass-through over
 * [HomeFeedRepository.observeSiteState] (DIP §6).
 */
class ObserveSiteStateUseCase(
    private val repository: HomeFeedRepository,
) {
    operator fun invoke(api: String): Flow<SiteState> = repository.observeSiteState(api)
}
