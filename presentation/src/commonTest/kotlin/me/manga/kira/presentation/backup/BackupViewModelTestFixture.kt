package me.manga.kira.presentation.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.backup.BackupExportResult
import me.manga.kira.domain.model.backup.BackupImportResult
import me.manga.kira.domain.model.backup.BackupProgress
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.repository.BackupImportArtifactRepository
import me.manga.kira.domain.repository.BackupRepository
import me.manga.kira.domain.usecase.backup.ClearBackupProgressUseCase
import me.manga.kira.domain.usecase.backup.DiscardBackupArtifactUseCase
import me.manga.kira.domain.usecase.backup.DiscardImportArtifactUseCase
import me.manga.kira.domain.usecase.backup.ExportBackupUseCase
import me.manga.kira.domain.usecase.backup.ImportBackupUseCase
import me.manga.kira.domain.usecase.backup.ObserveBackupProgressUseCase
import me.manga.kira.domain.usecase.backup.StopBackupUseCase
import me.manga.kira.domain.usecase.settings.ObserveCbzConversionUseCase
import me.manga.kira.presentation.testing.FakeSettingsRepository
import kotlin.coroutines.cancellation.CancellationException

internal val backupTestExportResult = BackupExportResult(
    archivePath = "/cache/kira-backup.kira.zip",
    suggestedName = "kira-backup.kira.zip",
    sizeBytes = 1_024,
    mangaCount = 2,
    chapterCount = 30,
    downloadCount = 0,
    skippedLooseDownloads = 0,
)

internal val backupTestImportResult = BackupImportResult(
    mangasAdded = 1,
    mangasMerged = 1,
    chaptersAdded = 5,
    chaptersMerged = 95,
    downloadsRestored = 0,
    historyMerged = 1,
)

internal class FakeBackupRepository : BackupRepository, BackupImportArtifactRepository {
    val progress = MutableStateFlow(BackupProgress())
    val exportCalls = mutableListOf<Pair<BackupScope, Boolean>>()
    val importCalls = mutableListOf<String>()
    val discardedArtifacts = mutableListOf<String>()
    val discardedImports = mutableListOf<String>()
    var importCancellation: CancellationException? = null
    var stopCount = 0
    var clearCount = 0
    var exportOutcome: AppResult<BackupExportResult> = AppResult.Success(backupTestExportResult)
    var importOutcome: AppResult<BackupImportResult> = AppResult.Success(backupTestImportResult)

    override fun observeProgress(): Flow<BackupProgress> = progress

    override suspend fun exportBackup(
        scope: BackupScope,
        includeDownloads: Boolean,
    ): AppResult<BackupExportResult> {
        exportCalls += scope to includeDownloads
        return exportOutcome
    }

    override suspend fun importBackup(archivePath: String): AppResult<BackupImportResult> {
        importCalls += archivePath
        importCancellation?.let { throw it }
        return importOutcome
    }

    override suspend fun discardExportArtifact(archivePath: String) {
        discardedArtifacts += archivePath
    }

    override suspend fun discardImportArtifact(archivePath: String): AppResult<Unit> {
        discardedImports += archivePath
        return AppResult.Success(Unit)
    }

    override fun stop() {
        stopCount++
    }

    override fun clearProgress() {
        clearCount++
        progress.value = BackupProgress()
    }
}

internal fun buildBackupVm(
    repo: FakeBackupRepository,
    scope: BackupScope = BackupScope.FullLibrary,
    settings: FakeSettingsRepository = FakeSettingsRepository(),
): BackupViewModel =
    BackupViewModel(
        scope = scope,
        files = BackupFileOperations(
            ExportBackupUseCase(repo),
            ImportBackupUseCase(repo),
            DiscardBackupArtifactUseCase(repo),
            DiscardImportArtifactUseCase(repo),
        ),
        progressActions = BackupProgressOperations(
            ObserveBackupProgressUseCase(repo),
            StopBackupUseCase(repo),
            ClearBackupProgressUseCase(repo),
        ),
        observeCbzConversion = ObserveCbzConversionUseCase(settings),
    )
