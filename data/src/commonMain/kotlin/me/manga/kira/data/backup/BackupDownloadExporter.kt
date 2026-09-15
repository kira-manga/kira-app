package me.manga.kira.data.backup

import me.manga.kira.data.download.artifacts.ChapterArtifactReference
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.dao.BackupDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.cbz.CbzReader
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.IOException
import okio.Path

/**
 * Exports readable chapter archives while sharing the download/restore file pin. Explicit restored
 * generations never fall back to a different canonical archive, including after container drift.
 */
class BackupDownloadExporter(
    private val backupDao: BackupDao,
    private val downloads: ChapterDownloadDao,
    private val files: AppFileSystem,
    private val cbzReader: CbzReader,
    private val artifacts: ChapterArtifacts,
) {
    internal suspend fun candidate(chapter: SavedChapterEntity, entryName: String): PackedBackupDownload? {
        val owner = ChapterArtifactOwner.of(chapter)
        return artifacts.read(chapter.id) { record -> candidate(owner, record, entryName) }
    }

    internal suspend fun write(writer: BackupZipWriter, packed: PackedBackupDownload) {
        artifacts.read(packed.owner.chapterId) { record ->
            val current = candidate(packed.owner, record, packed.entryName)
            if (current == null || current.path != packed.path || current.sizeBytes != packed.sizeBytes) {
                throw IOException("Backup source chapter changed before copying")
            }
            // Keep the pin through both the writer's CRC pass and its actual archive copy.
            writer.writeEntryFromFile(packed.entryName, files.fileSystem(), packed.path)
        }
    }

    private suspend fun candidate(
        owner: ChapterArtifactOwner,
        record: ChapterArtifactEntity?,
        entryName: String,
    ): PackedBackupDownload? {
        val current = backupDao.getChapterByMangaAndUrl(owner.mangaId, owner.chapterUrl) ?: return null
        if (!owner.matches(current) || !current.isDownloaded) return null
        if (downloads.getDownloadByChapter(owner.chapterId)?.state in ACTIVE_DOWNLOAD_STATES) return null
        if (record != null) {
            if (record.mangaId != owner.mangaId || record.chapterUrl != owner.chapterUrl) return null
            if (record.retiring || record.token != null) return null
        }
        val relative = record?.committedRelativePath
        val path = if (relative != null) {
            ChapterArtifactReference.resolve(files, owner, relative)
        } else {
            cbzReader.cbzPath(owner.mangaId, owner.chapterId)
        }
        val metadata = files.fileSystem().metadataOrNull(path) ?: return null
        if (!metadata.isRegularFile) return null
        val size = metadata.size ?: return null
        return PackedBackupDownload(entryName, owner, path, size)
    }

    private companion object {
        val ACTIVE_DOWNLOAD_STATES = setOf(
            DownloadingState.QUEUED,
            DownloadingState.RUNNING,
            DownloadingState.DOWNLOADED,
            DownloadingState.COMPRESSING,
        )
    }
}

internal data class PackedBackupDownload(
    val entryName: String,
    val owner: ChapterArtifactOwner,
    val path: Path,
    val sizeBytes: Long,
)
