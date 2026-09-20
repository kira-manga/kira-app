package me.manga.kira.domain.usecase.reader

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.progress.LegacyProgressReconciliation
import me.manga.kira.domain.repository.LegacyReadProgressRepository

/** Performs a bounded reopening pass without reapplying any already receipted position. */
class ReconcileLegacyReadProgressUseCase(private val repository: LegacyReadProgressRepository) {
    suspend operator fun invoke(): AppResult<LegacyProgressReconciliation> = repository.reconcile()
}
