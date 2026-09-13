package me.manga.kira.presentation.features.download.domain

import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.domain.service.FileService
import me.manga.kira.presentation.features.library.domain.LibraryRepository

/**
 * Persists a service's retained paths and notification paths in their existing order.
 * These are still separate suspend calls, not a transaction or a terminal SUCCESS write.
 * Errors and cancellation propagate unchanged; the worker owns completion and stop cleanup.
 */
class ChapterDownloadPersistence(
    private val libraryRepository: LibraryRepository,
    private val notificationDao: NotificationDao,
    private val chapterDownloadDao: ChapterDownloadDao,
    private val fileService: FileService,
) {
    /** Retains the saved-chapter write before the notification-path write, without catching either. */
    suspend fun savePaths(
        chapterId: Long,
        paths: List<String>,
    ) {
        libraryRepository.updateChapterLocalPaths(chapterId, paths)
        notificationDao.addLocalImagePathByChapterId(chapterId, paths)
    }

    /** Forwards the existing failure transaction; never converts cancellation into a failure. */
    suspend fun recordFailure(
        chapterId: Long,
        errorMessage: String?,
    ) {
        chapterDownloadDao.updateFailure(chapterId, errorMessage)
    }

    fun deleteChapterFiles(
        mangaId: Long,
        chapterId: Long,
    ) {
        fileService.deleteChapterFiles(mangaId, chapterId)
    }

    suspend fun deleteMangaFiles(mangaId: Long) {
        fileService.deleteMangaFiles(mangaId)
    }
}
