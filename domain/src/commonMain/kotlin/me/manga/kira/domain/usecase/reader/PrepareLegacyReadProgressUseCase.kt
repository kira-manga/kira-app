package me.manga.kira.domain.usecase.reader

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.progress.LegacyProgressPreparation
import me.manga.kira.domain.repository.LegacyReadProgressRepository

/** Optional scoped preparation before a native read; unknown legacy ownership remains recoverable. */
class PrepareLegacyReadProgressUseCase(private val repository: LegacyReadProgressRepository) {
    suspend operator fun invoke(chapter: ChapterLocator): AppResult<LegacyProgressPreparation> =
        repository.prepare(chapter)
}
