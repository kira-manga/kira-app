package me.manga.yamiapk.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.presentation.features.library.data.SavedMangaWithMetrics

@Dao
interface MangaDao {

    @Query(
        """
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
    """
    )
    fun getSavedMangaWithMetricsFlow(): Flow<List<SavedMangaWithMetrics>>
    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateManga(manga: SavedMangaEntity): Int
    @Query("SELECT * FROM saved_manga ORDER BY title ASC")
    fun getAllSavedMangaFlow(): Flow<List<SavedMangaEntity>>

    @Query("SELECT api FROM saved_manga WHERE id = :mangaId LIMIT 1")
    suspend fun getApiByMangaId(mangaId: Long): String?

    @Query("UPDATE saved_manga SET lastOpenTimestamp = :timestamp WHERE id = :mangaId")
    suspend fun updateLastOpenTimestamp(mangaId: Long, timestamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveManga(manga: SavedMangaEntity)

    @Query("""
      SELECT id 
      FROM saved_manga 
      WHERE api   = :api 
        AND title = :title
      LIMIT 1
    """)
    suspend fun getIdByApiAndTitle(api: String, title: String): Long?

    @Query("""
      SELECT id 
      FROM saved_manga 
      WHERE url   = :url
      LIMIT 1
    """)
    suspend fun getIdByUrl(url : String): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM saved_manga WHERE id = :mangaId)")
    suspend fun isMangaSaved(mangaId: Long): Boolean

    @Query("SELECT * FROM saved_manga WHERE id = :mangaId LIMIT 1")
    suspend fun getMangaById(mangaId: Long): SavedMangaEntity?

    @Query(
        "SELECT * FROM saved_manga " +
                "WHERE title LIKE '%' || :query || '%' " +
                "ORDER BY title ASC"
    )
    fun searchMangaByTitle(query: String): Flow<List<SavedMangaEntity>>

    @Query("SELECT api FROM saved_manga WHERE id = :mangaLocalId LIMIT 1")
    suspend fun getApiByLocalId(mangaLocalId: Long): String?





    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveChapters(chapters: List<SavedChapterEntity>)










    @Query("DELETE FROM saved_manga WHERE id = :mangaId")
    suspend fun removeMangaById(mangaId: Long)



    @Query("SELECT EXISTS(SELECT 1 FROM saved_manga WHERE id = :mangaId)")
    fun isMangaSavedFlow(mangaId: Long): Flow<Boolean>





    // update url

    @Query("SELECT * FROM saved_manga WHERE api = :apiName")
    suspend fun getMangaByApi(apiName: String): List<SavedMangaEntity>

    @Query("SELECT id FROM saved_manga WHERE api = :apiName")
    suspend fun getMangaIdsByApi(apiName: String): List<Long>

    @Update
    suspend fun update(manga: SavedMangaEntity)
}