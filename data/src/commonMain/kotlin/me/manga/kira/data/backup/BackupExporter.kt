@file:OptIn(kotlin.time.ExperimentalTime::class)

package me.manga.kira.data.backup

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.backup.model.BackupHistoryItem
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.local.dao.BackupDao
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.backup.BackupExportResult
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.repository.ReadProgressRepository
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.backup.ZipLimitExceededException
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.time.Clock

/** Informational export provenance; neither value is an import admission requirement. */
interface BackupExportProvenance {
    val appVersion: String
    val platformName: String
}

/** Values captured at the composition root, rather than globally bound unqualified strings. */
data class FixedBackupExportProvenance(
    override val appVersion: String,
    override val platformName: String,
) : BackupExportProvenance

/** Builds the existing ZIP32 backup format; chapter copies are pinned by [BackupDownloadExporter]. */
class BackupExporter(
    private val backupDao: BackupDao,
    private val readProgress: ReadProgressRepository,
    private val files: AppFileSystem,
    private val downloads: BackupDownloadExporter,
    private val provenance: BackupExportProvenance,
) {
    internal suspend fun run(scope: BackupScope, includeDownloads: Boolean, run: BackupRun): BackupExportResult {
        val rows = resolveScopeRows(scope)
        run.update { it.copy(totalMangas = rows.size) }
        val content = collectMangas(rows, includeDownloads, run)
        if (content.packed.sumOf { it.sizeBytes } > MAX_PACKED_BYTES || content.packed.size + 1 > MAX_ARCHIVE_ENTRIES) {
            throw ZipLimitExceededException("Backup export exceeds ZIP32 limits")
        }
        val document = BackupFile(
            formatVersion = BACKUP_FORMAT_VERSION,
            appVersion = provenance.appVersion,
            dbVersion = BACKUP_DB_VERSION,
            platform = provenance.platformName,
            createdAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            includesDownloads = content.packed.isNotEmpty(),
            mangas = content.mangas,
            history = exportHistory(scope, rows),
        )
        return writeArchive(document, suggestedFileName(scope, rows), content, run)
    }

    internal fun discard(archivePath: String) {
        val path = archivePath.toPath()
        // Exact parent containment, not a prefix which could name an unrelated directory.
        if (path.parent == files.cacheDir / EXPORT_DIR) {
            runCatchingCancellable { files.fileSystem().delete(path, mustExist = false) }
        }
    }

    private suspend fun resolveScopeRows(scope: BackupScope): List<SavedMangaEntity> =
        when (scope) {
            is BackupScope.FullLibrary -> backupDao.getAllSavedManga()
            is BackupScope.Mangas -> scope.keys.mapNotNull { backupDao.getMangaByApiAndTitle(it.api, it.title) }.distinctBy { it.id }
        }

    private suspend fun collectMangas(
        rows: List<SavedMangaEntity>,
        includeDownloads: Boolean,
        run: BackupRun,
    ): ExportContent {
        val content = ExportContent()
        rows.forEachIndexed { index, row ->
            run.checkpoint()
            run.update { it.copy(processedMangas = index, currentTitle = row.title) }
            val chapters = exportChapters(row.id, includeDownloads, content)
            content.chapterCount += chapters.size
            content.mangas += row.toBackup(chapters)
        }
        run.update { it.copy(processedMangas = rows.size, currentTitle = "", totalDownloads = content.packed.size) }
        return content
    }

    private suspend fun exportChapters(
        mangaId: Long,
        includeDownloads: Boolean,
        content: ExportContent,
    ): List<BackupChapter> = backupDao.getChaptersForManga(mangaId).map { chapter ->
        val packed = if (includeDownloads && chapter.isDownloaded) {
            downloads.candidate(chapter, "$DOWNLOADS_DIR/${content.packed.size}.cbz")
        } else null
        if (packed != null) content.packed += packed
        else if (includeDownloads && chapter.isDownloaded) content.skippedLoose++
        chapter.toBackup(readProgress.load(chapter.url), packed?.entryName)
    }

    private suspend fun exportHistory(scope: BackupScope, rows: List<SavedMangaEntity>): List<BackupHistoryItem> {
        val all = backupDao.getAllHistoryOnce()
        val relevant = when (scope) {
            is BackupScope.FullLibrary -> all
            is BackupScope.Mangas -> {
                val urls = rows.mapTo(HashSet()) { it.url }
                all.filter { it.mangaUrl in urls }
            }
        }
        return relevant.map { it.toBackup() }
    }

    private suspend fun writeArchive(
        document: BackupFile,
        name: String,
        content: ExportContent,
        run: BackupRun,
    ): BackupExportResult {
        val dir = files.cacheDir / EXPORT_DIR
        files.fileSystem().createDirectories(dir)
        val archive = dir / name
        writeEntries(archive, document, content.packed, run)
        return BackupExportResult(
            archivePath = archive.toString(),
            suggestedName = name,
            sizeBytes = files.fileSystem().metadataOrNull(archive)?.size ?: 0L,
            mangaCount = content.mangas.size,
            chapterCount = content.chapterCount,
            downloadCount = content.packed.size,
            skippedLooseDownloads = content.skippedLoose,
        )
    }

    private suspend fun writeEntries(
        archive: Path,
        document: BackupFile,
        packed: List<PackedBackupDownload>,
        run: BackupRun,
    ) {
        val fs = files.fileSystem()
        var completed = false
        try {
            fs.sink(archive).buffer().use { sink ->
                val writer = BackupZipWriter(sink)
                writer.writeEntryBytes(BACKUP_JSON_ENTRY, backupJson.encodeToString(document).encodeToByteArray())
                packed.forEachIndexed { index, download ->
                    run.checkpoint()
                    downloads.write(writer, download)
                    run.update { it.copy(processedDownloads = index + 1) }
                }
                run.checkpoint()
                writer.finish()
            }
            completed = true
        } finally {
            if (!completed) runCatchingCancellable { fs.delete(archive, mustExist = false) }
        }
    }

    private fun suggestedFileName(scope: BackupScope, rows: List<SavedMangaEntity>): String {
        val singleTitle = rows.singleOrNull()?.takeIf { scope is BackupScope.Mangas }?.title
        return if (singleTitle != null) "kira-manga-${sanitizeForFileName(singleTitle)}-${timestampSuffix()}.kira.zip"
        else "kira-backup-${timestampSuffix()}.kira.zip"
    }

    private fun timestampSuffix(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        fun two(value: Int) = value.toString().padStart(2, '0')
        return "${now.year}${two(now.month.number)}${two(now.day)}-${two(now.hour)}${two(now.minute)}${two(now.second)}"
    }

    private fun sanitizeForFileName(title: String): String = title
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")
        .take(MAX_TITLE_IN_FILENAME)
        .ifEmpty { "manga" }

    private class ExportContent {
        val mangas = mutableListOf<BackupManga>()
        val packed = mutableListOf<PackedBackupDownload>()
        var chapterCount = 0
        var skippedLoose = 0
    }

    private companion object {
        const val EXPORT_DIR = "backup_export"
        const val DOWNLOADS_DIR = "downloads"
        const val MAX_TITLE_IN_FILENAME = 40
        const val MAX_PACKED_BYTES = 0xFFFFFFFFL - 64L * 1024 * 1024
        const val MAX_ARCHIVE_ENTRIES = 65_000
    }
}
