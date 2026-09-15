package me.manga.kira.data.download.artifacts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.dao.ChapterArtifactDao
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.isOwnedBy
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.IOException
import okio.Path.Companion.toPath

/** Live engine adapter: original attempt authority, exact-file sizes and post-producer settlement. */
class ChapterDownloadArtifacts(
    val ownership: ChapterArtifacts,
    private val dao: ChapterArtifactDao,
    private val commits: ChapterArtifactCommitDao,
    private val recovery: ChapterArtifactRecovery,
    private val files: AppFileSystem,
) {
    suspend fun enqueue(chapter: SavedChapterEntity, requested: ChapterDownloadEntity): ChapterArtifactClaim? =
        ownership.enqueue(chapter, requested)

    suspend fun retry(expected: ChapterDownloadEntity, prepare: (String) -> Unit = {}): ChapterArtifactClaim? =
        ownership.retry(expected, prepare)

    suspend fun claim(entity: ChapterDownloadEntity): ChapterArtifactClaim? = ownership.downloadClaim(entity)

    /** Preserve queue order among eligible rows; a closing parent cannot block unrelated manga. */
    suspend fun scanQueued(queued: List<ChapterDownloadEntity>): QueuedArtifactAdmission {
        val blocked = linkedSetOf<Deferred<Unit>>()
        for (row in queued) {
            val admission = ownership.downloadAdmission(row)
            admission.claim?.let { return QueuedArtifactAdmission(QueuedChapterArtifact(row, it), blocked.toList()) }
            admission.parentReopen?.let { blocked += it }
        }
        return QueuedArtifactAdmission(null, blocked.toList())
    }

    /** Worker drains wait only for transient refusal, then re-read and recheck each exact ledger. */
    suspend fun awaitNextQueued(readQueued: suspend () -> List<ChapterDownloadEntity>): QueuedChapterArtifact? {
        while (true) {
            val admission = scanQueued(readQueued())
            admission.attempt?.let { return it }
            if (admission.parentReopens.isEmpty()) return null
            admission.awaitParentReopen()
        }
    }

    suspend fun complete(
        claim: ChapterArtifactClaim,
        entity: ChapterDownloadEntity,
        paths: List<String>,
        terminal: Boolean = true,
    ): Boolean = ownership.publish(claim) {
        commits.commitDownload(claim, entity, paths, exactSize(paths), terminal)
    } == true

    suspend fun fail(claim: ChapterArtifactClaim, message: String?): Boolean =
        ownership.publish(claim) { commits.failDownload(claim, message) } == true

    /** Keep the Android service's existing path-write seams under the original publication fence. */
    suspend fun preparePaths(claim: ChapterArtifactClaim, action: suspend () -> Unit) {
        ownership.publish(claim) {
            val record = checkNotNull(dao.get(claim.owner.chapterId))
            check(record.isOwnedBy(claim))
            if (record.committedRelativePath == null) {
                // Receipt precedes the separate legacy writes, so a failed/cancelled return is
                // still attributable to this token during authoritative settlement.
                check(dao.update(record.copy(committedToken = claim.token)) == 1)
                action()
            }
        }
    }

    /** Returns the revoked original attempt; callers cancel native work before draining it. */
    suspend fun cancel(chapterId: Long, message: String): ChapterArtifactClaim? {
        val claim = ownership.currentClaim(chapterId) ?: dao.download(chapterId)?.let { claim(it) } ?: return null
        if (claim.operation != ChapterArtifactOperation.DOWNLOAD) return null
        ownership.invalidate(claim) { commits.failDownload(claim, message) }
        return claim
    }

    /**
     * All engines use one state-aware history deletion. FAILED cleanup owns its captured ledger
     * until partial files, row removal and token release finish. Stop receives that exact token;
     * neither a delayed Delete nor its cancellation may target a replacement attempt.
     */
    suspend fun deleteAttempt(
        expected: ChapterDownloadEntity,
        stop: suspend (ChapterArtifactClaim) -> Unit = {},
    ): Boolean = try {
        if (expected.state == DownloadingState.SUCCESS) {
            ownership.parentRemoval(expected.mangaId) { dao.removeSuccessHistory(expected) }
        } else {
            val failed = ownership.beginFailedCleanup(expected)
            val claim = failed ?: if (expected.state == DownloadingState.FAILED) null else {
                ownership.cancelCapturedDownload(expected) { captured ->
                    commits.failDownload(captured, DownloadedChapter.CANCELLED_BY_USER_SENTINEL)
                }?.let { ownership.beginFailedCleanup(expected) }
            }
            if (claim == null) false else {
                stop(claim)
                withContext(NonCancellable) { recovery.settleFailedCleanup(ownership, claim) }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false // Keep the exact cleanup intent/row; never leak filesystem or provider details.
    }

    suspend fun settle(
        claim: ChapterArtifactClaim,
        requeue: Boolean = false,
        retainFailedPages: Boolean = true,
        afterIncomplete: suspend () -> Unit = {},
    ): Boolean = withContext(NonCancellable) {
        recovery.settleDownload(ownership, claim, requeue, retainFailedPages, afterIncomplete)
    }

    /** A racing worker may have already settled this cancelled token; prove that exact outcome. */
    suspend fun settleCancelled(claim: ChapterArtifactClaim): Boolean = try {
        if (settle(claim, retainFailedPages = false)) true else ownership.read(claim.owner.chapterId) { record ->
            val row = dao.download(claim.owner.chapterId)
            record != null && record.token == null && record.retiredRelativePath == null &&
                record.mangaId == claim.owner.mangaId && record.chapterUrl == claim.owner.chapterUrl &&
                row != null && row.id == claim.downloadId && row.mangaId == claim.owner.mangaId &&
                row.url == claim.owner.chapterUrl && row.state == DownloadingState.FAILED &&
                row.errorMsg == DownloadedChapter.CANCELLED_BY_USER_SENTINEL
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    suspend fun exactSize(paths: List<String>): Long = withContext(platformIoDispatcher) {
        paths.distinct().fold(0L) { total, path ->
            val metadata = files.fileSystem().metadata(path.toPath())
            val size = metadata.size ?: throw IOException("Missing artifact size")
            if (!metadata.isRegularFile || size < 0 || total > Long.MAX_VALUE - size) {
                throw IOException("Invalid artifact size")
            }
            total + size
        }
    }
}

data class QueuedChapterArtifact(val chapter: ChapterDownloadEntity, val claim: ChapterArtifactClaim)

data class QueuedArtifactAdmission(val attempt: QueuedChapterArtifact?, val parentReopens: List<Deferred<Unit>>) {
    suspend fun awaitParentReopen() {
        require(parentReopens.isNotEmpty())
        select<Unit> { parentReopens.forEach { parent -> parent.onAwait { Unit } } }
    }
}
