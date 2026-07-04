package me.manga.kira.domain.usecase.home

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.HomeFeedRepository

/**
 * Observe the active Home tab index (Epic H1b).
 *
 * Thin pass-through over [HomeFeedRepository.observeActiveTabIndex] (DIP §6).
 */
class ObserveActiveTabIndexUseCase(
    private val repository: HomeFeedRepository,
) {
    operator fun invoke(): Flow<Int> = repository.observeActiveTabIndex()
}
