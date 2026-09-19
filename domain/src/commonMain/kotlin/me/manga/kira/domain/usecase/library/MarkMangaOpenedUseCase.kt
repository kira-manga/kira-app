package me.manga.kira.domain.usecase.library

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Record a chapter-open event for the displayed saved owner, bumping its last-open timestamp.
 * The Library LAST_READ sort uses this date. A missing/replaced owner is a failure, not a no-op.
 */
class MarkMangaOpenedUseCase(
    private val repository: LibraryRepository,
) {
    suspend operator fun invoke(owner: SavedWorkIdentity): AppResult<Unit> = repository.markOpened(owner)
}
