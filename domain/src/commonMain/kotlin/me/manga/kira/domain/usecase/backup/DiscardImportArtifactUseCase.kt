package me.manga.kira.domain.usecase.backup

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.BackupImportArtifactRepository

/** Release an unclaimed import picker result on busy rejection, cancellation or completed import. */
class DiscardImportArtifactUseCase(
    private val repository: BackupImportArtifactRepository,
) {
    suspend operator fun invoke(archivePath: String): AppResult<Unit> = repository.discardImportArtifact(archivePath)
}
