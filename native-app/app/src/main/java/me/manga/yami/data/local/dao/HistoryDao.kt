package me.manga.yamiapk.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.data.local.entity.HistoryItemD
import java.time.LocalDateTime

@Dao
interface HistoryDao {


    @Query("SELECT * FROM history_items ORDER BY lastReadDate DESC")
    fun getAllHistory(): Flow<List<HistoryItemD>>

    @Query("UPDATE history_items SET mangaImageUrl = :newImageUrl WHERE mangaId = :mangaId")
    suspend fun updateMangaImageUrl(mangaId: Long, newImageUrl: String)

    @Query("SELECT * FROM history_items WHERE mangaUrl = :mangaUrl ORDER BY lastReadDate DESC")
    fun getHistoryByManga(mangaUrl: String): Flow<List<HistoryItemD>>

    @Query("SELECT * FROM history_items WHERE chapterUrl = :chapterUrl")
    suspend fun getHistoryByChapter(chapterUrl: String): HistoryItemD?

    @Query("SELECT * FROM history_items WHERE mangaUrl = :mangaUrl LIMIT 1")
    suspend fun getHistoryItemByMangaUrl(mangaUrl: String): HistoryItemD?

    @Query("""
    SELECT id
      FROM history_items
     WHERE mangaUrl = :mangaUrl
  ORDER BY lastReadDate DESC
     LIMIT 1
  """)
    fun getLatestHistoryIdByManga(mangaUrl: String): Flow<Long?>

    @Transaction
    suspend fun insertOrUpdateHistory(historyItemD: HistoryItemD) {
        val existingItem = getHistoryItemByMangaUrl(historyItemD.mangaUrl)

        if (existingItem != null) {

            // Update existing entry with new chapter info and date
            val updatedItem = existingItem.copy(
                chapterUrl = historyItemD.chapterUrl,
                chapterTitle = historyItemD.chapterTitle,
                lastReadDate = historyItemD.lastReadDate,
                lastReadPage = historyItemD.lastReadPage,
                totalPages = historyItemD.totalPages
            )
            updateHistory(updatedItem)
        } else {
            insertHistory(historyItemD)
        }
    }

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertHistory(historyItemD: HistoryItemD)

    @Update
    suspend fun updateHistory(historyItemD: HistoryItemD)

    @Delete
    suspend fun deleteHistory(historyItemD: HistoryItemD)

    @Query("DELETE FROM history_items WHERE mangaUrl = :mangaUrl")
    suspend fun deleteHistoryByManga(mangaUrl: String)

    @Query("DELETE FROM history_items")
    suspend fun deleteAllHistory()



    @Query("""
        UPDATE history_items
           SET chapterUrl       = :chapterUrl,
               chapterTitle     = :chapterTitle,
               isDownloaded     = :isDownloaded,
               localImagePaths  = :localImagePaths,
               lastReadDate     = :lastReadDate,
               lastReadPage     = :lastReadPage,
               totalPages       = :totalPages
         WHERE id = :id
    """)
    suspend fun updateHistoryItem(
        id: Long,
        chapterUrl: String,
        chapterTitle: String,
        isDownloaded: Boolean,
        localImagePaths: List<String> = listOf(),
        lastReadDate: LocalDateTime,
        lastReadPage: Int,
        totalPages: Int
    )

    // update url

    @Query("SELECT * FROM history_items WHERE api = :apiName")
    suspend fun getHistoryByApi(apiName: String): List<HistoryItemD>

    @Update
    suspend fun update(historyItem: HistoryItemD)
}