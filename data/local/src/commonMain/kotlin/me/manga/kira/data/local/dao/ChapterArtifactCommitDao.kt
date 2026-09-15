package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.ChapterConversionRoster
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.isOwnedBy
import me.manga.kira.presentation.features.download.data.DownloadingState

/** Atomic restore publication and authoritative settlement, separate from file I/O. */
@Dao
interface ChapterArtifactCommitDao {
    @Query("SELECT * FROM chapter_artifacts WHERE chapterId = :chapterId")
    suspend fun artifact(chapterId: Long): ChapterArtifactEntity?

    @Query("SELECT * FROM saved_chapters WHERE id = :chapterId")
    suspend fun saved(chapterId: Long): SavedChapterEntity?

    @Query("SELECT * FROM chapter_downloads WHERE chapterId = :chapterId")
    suspend fun download(chapterId: Long): ChapterDownloadEntity?

    @Query("SELECT api FROM saved_manga WHERE id = :mangaId")
    suspend fun mangaApi(mangaId: Long): String?

    @Query(
        "SELECT * FROM notifications WHERE chapterId = :chapterId AND mangaId = :mangaId " +
            "AND chapterUrl = :chapterUrl AND api = :api",
    )
    suspend fun notifications(chapterId: Long, mangaId: Long, chapterUrl: String, api: String): List<ChapterNotification>

    @Update(entity = SavedChapterEntity::class)
    suspend fun writeSaved(update: ArtifactReadableUpdate): Int

    @Update(entity = ChapterNotification::class)
    suspend fun writeNotification(update: ArtifactReadableUpdate): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeDownload(row: ChapterDownloadEntity): Long

    @Update
    suspend fun writeArtifact(record: ChapterArtifactEntity): Int

    @Query("DELETE FROM chapter_downloads WHERE chapterId = :chapterId AND id = :downloadId")
    suspend fun removeDownload(chapterId: Long, downloadId: Long): Int

    /** DELETE custody outlives both this transaction and any later filesystem failure. */
    @Transaction
    suspend fun clearForRemoval(claim: ChapterArtifactClaim): Boolean {
        val record = artifact(claim.owner.chapterId) ?: return false
        if (!record.isOwnedBy(claim) || claim.operation != ChapterArtifactOperation.DELETE) return false
        val chapter = saved(claim.owner.chapterId)
        if (chapter != null && !claim.owner.matches(chapter)) return false
        val row = download(claim.owner.chapterId)
        if (row != null && (row.id != claim.downloadId || row.mangaId != claim.owner.mangaId ||
                row.url != claim.owner.chapterUrl)
        ) return false
        if (chapter != null) {
            check(writeSaved(ArtifactReadableUpdate(chapter.id, false, emptyList())) == 1)
            val api = mangaApi(chapter.mangaId) ?: row?.api
            if (api != null) {
                for (mirror in notifications(chapter.id, chapter.mangaId, chapter.url, api)) {
                    check(writeNotification(ArtifactReadableUpdate(mirror.id, false, emptyList())) == 1)
                }
            }
        }
        if (row != null) check(removeDownload(row.chapterId, row.id) == 1)
        return true
    }

    /** Complete retained-input publication; cleanup waits for authoritative post-producer readback. */
    @Transaction
    suspend fun commitConversion(
        claim: ChapterArtifactClaim,
        expected: SavedChapterEntity,
        paths: List<String>,
        sizeBytes: Long,
    ): Boolean {
        require(paths.isNotEmpty() && sizeBytes > 0)
        val record = artifact(expected.id) ?: return false
        if (!record.isOwnedBy(claim) || record.retiring || claim.operation != ChapterArtifactOperation.CONVERT ||
            record.committedRelativePath != null || record.retiredRelativePath != null ||
            claim.pending != null || record.ownsPendingPath
        ) return false
        val sources = claim.conversionSourceRoster?.let(ChapterConversionRoster::decode) ?: return false
        if (sources.map { it.storedPath } != expected.localImagePaths) return false
        val chapter = saved(expected.id) ?: return false
        if (!sameDownloadSnapshot(chapter, expected) || !chapter.isDownloaded || !claim.owner.matches(chapter)) return false
        val api = mangaApi(chapter.mangaId) ?: return false
        val row = download(chapter.id)
        if (row != null && (row.id != claim.downloadId || !row.matches(chapter) || row.isActiveArtifactDownload() || row.api != api)) return false
        check(writeSaved(ArtifactReadableUpdate(chapter.id, true, paths)) == 1)
        for (mirror in notifications(chapter.id, chapter.mangaId, chapter.url, api)) {
            check(writeNotification(ArtifactReadableUpdate(mirror.id, true, paths)) == 1)
        }
        if (row != null) check(writeDownload(row.copy(sizeBytes = sizeBytes)) == row.id)
        check(writeArtifact(record.copy(committedToken = claim.token)) == 1)
        return true
    }

