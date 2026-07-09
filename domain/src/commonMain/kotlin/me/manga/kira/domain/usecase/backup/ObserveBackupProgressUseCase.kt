package me.manga.kira.domain.usecase.backup

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.backup.BackupProgress
import me.manga.kira.domain.repository.BackupRepository

/** Observe backup export/import progress (hot stream; idle baseline when nothing runs). */
class ObserveBackupProgressUseCase(
    private val repository: BackupRepository,
) {
    operator fun invoke(): Flow<BackupProgress> = repository.observeProgress()
}
