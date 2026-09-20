package me.manga.kira.data.repository.progress

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.progress.LegacyProgressCleanup
import me.manga.kira.domain.model.progress.LegacyProgressDisposition
import me.manga.kira.domain.model.progress.LegacyProgressPreparation
import me.manga.kira.domain.model.progress.LegacyProgressReconciliation
import me.manga.kira.domain.repository.LegacyReadProgressRepository

/**
 * Copy/receipt commit precedes exact Settings cleanup; acknowledgement is a separate writer.
 * Unbound until composition supplies accepted-policy authority, a shared gate, and genuine writer
 * cutover ownership. Native Room progress can operate while legacy cleanup remains deferred.
 */
class RoomLegacyReadProgressRepository(
    private val owners: ProgressOwnerTransactions,
    private val settings: LegacyProgressSettings,
) : LegacyReadProgressRepository {
    private val reconciler = LegacyProgressReconciler(owners, settings)

    override suspend fun prepare(chapter: ChapterLocator): AppResult<LegacyProgressPreparation> =
        progressStorageResult {
            val captured = settings.capture(chapter.chapterUrl)
                ?: return@progressStorageResult LegacyProgressPreparation(LegacyProgressDisposition.ABSENT)
            val decision = owners.write { LegacyProgressTransfer(this).commit(chapter, captured) }
            val cleanup = decision.receipt?.let { reconciler.finish(it) } ?: LegacyProgressCleanup.NOT_ATTEMPTED
            LegacyProgressPreparation(decision.disposition, cleanup)
        }

    override suspend fun reconcile(): AppResult<LegacyProgressReconciliation> =
        progressStorageResult { reconciler.reconcile() }
}
