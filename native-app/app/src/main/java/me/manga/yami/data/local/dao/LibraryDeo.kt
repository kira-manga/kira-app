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
import me.manga.yamiapk.presentation.features.home.data.ApiTitle

@Dao
interface LibraryDeo {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertManga(manga: SavedMangaEntity): Long

    @Query("SELECT id FROM saved_manga WHERE url = :url LIMIT 1")
    suspend fun getMangaIdByUrl(url: String): Long?
    @Query("SELECT url FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun getSavedChapterUrls(mangaId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChapters(chapters: List<SavedChapterEntity>)
    //   Flow-based query to observe saved manga titles
    @Query("SELECT title FROM saved_manga")
    fun getSavedMangaTitlesFlow(): Flow<List<String>>

    @Query("SELECT api, title FROM saved_manga")
    fun getSavedMangaApiTitleFlow(): Flow<List<ApiTitle>>


    @Transaction
    suspend fun saveMangaWithChapters(
        manga: SavedMangaEntity,
        chapters: List<SavedChapterEntity>
    ) {
        // 1) upsert your manga (as you already have)
        val inserted = insertManga(manga)
        val mangaId = if (inserted != -1L) inserted
        else getMangaIdByUrl(manga.url)
            ?: return

        // 2) fetch existing chapter URLs
        val existingUrls = getSavedChapterUrls(mangaId).toSet()

        // 3) filter our new batch

        val newChapters = chapters
            .map { it.copy(mangaId = mangaId) }
            .filter { it.url !in existingUrls }


        // 4) only insert the truly new ones
        insertChapters(newChapters)
    }

    @Query("SELECT id FROM saved_manga WHERE title = :title LIMIT 1")
    suspend fun getMangaIdByTitle(title: String): Long?

    // -- Deletion support --
    @Query("DELETE FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun deleteChaptersByMangaId(mangaId: Long)

    @Query("DELETE FROM saved_manga WHERE url = :url")
    suspend fun deleteMangaByUrl(url: String): Int
    @Query("DELETE FROM saved_manga WHERE title = :title")
    suspend fun deleteMangaByTitle(title: String): Int

    @Query("DELETE FROM saved_manga WHERE id = :id")
    suspend fun deleteMangaById(id: Long): Int
    @Transaction
    suspend fun deleteMangaWithChapters(title: String) {
        val mangaId = getMangaIdByTitle(title) ?: return
        deleteChaptersByMangaId(mangaId)
        deleteMangaByTitle(title)

    }



    @Transaction
    suspend fun removeMangaWithChapters(id: Long) {
        removeAllChaptersForManga(id)
        removeAllNotification(id)
        removeHistory(id)
        deleteMangaById(id)
    }

    @Query("DELETE FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun removeAllChaptersForManga(mangaId: Long)

    @Query("DELETE FROM notifications WHERE mangaId = :mangaId")
    suspend fun removeAllNotification(mangaId: Long)
    @Query("DELETE FROM history_items WHERE mangaId = :mangaId")
    suspend fun removeHistory(mangaId: Long)
    @Delete
    suspend fun removeManga(manga: SavedMangaEntity)




    @Transaction
    suspend fun markChapterAndNotificationRead(chapterId: Long) {
        markChapterAsReadInternal(chapterId)
        markNotificationReadInternal(chapterId)
    }

    @Query("UPDATE saved_chapters SET isRead = NOT isRead WHERE id = :chapterId")
    suspend fun markChapterAsReadInternal(chapterId: Long)

    @Query("UPDATE notifications SET isRead = NOT isRead WHERE chapterId = :chapterId")
    suspend fun markNotificationReadInternal(chapterId: Long)

//    @Transaction
//    suspend fun deleteMangaWithChaptersById(mangaId: Long) {
//        deleteChaptersByMangaId(mangaId)
//        deleteMangaById(mangaId)
//    }
}