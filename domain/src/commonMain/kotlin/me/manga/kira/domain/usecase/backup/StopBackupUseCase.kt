package me.manga.kira.domain.usecase.backup

import me.manga.kira.domain.repository.BackupRepository

/** Cooperatively stop the running export/import (in-flight item finishes; no torn writes). */
class StopBackupUseCase(private val repository: BackupRepository) {
    operator fun invoke() = repository.stop()
}
