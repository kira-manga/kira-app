package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactFile
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.ChapterConversionRoster
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.claimOrNull
import me.manga.kira.data.local.entity.isOwnedBy
import me.manga.kira.presentation.features.download.data.DownloadingState

/** Atomic admission shared by restore and all engines; filesystem work never runs in this DAO. */
@Dao
interface ChapterArtifactDao {
    @Query("SELECT * FROM chapter_artifacts WHERE chapterId = :chapterId")
    suspend fun get(chapterId: Long): ChapterArtifactEntity?

    @Query("SELECT * FROM chapter_artifacts WHERE token IS NOT NULL")
    suspend fun getUnsettled(): List<ChapterArtifactEntity>

    @Query("SELECT * FROM chapter_artifacts WHERE mangaId = :mangaId")
    suspend fun getForManga(mangaId: Long): List<ChapterArtifactEntity>

    @Query("SELECT * FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun savedForManga(mangaId: Long): List<SavedChapterEntity>

    @Query("SELECT * FROM saved_chapters WHERE id = :chapterId")
    suspend fun saved(chapterId: Long): SavedChapterEntity?

    @Query("SELECT api FROM saved_manga WHERE id = :mangaId")
    suspend fun mangaApi(mangaId: Long): String?

    @Query("SELECT * FROM chapter_downloads WHERE chapterId = :chapterId")
    suspend fun download(chapterId: Long): ChapterDownloadEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: ChapterArtifactEntity)

