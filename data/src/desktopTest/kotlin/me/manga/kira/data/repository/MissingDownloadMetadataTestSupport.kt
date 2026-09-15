package me.manga.kira.data.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.dao.ChapterArtifactRepairDao
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal suspend fun DownloadRecoveryFixture.repairMissingMetadata(
    records: ChapterArtifactRepairDao = db.chapterArtifactRepairDao(),
    files: AppFileSystem = appFileSystem,
): Int = MissingDownloadMetadataRepair(records, files, artifactRuntime.ownership).reconcile()

internal suspend fun DownloadRecoveryFixture.offlineMirrors(original: RetainedDownload): Pair<ChapterNotification, HistoryItemD> {
    val manga = assertNotNull(db.mangaDao().getMangaById(original.saved.mangaId))
    val chapter = original.saved
    val notification = ChapterNotification(
        api = manga.api, language = manga.language, mangaId = manga.id,
        mangaTitle = manga.title, mangaImageUrl = manga.imageUrl, mangaUrl = manga.url,
        chapterId = chapter.id, chapterNumber = chapter.number, chapterUrl = chapter.url,
        notificationDate = LocalDate(2024, 1, 2), isRead = true, isDownloaded = true,
        localImagePaths = chapter.localImagePaths,
    )
    val id = db.notificationDao().insertNotificationsList(listOf(notification)).single()
    val history = HistoryItemD(
        api = manga.api, language = manga.language, mangaId = 0,
        mangaUrl = manga.url, mangaTitle = manga.title, mangaImageUrl = manga.imageUrl,
        chapterUrl = chapter.url, chapterTitle = chapter.name, isDownloaded = true,
        localImagePaths = chapter.localImagePaths, lastReadDate = LocalDateTime(2024, 2, 3, 4, 5),
        lastReadPage = 7, totalPages = 19,
    )
    db.backupDao().insertHistoryRow(history)
    return notification.copy(id = id) to assertNotNull(db.backupDao().getHistoryByMangaUrl(manga.url))
}

internal suspend fun DownloadRecoveryFixture.assertMissingMetadata(original: RetainedDownload, ledgerAbsent: Boolean = false) {
    assertEquals(original.saved.copy(isDownloaded = false, localImagePaths = emptyList()), saved(original))
    if (ledgerAbsent) {
        assertNull(dao.getDownloadByChapter(original.saved.id))
    } else {
        val expected = if (original.download.state == DownloadingState.SUCCESS) {
            original.download.copy(state = DownloadingState.FAILED, progress = 0, sizeBytes = 0, errorMsg = null)
        } else original.download
        assertEquals(expected, download(original))
    }
}

internal suspend fun DownloadRecoveryFixture.assertMirrorsCleared(mirrors: Pair<ChapterNotification, HistoryItemD>) {
    val (notification, history) = mirrors
    assertEquals(notification.copy(isDownloaded = false, localImagePaths = emptyList()),
        db.notificationDao().getNotificationByChapterId(notification.chapterId))
    assertEquals(history.copy(isDownloaded = false, localImagePaths = emptyList()),
        db.backupDao().getAllHistoryOnce().single { it.id == history.id })
}
