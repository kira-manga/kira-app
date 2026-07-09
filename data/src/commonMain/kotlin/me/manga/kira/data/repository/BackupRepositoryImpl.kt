@file:OptIn(ExperimentalTime::class)

package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.appFailure
import me.manga.kira.core.result.appSuccess
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.backup.BACKUP_DB_VERSION
import me.manga.kira.data.backup.BACKUP_FORMAT_VERSION
import me.manga.kira.data.backup.BACKUP_JSON_ENTRY
import me.manga.kira.data.backup.BackupMergePolicy
import me.manga.kira.data.backup.backupJson
import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.backup.model.BackupHistoryItem
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.backup.toBackup
import me.manga.kira.data.backup.toEntity
import me.manga.kira.data.local.dao.BackupDao
import me.manga.kira.data.local.dao.ImportedChapterResult
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.backup.BackupExportResult
import me.manga.kira.domain.model.backup.BackupImportResult
import me.manga.kira.domain.model.backup.BackupPhase
import me.manga.kira.domain.model.backup.BackupProgress
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.repository.BackupRepository
import me.manga.kira.domain.repository.ReadProgressRepository
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.backup.ZipLimitExceededException
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Cross-platform [BackupRepository]: builds and merge-imports the single-ZIP backup archive
 * (`backup.json` as the first entry; Phase B adds `downloads/<n>.cbz` entries). Same
 * hot-progress / cooperative-stop / cancellation-reset posture as the CBZ bulk-conversion
 * engine in [SettingsRepositoryImpl].
 *
 * [appVersion] and [platformName] are provenance strings stamped into the document (import never
 * gates on them); the composition root supplies both — there is no cross-platform provider.
 */
