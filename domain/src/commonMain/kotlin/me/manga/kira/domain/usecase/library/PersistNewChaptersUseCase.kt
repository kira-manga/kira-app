package me.manga.kira.domain.usecase.library

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Persist refresh-discovered chapters that aren't yet saved for an in-library manga, flagging them
 * NEW (native parity). Returns the count inserted; no-op (0) when the manga isn't in the library.
 *
 * Invoked from the Details refresh success path so newly-published chapters survive leaving and
 * reopening the screen and gain the NEW badge — instead of living only in ViewModel state.
 */
class PersistNewChaptersUseCase(
    private val repository: LibraryRepository,
) {
    suspend operator fun invoke(
        api: String,
        language: String,
        title: String,
        fetched: List<Chapter>,
    ): AppResult<Int> = repository.persistNewChapters(api, language, title, fetched)
}
