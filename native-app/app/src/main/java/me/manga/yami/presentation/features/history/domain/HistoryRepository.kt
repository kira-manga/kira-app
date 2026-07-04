package me.manga.yamiapk.presentation.features.history.domain

import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.data.local.dao.HistoryDao
import me.manga.yamiapk.data.local.entity.HistoryItemD
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(
    private val historyDao: HistoryDao
) {
    fun getAllHistory(): Flow<List<HistoryItemD>> = historyDao.getAllHistory()

    fun getHistoryByManga(mangaUrl: String): Flow<List<HistoryItemD>> =
        historyDao.getHistoryByManga(mangaUrl)

    fun getHistoryByMangaUrl(mangaUrl: String): Flow<Long?> =
        historyDao.getLatestHistoryIdByManga(mangaUrl)

    suspend fun getHistoryByChapter(chapterUrl: String): HistoryItemD? =
        historyDao.getHistoryByChapter(chapterUrl)

    suspend fun insertHistory(historyItemD: HistoryItemD) =
        historyDao.insertOrUpdateHistory(historyItemD)

    suspend fun updateHistory(historyItemD: HistoryItemD) =
        historyDao.updateHistory(historyItemD)

    suspend fun deleteHistory(historyItemD: HistoryItemD) =
        historyDao.deleteHistory(historyItemD)

    suspend fun deleteHistoryByManga(mangaUrl: String) =
        historyDao.deleteHistoryByManga(mangaUrl)

    suspend fun deleteAllHistory() = historyDao.deleteAllHistory()

    suspend fun updateHistoryItem(
        id: Long,
        chapterUrl: String,
        chapterTitle: String,
        isDownloaded: Boolean,
        localImagePaths: List<String>,
        lastReadDate: LocalDateTime = LocalDateTime.now(),
        lastReadPage: Int = 0,
        totalPages: Int = 0
    ) {
        historyDao.updateHistoryItem(
            id,
            chapterUrl,
            chapterTitle,
            isDownloaded,
            localImagePaths,
            lastReadDate,
            lastReadPage,
            totalPages
        )
    }

}