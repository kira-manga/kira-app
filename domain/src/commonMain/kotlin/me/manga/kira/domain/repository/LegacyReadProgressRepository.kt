package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.progress.LegacyProgressPreparation
import me.manga.kira.domain.model.progress.LegacyProgressReconciliation

/** Explicit transfer preparation; neither operation forges a Reader handle or implies a flush. */
interface LegacyReadProgressRepository {
    /** Captures this raw chapter's legacy key; copying requires a unique saved work AND chapter. */
    suspend fun prepare(chapter: ChapterLocator): AppResult<LegacyProgressPreparation>

    /** Reopening cleanup for every durable receipt. An existing receipt can never copy again. */
    suspend fun reconcile(): AppResult<LegacyProgressReconciliation>
}
