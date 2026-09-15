package me.manga.kira.data.download.artifacts

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Deferred
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
import me.manga.kira.platform.filesystem.AppFileSystem
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

    suspend fun settle(
        claim: ChapterArtifactClaim,
        requeue: Boolean = false,
        retainFailedPages: Boolean = true,
        afterIncomplete: suspend () -> Unit = {},
    ): Boolean = withContext(NonCancellable) {
        recovery.settleDownload(ownership, claim, requeue, retainFailedPages, afterIncomplete)
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
