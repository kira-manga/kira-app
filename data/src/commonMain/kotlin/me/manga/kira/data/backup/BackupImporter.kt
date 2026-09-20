package me.manga.kira.data.backup

import me.manga.kira.data.local.dao.BackupDao
import me.manga.kira.data.local.dao.ChapterRestoreOutcome
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.backup.BackupImportResult
import me.manga.kira.domain.model.identity.ChapterLocator
import okio.IOException
import okio.use

/**
 * Admits the entire archive before any live merge. Each scoped work's metadata, history and
 * progress share the real owner writer. Publication is independently settled by
 * [RestoredDownloadPublisher], never compensated here or replaced by a canonical-file write.
 */
class BackupImporter(
    private val backupDao: BackupDao,
    private val mergeWriter: BackupMergeWriter,
    private val preflight: BackupArchivePreflight,
    private val publisher: RestoredDownloadPublisher,
) {
    internal suspend fun run(archivePath: String, run: BackupRun): BackupImportResult =
        preflight.prepare(archivePath, run::checkpoint).use { plan -> consume(plan, run) }

    private suspend fun consume(plan: ValidatedBackupPlan, run: BackupRun): BackupImportResult {
        val document = plan.manifest.document
        val batch = mergeWriter.batch(document, plan.manifest.history)
        run.update { it.copy(totalMangas = document.mangas.size, totalDownloads = plan.downloads.size) }
        val imported = ImportState()
        var processedMangas = 0
        for (group in batch.groups) {
            // A user stop cannot interrupt the current work's metadata/history/progress transaction.
            run.checkpoint()
            run.update { it.copy(processedMangas = processedMangas, currentTitle = group.manga?.title.orEmpty()) }
            val outcome = mergeWriter.importGroup(batch, group)
            imported.record(group, outcome)
            restoreDownloads(group, outcome, plan, imported, run)
            if (group.manga != null) processedMangas++
        }
        run.update { it.copy(processedMangas = document.mangas.size, currentTitle = "") }
        run.checkpoint()
        return imported.result()
    }

    private suspend fun restoreDownloads(
        group: BackupImportGroup,
        outcome: BackupOwnedMerge,
        plan: ValidatedBackupPlan,
        imported: ImportState,
        run: BackupRun,
    ) {
        for (chapter in group.manga?.chapters.orEmpty()) {
            val reference = chapter.downloadEntry ?: continue
            val receiver = outcome.manga ?: throw BackupOwnershipException("backup_download_work_missing")
            val retained = outcome.chapters[ChapterLocator(group.work, chapter.url)]
                ?: throw BackupOwnershipException("backup_download_chapter_missing")
            mergeWriter.checkDownloadOwner(receiver, retained)
            if (restoreDownload(plan.downloads.getValue(reference), receiver, retained)) imported.downloadsRestored++
            run.update { it.copy(processedDownloads = it.processedDownloads + 1) }
        }
    }

    private suspend fun restoreDownload(
        download: ValidatedBackupDownload,
        receiver: SavedMangaEntity,
        retained: SavedChapterEntity,
    ): Boolean {
        val current = backupDao.getChapterByMangaAndUrl(receiver.id, retained.url) ?: return false
        if (current.id != retained.id || current.isDownloaded) return false
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

    private class ImportState {
        var mangasAdded = 0
        var mangasMerged = 0
        var chaptersAdded = 0
        var chaptersMerged = 0
        var downloadsRestored = 0
        var historyMerged = 0

        fun record(group: BackupImportGroup, outcome: BackupOwnedMerge) {
            historyMerged += outcome.historyMerged
            if (group.manga == null) return
            if (outcome.mangaWasNew) mangasAdded++ else mangasMerged++
            chaptersAdded += outcome.chaptersAdded
            chaptersMerged += outcome.chaptersMerged
        }

        fun result() = BackupImportResult(
            mangasAdded, mangasMerged, chaptersAdded, chaptersMerged, downloadsRestored, historyMerged,
        )
    }
}
