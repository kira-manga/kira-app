package me.manga.kira.data.download.artifacts

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.dao.ChapterArtifactDao
import me.manga.kira.data.local.dao.ChapterRestoreOutcome
import me.manga.kira.data.local.dao.ChapterDownloadOutcome
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.claimOrNull
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState

/** Bounded per-chapter restore settlement. Unknown SQL/file outcomes keep both intent and bytes. */
class ChapterArtifactRecovery(
    private val dao: ChapterArtifactDao,
    private val commits: ChapterArtifactCommitDao,
    private val appFileSystem: AppFileSystem,
) {
    private val log = Logger.withTag("ChapterArtifactRecovery")

    /** Called once by the shared admission barrier, before any new producer/import can start. */
    suspend fun beforeAdmission(artifacts: ChapterArtifacts) {
        for (record in dao.getUnsettled()) {
            try {
                if (record.operation == ChapterArtifactOperation.RESTORE) {
                    record.claimOrNull()?.let { settleRestore(artifacts, it) }
                } else if (record.operation == ChapterArtifactOperation.DOWNLOAD) {
                    val claim = record.claimOrNull() ?: continue
                    val row = dao.download(record.chapterId)
                    if (record.retiring || row == null || row.state.name in setOf("SUCCESS", "FAILED")) {
                        settleDownload(artifacts, claim, requeue = true)
                    }
                } else if (record.operation == ChapterArtifactOperation.DELETE) {
                    record.claimOrNull()?.let { claim ->
                        if (!artifacts.settle(claim) { clearAndDelete(claim) }) {
                            log.w { "Chapter cleanup retained for retry" }
                        }
                    }
                } else if (record.operation == ChapterArtifactOperation.CONVERT) {
                    // The old process's native codec is gone. Preserve whichever paths/bytes actually
                    // committed; conversion atomicity/recovery is separate from shared file custody.
                    record.claimOrNull()?.let { claim -> artifacts.settle(claim) { true } }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep this chapter's durable custody without blocking unrelated chapters.
                log.w { "Chapter cleanup retained for retry" }
            }
        }
    }

    /** Caller holds this chapter's exclusive files/transition gates and its durable DELETE token. */
    internal suspend fun clearAndDelete(claim: ChapterArtifactClaim): Boolean = withContext(platformIoDispatcher) {
        if (!commits.clearForRemoval(claim)) return@withContext false
        appFileSystem.fileSystem().deleteRecursively(
            appFileSystem.chapterDir(claim.owner.mangaId, claim.owner.chapterId),
            mustExist = false,
        )
        true
    }

    /** Call after the producer's actual finally, not merely after requesting Job cancellation. */
    suspend fun settleDownload(
        artifacts: ChapterArtifacts,
        claim: ChapterArtifactClaim,
        requeue: Boolean = false,
        retainFailedPages: Boolean = true,
        afterIncomplete: suspend () -> Unit = {},
    ): Boolean = try {
        artifacts.settle(claim) { record ->
            when (commits.downloadOutcome(claim)) {
                ChapterDownloadOutcome.COMPLETE -> {
                    withContext(platformIoDispatcher) {
                        val manifest = appFileSystem.chapterDir(claim.owner.mangaId, claim.owner.chapterId) / "manifest.json"
                        appFileSystem.fileSystem().delete(manifest, mustExist = false)
                        record.retiredRelativePath?.let { retired ->
                            val path = ChapterArtifactReference.resolve(appFileSystem, claim.owner, retired)
                            appFileSystem.fileSystem().deleteRecursively(checkNotNull(path.parent), mustExist = false)
                            check(dao.releaseRetiredPath(record.chapterId, claim.token, retired) == 1)
                        }
                    }
                    true
                }
                ChapterDownloadOutcome.INCOMPLETE -> {
                    val row = dao.download(record.chapterId)
                    // Ordinary failure keeps verified page work available to Retry, but only after
                    // the old producer has drained. Cancellation/system-stop still discard partials.
                    val retainForRetry = retainFailedPages && row != null && row.id == claim.downloadId &&
                        row.state == DownloadingState.FAILED &&
                        row.errorMsg != DownloadedChapter.CANCELLED_BY_USER_SENTINEL
                    if (!commits.settleIncompleteDownload(claim, requeue)) false else {
                        if (!retainForRetry) deleteUncommittedPages(claim)
                        afterIncomplete()
                        true
                    }
                }
                ChapterDownloadOutcome.UNKNOWN -> false
            }
        }.also { settled -> if (!settled) log.w { "Chapter cleanup retained for retry" } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        log.w { "Chapter cleanup retained for retry" }
        false // Failed read/cleanup/release retains the exact token for recovery.
    }

    /** No recursive chapter deletion: a prior CBZ/restored generation is not this attempt's file. */
    private suspend fun deleteUncommittedPages(claim: ChapterArtifactClaim) = withContext(platformIoDispatcher) {
        val fs = appFileSystem.fileSystem()
        val directory = appFileSystem.chapterDir(claim.owner.mangaId, claim.owner.chapterId)
        if (!fs.exists(directory)) return@withContext
        fs.list(directory).filter { path ->
            path.name.startsWith("image_") || path.name.startsWith(".image_") ||
                path.name.startsWith(".manifest-") || path.name == "manifest.json" || path.name.endsWith(".cbz.part")
        }.forEach { fs.delete(it, mustExist = false) }
        if (fs.list(directory).isEmpty()) fs.delete(directory, mustExist = false)
    }

    /** [createdLocally] proves exclusive empty-directory creation even before its Room receipt. */
    suspend fun settleRestore(
        artifacts: ChapterArtifacts,
        claim: ChapterArtifactClaim,
        createdLocally: Boolean = false,
    ): ChapterRestoreOutcome {
        var outcome = ChapterRestoreOutcome.UNKNOWN
        try {
            artifacts.settle(claim) { record ->
                val pending = claim.pending ?: return@settle false
                val target = ChapterArtifactReference.resolve(appFileSystem, claim.owner, pending.relativePath)
                outcome = commits.readRestoreOutcome(claim, target.toString(), pending.sizeBytes)
                when (outcome) {
                    ChapterRestoreOutcome.COMMITTED -> {
                        val file = withContext(platformIoDispatcher) {
                            appFileSystem.fileSystem().metadataOrNull(target)
                        }
                        if (file?.isRegularFile != true || file.size != pending.sizeBytes) {
                            outcome = ChapterRestoreOutcome.UNKNOWN
                            false
                        } else true
                    }
                    ChapterRestoreOutcome.NOT_COMMITTED -> cleanUncommitted(record, claim, createdLocally)
                    ChapterRestoreOutcome.UNKNOWN -> false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A release failure cannot undo a commit already proved above. Before that proof,
            // the default remains UNKNOWN and the operation's durable token is retained.
            log.w { "Chapter cleanup retained for retry" }
        }
        if (outcome == ChapterRestoreOutcome.UNKNOWN) log.w { "Chapter cleanup retained for retry" }
        return outcome
    }

    private suspend fun cleanUncommitted(
        record: ChapterArtifactEntity,
        claim: ChapterArtifactClaim,
        createdLocally: Boolean,
    ): Boolean = withContext(platformIoDispatcher) {
        val target = ChapterArtifactReference.resolve(appFileSystem, claim.owner, checkNotNull(claim.relativePath))
        val directory = checkNotNull(target.parent)
        val fs = appFileSystem.fileSystem()
        when {
            record.ownsPendingPath -> fs.deleteRecursively(directory, mustExist = false)
            createdLocally -> fs.delete(directory, mustExist = false) // No bytes preceded the ownership receipt.
            fs.exists(directory) -> return@withContext false // Creation outcome/collision is not deletion authority.
        }
        true
    }
}
