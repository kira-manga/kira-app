package me.manga.kira.domain.usecase.backup

import me.manga.kira.domain.repository.BackupRepository

/** Reset the backup progress stream to idle (progress dialog dismissed). */
class ClearBackupProgressUseCase(
    private val repository: BackupRepository,
) {
    operator fun invoke() = repository.clearProgress()
}
