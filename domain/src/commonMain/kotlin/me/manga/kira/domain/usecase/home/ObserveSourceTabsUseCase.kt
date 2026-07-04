package me.manga.kira.domain.usecase.home

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.domain.repository.HomeFeedRepository

/**
 * Observe the enabled-source tabs for the Home tab row (Epic H1b).
 *
 * Thin pass-through over [HomeFeedRepository.observeSourceTabs] — exists so presentation depends on
 * a use case, not the repository (DIP §6). Constructor injection; Koin binds as a factory.
 */
class ObserveSourceTabsUseCase(
    private val repository: HomeFeedRepository,
) {
    operator fun invoke(): Flow<List<SourceTab>> = repository.observeSourceTabs()
}
