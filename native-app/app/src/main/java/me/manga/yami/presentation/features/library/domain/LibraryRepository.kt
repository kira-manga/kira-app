package me.manga.yamiapk.presentation.features.library.domain

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.manga.yamiapk.data.local.dao.ChapterDao
import me.manga.yamiapk.data.local.dao.HistoryDao
import me.manga.yamiapk.data.local.dao.LibraryDeo
import me.manga.yamiapk.data.local.dao.MangaDao
import me.manga.yamiapk.data.local.dao.NotificationDao
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.domain.model.MangaDisplayItem
import me.manga.yamiapk.domain.service.FileService
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import me.manga.yamiapk.presentation.features.library.data.SavedMangaWithMetrics
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val libraryDeo: LibraryDeo,
    private val notificationDao: NotificationDao,
    private val historyDao: HistoryDao,
    private val fileService: FileService
) {


    suspend fun updateManga(manga: SavedMangaEntity) = withContext(Dispatchers.IO) {
        mangaDao.updateManga(manga)
    }
    suspend fun isMangaExists (id: Long): Boolean = mangaDao.isMangaSaved(id)

    suspend fun getApiById(mangaId: Long)= mangaDao.getApiByMangaId(mangaId)
    suspend fun getIdByApiTitle(key: ApiTitle)= mangaDao.getIdByApiAndTitle(key.api, key.title)
    suspend fun getIdByUrl(url: String)= mangaDao.getIdByUrl(url)



    fun isChapterBookmarkedFlow(chapterId: Long): Flow<Boolean> =
        chapterDao.getChapterById(chapterId)
            .map { it?.isBookmarked == true }


    suspend fun insertChapterList(chapters: List<SavedChapterEntity>): List<Long> =
        withContext(Dispatchers.IO) {
            try {
                // Use the safe method with IGNORE strategy
                val results = chapterDao.insertChaptersSafely(chapters)
                results
            } catch (e: Exception) {
                // Return empty list on error to prevent crashes
                emptyList()
            }
        }

    fun getDisplayItemsManga(): Flow<List<SavedMangaWithMetrics>>
        {
        return mangaDao.getSavedMangaWithMetricsFlow() }

    fun getDisplayItemsFlow(): Flow<List<MangaDisplayItem>> {
        return getDisplayItemsManga()
            .map { listWithMetrics ->
                listWithMetrics.map { item ->
                    MangaDisplayItem(
                        manga = item.manga,
                        totalChapters = item.totalChapters,
                        readCount = item.readCount,
                        downloadedCount = item.downloadedCount,
                        bookmarkedCount = item.bookmarkedCount,

                    )
                }
            }.flowOn(Dispatchers.IO)  // ensure DB on IO

    }
    fun getAllSavedManga(): Flow<List<SavedMangaEntity>> = mangaDao.getAllSavedMangaFlow()
    fun searchSavedManga(query: String): Flow<List<SavedMangaEntity>> =
        mangaDao.searchMangaByTitle(query)

    suspend fun updateLastOpenTimestamp(mangaId: Long, timestamp: Long = System.currentTimeMillis()) {
        mangaDao.updateLastOpenTimestamp(mangaId, timestamp)
    }
    suspend fun deleteChapter(chapter: SavedChapterEntity) = chapterDao.deleteChapterById(chapter.id)

    suspend fun getMangaById(mangaId: Long): SavedMangaEntity? = mangaDao.getMangaById(mangaId)

    fun getChaptersByMangaId(mangaId: Long): Flow<List<SavedChapterEntity>> =
        chapterDao.getChaptersByMangaId(mangaId)
    suspend fun insertChapters(chapters: List<SavedChapterEntity>) {
        chapterDao.insertAll(chapters)
    }
    suspend fun updateChapterLastReadDate(chapterId: Long) =
        chapterDao.updateChapterLastReadDate(chapterId)

    suspend fun updateChapterLocalPaths(chapterId: Long, paths: List<String>) =
        chapterDao.updateChapterLocalPaths(chapterId,paths)


    suspend fun markChapterAsDownloaded(chapterId: Long) =
        chapterDao.markChapterDownloaded(chapterId)
    suspend fun updateChapterLocalPathsByUrl(chapterUrl: String, paths: List<String>) =
        chapterDao.updateChapterLocalPathsByUrl(chapterUrl,paths)

    suspend fun getChapterIdByUrl(chapterUrl: String) =
        chapterDao.getChapterIdByUrl(chapterUrl)



    suspend fun toggleChapterBookmark(chapterId: Long) =
        chapterDao.toggleChapterBookmark(chapterId)
    suspend fun toggleChapterRead(chapterId: Long) =
        libraryDeo.markChapterAndNotificationRead(chapterId)

    // In your Repository
    suspend fun toggleChaptersBookmark(chapterIds: List<Long>) =
        chapterDao.toggleChaptersBookmark(chapterIds)

    suspend fun toggleChaptersRead(chapterIds: List<Long>) =
        chapterDao.toggleChaptersRead(chapterIds)
    // In repository
    suspend fun markChaptersRead(chapterIds: List<Long>) =
        chapterDao.markChaptersRead(chapterIds)
    suspend fun markChapterAsRead(chapterId: Long) {
        chapterDao.markChapterAsRead(chapterId)

    }
    suspend fun markChapterIsNew(chapterId: Long) =
        chapterDao.markChapterIsNew(chapterId)

    suspend fun deleteDownloadedChapters(chapters: Set<SavedChapterEntity>) = withContext(Dispatchers.IO) {
        // 1) Delete all rows in one go (you’ll need to add this DAO method)
        val ids = chapters.map { it.id }
        chapterDao.markChaptersNotDownloaded(ids)
        // 2) Delete files in parallel for speed
        coroutineScope {
            chapters.map { chapter ->
                async {
                    fileService.deleteChapterFiles(chapter.mangaId, chapter.id)
                }
            }.awaitAll()
        }
    }

    suspend fun updateMangaImageUrlEverywhere(mangaId: Long, newImageUrl: String) = withContext(Dispatchers.IO) {
        mangaDao.getMangaById(mangaId)?.let { manga ->
            mangaDao.updateManga(manga.copy(imageUrl = newImageUrl))
        }
        notificationDao.updateMangaImageUrl(mangaId, newImageUrl)
        historyDao.updateMangaImageUrl(mangaId, newImageUrl)
    }
    }


