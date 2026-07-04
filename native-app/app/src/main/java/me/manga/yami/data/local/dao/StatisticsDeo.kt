package me.manga.yamiapk.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatisticsDeo {



    // 1. Count total saved manga
    @Query("SELECT COUNT(*) FROM saved_manga")
    fun getTotalMangaCount(): Flow<Int>

    // 2. Count total chapters across all manga
    @Query("SELECT COUNT(*) FROM saved_chapters")
    fun getTotalChaptersCount(): Flow<Int>

    // 3. Count downloaded chapters
    @Query("SELECT COUNT(*) FROM saved_chapters WHERE isDownloaded = 1")
    fun getDownloadedChaptersCount(): Flow<Int>

    // 4. Count read chapters
    @Query("SELECT COUNT(*) FROM saved_chapters WHERE isRead = 1")
    fun getReadChaptersCount(): Flow<Int>

    // 5. Count bookmarked chapters
    @Query("SELECT COUNT(*) FROM saved_chapters WHERE isBookmarked = 1")
    fun getBookmarkedChaptersCount(): Flow<Int>

    // 6. Count completed manga (all chapters read)
    @Query(
        """
        SELECT COUNT(*)
          FROM saved_manga m
         WHERE NOT EXISTS(
           SELECT 1 FROM saved_chapters c
            WHERE c.mangaId = m.id AND c.isRead = 0
         )
        """
    )
    fun getCompletedMangaCount(): Flow<Int>



    @Query(
        """
        SELECT COUNT(*)
          FROM saved_manga m
         WHERE EXISTS(
           SELECT 1 FROM saved_chapters c
            WHERE c.mangaId = m.id AND c.isRead = 1
         )
        """
    )
    fun getStartedMangaCount(): Flow<Int>

    // Alternatively, suspend versions if you need single-shot calls:
    @Query("SELECT COUNT(*) FROM saved_manga")
    suspend fun getTotalMangaCountOnce(): Int

    @Query("SELECT COUNT(*) FROM saved_chapters")
    suspend fun getTotalChaptersCountOnce(): Int

    @Query("SELECT COUNT(*) FROM saved_chapters WHERE isDownloaded = 1")
    suspend fun getDownloadedChaptersCountOnce(): Int

    @Query("SELECT COUNT(*) FROM saved_chapters WHERE isRead = 1")
    suspend fun getReadChaptersCountOnce(): Int

    @Query("SELECT COUNT(*) FROM saved_chapters WHERE isBookmarked = 1")
    suspend fun getBookmarkedChaptersCountOnce(): Int

    @Query(
        """
        SELECT COUNT(*)
          FROM saved_manga m
         WHERE NOT EXISTS(
           SELECT 1 FROM saved_chapters c
            WHERE c.mangaId = m.id AND c.isRead = 0
         )
        """
    )
    suspend fun getCompletedMangaCountOnce(): Int

    @Query(
        """
        SELECT COUNT(*)
          FROM saved_manga m
         WHERE EXISTS(
           SELECT 1 FROM saved_chapters c
            WHERE c.mangaId = m.id AND c.isRead = 1
         )
        """
    )
    suspend fun getStartedMangaCountOnce(): Int
}