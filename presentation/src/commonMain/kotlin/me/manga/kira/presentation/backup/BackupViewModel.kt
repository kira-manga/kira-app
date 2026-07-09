package me.manga.kira.presentation.backup

import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.usecase.backup.ClearBackupProgressUseCase
import me.manga.kira.domain.usecase.backup.DiscardBackupArtifactUseCase
import me.manga.kira.domain.usecase.backup.ExportBackupUseCase
import me.manga.kira.domain.usecase.backup.ImportBackupUseCase
import me.manga.kira.domain.usecase.backup.ObserveBackupProgressUseCase
import me.manga.kira.domain.usecase.backup.StopBackupUseCase
import me.manga.kira.domain.usecase.settings.ObserveCbzConversionUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Backup & restore (export / merge-import of the library, or of the mangas a scoped route
 * carries).
 *
 * Picker round-trip: [BackupIntent.OnExport] runs the export and, on success, emits
 * [BackupEffect.LaunchExportPicker] with the finished cache artifact; the route layer runs the
 * platform save-picker and reports back via [BackupIntent.OnExportDelivered] (the artifact is
 * discarded on every outcome). Import mirrors it: [BackupIntent.OnImport] emits
 * [BackupEffect.LaunchImportPicker]; the picked file comes back as an app-sandbox copy via
 * [BackupIntent.OnImportFilePicked].
 *
 * Subscriptions start in `init` (pure-display posture): the progress dialog must reflect a run
 * that outlives a recreated screen, and the CBZ-conversion busy flag must be current before the
 * user can tap anything. Long-running work executes inside `handle` — leaving the screen cancels
 * the run cooperatively (the repository resets its hot flow on cancellation; a cancelled import
 * is a consistent partial merge and re-running the same file converges).
 */
class BackupViewModel(
    scope: BackupScope,
    private val exportBackup: ExportBackupUseCase,
    private val importBackup: ImportBackupUseCase,
    observeBackupProgress: ObserveBackupProgressUseCase,
    private val stopBackup: StopBackupUseCase,
    private val clearBackupProgress: ClearBackupProgressUseCase,
    private val discardBackupArtifact: DiscardBackupArtifactUseCase,
    observeCbzConversion: ObserveCbzConversionUseCase,
) : MviViewModel<BackupState, BackupIntent, BackupEffect>(BackupState(scope = scope)) {
    init {
        launchSafely {
            observeBackupProgress().collect { snapshot ->
                updateState { it.copy(progress = snapshot) }
            }
        }
        launchSafely {
            observeCbzConversion().collect { conversion ->
                updateState { it.copy(isCbzConversionRunning = conversion.isConverting) }
            }
        }
    }

    override suspend fun handle(intent: BackupIntent) {
        when (intent) {
            BackupIntent.OnToggleIncludeDownloads ->
                updateState {
                    it.copy(includeDownloads = !it.includeDownloads)
                }
            BackupIntent.OnExport -> startExport()
            is BackupIntent.OnExportDelivered -> finishExportHandoff(intent.success)
            BackupIntent.OnImport -> requestImportPicker()
            is BackupIntent.OnImportFilePicked -> startImport(intent.localPath)
            BackupIntent.OnStop -> stopBackup()
            BackupIntent.OnDismissResult -> dismissResult()
            BackupIntent.OnBack -> emit(BackupEffect.NavigateBack)
        }
    }

    private suspend fun startExport() {
        val current = state.value
        if (!current.canStartRun) return
        updateState { it.copy(error = null) }
        when (val result = exportBackup(current.scope, current.includeDownloads)) {
            is AppResult.Success ->
                emit(
                    BackupEffect.LaunchExportPicker(
                        archivePath = result.value.archivePath,
                        suggestedName = result.value.suggestedName,
                    ),
                )
            is AppResult.Failure ->
                if (result.error !is AppError.Cancelled) {
                    updateState { it.copy(error = result.error) }
                }
        }
    }

    private suspend fun finishExportHandoff(success: Boolean) {
        // The picker copied (or abandoned) the cache artifact — it is garbage on every outcome.
        state.value.progress.exportResult
            ?.let { discardBackupArtifact(it.archivePath) }
        if (!success) {
            // Save-picker dismissed: nothing was delivered, drop the terminal summary silently.
            clearBackupProgress()
        }
    }

    private suspend fun requestImportPicker() {
        val current = state.value
        if (!current.canStartRun || current.isScoped) return
        emit(BackupEffect.LaunchImportPicker)
    }

    private suspend fun startImport(localPath: String?) {
        if (localPath == null) return // picker cancelled
        if (!state.value.canStartRun) return
        updateState { it.copy(error = null) }
        val result = importBackup(localPath)
        if (result is AppResult.Failure && result.error !is AppError.Cancelled) {
            updateState { it.copy(error = result.error) }
        }
    }

    private fun dismissResult() {
        if (state.value.progress.isRunning) return
        clearBackupProgress()
        updateState { it.copy(error = null) }
    }
}
