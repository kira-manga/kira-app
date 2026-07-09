package me.manga.kira.domain.usecase.backup

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.backup.BackupExportResult
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.repository.BackupRepository

/** Build a backup archive (full library or selected mangas) into app cache. */
class ExportBackupUseCase(
    private val repository: BackupRepository,
) {
    suspend operator fun invoke(
        scope: BackupScope,
        includeDownloads: Boolean,
    ): AppResult<BackupExportResult> = repository.exportBackup(scope, includeDownloads)
}
