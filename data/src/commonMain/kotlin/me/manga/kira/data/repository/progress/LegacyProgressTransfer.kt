package me.manga.kira.data.repository.progress

import me.manga.kira.data.local.dao.ReaderProgressSnapshot
import me.manga.kira.data.local.entity.ReaderLegacyCapture
import me.manga.kira.data.local.entity.ReaderLegacyCleanupEntity
import me.manga.kira.data.local.entity.ReaderLegacyDisposition
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.progress.LegacyProgressDisposition

/** Room-only phase. No Settings access until the entire owning transaction has committed. */
internal class LegacyProgressTransfer(private val session: ProgressOwnerSession) {
    private val storage = session.storage

    suspend fun commit(request: ChapterLocator, captured: CapturedLegacyProgress): LegacyTransferDecision {
        storage.cleanup.find(captured.key, captured.payload)?.let {
            return LegacyTransferDecision(LegacyProgressDisposition.CLEANUP_ONLY, it)
        }
        val position = captured.decode(request.chapterUrl) ?: return retained()
        val proven = LegacyProgressOwnerResolver(storage.savedCatalog, session.policy).prove(position)
            ?: return retained()
        val resolved = session.resolveChapter(request)
        if (resolved.owner != proven) return retained()
        val current = session.anchors.ensure(resolved)
        val disposition = copyIfUnclaimed(current, position.pageIndex)
        val receipt = storage.cleanup.recordOnce(
            ReaderLegacyCleanupEntity(
                captured.key, captured.payload, current.chapterId,
                ReaderLegacyCapture(current.workGeneration, current.chapterGeneration, disposition),
            ),
        )
        val outcome = if (disposition == ReaderLegacyDisposition.COPIED) {
            LegacyProgressDisposition.COPIED
        } else {
            LegacyProgressDisposition.SUPERSEDED
        }
        return LegacyTransferDecision(outcome, receipt)
    }

    private suspend fun copyIfUnclaimed(current: ReaderProgressSnapshot, page: Int): ReaderLegacyDisposition {
        if (current.pageIndex != null || current.workGeneration != 0L || current.chapterGeneration != 0L) {
            return ReaderLegacyDisposition.SUPERSEDED
        }
        requireProgress(storage.progress.savePosition(current, page), ProgressRejection.WRITE_COUNT)
        return ReaderLegacyDisposition.COPIED
    }

    private fun retained() = LegacyTransferDecision(LegacyProgressDisposition.RETAINED, null)
}

internal data class LegacyTransferDecision(
    val disposition: LegacyProgressDisposition,
    val receipt: ReaderLegacyCleanupEntity?,
)
