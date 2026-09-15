package me.manga.kira.presentation.backup

import me.manga.kira.core.error.AppError
import me.manga.kira.presentation.mvi.MviIntent

/** User actions on the Backup & restore screen. */
sealed interface BackupIntent : MviIntent {
    /** Flip the "include downloaded chapters" export option. */
    data object OnToggleIncludeDownloads : BackupIntent

    /** Start an export of the screen's scope (busy-guarded). */
    data object OnExport : BackupIntent

    /**
     * The platform save-picker round-trip finished. [success] is false when the user dismissed
     * the picker or the copy failed — the cache artifact is discarded either way.
     */
    data class OnExportDelivered(
        val success: Boolean,
    ) : BackupIntent

    /** Ask for the platform open-picker (busy-guarded; unavailable in scoped mode). */
    data object OnImport : BackupIntent

    /** Picker finished: mobile owns a bounded snapshot; Desktop passes an original; null cancels. */
    data class OnImportFilePicked(
        val localPath: String?,
    ) : BackupIntent

    /** Acquisition failed before an import existed; cancellation is not represented as failure. */
    data class OnImportFilePickFailed(
        val error: AppError,
    ) : BackupIntent

    /** Cooperatively stop the running export/import. */
    data object OnStop : BackupIntent

    /** Dismiss the terminal progress dialog / error. */
    data object OnDismissResult : BackupIntent

    /** Leave the screen. */
    data object OnBack : BackupIntent
}
