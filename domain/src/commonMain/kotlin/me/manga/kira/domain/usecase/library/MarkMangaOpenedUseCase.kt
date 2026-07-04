package me.manga.kira.domain.usecase.library

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Record that a manga's Details screen was just opened, bumping its `lastOpenTimestamp` (no-op when
 * not in the library). The Library's LAST_READ sort orders by this (native parity — native bumps the
 * timestamp on each Details open).
 */
class MarkMangaOpenedUseCase(
    private val repository: LibraryRepository,
) {
    suspend operator fun invoke(api: String, language: String, title: String): AppResult<Unit> =
        repository.markOpened(api, language, title)
}
