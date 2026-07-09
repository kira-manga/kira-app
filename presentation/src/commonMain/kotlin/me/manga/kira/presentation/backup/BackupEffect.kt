package me.manga.kira.presentation.backup

import me.manga.kira.presentation.mvi.MviEffect

/** One-shot side effects — trigger data only, never rendering data or localized text. */
sealed interface BackupEffect : MviEffect {

    /** Hand the finished archive to the platform save-picker. */
    data class LaunchExportPicker(val archivePath: String, val suggestedName: String) : BackupEffect

    /** Open the platform file-picker for a backup archive. */
    data object LaunchImportPicker : BackupEffect

    data object NavigateBack : BackupEffect
}
