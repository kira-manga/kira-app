package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.presentation.features.download.data.DownloadingState

/** Derived-state repair only. The caller must prove absence while holding the shared file pin. */
@Dao
interface ChapterArtifactRepairDao {
    @Query(
        """
        SELECT saved.id FROM saved_chapters AS saved
        LEFT JOIN chapter_downloads AS downloads ON downloads.chapterId = saved.id
        LEFT JOIN chapter_artifacts AS artifacts ON artifacts.chapterId = saved.id
        WHERE saved.isDownloaded = 1 OR downloads.state = 'SUCCESS'
            OR artifacts.committedRelativePath IS NOT NULL
        ORDER BY saved.id
        """,
    )
    suspend fun candidateIds(): List<Long>

    @Query("SELECT * FROM saved_chapters WHERE id = :chapterId")
    suspend fun saved(chapterId: Long): SavedChapterEntity?

    @Query("SELECT * FROM chapter_downloads WHERE chapterId = :chapterId")
    suspend fun download(chapterId: Long): ChapterDownloadEntity?

    @Query("SELECT * FROM chapter_artifacts WHERE chapterId = :chapterId")
    suspend fun artifact(chapterId: Long): ChapterArtifactEntity?

    @Query("SELECT id, api, url FROM saved_manga WHERE id = :mangaId")
    suspend fun parent(mangaId: Long): ArtifactRepairParent?

    @Query(
        "SELECT * FROM notifications WHERE chapterId = :chapterId AND mangaId = :mangaId " +
            "AND chapterUrl = :chapterUrl AND api = :api",
    )
    suspend fun notifications(chapterId: Long, mangaId: Long, chapterUrl: String, api: String): List<ChapterNotification>

    @Query("SELECT * FROM history_items WHERE api = :api AND mangaUrl = :mangaUrl AND chapterUrl = :chapterUrl")
    suspend fun history(api: String, mangaUrl: String, chapterUrl: String): List<HistoryItemD>

    @Update(entity = SavedChapterEntity::class)
    suspend fun writeSaved(update: ArtifactReadableUpdate): Int

    @Update(entity = ChapterNotification::class)
    suspend fun writeNotification(update: ArtifactReadableUpdate): Int

    @Update(entity = HistoryItemD::class)
    suspend fun writeHistory(update: ArtifactReadableUpdate): Int

    @Query(
        "UPDATE chapter_downloads SET state = 'FAILED', progress = 0, sizeBytes = 0, errorMsg = NULL " +
            "WHERE id = :downloadId AND chapterId = :chapterId AND state = 'SUCCESS'",
    )
    suspend fun clearSuccess(downloadId: Long, chapterId: Long): Int

    @Query("UPDATE chapter_artifacts SET committedToken = NULL, committedRelativePath = NULL WHERE chapterId = :chapterId")
    suspend fun clearCommittedReference(chapterId: Long): Int

    /** Active work and unresolved custody are never candidates, including revoked/pending work. */
    @Transaction
    suspend fun snapshot(chapterId: Long): ArtifactRepairSnapshot? {
        val chapter = saved(chapterId) ?: return null
        val owner = parent(chapter.mangaId) ?: return null
        val row = download(chapterId)
        if (row != null && (!row.matches(chapter) || row.api != owner.api ||
                (row.state != DownloadingState.SUCCESS && row.state != DownloadingState.FAILED))
        ) return null
        val record = artifact(chapterId)
        if (record != null && (record.mangaId != chapter.mangaId || record.chapterUrl != chapter.url ||
                !record.isSettledForRepair())
        ) return null
        if (!chapter.isDownloaded && chapter.localImagePaths.isEmpty() &&
            row?.state != DownloadingState.SUCCESS && record?.committedRelativePath == null
        ) return null
        return ArtifactRepairSnapshot(chapter, row, record, owner)
    }

    /** No insert/delete or file compensation: a failed return never authorizes further mutation. */
    @Transaction
    suspend fun clearMissing(expected: ArtifactRepairSnapshot): Boolean {
        val current = snapshot(expected.chapter.id) ?: return false
        if (!sameDownloadSnapshot(current.chapter, expected.chapter) || current.download != expected.download ||
            current.artifact != expected.artifact || current.parent != expected.parent
        ) return false
        val chapter = current.chapter
        check(writeSaved(ArtifactReadableUpdate(chapter.id, false, emptyList())) == 1)
        clearMirrors(current)
        current.download?.takeIf { it.state == DownloadingState.SUCCESS }?.let {
            // Existing FAILED (including explicit user cancellation) keeps its original semantics.
            check(clearSuccess(it.id, chapter.id) == 1)
        }
        current.artifact?.let { check(clearCommittedReference(chapter.id) == 1) }
        return true
    }
}

/** Called only inside the guarded generated Room transaction. */
private suspend fun ChapterArtifactRepairDao.clearMirrors(snapshot: ArtifactRepairSnapshot) {
    val chapter = snapshot.chapter
    for (mirror in notifications(chapter.id, chapter.mangaId, chapter.url, snapshot.parent.api)) {
        check(writeNotification(ArtifactReadableUpdate(mirror.id, false, emptyList())) == 1)
    }
    // Rework history legitimately has mangaId=0; source + both URLs, not that ID, own it.
    for (mirror in history(snapshot.parent.api, snapshot.parent.url, chapter.url)) {
        check(writeHistory(ArtifactReadableUpdate(mirror.id, false, emptyList())) == 1)
    }
}

/** Parent identity only; mutable library preferences must neither block nor be overwritten. */
data class ArtifactRepairParent(val id: Long, val api: String, val url: String)

/** Retained before probing; the writer rechecks every authority-bearing field in one transaction. */
data class ArtifactRepairSnapshot(
    val chapter: SavedChapterEntity,
    val download: ChapterDownloadEntity?,
    val artifact: ChapterArtifactEntity?,
    val parent: ArtifactRepairParent,
)

private fun ChapterArtifactEntity.isSettledForRepair(): Boolean =
    token == null && operation == null && !retiring && downloadId == null &&
        pendingRelativePath == null && pendingSizeBytes == null && !ownsPendingPath && retiredRelativePath == null &&
        conversionSourceRoster == null
