package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import me.manga.kira.data.local.entity.ChapterNotification
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.assertEquals

/** Narrow stat/delete cuts using the same real bytes and existing Room fixture. */
internal class ConversionFileFaults(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    var statFailure: Path? = null
    var deleteFailure: Path? = null
    val deleteAttempts = mutableListOf<Path>()

    override fun metadataOrNull(path: Path) = if (path == statFailure) {
        statFailure = null
        throw IOException("conversion stat failed")
    } else super.metadataOrNull(path)

    override fun delete(path: Path, mustExist: Boolean) {
        deleteAttempts += path
        if (path == deleteFailure) throw IOException("conversion source cleanup failed")
        super.delete(path, mustExist)
    }
}

internal suspend fun DownloadRecoveryFixture.conversionMirror(original: RetainedDownload): ChapterNotification {
    val saved = original.saved
    db.notificationDao().insertNotificationsList(listOf(ChapterNotification(
        api = original.download.api, language = "en", mangaId = saved.mangaId, mangaTitle = "Original title",
        mangaImageUrl = "cover", mangaUrl = "https://example.test/manga/${saved.mangaId}",
        chapterId = saved.id, chapterNumber = saved.number, chapterUrl = saved.url,
        isRead = true, isDownloaded = true, localImagePaths = saved.localImagePaths,
    )))
    return conversionMirror(saved.id)
}

internal suspend fun DownloadRecoveryFixture.conversionMirror(chapterId: Long): ChapterNotification =
    db.notificationDao().getAllNotifications().first().single { it.chapterId == chapterId }

internal suspend fun DownloadRecoveryFixture.assertConverted(
    original: RetainedDownload, mirror: ChapterNotification, archive: Path, historyAbsent: Boolean = false,
) {
    val paths = listOf(archive.toString())
    assertEquals(original.saved.copy(localImagePaths = paths), saved(original))
    assertEquals(mirror.copy(localImagePaths = paths), conversionMirror(original.saved.id))
    assertEquals(
        if (historyAbsent) null else original.download.copy(sizeBytes = fs.metadata(archive).size!!),
        dao.getDownloadByChapter(original.saved.id),
    )
}
