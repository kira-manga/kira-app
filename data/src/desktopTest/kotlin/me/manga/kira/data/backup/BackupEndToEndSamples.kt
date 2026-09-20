@file:OptIn(kotlin.time.ExperimentalTime::class)

package me.manga.kira.data.backup

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.repository.progress.ProgressRuntimeFixture
import me.manga.kira.data.repository.progress.progressValue
import me.manga.kira.data.repository.recoveryTestPng
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.platform.backup.BackupZipWriter
import okio.buffer

internal suspend fun seedBackupManga(
    source: ProgressRuntimeFixture,
    sourceFs: BackupTestFileSystem,
    title: String,
    slug: String,
    withDownload: Boolean,
): SeededBackupManga {
    val manga = source.parent(backupSourceManga(title, slug))
    val chapter = source.chapter(backupSourceChapter(manga.id, slug, withDownload))
    val locator = ChapterLocator(WorkLocator(manga.api, manga.url), chapter.url)
    val handle = source.native.beginSession(locator).progressValue().handle
    source.native.save(handle, 7).progressValue()
    source.db.backupDao().insertHistoryRow(backupSourceHistory(manga, chapter))
    if (withDownload) writeOnePageCbz(sourceFs, manga.id, chapter.id)
    return SeededBackupManga(manga, chapter)
}

private fun backupSourceManga(title: String, slug: String) = SavedMangaEntity(
    api = "source",
    language = "ar",
    url = "https://current.test/manga/$slug",
    imageUrl = "https://images.example/$slug-cover.webp",
    title = title,
    description = "$title description",
    status = "Ongoing",
    rating = "4.8",
    genres = listOf("action", "fantasy"),
    savedTimestamp = 100,
    lastOpenTimestamp = 200,
    isLiked = true,
    isWatchingNow = true,
)

private fun backupSourceChapter(mangaId: Long, slug: String, withDownload: Boolean) = SavedChapterEntity(
    mangaId = mangaId,
    name = "Chapter 1",
    number = "1",
    url = "https://current.test/chapter/$slug-1",
    date = LocalDate(2026, 7, 18),
    isDownloaded = withDownload,
    isBookmarked = true,
    isRead = true,
    lastReadDate = 900,
    localImagePaths = emptyList(),
)

private fun backupSourceHistory(manga: SavedMangaEntity, chapter: SavedChapterEntity) = HistoryItemD(
    api = manga.api,
    language = manga.language,
    mangaId = manga.id,
    mangaUrl = manga.url,
    mangaTitle = manga.title,
    mangaImageUrl = manga.imageUrl,
    chapterUrl = chapter.url,
    chapterTitle = chapter.name,
    isDownloaded = chapter.isDownloaded,
    lastReadDate = LocalDateTime(2026, 7, 18, 12, 0),
    lastReadPage = 7,
    totalPages = 20,
)

private fun writeOnePageCbz(sourceFs: BackupTestFileSystem, mangaId: Long, chapterId: Long) {
    val path = backupTestCbzReader(sourceFs).cbzPath(mangaId, chapterId)
    sourceFs.fileSystem().createDirectories(checkNotNull(path.parent))
    sourceFs.fileSystem().sink(path).buffer().use { sink ->
        BackupZipWriter(sink).apply {
            writeEntryBytes("001.png", recoveryTestPng())
            finish()
        }
    }
}

internal data class SeededBackupManga(val manga: SavedMangaEntity, val chapter: SavedChapterEntity)
