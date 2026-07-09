package me.manga.kira.domain.usecase.backup

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.backup.BackupImportResult
import me.manga.kira.domain.repository.BackupRepository

/** Merge-import a backup archive previously copied into the app sandbox. */
class ImportBackupUseCase(private val repository: BackupRepository) {
    suspend operator fun invoke(archivePath: String): AppResult<BackupImportResult> =
        repository.importBackup(archivePath)
}
