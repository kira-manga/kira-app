package me.manga.kira.domain.usecase.home

import me.manga.kira.domain.repository.HomeFeedRepository

/**
 * Select the active Home source tab by index (Epic H1b).
 *
 * Delegates to [HomeFeedRepository.selectTab]; the impl clears the source cache and resets
 * pagination on switch (locked decision H-§77-(3)). Thin pass-through (DIP §6).
 */
class SelectSourceTabUseCase(
    private val repository: HomeFeedRepository,
) {
    suspend operator fun invoke(index: Int) = repository.selectTab(index)
}
