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
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.ImportedChapterResult
import me.manga.kira.data.local.dao.ImportedMangaResult
import me.manga.kira.data.local.dao.RestoredChapterUpdate
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
import me.manga.kira.platform.cbz.CbzReader
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.FileSystem
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
 * Cross-platform [BackupRepository]: builds and merge-imports the single-ZIP backup archive —
 * `backup.json` as the first entry, plus one `downloads/<n>.cbz` entry per packed downloaded
 * chapter when the export opts in. Same hot-progress / cooperative-stop / cancellation-reset
 * posture as the CBZ bulk-conversion engine in [SettingsRepositoryImpl].
 *
 * Download packing safety: a chapter is packed only when its CBZ exists AND its download-queue
 * row is not active (never zip a half-finalized archive the background engine still owns);
 * `isDownloaded` chapters without a CBZ (loose pages) are skipped and counted so the UI can hint
 * at "Compress Existing Downloads". On import, a packed CBZ is streamed to `<target>.cbz.part`
 * and atomically renamed BEFORE the chapter row's `isDownloaded`/`localImagePaths` flip — a
 * cancel can never leave a readable-looking row pointing at a torn archive.
 *
 * [appVersion] and [platformName] are provenance strings stamped into the document (import never
 * gates on them); the composition root supplies both — there is no cross-platform provider.
 */
