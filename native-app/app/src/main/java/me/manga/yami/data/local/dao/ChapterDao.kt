package me.manga.yamiapk.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.data.local.entity.SavedChapterEntity

@Dao
interface ChapterDao {

    @Query("SELECT * FROM saved_chapters WHERE isDownloaded = 1")
    suspend fun getAllDownloadedChapters(): List<SavedChapterEntity>

    @Query("SELECT isDownloaded FROM saved_chapters WHERE url = :url LIMIT 1")
    fun isChapterDownloadedFlow(url: String): Flow<Boolean>

    @Query("SELECT * FROM saved_chapters WHERE mangaId = :mangaId ORDER BY id ASC")
    fun getChaptersByMangaId(mangaId: Long): Flow<List<SavedChapterEntity>>


    @Transaction
    suspend fun insertChaptersSafely(chapters: List<SavedChapterEntity>): List<Long> {
        return insertChapters(chapters)
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChapters(chapters: List<SavedChapterEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chapters: List<SavedChapterEntity>)




    @Query("UPDATE saved_chapters SET lastReadDate = :timestamp WHERE id = :chapterId")
    suspend fun updateChapterLastReadDate(chapterId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE saved_chapters SET localImagePaths = :paths WHERE id = :chapterId")
    suspend fun updateChapterLocalPaths(chapterId: Long, paths: List<String>)

    @Query("UPDATE saved_chapters SET localImagePaths = :paths WHERE url = :url")
    suspend fun updateChapterLocalPathsByUrl(url: String, paths: List<String>): Int

    @Query("SELECT id FROM saved_chapters WHERE url = :url LIMIT 1")
    suspend fun getChapterIdByUrl(url: String): Long?

    @Query("UPDATE saved_chapters SET isDownloaded = 1 WHERE id = :chapterId")
    suspend fun markChapterDownloaded(chapterId: Long)

    @Query("UPDATE saved_chapters SET isBookmarked = NOT isBookmarked WHERE id = :chapterId")
    suspend fun toggleChapterBookmark(chapterId: Long)

    @Query("UPDATE saved_chapters SET isRead = NOT isRead WHERE id = :chapterId")
    suspend fun toggleChapterRead(chapterId: Long)

//    @Query(
//        "UPDATE saved_chapters SET isBookmarked = NOT isBookmarked WHERE id IN (:chapterIds)"
//    )
//    suspend fun toggleChaptersBookmark(chapterIds: List<Long>)

//    @Query(
//        "UPDATE saved_chapters SET isRead = NOT isRead WHERE id IN (:chapterIds)"
//    )
//    suspend fun toggleChaptersRead(chapterIds: List<Long>)

    @Query("UPDATE saved_chapters SET isRead = 1, lastReadDate = :currentTime WHERE id = :chapterId")
    suspend fun markChapterAsRead(chapterId: Long, currentTime: Long = System.currentTimeMillis())

    // New: mark as read
    @Query("UPDATE saved_chapters SET isNew = 0 WHERE id = :chapterId")
    suspend fun markChapterIsNew(chapterId: Long)
    // New: mark as read
//    @Query("UPDATE saved_chapters SET isRead = 1 WHERE id IN (:chapterIds)")
//    suspend fun markChaptersRead(chapterIds: List<Long>)



    @Query("SELECT * FROM saved_chapters WHERE id = :chapterId LIMIT 1")
    fun getChapterById(chapterId: Long): Flow<SavedChapterEntity?>

    @Query("SELECT * FROM saved_chapters WHERE id = :chapterId LIMIT 1")
    suspend fun getChapterByIdSuspend(chapterId: Long): SavedChapterEntity?






    @Query("DELETE FROM saved_chapters WHERE id IN (:ids)")
    suspend fun deleteChaptersByIds(ids: List<Long>)




    @Query("""
      UPDATE saved_chapters 
        SET isDownloaded = 0, 
            localImagePaths = :emptyList 
        WHERE id IN (:ids)
    """)
    suspend fun markChaptersNotDownloaded(ids: List<Long>, emptyList: List<String> = emptyList())






    @Query("DELETE FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun removeAllChaptersForManga(mangaId: Long)


    @Query("SELECT * FROM saved_chapters WHERE isBookmarked = 1 ORDER BY id ASC")
    fun getBookmarkedChapters(): Flow<List<SavedChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: SavedChapterEntity): Long

    @Query("DELETE FROM saved_chapters WHERE id = :chapterId")
    suspend fun deleteChapterById(chapterId: Long)


    @Query("SELECT * FROM saved_chapters WHERE id IN (:ids)")
    suspend fun getChaptersByIds(ids: List<Long>): List<SavedChapterEntity>



    // update url
    @Query("SELECT * FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun getChaptersByMangaIdR(mangaId: Long): List<SavedChapterEntity>

    @Update
    suspend fun update(chapter: SavedChapterEntity)
    suspend fun markChaptersRead(chapterIds: List<Long>) {
        chapterIds.chunked(500).forEach { batch ->
            markChaptersReadBatch(batch)
        }
    }

    @Transaction
    suspend fun toggleChaptersRead(chapterIds: List<Long>) {
        chapterIds.chunked(500).forEach { batch ->
            toggleChaptersReadBatch(batch)
        }
    }

    @Transaction
    suspend fun toggleChaptersBookmark(chapterIds: List<Long>) {
        chapterIds.chunked(500).forEach { batch ->
            toggleChaptersBookmarkBatch(batch)
        }
    }


    @Query("UPDATE saved_chapters SET isRead = 1 WHERE id IN (:chapterIds)")
    suspend fun markChaptersReadBatch(chapterIds: List<Long>)

    @Query("UPDATE saved_chapters SET isRead = NOT isRead WHERE id IN (:chapterIds)")
    suspend fun toggleChaptersReadBatch(chapterIds: List<Long>)

    @Query("UPDATE saved_chapters SET isBookmarked = NOT isBookmarked WHERE id IN (:chapterIds)")
    suspend fun toggleChaptersBookmarkBatch(chapterIds: List<Long>)




}