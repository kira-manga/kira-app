package me.manga.kira.presentation.backup

import me.manga.kira.domain.usecase.backup.ClearBackupProgressUseCase
import me.manga.kira.domain.usecase.backup.DiscardBackupArtifactUseCase
import me.manga.kira.domain.usecase.backup.DiscardImportArtifactUseCase
import me.manga.kira.domain.usecase.backup.ExportBackupUseCase
import me.manga.kira.domain.usecase.backup.ImportBackupUseCase
import me.manga.kira.domain.usecase.backup.ObserveBackupProgressUseCase
import me.manga.kira.domain.usecase.backup.StopBackupUseCase

/** File operations grouped for the backup VM without leaking platform/file handles. */
data class BackupFileOperations(
    val exportBackup: ExportBackupUseCase,
    val importBackup: ImportBackupUseCase,
    val discardExport: DiscardBackupArtifactUseCase,
    val discardImport: DiscardImportArtifactUseCase,
)

/** Progress/control operations for the app-lifetime backup run. */
data class BackupProgressOperations(
    val observe: ObserveBackupProgressUseCase,
    val stop: StopBackupUseCase,
    val clear: ClearBackupProgressUseCase,
)
