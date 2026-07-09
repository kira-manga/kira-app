package me.manga.kira.domain.model.backup

/** Which operation a [BackupProgress] snapshot belongs to. Retained on terminal snapshots. */
enum class BackupPhase { IDLE, EXPORTING, IMPORTING }

/**
 * Progress snapshot for a backup export/import run — same posture as
 * [me.manga.kira.domain.model.settings.CbzConversionProgress]: the `:data` BackupRepository impl
 * owns a single hot `MutableStateFlow<BackupProgress>` and re-emits a copy per processed item;
 * `:presentation` projects it into MVI state; `:ui` renders a state-driven progress dialog.
 *
 * Terminal semantics: [isRunning] flips to `false` on every terminal state; the dialog shows the
 * terminal variant when `!isRunning` AND ([failed] || [wasStopped] || one of the results is
 * non-null). [failed] is a presence-only marker — the typed AppError travels on the use-case
 * return value, not through this stream. The default instance is the idle baseline replayed to
 * fresh subscribers; the dialog treats it as "nothing to show".
 */
data class BackupProgress(
    val phase: BackupPhase = BackupPhase.IDLE,
    val isRunning: Boolean = false,
    val totalMangas: Int = 0,
    val processedMangas: Int = 0,
    val totalDownloads: Int = 0,
    val processedDownloads: Int = 0,
    val currentTitle: String = "",
    val failed: Boolean = false,
    val wasStopped: Boolean = false,
    val exportResult: BackupExportResult? = null,
    val importResult: BackupImportResult? = null,
)

/**
 * Outcome of a completed export. [archivePath] points at the finished archive in app cache — the
 * UI hands it to the platform save-picker, then discards it via `discardExportArtifact`.
 * [skippedLooseDownloads] counts downloaded chapters that could not be included because they have
 * no CBZ yet (loose pages only) — the UI hints at "Compress Existing Downloads".
 */
data class BackupExportResult(
    val archivePath: String,
    val suggestedName: String,
    val sizeBytes: Long,
    val mangaCount: Int,
    val chapterCount: Int,
    val downloadCount: Int,
    val skippedLooseDownloads: Int,
)

/** Outcome of a completed import (merge — nothing is ever deleted). */
data class BackupImportResult(
    val mangasAdded: Int,
    val mangasMerged: Int,
    val chaptersAdded: Int,
    val chaptersMerged: Int,
    val downloadsRestored: Int,
    val historyMerged: Int,
)