class BackupRepositoryImpl(
    private val backupDao: BackupDao,
    private val readProgress: ReadProgressRepository,
    private val appFileSystem: AppFileSystem,
    private val dispatchers: DispatcherProvider,
    private val cbzReader: CbzReader,
    private val chapterDownloadDao: ChapterDownloadDao,
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

    private suspend fun runExport(
        scope: BackupScope,
        includeDownloads: Boolean,
    ): AppResult<BackupExportResult> {
        val rows = resolveScopeRows(scope)
        progress.update { it.copy(totalMangas = rows.size) }

        val mangas = ArrayList<BackupManga>(rows.size)
        val packed = ArrayList<PackedDownload>()
        var chapterCount = 0
        var skippedLoose = 0
        rows.forEachIndexed { index, row ->
            if (shouldStop.value) return stoppedTerminal()
            progress.update { it.copy(processedMangas = index, currentTitle = row.title) }
            val export = exportChapters(row.id, includeDownloads, packed)
            chapterCount += export.chapters.size
            skippedLoose += export.skippedLoose
            mangas += row.toBackup(export.chapters)
        }
        progress.update {
            it.copy(processedMangas = rows.size, currentTitle = "", totalDownloads = packed.size)
        }

        // ZIP32 preflight — fail typed BEFORE writing rather than mid-archive (the writer's own
        // guard remains the byte-exact backstop).
        val packedBytes = packed.sumOf { it.sizeBytes }
        if (packedBytes > MAX_PACKED_BYTES || packed.size + 1 > MAX_ARCHIVE_ENTRIES) {
            return appFailure(AppError.Validation.OutOfRange("backup_size"))
        }

        val document =
            BackupFile(
                formatVersion = BACKUP_FORMAT_VERSION,
                appVersion = appVersion,
                dbVersion = BACKUP_DB_VERSION,
                platform = platformName,
                createdAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                includesDownloads = packed.isNotEmpty(),
                mangas = mangas,
                history = exportHistory(scope, rows),
            )
        val result =
            writeArchive(
                document = document,
                name = suggestedFileName(scope, rows),
                mangaCount = mangas.size,
                chapterCount = chapterCount,
                packed = packed,
                skippedLoose = skippedLoose,
            ) ?: return stoppedTerminal()
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

    private class ChapterExport(
        val chapters: List<BackupChapter>,
        val skippedLoose: Int,
    )

    private class PackedDownload(
        val entryName: String,
        val path: Path,
        val sizeBytes: Long,
    )

    /**
     * Maps one manga's chapter rows, and (when opted in) registers each packable CBZ into
     * [packed]. A downloaded chapter is packable only when its CBZ exists on disk AND the
     * download queue isn't actively writing into its chapter dir; everything else `isDownloaded`
     * counts as skipped-loose for the UI hint.
     */
    private suspend fun exportChapters(
        mangaId: Long,
        includeDownloads: Boolean,
        packed: MutableList<PackedDownload>,
    ): ChapterExport {
        var skippedLoose = 0
        val chapters =
            backupDao.getChaptersForManga(mangaId).map { chapter ->
                var downloadEntry: String? = null
                if (includeDownloads && chapter.isDownloaded) {
                    val active =
                        chapterDownloadDao.getDownloadByChapter(chapter.id)?.state in ACTIVE_DOWNLOAD_STATES
                    if (!active && cbzReader.cbzExists(mangaId, chapter.id)) {
                        val path = cbzReader.cbzPath(mangaId, chapter.id)
                        val size = appFileSystem.fileSystem().metadataOrNull(path)?.size ?: 0L
                        val entryName = "$DOWNLOADS_DIR/${packed.size}.cbz"
                        packed += PackedDownload(entryName, path, size)
                        downloadEntry = entryName
                    } else {
                        skippedLoose++
                    }
                }
                chapter.toBackup(
                    resumePage = readProgress.load(chapter.url),
                    downloadEntry = downloadEntry,
                )
            }
        return ChapterExport(chapters, skippedLoose)
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

    /** Returns null when the user stopped mid-write (the torn archive is already deleted). */
    private fun writeArchive(
        document: BackupFile,
        name: String,
        mangaCount: Int,
        chapterCount: Int,
        packed: List<PackedDownload>,
        skippedLoose: Int,
    ): BackupExportResult? {
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
                packed.forEachIndexed { index, download ->
                    if (shouldStop.value) return null
                    writer.writeEntryFromFile(download.entryName, fs, download.path)
                    progress.update { it.copy(processedDownloads = index + 1) }
                }
                writer.finish()
            }
            completed = true
        } finally {
            // Never leave a torn archive behind (stop, cancel, or I/O failure mid-write).
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
            downloadCount = packed.size,
            skippedLooseDownloads = skippedLoose,
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

        val totalDownloads = document.mangas.sumOf { manga -> manga.chapters.count { it.downloadEntry != null } }
        progress.update { it.copy(totalMangas = document.mangas.size, totalDownloads = totalDownloads) }

        // Reopened (parseArchive used its own handle) for streaming the packed CBZ entries.
        val zipFs = if (totalDownloads > 0) appFileSystem.fileSystem().openZip(path) else null

        val counters = ImportCounters()
        val resolvedIdsByUrl = HashMap<String, Long>()
        document.mangas.forEachIndexed { index, manga ->
            // Stop between mangas: each manga merges in its own transaction, so the partial
            // import is consistent and re-running the same file converges (idempotent).
            if (shouldStop.value) return stoppedTerminal()
            progress.update { it.copy(processedMangas = index, currentTitle = manga.title) }
            importOneManga(manga, counters, resolvedIdsByUrl, zipFs)
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
                downloadsRestored = counters.downloadsRestored,
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
        zipFs: FileSystem?,
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
        if (zipFs != null) restoreDownloads(zipFs, manga, outcome, counters)
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

    /** Per-chapter isolation: one torn/missing entry is skipped, the rest keep restoring. */
    private suspend fun restoreDownloads(
        zipFs: FileSystem,
        manga: BackupManga,
        outcome: ImportedMangaResult,
        counters: ImportCounters,
    ) {
        for (chapter in manga.chapters) {
            val entryName = chapter.downloadEntry ?: continue
            val chapterOutcome = outcome.chaptersByUrl[chapter.url] ?: continue
            val restored =
                runCatchingCancellable {
                    restoreOneDownload(
                        zipFs = zipFs,
                        entryName = entryName,
                        mangaId = outcome.mangaId,
                        chapterUrl = chapter.url,
                        chapterId = chapterOutcome.chapterId,
                    )
                }.getOrDefault(false)
            if (restored) counters.downloadsRestored++
            progress.update { it.copy(processedDownloads = it.processedDownloads + 1) }
        }
    }

    /**
     * Streams one packed CBZ to the chapter's canonical `DefaultCbzReader.cbzPath` location.
     * Order is the torn-archive guard: bytes land in `.part`, the rename is atomic, and only
     * then do the chapter row's reader-facing columns flip. Already-downloaded local chapters
     * keep their own archive (never overwritten).
     */
    private suspend fun restoreOneDownload(
        zipFs: FileSystem,
        entryName: String,
        mangaId: Long,
        chapterUrl: String,
        chapterId: Long,
    ): Boolean {
        val current = backupDao.getChapterByMangaAndUrl(mangaId, chapterUrl)
        if (current == null || current.isDownloaded) return false
        val fs = appFileSystem.fileSystem()
        val target = cbzReader.cbzPath(mangaId, chapterId)
        val parentDir = target.parent ?: return false
        fs.createDirectories(parentDir)
        val part = parentDir / "${target.name}.part"
        var copied = false
        try {
            fs.sink(part).buffer().use { sink ->
                zipFs.source(entryName.toPath()).buffer().use { source ->
                    sink.writeAll(source)
                }
            }
            copied = true
        } finally {
            if (!copied) {
                runCatchingCancellable { fs.delete(part, mustExist = false) }
            }
        }
        fs.atomicMove(part, target)
        backupDao.markChapterRestored(
            RestoredChapterUpdate(
                id = chapterId,
                isDownloaded = true,
                localImagePaths = listOf(target.toString()),
            ),
        )
        return true
    }

    private class ImportCounters {
        var mangasAdded = 0
        var mangasMerged = 0
        var chaptersAdded = 0
        var chaptersMerged = 0
        var downloadsRestored = 0
    }

    private companion object {
        const val EXPORT_DIR = "backup_export"
        const val DOWNLOADS_DIR = "downloads"
        const val MAX_TITLE_IN_FILENAME = 40

        // ZIP32 ceilings with headroom for the manifest + per-entry headers.
        const val MAX_PACKED_BYTES = 0xFFFFFFFFL - 64L * 1024 * 1024
        const val MAX_ARCHIVE_ENTRIES = 65_000

        /**
         * Download-queue states whose chapter dir the background engine still owns (same set the
         * CBZ bulk-converter skips) — packing such a chapter could zip a half-finalized archive.
         */
        val ACTIVE_DOWNLOAD_STATES =
            setOf(
                DownloadingState.QUEUED,
                DownloadingState.RUNNING,
                DownloadingState.DOWNLOADED,
                DownloadingState.COMPRESSING,
            )
    }
}