class BackupRepositoryImpl(
    private val backupDao: BackupDao,
    private val readProgress: ReadProgressRepository,
    private val appFileSystem: AppFileSystem,
    private val dispatchers: DispatcherProvider,
    private val appVersion: String,
    private val platformName: String,
) : BackupRepository {
    private val progress = MutableStateFlow(BackupProgress())

    // Cooperative stop, checked between mangas — StateFlow for the cross-thread visibility edge.
    private val shouldStop = MutableStateFlow(false)

    // One run at a time; CAS so two racing submits can't both start (app-lifetime Koin single).
    private val runGate = MutableStateFlow(false)

    override fun observeProgress(): Flow<BackupProgress> = progress.asStateFlow()

    override suspend fun exportBackup(
        scope: BackupScope,
        includeDownloads: Boolean,
    ): AppResult<BackupExportResult> = runExclusive(BackupPhase.EXPORTING) { runExport(scope, includeDownloads) }

    override suspend fun importBackup(archivePath: String): AppResult<BackupImportResult> =
        runExclusive(BackupPhase.IMPORTING) { runImport(archivePath) }

    override suspend fun discardExportArtifact(archivePath: String) {
        withContext(dispatchers.io) {
            // Only ever delete inside our own export staging dir, never an arbitrary path.
            val exportRoot = (appFileSystem.cacheDir / EXPORT_DIR).toString()
            if (archivePath.startsWith(exportRoot)) {
                runCatchingCancellable {
                    appFileSystem.fileSystem().delete(archivePath.toPath(), mustExist = false)
                }
            }
        }
    }

    override fun stop() {
        shouldStop.value = true
    }

    override fun clearProgress() {
        // Guarded against clobbering a live run (the VM guards on its side too).
        progress.update { if (it.isRunning) it else BackupProgress() }
    }

    // --- Run orchestration ---

    private suspend fun <T> runExclusive(
        phase: BackupPhase,
        block: suspend () -> AppResult<T>,
    ): AppResult<T> {
        if (!runGate.compareAndSet(expect = false, update = true)) {
            return appFailure(AppError.Storage.Constraint("a backup operation is already running"))
        }
        shouldStop.value = false
        progress.value = BackupProgress(phase = phase, isRunning = true)
        return try {
            val result = withContext(dispatchers.io) { block() }
            if (result is AppResult.Failure && result.error !is AppError.Cancelled) {
                progress.update { it.copy(isRunning = false, failed = true) }
            }
            result
        } catch (ce: CancellationException) {
            // Same rationale as the CBZ engine: reset the app-lifetime hot flow, or the progress
            // dialog outlives the dead run as an undismissable modal. Rethrow so structured
            // concurrency unwinds cooperatively.
            progress.value = BackupProgress()
            throw ce
        } catch (t: Throwable) {
            progress.update { it.copy(isRunning = false, failed = true) }
            appFailure(mapUnexpected(t))
        } finally {
            runGate.value = false
        }
    }

    private fun mapUnexpected(t: Throwable): AppError =
        when (t) {
            is ZipLimitExceededException -> AppError.Validation.OutOfRange("backup_size", t)
            is IOException -> AppError.Storage.Io(t)
            else -> AppError.Unexpected(t.message ?: "backup operation failed", t)
        }

    /** Cooperative stop between items: terminal Stopped snapshot; partial work stays consistent. */
    private fun stoppedTerminal(): AppResult<Nothing> {
        progress.update { it.copy(isRunning = false, wasStopped = true) }
        return appFailure(AppError.Cancelled())
    }

    // --- Export ---

    /**
     * Phase A ships metadata-only: [includeDownloads] is accepted (the port shape is final) but
     * CBZ packing lands in Phase B, so the document is always stamped `includesDownloads = false`.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun runExport(
        scope: BackupScope,
        includeDownloads: Boolean,
    ): AppResult<BackupExportResult> {
        val rows = resolveScopeRows(scope)
        progress.update { it.copy(totalMangas = rows.size) }

        val mangas = ArrayList<BackupManga>(rows.size)
        var chapterCount = 0
        rows.forEachIndexed { index, row ->
            if (shouldStop.value) return stoppedTerminal()
            progress.update { it.copy(processedMangas = index, currentTitle = row.title) }
            val chapters = exportChapters(row.id)
            chapterCount += chapters.size
            mangas += row.toBackup(chapters)
        }
        progress.update { it.copy(processedMangas = rows.size, currentTitle = "") }

        val document =
            BackupFile(
                formatVersion = BACKUP_FORMAT_VERSION,
                appVersion = appVersion,
                dbVersion = BACKUP_DB_VERSION,
                platform = platformName,
                createdAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                includesDownloads = false,
                mangas = mangas,
                history = exportHistory(scope, rows),
            )
        val result =
            writeArchive(
                document = document,
                name = suggestedFileName(scope, rows),
                mangaCount = mangas.size,
                chapterCount = chapterCount,
            )
        progress.update { it.copy(isRunning = false, exportResult = result) }
        return appSuccess(result)
    }

    private suspend fun resolveScopeRows(scope: BackupScope): List<SavedMangaEntity> =
        when (scope) {
            is BackupScope.FullLibrary -> backupDao.getAllSavedManga()
            is BackupScope.Mangas ->
                scope.keys
                    .mapNotNull { backupDao.getMangaByApiAndTitle(it.api, it.title) }
                    .distinctBy { it.id }
        }

    private suspend fun exportChapters(mangaId: Long): List<BackupChapter> =
        backupDao.getChaptersForManga(mangaId).map { chapter ->
            chapter.toBackup(
                resumePage = readProgress.load(chapter.url),
                downloadEntry = null, // Phase B packs CBZ entries; metadata-only until then.
            )
        }

    private suspend fun exportHistory(
        scope: BackupScope,
        rows: List<SavedMangaEntity>,
    ): List<BackupHistoryItem> {
        val all = backupDao.getAllHistoryOnce()
        val relevant =
            when (scope) {
                is BackupScope.FullLibrary -> all
                is BackupScope.Mangas -> {
                    val urls = rows.mapTo(HashSet()) { it.url }
                    all.filter { it.mangaUrl in urls }
                }
            }
        return relevant.map { it.toBackup() }
    }

    private fun writeArchive(
        document: BackupFile,
        name: String,
        mangaCount: Int,
        chapterCount: Int,
    ): BackupExportResult {
        val fs = appFileSystem.fileSystem()
        val dir = appFileSystem.cacheDir / EXPORT_DIR
        fs.createDirectories(dir)
        val archive = dir / name
        val jsonBytes = backupJson.encodeToString(document).encodeToByteArray()
        var completed = false
        try {
            fs.sink(archive).buffer().use { sink ->
                val writer = BackupZipWriter(sink)
                writer.writeEntryBytes(BACKUP_JSON_ENTRY, jsonBytes)
                writer.finish()
            }
            completed = true
        } finally {
            // Never leave a torn archive behind (cancel or I/O failure mid-write).
            if (!completed) {
                runCatchingCancellable { fs.delete(archive, mustExist = false) }
            }
        }
        return BackupExportResult(
            archivePath = archive.toString(),
            suggestedName = name,
            sizeBytes = fs.metadataOrNull(archive)?.size ?: 0L,
            mangaCount = mangaCount,
            chapterCount = chapterCount,
            downloadCount = 0,
            skippedLooseDownloads = 0,
        )
    }

    private fun suggestedFileName(
        scope: BackupScope,
        rows: List<SavedMangaEntity>,
    ): String {
        val singleTitle = rows.singleOrNull()?.takeIf { scope is BackupScope.Mangas }?.title
        return if (singleTitle != null) {
            "kira-manga-${sanitizeForFileName(singleTitle)}-${timestampSuffix()}.kira.zip"
        } else {
            "kira-backup-${timestampSuffix()}.kira.zip"
        }
    }

    private fun timestampSuffix(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

        fun two(v: Int) = v.toString().padStart(2, '0')
        return "${now.year}${two(now.month.number)}${two(now.day)}-" +
            "${two(now.hour)}${two(now.minute)}${two(now.second)}"
    }

    private fun sanitizeForFileName(title: String): String {
        val cleaned =
            title
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .split('-')
                .filter { it.isNotEmpty() }
                .joinToString("-")
                .take(MAX_TITLE_IN_FILENAME)
        return cleaned.ifEmpty { "manga" }
    }

    // --- Import ---

    private suspend fun runImport(archivePath: String): AppResult<BackupImportResult> {
        val path = archivePath.toPath()
        if (!appFileSystem.fileSystem().exists(path)) {
            return appFailure(AppError.Storage.NotFound("backup_file"))
        }
        val document =
            when (val parsed = parseArchive(path)) {
                is AppResult.Failure -> return parsed
                is AppResult.Success -> parsed.value
            }
        if (document.formatVersion > BACKUP_FORMAT_VERSION) {
            return appFailure(AppError.Validation.OutOfRange("formatVersion"))
        }

        progress.update { it.copy(totalMangas = document.mangas.size) }
        val counters = ImportCounters()
        val resolvedIdsByUrl = HashMap<String, Long>()
        document.mangas.forEachIndexed { index, manga ->
            // Stop between mangas: each manga merges in its own transaction, so the partial
            // import is consistent and re-running the same file converges (idempotent).
            if (shouldStop.value) return stoppedTerminal()
            progress.update { it.copy(processedMangas = index, currentTitle = manga.title) }
            importOneManga(manga, counters, resolvedIdsByUrl)
        }
        progress.update { it.copy(processedMangas = document.mangas.size, currentTitle = "") }

        var historyMerged = 0
        for (item in document.history) {
            if (shouldStop.value) return stoppedTerminal()
            val resolvedId =
                resolvedIdsByUrl[item.mangaUrl]
                    ?: backupDao.getMangaByUrl(item.mangaUrl)?.id
            backupDao.importHistoryMerging(
                item.toEntity(resolvedId),
                BackupMergePolicy::shouldReplaceHistory,
            )
            historyMerged++
        }

        val result =
            BackupImportResult(
                mangasAdded = counters.mangasAdded,
                mangasMerged = counters.mangasMerged,
                chaptersAdded = counters.chaptersAdded,
                chaptersMerged = counters.chaptersMerged,
                downloadsRestored = 0,
                historyMerged = historyMerged,
            )
        progress.update { it.copy(isRunning = false, importResult = result) }
        return appSuccess(result)
    }

    /**
     * Not-a-ZIP, missing `backup.json`, and undecodable JSON all collapse into one user-facing
     * failure mode: "this file is not a Kira backup".
     */
    private fun parseArchive(path: Path): AppResult<BackupFile> =
        try {
            val text =
                appFileSystem
                    .fileSystem()
                    .openZip(path)
                    .source(BACKUP_JSON_ENTRY.toPath())
                    .buffer()
                    .use { it.readUtf8() }
            appSuccess(backupJson.decodeFromString<BackupFile>(text))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            appFailure(AppError.Validation.Format("backup_file", t))
        }

    private suspend fun importOneManga(
        manga: BackupManga,
        counters: ImportCounters,
        resolvedIdsByUrl: MutableMap<String, Long>,
    ) {
        val outcome =
            backupDao.importMangaMerging(
                incoming = manga.toEntity(),
                incomingChapters = manga.chapters.map { it.toEntity() },
                mergeManga = BackupMergePolicy::mergeManga,
                mergeChapter = BackupMergePolicy::mergeChapter,
            )
        if (outcome.mangaId == -1L) return
        resolvedIdsByUrl[manga.url] = outcome.mangaId
        if (outcome.mangaWasNew) counters.mangasAdded++ else counters.mangasMerged++
        counters.chaptersAdded += outcome.chaptersAdded
        counters.chaptersMerged += outcome.chaptersMerged
        restoreResumePages(manga.chapters, outcome.chaptersByUrl)
    }

    private suspend fun restoreResumePages(
        chapters: List<BackupChapter>,
        outcomes: Map<String, ImportedChapterResult>,
    ) {
        for (chapter in chapters) {
            val page = chapter.resumePage ?: continue
            val outcome = outcomes[chapter.url] ?: continue
            val localSaved = if (outcome.wasNew) null else readProgress.load(chapter.url)
            val restore =
                BackupMergePolicy.shouldRestoreResumePage(
                    chapterWasNew = outcome.wasNew,
                    incomingLastReadDate = chapter.lastReadDate,
                    localLastReadDateBefore = outcome.localLastReadDateBefore,
                    localSavedPage = localSaved,
                )
            if (restore) readProgress.save(chapter.url, page)
        }
    }

    private class ImportCounters {
        var mangasAdded = 0
        var mangasMerged = 0
        var chaptersAdded = 0
        var chaptersMerged = 0
    }

    private companion object {
        const val EXPORT_DIR = "backup_export"
        const val MAX_TITLE_IN_FILENAME = 40
    }
}
