package me.manga.kira.domain.usecase.reader

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.ProgressSnapshot
import me.manga.kira.domain.repository.ScopedReadProgressRepository

/** Deliberate Reader entry only; export and background saves must not acquire new sessions. */
class BeginReadProgressSessionUseCase(private val repository: ScopedReadProgressRepository) {
    suspend operator fun invoke(chapter: ChapterLocator): AppResult<ProgressSnapshot> = repository.beginSession(chapter)
}
