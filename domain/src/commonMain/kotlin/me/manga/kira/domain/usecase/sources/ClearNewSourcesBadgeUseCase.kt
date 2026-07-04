package me.manga.kira.domain.usecase.sources

import me.manga.kira.domain.repository.SourcesRepository

/**
 * U2 (new-sources badge): clear the "NEW" chip once the user opens the source-edit surface —
 * native parity with `HomeRoute`'s `setNewSources(false)` on the edit-tabs click.
 */
class ClearNewSourcesBadgeUseCase(
    private val repository: SourcesRepository,
) {
    suspend operator fun invoke() = repository.setHasNewSources(false)
}