    @Update
    suspend fun update(record: ChapterArtifactEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(row: ChapterDownloadEntity): Long

    /** No fake QUEUED restore row: retain a prior terminal history id until the actual commit. */
    @Transaction
    suspend fun claimRestore(
        expected: SavedChapterEntity,
        token: String,
        pending: ChapterArtifactFile,
    ): ChapterArtifactClaim? {
        val current = saved(expected.id) ?: return null
        if (!sameDownloadSnapshot(current, expected) || current.isDownloaded) return null
        if (mangaApi(current.mangaId) == null) return null
        if (get(current.id)?.committedRelativePath != null) return null
        require(pending.sizeBytes > 0)
        val row = download(expected.id)
        if (row != null && (row.isActiveArtifactDownload() || !row.matches(current))) return null
        return reserve(current, token, ChapterArtifactOperation.RESTORE, row?.id, pending)
    }

    /** Insert the queue row AND its file owner in the same writer transaction. */
    @Transaction
    suspend fun enqueue(
        expected: SavedChapterEntity,
        requested: ChapterDownloadEntity,
        token: String,
    ): ChapterArtifactClaim? {
        val current = saved(expected.id) ?: return null
        if (!ChapterArtifactOwner.of(expected).matches(current) || !requested.matches(current)) return null
        if (mangaApi(current.mangaId) != requested.api) return null
        val record = get(current.id)
        if (!record.canReserve(current.mangaId)) return null
        val existing = download(current.id)
        if (existing != null && (existing.isActiveArtifactDownload() || !existing.matches(current))) return null
        val id = insertDownload(requested.copy(id = 0))
        check(id > 0)
        return checkNotNull(reserve(current, token, ChapterArtifactOperation.DOWNLOAD, id, null))
    }

    /** Preflight under coordinator file/transition pins; retry still rechecks in its writer transaction. */
    @Transaction
    suspend fun retryCandidate(expected: ChapterDownloadEntity): SavedChapterEntity? {
        if (expected.state != DownloadingState.FAILED) return null
        val current = download(expected.chapterId) ?: return null
        if (!current.sameAttempt(expected) || current.api != expected.api || current.state != DownloadingState.FAILED) return null
        val chapter = saved(current.chapterId) ?: return null
        if (!current.matches(chapter) || mangaApi(chapter.mangaId) != current.api) return null
        val record = get(chapter.id)
        if (!record.canReserve(chapter.mangaId) || (record != null && record.chapterUrl != chapter.url)) return null
        return chapter
    }

    /** Retry is a compare-and-reserve, never fresh enqueue after a stale UI capture. */
    @Transaction
    suspend fun retry(expected: ChapterDownloadEntity, token: String): ChapterArtifactClaim? {
        val chapter = retryCandidate(expected) ?: return null
        val current = download(expected.chapterId) ?: return null
        return enqueue(chapter, current.copy(
            id = 0, state = DownloadingState.QUEUED, progress = 0, errorMsg = null, sizeBytes = 0,
        ), token)
    }

    /**
     * Bootstrap a pre-upgrade active row only when no artifact record exists. Tokenless native
     * tasks must be fenced by their transport; they cannot be adopted under this new token.
     */
    @Transaction
    suspend fun claimExistingDownload(expected: ChapterDownloadEntity, token: String): ChapterArtifactClaim? {
        val current = download(expected.chapterId) ?: return null
        if (!current.sameAttempt(expected) || !current.isActiveArtifactDownload()) return null
        val chapter = saved(current.chapterId) ?: return null
        if (!current.matches(chapter) || mangaApi(chapter.mangaId) != current.api) return null
        val record = get(chapter.id)
        if (record != null) {
            // A fully settled system-stop/requeue starts a new token, never revives the old one.
            if (record.canReserve(chapter.mangaId) && record.chapterUrl == chapter.url) {
                return reserve(chapter, token, ChapterArtifactOperation.DOWNLOAD, current.id, null)
            }
            return record.claimOrNull()?.takeIf {
                !record.retiring && it.operation == ChapterArtifactOperation.DOWNLOAD &&
                    it.downloadId == expected.id && it.owner.matches(chapter)
            }
        }
        return reserve(chapter, token, ChapterArtifactOperation.DOWNLOAD, current.id, null)
    }

    /** Cancel only the captured active ledger; never refresh authority from chapterId alone. */
    @Transaction
    suspend fun claimCapturedCancellation(expected: ChapterDownloadEntity, token: String): ChapterArtifactClaim? {
        val current = download(expected.chapterId) ?: return null
        if (!current.sameAttempt(expected) || current.api != expected.api || !current.isActiveArtifactDownload()) return null
        val claim = claimExistingDownload(current, token) ?: return null
        check(revoke(current.chapterId, claim.token) == 1)
        return claim
    }

    /**
     * Terminal cleanup is not producer authority. Legacy and tokenless retained FAILED attempts
     * get durable custody; an exact old DOWNLOAD token is reused only to drain its real file users.
     * A conversion, restore, replacement ledger or unresolved retired path is never taken over.
     */
    @Transaction
    suspend fun claimFailedCleanup(expected: ChapterDownloadEntity, token: String): ChapterArtifactClaim? {
        require(token.isNotBlank())
        val current = download(expected.chapterId) ?: return null
        if (!current.sameAttempt(expected) || current.api != expected.api || current.state != DownloadingState.FAILED) return null
        val chapter = saved(current.chapterId) ?: return null
        if (!current.matches(chapter) || mangaApi(chapter.mangaId) != current.api) return null
        val previous = get(chapter.id)
        if (previous != null && (previous.mangaId != chapter.mangaId || previous.chapterUrl != chapter.url ||
                previous.retiredRelativePath != null || previous.pendingRelativePath != null || previous.pendingSizeBytes != null)
        ) return null
        val original = previous?.claimOrNull()
        if (original != null) {
            if (original.downloadId != current.id || !original.owner.matches(chapter) ||
                original.operation !in setOf(ChapterArtifactOperation.DOWNLOAD, ChapterArtifactOperation.FAILED_CLEANUP)
            ) return null
        } else if (!previous.canReserve(chapter.mangaId)) return null
        val next = (previous ?: ChapterArtifactEntity(chapter.id, chapter.mangaId, chapter.url)).copy(
            token = original?.token ?: token, operation = ChapterArtifactOperation.FAILED_CLEANUP,
            retiring = true, downloadId = current.id, pendingRelativePath = null,
            pendingSizeBytes = null, ownsPendingPath = false, conversionSourceRoster = null,
        )
        if (previous == null) insert(next) else check(update(next) == 1)
        return checkNotNull(next.claimOrNull())
    }

    /** Read authority again under the file/transition gates before touching failed-attempt bytes. */
    @Transaction
    suspend fun canSettleFailedCleanup(claim: ChapterArtifactClaim): Boolean {
        val record = get(claim.owner.chapterId) ?: return false
        if (!record.isOwnedBy(claim) || !record.retiring || claim.operation != ChapterArtifactOperation.FAILED_CLEANUP) return false
        val chapter = saved(record.chapterId) ?: return false
        val row = download(chapter.id) ?: return false
        return claim.owner.matches(chapter) && row.id == claim.downloadId && row.matches(chapter) &&
            row.state == DownloadingState.FAILED && mangaApi(chapter.mangaId) == row.api
    }

    @Query("DELETE FROM chapter_downloads WHERE chapterId = :chapterId AND id = :downloadId AND state = :terminalState")
    suspend fun removeTerminalDownload(chapterId: Long, downloadId: Long, terminalState: DownloadingState): Int

    /** Files are settled first; removal and release commit together or leave BOTH row and custody. */
    @Transaction
    suspend fun finishFailedCleanup(claim: ChapterArtifactClaim): Boolean {
        if (!canSettleFailedCleanup(claim)) return false
        check(removeTerminalDownload(claim.owner.chapterId, checkNotNull(claim.downloadId), DownloadingState.FAILED) == 1)
        check(release(claim.owner.chapterId, claim.token) == 1)
        return true
    }

    /** History-only removal does not acquire file authority or disturb a readable conversion. */
    @Transaction
    suspend fun removeSuccessHistory(expected: ChapterDownloadEntity): Boolean {
        val current = download(expected.chapterId) ?: return false
        if (!current.sameAttempt(expected) || current.api != expected.api || current.state != DownloadingState.SUCCESS) return false
        return removeTerminalDownload(current.chapterId, current.id, DownloadingState.SUCCESS) == 1
    }

    /** Rechecks the original owner and ledger, not just whatever currently has chapterId. */
    @Transaction
    suspend fun canPublish(claim: ChapterArtifactClaim): Boolean {
        val record = get(claim.owner.chapterId) ?: return false
        if (record.retiring || !record.isOwnedBy(claim) || claim.operation == ChapterArtifactOperation.FAILED_CLEANUP) return false
        val chapter = saved(claim.owner.chapterId) ?: return false
        if (!claim.owner.matches(chapter) || mangaApi(chapter.mangaId) == null) return false
        val row = download(chapter.id)
        // Removing SUCCESS history is row-only and must not cancel an already admitted manual
        // conversion. A replacement row, however, never inherits the old attempt's authority.
        if ((row?.id != claim.downloadId && !(claim.operation == ChapterArtifactOperation.CONVERT && row == null)) ||
            (row != null && !row.matches(chapter))
        ) return false
        return claim.operation != ChapterArtifactOperation.DOWNLOAD || row?.isActiveArtifactDownload() == true
    }

    /** Revocation is durable; it does NOT release file custody or imply native work has stopped. */
    @Query("UPDATE chapter_artifacts SET retiring = 1 WHERE chapterId = :chapterId AND token = :token")
    suspend fun revoke(chapterId: Long, token: String): Int

    /** Only after exclusive directory creation; no bytes are copied before this receipt. */
    @Query(
        "UPDATE chapter_artifacts SET ownsPendingPath = 1 " +
            "WHERE chapterId = :chapterId AND token = :token AND retiring = 0",
    )
    suspend fun confirmPendingPathOwnership(chapterId: Long, token: String): Int

    /** Caller has drained file users and settled/cleaned this exact operation before release. */
    @Query(
        """
        UPDATE chapter_artifacts SET token = NULL, operation = NULL, retiring = 0,
            downloadId = NULL, pendingRelativePath = NULL, pendingSizeBytes = NULL, ownsPendingPath = 0,
            conversionSourceRoster = NULL
        WHERE chapterId = :chapterId AND token = :token AND retiredRelativePath IS NULL
        """,
    )
    suspend fun release(chapterId: Long, token: String): Int

    @Query(
        "UPDATE chapter_artifacts SET retiredRelativePath = NULL " +
            "WHERE chapterId = :chapterId AND token = :token AND retiredRelativePath = :path",
    )
    suspend fun releaseRetiredPath(chapterId: Long, token: String, path: String): Int

    @Query("DELETE FROM chapter_artifacts WHERE chapterId = :chapterId AND token = :token AND operation = 'delete'")
    suspend fun finishRemoval(chapterId: Long, token: String): Int

    /** Called only with the old token revoked and all of its process-local users drained. */
    @Transaction
    suspend fun reserveRemoval(owner: ChapterArtifactOwner, token: String): ChapterArtifactClaim? {
        val current = saved(owner.chapterId)
        if (current != null && !owner.matches(current)) return null
        val previous = get(owner.chapterId)
        if (previous != null && (previous.mangaId != owner.mangaId || previous.chapterUrl != owner.chapterUrl)) return null
        val next = (previous ?: ChapterArtifactEntity(owner.chapterId, owner.mangaId, owner.chapterUrl)).copy(
            token = token, operation = ChapterArtifactOperation.DELETE, retiring = false,
            downloadId = download(owner.chapterId)?.id, pendingRelativePath = null,
            pendingSizeBytes = null, ownsPendingPath = false, conversionSourceRoster = null,
        )
        if (previous == null) insert(next) else check(update(next) == 1)
        return checkNotNull(next.claimOrNull())
    }

    @Transaction
    suspend fun claimConversion(expected: SavedChapterEntity, token: String, sourceRoster: String): ChapterArtifactClaim? {
        require(ChapterConversionRoster.decode(sourceRoster).map { it.storedPath } == expected.localImagePaths)
        val current = saved(expected.id) ?: return null
        if (!sameDownloadSnapshot(current, expected) || !current.isDownloaded) return null
        val api = mangaApi(current.mangaId) ?: return null
        val row = download(current.id)
        if (row != null && (row.isActiveArtifactDownload() || !row.matches(current) || row.api != api)) return null
        // Explicit restored archives already are CBZ; conversion must never replace their reference.
        if (get(current.id)?.committedRelativePath != null) return null
        return reserve(current, token, ChapterArtifactOperation.CONVERT, row?.id, null, sourceRoster)
    }

    @Transaction
    suspend fun reserve(
        chapter: SavedChapterEntity,
        token: String,
        operation: String,
        downloadId: Long?,
        pending: ChapterArtifactFile?,
        conversionSourceRoster: String? = null,
    ): ChapterArtifactClaim? {
        require(token.isNotBlank())
        require((operation == ChapterArtifactOperation.CONVERT) == (conversionSourceRoster != null))
        val previous = get(chapter.id)
        if (!previous.canReserve(chapter.mangaId)) return null
        val next = (previous ?: ChapterArtifactEntity(chapter.id, chapter.mangaId, chapter.url)).copy(
            chapterUrl = chapter.url, token = token, operation = operation, retiring = false,
            downloadId = downloadId, pendingRelativePath = pending?.relativePath,
            pendingSizeBytes = pending?.sizeBytes, ownsPendingPath = false,
            conversionSourceRoster = conversionSourceRoster,
        )
        if (previous == null) insert(next) else check(update(next) == 1)
        return checkNotNull(next.claimOrNull())
    }
}

internal fun ChapterArtifactEntity?.canReserve(mangaId: Long): Boolean =
    this == null || (this.mangaId == mangaId && token == null && retiredRelativePath == null && conversionSourceRoster == null)

internal fun sameDownloadSnapshot(actual: SavedChapterEntity, expected: SavedChapterEntity): Boolean =
    ChapterArtifactOwner.of(expected).matches(actual) && actual.isDownloaded == expected.isDownloaded &&
        actual.localImagePaths == expected.localImagePaths

internal fun ChapterDownloadEntity.matches(chapter: SavedChapterEntity): Boolean =
    chapterId == chapter.id && mangaId == chapter.mangaId && url == chapter.url

internal fun ChapterDownloadEntity.sameAttempt(expected: ChapterDownloadEntity): Boolean =
    id == expected.id && chapterId == expected.chapterId && mangaId == expected.mangaId && url == expected.url

internal fun ChapterDownloadEntity.isActiveArtifactDownload(): Boolean =
    state == DownloadingState.QUEUED || state == DownloadingState.RUNNING ||
        state == DownloadingState.DOWNLOADED || state == DownloadingState.COMPRESSING