    /** All metadata/ledger/mirror checks run in one actual Room read transaction. */
    @Transaction
    suspend fun readConversionOutcome(
        claim: ChapterArtifactClaim,
        canonicalPath: String,
        sizeBytes: Long?,
    ): ChapterConversionOutcome = conversionOutcome(claim, canonicalPath, sizeBytes)

    /** Original-token terminal publication, including retirement of an older restored generation. */
    @Transaction
    suspend fun commitDownload(
        claim: ChapterArtifactClaim,
        expected: ChapterDownloadEntity,
        paths: List<String>,
        sizeBytes: Long,
        terminal: Boolean = true,
    ): Boolean {
        require(paths.isNotEmpty() && sizeBytes >= 0)
        val record = artifact(claim.owner.chapterId) ?: return false
        if (!record.isOwnedBy(claim) || record.retiring || claim.operation != ChapterArtifactOperation.DOWNLOAD) return false
        val chapter = saved(claim.owner.chapterId) ?: return false
        val row = download(chapter.id) ?: return false
        if (!claim.owner.matches(chapter) || !row.sameAttempt(expected) || row.id != claim.downloadId ||
            !row.matches(chapter) || !row.isActiveArtifactDownload() || mangaApi(chapter.mangaId) != row.api
        ) return false
        // During a retry, leave an already committed restore readable until the new terminal commit.
        if (!terminal && record.committedRelativePath != null) return true
        check(writeSaved(ArtifactReadableUpdate(chapter.id, true, paths)) == 1)
        for (mirror in notifications(chapter.id, chapter.mangaId, chapter.url, row.api)) {
            check(writeNotification(ArtifactReadableUpdate(mirror.id, true, paths)) == 1)
        }
        val next = if (terminal) {
            row.copy(state = DownloadingState.SUCCESS, progress = 100, errorMsg = null, sizeBytes = sizeBytes)
        } else row.copy(sizeBytes = sizeBytes)
        check(writeDownload(next) == row.id)
        check(writeArtifact(record.copy(
            committedToken = claim.token,
            committedRelativePath = null,
            retiredRelativePath = record.committedRelativePath ?: record.retiredRelativePath,
        )) == 1)
        return true
    }

    /** Failure/cancel cannot affect a replacement attempt or an already committed success. */
    @Transaction
    suspend fun failDownload(claim: ChapterArtifactClaim, message: String?): Boolean {
        val record = artifact(claim.owner.chapterId) ?: return false
        if (!record.isOwnedBy(claim) || claim.operation != ChapterArtifactOperation.DOWNLOAD) return false
        val chapter = saved(claim.owner.chapterId) ?: return false
        val row = download(chapter.id) ?: return false
        if (!claim.owner.matches(chapter) || row.id != claim.downloadId || !row.matches(chapter) ||
            row.state == DownloadingState.SUCCESS
        ) return false
        check(writeDownload(row.copy(state = DownloadingState.FAILED, progress = 0, errorMsg = message)) == row.id)
        revertOwnedReadable(record, claim, chapter, row.api)
        return true
    }

    /** Files may only be compensated after this authoritative, token-bound read succeeds. */
    @Transaction
    suspend fun downloadOutcome(claim: ChapterArtifactClaim): ChapterDownloadOutcome {
        val record = artifact(claim.owner.chapterId) ?: return ChapterDownloadOutcome.UNKNOWN
        if (!record.isOwnedBy(claim)) return ChapterDownloadOutcome.UNKNOWN
        val chapter = saved(claim.owner.chapterId) ?: return ChapterDownloadOutcome.UNKNOWN
        if (!claim.owner.matches(chapter)) return ChapterDownloadOutcome.UNKNOWN
        val row = download(chapter.id)
        if (row != null && (row.id != claim.downloadId || !row.matches(chapter))) return ChapterDownloadOutcome.UNKNOWN
        if (record.committedToken == claim.token && chapter.isDownloaded && chapter.localImagePaths.isNotEmpty() &&
            (row == null || row.state == DownloadingState.SUCCESS)
        ) return ChapterDownloadOutcome.COMPLETE
        if (row == null || row.state == DownloadingState.SUCCESS) return ChapterDownloadOutcome.UNKNOWN
        return ChapterDownloadOutcome.INCOMPLETE
    }

    @Transaction
    suspend fun settleIncompleteDownload(claim: ChapterArtifactClaim, requeue: Boolean): Boolean {
        if (downloadOutcome(claim) != ChapterDownloadOutcome.INCOMPLETE) return false
        val record = checkNotNull(artifact(claim.owner.chapterId))
        val chapter = checkNotNull(saved(claim.owner.chapterId))
        val row = checkNotNull(download(chapter.id))
        revertOwnedReadable(record, claim, chapter, row.api)
        if (requeue && row.isActiveArtifactDownload()) {
            check(writeDownload(row.copy(state = DownloadingState.QUEUED, progress = 0)) == row.id)
        }
        return true
    }

