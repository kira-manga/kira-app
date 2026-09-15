package me.manga.kira.data.backup

import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.local.dao.BackupDao
import me.manga.kira.data.local.dao.ChapterRestoreOutcome
import me.manga.kira.data.local.dao.ImportedChapterResult
import me.manga.kira.data.local.dao.ImportedMangaResult
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.backup.BackupImportResult
import me.manga.kira.domain.repository.ReadProgressRepository
import okio.IOException
import okio.use

/**
 * Admits the entire archive before any live merge. Metadata remains transactional per manga;
 * publication is independently settled by [RestoredDownloadPublisher], never compensated here.
 */
class BackupImporter(
    private val backupDao: BackupDao,
    private val readProgress: ReadProgressRepository,
    private val preflight: BackupArchivePreflight,
    private val publisher: RestoredDownloadPublisher,
) {
    internal suspend fun run(archivePath: String, run: BackupRun): BackupImportResult =
        preflight.prepare(archivePath, run::checkpoint).use { plan -> consume(plan, run) }

    private suspend fun consume(plan: ValidatedBackupPlan, run: BackupRun): BackupImportResult {
        val mangas = plan.manifest.document.mangas
        run.update { it.copy(totalMangas = mangas.size, totalDownloads = plan.downloads.size) }
        val imported = ImportState()
        mangas.forEachIndexed { index, manga ->
            // A user stop cannot interrupt the current manga's metadata transaction.
            run.checkpoint()
            run.update { it.copy(processedMangas = index, currentTitle = manga.title) }
            importManga(manga, plan, imported, run)
        }
        run.update { it.copy(processedMangas = mangas.size, currentTitle = "") }
        run.checkpoint()
        val historyMerged = importHistory(plan.manifest.history, imported.resolvedIdsByUrl, run)
        return imported.result(historyMerged)
    }

    private suspend fun importManga(
        manga: BackupManga,
        plan: ValidatedBackupPlan,
        imported: ImportState,
        run: BackupRun,
    ) {
        val outcome = backupDao.importMangaMerging(
            incoming = manga.toEntity(),
            incomingChapters = manga.chapters.map { it.toEntity() },
            mergeManga = BackupMergePolicy::mergeManga,
            mergeChapter = BackupMergePolicy::mergeChapter,
        )
        if (outcome.mangaId == -1L) return
        imported.record(manga.url, outcome)
        restoreResumePages(manga.chapters, outcome.chaptersByUrl)
        restoreDownloads(manga, outcome, plan, imported, run)
    }

    private suspend fun restoreResumePages(
        chapters: List<BackupChapter>,
        outcomes: Map<String, ImportedChapterResult>,
    ) {
        for (chapter in chapters) {
            val page = chapter.resumePage ?: continue
            val outcome = outcomes[chapter.url] ?: continue
            val localSaved = if (outcome.wasNew) null else readProgress.load(chapter.url)
            val restore = BackupMergePolicy.shouldRestoreResumePage(
                chapterWasNew = outcome.wasNew,
                incomingLastReadDate = chapter.lastReadDate,
                localLastReadDateBefore = outcome.localLastReadDateBefore,
                localSavedPage = localSaved,
            )
            if (restore) readProgress.save(chapter.url, page)
        }
    }

    private suspend fun restoreDownloads(
        manga: BackupManga,
        outcome: ImportedMangaResult,
        plan: ValidatedBackupPlan,
        imported: ImportState,
        run: BackupRun,
    ) {
        if (manga.chapters.none { it.downloadEntry != null }) return
        // Merge policy preserves local metadata: terminal rows must use the receiver, not DTOs.
        val receiver = resolveReceiver(manga, outcome.mangaId)
        for (chapter in manga.chapters) {
            val reference = chapter.downloadEntry ?: continue
            val chapterOutcome = outcome.chaptersByUrl[chapter.url]
            val restored = receiver != null && chapterOutcome != null &&
                restoreDownload(
                    plan.downloads.getValue(reference), receiver, chapter.url, chapterOutcome.chapterId,
                )
            if (restored) imported.downloadsRestored++
            run.update { it.copy(processedDownloads = it.processedDownloads + 1) }
        }
    }

    private suspend fun resolveReceiver(manga: BackupManga, id: Long): SavedMangaEntity? =
        (backupDao.getMangaByUrl(manga.url) ?: backupDao.getMangaByApiAndTitle(manga.api, manga.title))?.takeIf { it.id == id }

    private suspend fun restoreDownload(
        download: ValidatedBackupDownload,
        receiver: SavedMangaEntity,
        chapterUrl: String,
        chapterId: Long,
    ): Boolean {
        val current = backupDao.getChapterByMangaAndUrl(receiver.id, chapterUrl) ?: return false
        if (current.id != chapterId || current.isDownloaded) return false
        // The publisher rechecks this exact fresh identity and download snapshot transactionally.
        val outcome = publisher.publish(
            archive = RestoredChapterArchive(download.path, download.size),
            expected = current,
            api = receiver.api,
            mangaTitle = receiver.title,
        )
        return when (outcome) {
            ChapterRestoreOutcome.COMMITTED -> true
            ChapterRestoreOutcome.NOT_COMMITTED -> false
            // Never report completion or touch a publisher-owned generation when SQL is uncertain.
            ChapterRestoreOutcome.UNKNOWN -> throw IOException("Backup publication needs reconciliation")
        }
    }

    private suspend fun importHistory(
        history: List<HistoryItemD>,
        resolvedIdsByUrl: Map<String, Long>,
        run: BackupRun,
    ): Int {
        for (item in history) {
            run.checkpoint()
            val resolvedId = resolvedIdsByUrl[item.mangaUrl] ?: backupDao.getMangaByUrl(item.mangaUrl)?.id
            // Date conversion happened during preflight; no timezone-sensitive decoding after writes.
            backupDao.importHistoryMerging(item.copy(mangaId = resolvedId ?: 0), BackupMergePolicy::shouldReplaceHistory)
        }
        return history.size
    }

    private class ImportState {
        val resolvedIdsByUrl = mutableMapOf<String, Long>()
        var mangasAdded = 0
        var mangasMerged = 0
        var chaptersAdded = 0
        var chaptersMerged = 0
        var downloadsRestored = 0

        fun record(url: String, outcome: ImportedMangaResult) {
            resolvedIdsByUrl[url] = outcome.mangaId
            if (outcome.mangaWasNew) mangasAdded++ else mangasMerged++
            chaptersAdded += outcome.chaptersAdded
            chaptersMerged += outcome.chaptersMerged
        }

        fun result(historyMerged: Int) = BackupImportResult(
            mangasAdded, mangasMerged, chaptersAdded, chaptersMerged, downloadsRestored, historyMerged,
        )
    }
}
