package me.manga.kira.presentation.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.backup.BackupScope
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
 * [BackupEffect.LaunchImportPicker]; [BackupIntent.OnImportFilePicked] returns a bounded mobile
 * snapshot (or a Desktop original), while acquisition errors use [BackupIntent.OnImportFilePickFailed].
 *
 * Subscriptions start in `init` (pure-display posture): the progress dialog must reflect a run
 * that outlives a recreated screen, and the CBZ-conversion busy flag must be current before the
 * user can tap anything. Long-running work executes inside `handle` — leaving the screen cancels
 * the run cooperatively (the repository resets its hot flow on cancellation; a cancelled import
 * is a consistent partial merge and re-running the same file converges).
 */
class BackupViewModel(
    scope: BackupScope,
    private val files: BackupFileOperations,
    private val progressActions: BackupProgressOperations,
    observeCbzConversion: ObserveCbzConversionUseCase,
) : MviViewModel<BackupState, BackupIntent, BackupEffect>(BackupState(scope = scope)) {
    init {
        launchSafely {
            progressActions.observe().collect { snapshot ->
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
            is BackupIntent.OnImportFilePickFailed -> showFailure(intent.error)
            BackupIntent.OnStop -> progressActions.stop()
            BackupIntent.OnDismissResult -> dismissResult()
            BackupIntent.OnBack -> emit(BackupEffect.NavigateBack)
        }
    }

    private suspend fun startExport() {
        val current = state.value
        if (!current.canStartRun) return
        updateState { it.copy(error = null) }
        when (val result = files.exportBackup(current.scope, current.includeDownloads)) {
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
            ?.let { files.discardExport(it.archivePath) }
        if (!success) {
            // Save-picker dismissed: nothing was delivered, drop the terminal summary silently.
            progressActions.clear()
        }
    }

    private suspend fun requestImportPicker() {
        val current = state.value
        if (!current.canStartRun || current.isScoped) return
        emit(BackupEffect.LaunchImportPicker)
    }

    private suspend fun startImport(localPath: String?) {
        if (localPath == null) return // picker cancelled
        try {
            if (!state.value.canStartRun || state.value.isScoped) return
            updateState { it.copy(error = null) }
            val result = files.importBackup(localPath)
            if (result is AppResult.Failure) showFailure(result.error)
        } finally {
            // If the importer claimed the snapshot this is a no-op. Otherwise the busy/error/
            // cancellation path must not strand the picker capability or its acquisition slot.
            withContext(NonCancellable) {
                val cleanup = files.discardImport(localPath)
                if (cleanup is AppResult.Failure && state.value.error == null) showFailure(cleanup.error)
            }
        }
    }

    private fun showFailure(error: AppError) {
        if (error !is AppError.Cancelled) updateState { it.copy(error = error) }
    }

    private fun dismissResult() {
        if (state.value.progress.isRunning) return
        progressActions.clear()
        updateState { it.copy(error = null) }
    }
}
