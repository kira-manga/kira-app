package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.presentation.features.download.data.DownloadingState

/** Checked engagement-only update; backup cannot rewrite the retained owner's identity/metadata. */
data class BackupMangaUpdate(
    val id: Long,
    val isLiked: Boolean,
    val isWatchingNow: Boolean,
    val lastOpenTimestamp: Long,
    val savedTimestamp: Long,
)

/** Checked reading-only update; download columns and chapter metadata remain local. */
data class BackupChapterUpdate(
    val id: Long,
    val isRead: Boolean,
    val isBookmarked: Boolean,
    val lastReadDate: Long,
)

/** Partial-entity payload for [BackupDao.markChapterRestored]. */
data class RestoredChapterUpdate(
    val id: Long,
    val isDownloaded: Boolean,
    val localImagePaths: List<String>,
)

/**
 * Policy-free backup primitives. :data resolves complete source-scoped families in its real Room
 * writer, checks saved/history merge results, and owns saved/history/native/receipt atomicity.
 * No title fallback, IGNORE relookup, or URL-only history winner is selected by this DAO.
 */
@Dao
interface BackupDao {
    // --- Export reads (one-shot; the Flow variants elsewhere are for observation) ---

    @Query("SELECT * FROM saved_manga ORDER BY id ASC")
    suspend fun getAllSavedManga(): List<SavedMangaEntity>

    @Query("SELECT * FROM saved_chapters WHERE mangaId = :mangaId ORDER BY id ASC")
    suspend fun getChaptersForManga(mangaId: Long): List<SavedChapterEntity>

    @Query("SELECT * FROM history_items")
    suspend fun getAllHistoryOnce(): List<HistoryItemD>

    // --- Import primitives ---

    @Query("SELECT * FROM saved_manga WHERE url = :url LIMIT 1")
    suspend fun getMangaByUrl(url: String): SavedMangaEntity?

    // Primitive lookup is receiver-parent-ID scoped; owner/alias choice belongs to :data's writer.
    @Query("SELECT * FROM saved_chapters WHERE mangaId = :mangaId AND url = :url LIMIT 1")
    suspend fun getChapterByMangaAndUrl(
        mangaId: Long,
        url: String,
    ): SavedChapterEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMangaRow(manga: SavedMangaEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChapterRow(chapter: SavedChapterEntity): Long

    @Update
    suspend fun updateMangaRow(manga: SavedMangaEntity): Int

    @Update
    suspend fun updateChapterRow(chapter: SavedChapterEntity): Int

    @Update(entity = SavedMangaEntity::class)
    suspend fun updateMangaEngagement(update: BackupMangaUpdate): Int

    @Update(entity = SavedChapterEntity::class)
    suspend fun updateChapterReading(update: BackupChapterUpdate): Int

    /**
     * Flips a restored chapter's reader-facing download columns in ONE statement (the reader
     * must never observe isDownloaded=1 with stale paths). Partial-entity update rather than a
     * raw `@Query` because `localImagePaths` needs the [SavedChapterEntity] type converter — a
     * collection-typed `@Query` arg would be expanded as an IN-clause vararg instead. Callers
     * must have the CBZ fully in place (write-to-.part + atomic rename) BEFORE calling this.
     */
    @Update(entity = SavedChapterEntity::class)
    suspend fun markChapterRestored(update: RestoredChapterUpdate)

    @Query("SELECT * FROM chapter_downloads WHERE chapterId = :chapterId LIMIT 1")
    suspend fun getDownloadRowByChapter(chapterId: Long): ChapterDownloadEntity?

    // REPLACE rides the unique chapterId index — a stale FAILED/SUCCESS row is superseded in place.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownloadRow(row: ChapterDownloadEntity): Long

    /**
     * [markChapterRestored] + the `chapter_downloads` SUCCESS row, atomically. The download engines
     * finish a chapter by writing a SUCCESS queue row carrying `sizeBytes` — the Details/Downloads
     * screens read the per-chapter size and the per-manga total from those rows, so a restored
     * chapter without one shows no size at all. Inside the transaction the row is re-checked: when
     * the engine currently owns the chapter (state in [activeStates]) nothing is written and the
     * caller must not count the chapter as restored. A stale terminal row keeps its Room id so the
     * Downloads-screen ordering (id DESC) doesn't jump.
     */
    @Transaction
    suspend fun markChapterRestoredWithDownloadRow(
        update: RestoredChapterUpdate,
        downloadRow: ChapterDownloadEntity,
        activeStates: Set<DownloadingState>,
    ): Boolean {
        val existing = getDownloadRowByChapter(downloadRow.chapterId)
        if (existing != null && existing.state in activeStates) return false
        markChapterRestored(update)
        upsertDownloadRow(downloadRow.copy(id = existing?.id ?: 0))
        return true
    }

    @Insert
    suspend fun insertHistoryRow(item: HistoryItemD): Long

    @Query(
        """
        UPDATE history_items SET chapterUrl = :chapterUrl, chapterTitle = :chapterTitle,
            lastReadDate = :lastReadDate, lastReadPage = :lastReadPage, totalPages = :totalPages
        WHERE id = :id
        """,
    )
    suspend fun updateHistoryPosition(
        id: Long,
        chapterUrl: String,
        chapterTitle: String,
        lastReadDate: LocalDateTime,
        lastReadPage: Int,
        totalPages: Int,
    ): Int
}
