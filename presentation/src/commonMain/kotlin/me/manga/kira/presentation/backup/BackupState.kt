package me.manga.kira.presentation.backup

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.backup.BackupProgress
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.presentation.mvi.MviState

/**
 * Backup & restore screen state.
 *
 * [scope] is fixed at construction from the navigation route (default full library; a scoped
 * instance carries the manga keys picked on Details / Library multi-select). [progress] mirrors
 * the repository's hot stream — a run started here keeps reporting even if the OS recreates the
 * screen. [isCbzConversionRunning] gates starting a run while the Settings CBZ bulk-conversion
 * is busy (both walk the same chapter dirs). [error] is the typed failure of the LAST run;
 * `:ui` localizes it (never a raw string here).
 */
data class BackupState(
    val scope: BackupScope = BackupScope.FullLibrary,
    val includeDownloads: Boolean = false,
    val progress: BackupProgress = BackupProgress(),
    val isCbzConversionRunning: Boolean = false,
    val error: AppError? = null,
) : MviState {
    /** Scoped export (Details / Library selection) — the Import row is hidden in this mode. */
    val isScoped: Boolean get() = scope is BackupScope.Mangas

    /** Titles to show for a scoped export (empty for a full-library backup). */
    val scopeTitles: List<String>
        get() = (scope as? BackupScope.Mangas)?.keys?.map { it.title }.orEmpty()

    /** No new run may start while one is in flight or the CBZ converter owns the chapter dirs. */
    val canStartRun: Boolean get() = !progress.isRunning && !isCbzConversionRunning
}
