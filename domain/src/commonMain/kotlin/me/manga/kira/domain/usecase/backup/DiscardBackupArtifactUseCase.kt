package me.manga.kira.domain.usecase.backup

import me.manga.kira.domain.repository.BackupRepository

/** Delete a finished export artifact from app cache after the save-picker hand-off. */
class DiscardBackupArtifactUseCase(private val repository: BackupRepository) {
    suspend operator fun invoke(archivePath: String) = repository.discardExportArtifact(archivePath)
}
