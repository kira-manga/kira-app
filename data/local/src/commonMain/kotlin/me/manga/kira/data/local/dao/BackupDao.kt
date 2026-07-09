package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity

/**
 * Per-chapter outcome of a merge-import, keyed by chapter url in [ImportedMangaResult].
 *
 * [localLastReadDateBefore] is the local row's lastReadDate BEFORE the merge (0 when the chapter
 * was newly inserted) — the caller needs the pre-merge value to decide resume-page restoration,
 * and it is unrecoverable after the merged row has been written.
 */
data class ImportedChapterResult(
    val chapterId: Long,
    val wasNew: Boolean,
    val localLastReadDateBefore: Long,
)

/** Outcome of [BackupDao.importMangaMerging]. [mangaId] is -1 when the manga could not be resolved. */
data class ImportedMangaResult(
    val mangaId: Long,
    val mangaWasNew: Boolean,
    val chaptersByUrl: Map<String, ImportedChapterResult>,
    val chaptersAdded: Int,
    val chaptersMerged: Int,
)

/**
 * Backup/restore persistence primitives.
 *
 * Import deliberately does NOT reuse [LibraryDeo.saveMangaWithChapters]: that path is
 * IGNORE-insert-only and never updates existing rows, so it cannot express a merge (flags on
 * already-present mangas/chapters would silently keep their local values even when the backup
 * carries more-advanced state). The merge decisions themselves are injected as lambdas so this
 * module stays policy-free (policy lives in :data's BackupMergePolicy, where it is unit-testable).
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

    @Query("SELECT * FROM saved_manga WHERE api = :api AND title = :title LIMIT 1")
    suspend fun getMangaByApiAndTitle(
        api: String,
        title: String,
    ): SavedMangaEntity?

    // Chapter lookups MUST be mangaId-scoped: ChapterDao.getChapterIdByUrl is url-only LIMIT 1,
    // and a chapter url reused under another manga would mismap the merge.
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
    suspend fun updateMangaRow(manga: SavedMangaEntity)

    @Update
    suspend fun updateChapterRow(chapter: SavedChapterEntity)

    @Query("SELECT * FROM history_items WHERE mangaUrl = :mangaUrl LIMIT 1")
    suspend fun getHistoryByMangaUrl(mangaUrl: String): HistoryItemD?

    @Insert
    suspend fun insertHistoryRow(item: HistoryItemD)

    @Update
    suspend fun updateHistoryRow(item: HistoryItemD)

    /**
     * Merge-imports one manga and its chapters atomically.
     *
     * Manga resolution: url first (UNIQUE index), then (api, title) — the identity pair the rest
     * of the app keys on. Absent -> insert; on an IGNORE conflict (-1) re-lookup by url (same
     * tolerance as saveMangaWithChapters). Present -> update with [mergeManga].
     *
     * Chapters are UPSERTED: every incoming chapter absent locally is INSERTED (the backup is a
     * valid source of chapter data — a backup with chapters 1-100 imported over a local 1-95 must
     * end at 1-100), and every locally-present chapter is updated with [mergeChapter]. Incoming
     * chapter rows must carry id = 0; mangaId is overwritten with the resolved id here.
     *
     * Nothing is ever deleted; re-running the same import converges to the same state.
     */
    @Transaction
    suspend fun importMangaMerging(
        incoming: SavedMangaEntity,
        incomingChapters: List<SavedChapterEntity>,
        mergeManga: (local: SavedMangaEntity, incoming: SavedMangaEntity) -> SavedMangaEntity,
        mergeChapter: (local: SavedChapterEntity, incoming: SavedChapterEntity) -> SavedChapterEntity,
    ): ImportedMangaResult {
        val local = getMangaByUrl(incoming.url) ?: getMangaByApiAndTitle(incoming.api, incoming.title)
        val mangaWasNew: Boolean
        val mangaId: Long
        if (local == null) {
            val inserted = insertMangaRow(incoming)
            val resolved = if (inserted != -1L) inserted else getMangaByUrl(incoming.url)?.id
            if (resolved == null) {
                return ImportedMangaResult(
                    mangaId = -1L,
                    mangaWasNew = false,
                    chaptersByUrl = emptyMap(),
                    chaptersAdded = 0,
                    chaptersMerged = 0,
                )
            }
            mangaId = resolved
            mangaWasNew = inserted != -1L
        } else {
            updateMangaRow(mergeManga(local, incoming))
            mangaId = local.id
            mangaWasNew = false
        }

        var added = 0
        var merged = 0
        val chaptersByUrl = LinkedHashMap<String, ImportedChapterResult>()
        for (chapter in incomingChapters) {
            val localChapter = getChapterByMangaAndUrl(mangaId, chapter.url)
            if (localChapter == null) {
                val inserted = insertChapterRow(chapter.copy(id = 0, mangaId = mangaId))
                val chapterId = if (inserted != -1L) inserted else getChapterByMangaAndUrl(mangaId, chapter.url)?.id
                if (chapterId != null) {
                    chaptersByUrl[chapter.url] =
                        ImportedChapterResult(
                            chapterId = chapterId,
                            wasNew = true,
                            localLastReadDateBefore = 0,
                        )
                    added++
                }
            } else {
                updateChapterRow(mergeChapter(localChapter, chapter))
                chaptersByUrl[chapter.url] =
                    ImportedChapterResult(
                        chapterId = localChapter.id,
                        wasNew = false,
                        localLastReadDateBefore = localChapter.lastReadDate,
                    )
                merged++
            }
        }
        return ImportedMangaResult(
            mangaId = mangaId,
            mangaWasNew = mangaWasNew,
            chaptersByUrl = chaptersByUrl,
            chaptersAdded = added,
            chaptersMerged = merged,
        )
    }

    /**
     * Merge-imports one history row, keyed by mangaUrl (the HistoryDao upsert identity — one row
     * per manga). Absent -> insert as-is; present and [shouldReplace] -> the chapter-position
     * fields are replaced while the local row identity (id/mangaId/api/…) is kept.
     */
    @Transaction
    suspend fun importHistoryMerging(
        incoming: HistoryItemD,
        shouldReplace: (local: HistoryItemD, incoming: HistoryItemD) -> Boolean,
    ) {
        val local = getHistoryByMangaUrl(incoming.mangaUrl)
        if (local == null) {
            insertHistoryRow(incoming)
        } else if (shouldReplace(local, incoming)) {
            updateHistoryRow(
                local.copy(
                    chapterUrl = incoming.chapterUrl,
                    chapterTitle = incoming.chapterTitle,
                    lastReadDate = incoming.lastReadDate,
                    lastReadPage = incoming.lastReadPage,
                    totalPages = incoming.totalPages,
                ),
            )
        }
    }
}
