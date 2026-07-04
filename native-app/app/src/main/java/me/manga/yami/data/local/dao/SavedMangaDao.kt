package me.manga.yamiapk.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.presentation.features.library.data.SavedMangaWithMetrics


@Dao
interface SavedMangaDao {

    @Query("""
        SELECT 
          m.*, 
          COUNT(c.id) AS totalChapters,
          SUM(CASE WHEN c.isRead = 1 THEN 1 ELSE 0 END) AS readCount,
          SUM(CASE WHEN c.isDownloaded = 1 THEN 1 ELSE 0 END) AS downloadedCount,
          SUM(CASE WHEN c.isBookmarked = 1 THEN 1 ELSE 0 END) AS bookmarkedCount,
          MAX(c.lastReadDate) AS lastReadTs
        FROM saved_manga AS m
        LEFT JOIN saved_chapters AS c
          ON c.mangaId = m.id
        GROUP BY m.id
    """)
    fun getSavedMangaWithMetricsFlow(): Flow<List<SavedMangaWithMetrics>>

    @Query("SELECT * FROM saved_manga")
    fun getAllSavedManga(): Flow<List<SavedMangaEntity>>
    @Query("SELECT api FROM saved_manga WHERE id = :mangaId LIMIT 1")
    suspend fun getApiByMangaId(mangaId: Long): String?
    // 1. Is this manga already saved?
    @Query("SELECT EXISTS(SELECT 1 FROM saved_manga WHERE id = :mangaId)")
    fun isMangaSavedFlow(mangaId: String): Flow<Boolean>

    // 2. Is this chapter downloaded?
    @Query("SELECT isDownloaded FROM saved_chapters WHERE url = :url")
    fun isChapterDownloadedFlow(url: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveManga(manga: SavedMangaEntity)

    @Delete
    suspend fun removeManga(manga: SavedMangaEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM saved_manga WHERE id = :mangaId)")
    suspend fun isMangaSaved(mangaId: String): Boolean

    @Query("SELECT * FROM saved_manga WHERE id = :mangaId")
    suspend fun getMangaById(mangaId: String): SavedMangaEntity?

    @Query("SELECT * FROM saved_chapters WHERE mangaId = :mangaId ORDER BY id ASC")
    fun getChaptersByMangaId(mangaId: String): Flow<List<SavedChapterEntity>>

    @Query("UPDATE saved_chapters SET lastReadDate = :currentTime WHERE id = :chapterId")
    suspend fun updateChapterLastReadDate(chapterId: String, currentTime: Long = System.currentTimeMillis())

    @Query("SELECT * FROM saved_manga WHERE title = :title")
    suspend fun getMangaByTitle(title: String): SavedMangaEntity?

    @Query("SELECT * FROM saved_chapters WHERE mangaId = :mangaId ORDER BY number ASC")
    fun getChaptersForManga(mangaId: String): Flow<List<SavedChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveChapters(chapters: List<SavedChapterEntity>)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: SavedChapterEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<SavedChapterEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chapters: List<SavedChapterEntity>)

    @Delete
    suspend fun removeChapters(chapters: List<SavedChapterEntity>)

    @Query("DELETE FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun removeAllChaptersForManga(mangaId: String)

    @Query("UPDATE saved_chapters SET localImagePaths = :paths WHERE id = :chapterId")
    suspend fun updateChapterLocalPaths(chapterId: Long, paths: List<String>)
    /** Returns the PK of the chapter matching this URL, or null if none found */
    @Query("SELECT id FROM saved_chapters WHERE url = :url LIMIT 1")
    suspend fun getChapterIdByUrl(url: String): Long?
    // — new URL-based updater —
    @Query("UPDATE saved_chapters SET localImagePaths = :paths WHERE url = :url")
    suspend fun updateChapterLocalPathsByUrl(url: String, paths: List<String>): Int


    @Query("UPDATE saved_chapters SET isDownloaded = 1 WHERE id = :chapterId")
    suspend fun markChapterDownloaded(chapterId: Long)

    @Query("UPDATE saved_chapters SET isBookmarked = NOT isBookmarked WHERE id = :chapterId")
    suspend fun toggleChapterBookmark(chapterId: Long)
    @Query("UPDATE saved_chapters SET isRead = NOT isRead WHERE id = :chapterId")
    suspend fun toggleChapterRead(chapterId: Long)

    @Delete
    suspend fun deleteChapter(chapter: SavedChapterEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM saved_manga WHERE id = :mangaId)")
    suspend fun isMangaExists(mangaId: Long): Boolean

    // In your @Dao
    @Query("""
    UPDATE saved_chapters 
      SET isBookmarked = NOT isBookmarked 
    WHERE id IN (:chapterIds)
""")
    suspend fun toggleChaptersBookmark(chapterIds: List<Long>)

    @Query("""
    UPDATE saved_chapters 
      SET isRead = NOT isRead 
    WHERE id IN (:chapterIds)
""")
    suspend fun toggleChaptersRead(chapterIds: List<Long>)



    @Query("SELECT * FROM saved_chapters WHERE id IN (:ids)")
    suspend fun getChaptersByIds(ids: List<Long>): List<SavedChapterEntity>

    @Query(
        "SELECT * FROM saved_manga " +
                "WHERE title LIKE '%' || :query || '%' " +
                "ORDER BY title ASC"
    )
    fun searchMangaByTitle(query: String): Flow<List<SavedMangaEntity>>

    @Query("SELECT * FROM saved_chapters WHERE isBookmarked = 1 ORDER BY number ASC")
    fun getBookmarkedChapters(): Flow<List<SavedChapterEntity>>

    @Query("UPDATE saved_chapters SET isRead = 1, lastReadDate = :currentTime WHERE id = :chapterId")
    suspend fun markChapterAsRead(chapterId: Long, currentTime: Long = System.currentTimeMillis())

    @Transaction
    suspend fun saveMangaWithChapters(manga: SavedMangaEntity, chapters: List<SavedChapterEntity>) {
        saveManga(manga)
        removeAllChaptersForManga(manga.title)
        saveChapters(chapters)
    }

    @Transaction
    suspend fun removeMangaWithChapters(manga: SavedMangaEntity) {
        removeAllChaptersForManga(manga.title)
        removeManga(manga)
    }


    @Query("SELECT api FROM saved_manga WHERE id = :mangaLocalId LIMIT 1")
    suspend fun getApiByLocalId(mangaLocalId: Long): String?

    // New: observe a single chapter's bookmarked state
    @Query("SELECT * FROM saved_chapters WHERE id = :chapterId")
    fun getChapterById(chapterId: Long): Flow<SavedChapterEntity?>



    // x
    @Query("SELECT * FROM saved_chapters WHERE id = :id")
    suspend fun getByIdSuspend(id: Long): SavedChapterEntity?
}