    /** Reverts only this token's pre-compression readability, never a previous committed restore. */
    suspend fun revertOwnedReadable(
        record: ChapterArtifactEntity,
        claim: ChapterArtifactClaim,
        chapter: SavedChapterEntity,
        api: String,
    ) {
        if (record.committedToken != claim.token || record.committedRelativePath != null) return
        check(writeSaved(ArtifactReadableUpdate(chapter.id, false, emptyList())) == 1)
        for (mirror in notifications(chapter.id, chapter.mangaId, chapter.url, api)) {
            check(writeNotification(ArtifactReadableUpdate(mirror.id, false, emptyList())) == 1)
        }
        check(writeArtifact(record.copy(committedToken = null)) == 1)
    }

    /**
     * Files are already promoted within an exclusively owned generation. All required updates
     * are checked. A native commit can survive a cancelled suspend return: callers settle using
     * [readRestoreOutcome], never infer noncommit from a thrown exception.
     */
    @Transaction
    suspend fun commitRestore(
        claim: ChapterArtifactClaim,
        expected: SavedChapterEntity,
        absolutePath: String,
        requested: ChapterDownloadEntity,
    ): Boolean {
        val record = artifact(expected.id) ?: return false
        if (!record.isOwnedBy(claim) || record.retiring || !record.ownsPendingPath) return false
        if (claim.operation != ChapterArtifactOperation.RESTORE || claim.relativePath == null) return false
        check(requested.sizeBytes == record.pendingSizeBytes && requested.sizeBytes > 0)
        val current = saved(expected.id) ?: return false
        if (!sameDownloadSnapshot(current, expected) || current.isDownloaded || !claim.owner.matches(current)) return false
        if (!requested.matches(current) || mangaApi(current.mangaId) != requested.api) return false
        val prior = download(current.id)
        if (prior?.id != claim.downloadId || prior?.isActiveArtifactDownload() == true) return false
        if (prior != null && !prior.matches(current)) return false
        val paths = listOf(absolutePath)
        check(writeSaved(ArtifactReadableUpdate(current.id, true, paths)) == 1)
        check(writeDownload(requested.copy(id = prior?.id ?: 0, state = DownloadingState.SUCCESS, progress = 100, errorMsg = null)) > 0)
        for (mirror in notifications(current.id, current.mangaId, current.url, requested.api)) {
            check(writeNotification(ArtifactReadableUpdate(mirror.id, true, paths)) == 1)
        }
        check(writeArtifact(record.copy(committedToken = claim.token, committedRelativePath = claim.relativePath)) == 1)
        return true
    }

    /** UNKNOWN is a retained recovery state, never permission to compensate or count success. */
    @Transaction
    suspend fun readRestoreOutcome(
        claim: ChapterArtifactClaim,
        absolutePath: String,
        sizeBytes: Long,
    ): ChapterRestoreOutcome {
        val record = artifact(claim.owner.chapterId) ?: return ChapterRestoreOutcome.UNKNOWN
        val current = saved(claim.owner.chapterId) ?: return ChapterRestoreOutcome.UNKNOWN
        if (!claim.owner.matches(current)) return ChapterRestoreOutcome.UNKNOWN
        val row = download(current.id)
        if (record.committedToken == claim.token && record.committedRelativePath == claim.relativePath) {
            if (!current.isDownloaded || !referencesGeneration(current.localImagePaths, claim, absolutePath)) {
                return ChapterRestoreOutcome.UNKNOWN
            }
            if (row != null && (!row.matches(current) || row.state != DownloadingState.SUCCESS || row.sizeBytes != sizeBytes)) {
                return ChapterRestoreOutcome.UNKNOWN
            }
            val api = mangaApi(current.mangaId) ?: return ChapterRestoreOutcome.UNKNOWN
            if (notifications(current.id, current.mangaId, current.url, api).any {
                    !it.isDownloaded || !referencesGeneration(it.localImagePaths, claim, absolutePath)
                }
            ) return ChapterRestoreOutcome.UNKNOWN
            return ChapterRestoreOutcome.COMMITTED
        }
        if (!record.isOwnedBy(claim) || record.committedRelativePath == claim.relativePath) return ChapterRestoreOutcome.UNKNOWN
        if (current.localImagePaths.any { it == absolutePath || it.endsWith("/${claim.relativePath}") }) {
            return ChapterRestoreOutcome.UNKNOWN
        }
        if (row?.id != claim.downloadId || row?.isActiveArtifactDownload() == true) return ChapterRestoreOutcome.UNKNOWN
        return ChapterRestoreOutcome.NOT_COMMITTED
    }
}

/** Partial updates leave metadata, reading progress and notification identity untouched. */
data class ArtifactReadableUpdate(val id: Long, val isDownloaded: Boolean, val localImagePaths: List<String>)

/** Durable readback after the writer has unwound; a failed query is also UNKNOWN at the caller. */
enum class ChapterRestoreOutcome { COMMITTED, NOT_COMMITTED, UNKNOWN }

enum class ChapterDownloadOutcome { COMPLETE, INCOMPLETE, UNKNOWN }

private fun referencesGeneration(paths: List<String>, claim: ChapterArtifactClaim, absolutePath: String): Boolean =
    paths.singleOrNull()?.let { it == absolutePath || it.endsWith("/${claim.relativePath}") } == true
