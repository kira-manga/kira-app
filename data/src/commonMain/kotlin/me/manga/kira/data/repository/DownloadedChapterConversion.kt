package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.dao.ChapterConversionOutcome
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.platform.filesystem.AppFileSystem

/**
 * The I/O dependencies of Settings' existing-download conversion, separate from preferences/cache.
 * Chapters select the loose-page inputs, manga supplies progress titles, downloads protects active
 * work and stores refreshed sizes, and archives/files publish and measure the converted output.
 * The repository retains progress, cancellation, per-chapter error isolation, and all write ordering.
 */
class DownloadedChapterConversion(
    val chapters: ChapterDao,
    val archives: CbzWriter,
    val manga: MangaDao,
    val downloads: ChapterDownloadDao,
    val files: AppFileSystem,
    private val artifacts: ChapterArtifacts,
    private val commits: ChapterArtifactCommitDao,
    private val operations: DownloadOperationExclusion,
) {
    /**
     * Admit Settings' whole batch before recovery or chapter/active-row/title capture. Keep admission
     * through every conversion and its final settlement; nested calls reuse this graph's real gate.
     */
    suspend fun <T> withConversionOperation(block: suspend () -> T): T = operations.withOperation { block() }

    /** Retry only drained/retiring receipts, before Settings reads its eligible chapter snapshot. */
    suspend fun recover() = operations.withOperation { artifacts.recoverConversions() }

    /**
     * Retain admission through real writer completion and durable settlement, including cancellation.
     * Batch callers also hold this graph's operation before selecting their chapter/active-row roster.
     */
    suspend fun convert(chapter: SavedChapterEntity): Boolean = operations.withOperation { convertOwned(chapter) }

    private suspend fun convertOwned(chapter: SavedChapterEntity): Boolean {
        val claim = artifacts.beginConversion(chapter) ?: return false
        var outcome = ChapterConversionOutcome.UNKNOWN
        try {
            artifacts.convertFiles(
                claim,
                write = { paths -> archives.createCbzWithSplittingRetainingSources(paths, chapter.mangaId, chapter.id) },
                commit = { archive, size -> commits.commitConversion(claim, chapter, listOf(archive.toString()), size) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A thrown Room/dispatcher return can follow a real commit. Readback below decides.
        } finally {
            withContext(NonCancellable) {
                try {
                    outcome = artifacts.settleConversion(claim)
                } catch (_: Exception) {
                    // Preserve the original cancellation and both copies/custody on unknown proof.
                }
            }
        }
        return outcome == ChapterConversionOutcome.COMMITTED
    }
}
