package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.backup.BackupExportResult
import me.manga.kira.domain.model.backup.BackupImportResult
import me.manga.kira.domain.model.backup.BackupProgress
import me.manga.kira.domain.model.backup.BackupScope

/**
 * Library backup: export the library (or selected mangas) to a single archive file in app cache,
 * and merge-import such an archive back. File-picker/Uri handling is NOT this port's concern —
 * callers only ever exchange absolute paths inside the app sandbox (the platform picker layer
 * copies to/from user-visible storage), which keeps the port reusable for the planned
 * local-network sharing transport.
 */
interface BackupRepository {
    /** Hot progress stream (idle baseline when nothing runs). One run at a time. */
    fun observeProgress(): Flow<BackupProgress>

    /**
     * Build a backup archive in app cache and return where it landed. With [includeDownloads],
     * chapters that already have a CBZ are packed into the archive as well.
     */
    suspend fun exportBackup(scope: BackupScope, includeDownloads: Boolean): AppResult<BackupExportResult>

    /**
     * Merge-import the archive at [archivePath] (an app-sandbox copy). Adds missing
     * mangas/chapters, ORs read/bookmark flags, keeps the newer read position, restores packed
     * downloads. Never deletes local data; re-running the same import is idempotent.
     */
    suspend fun importBackup(archivePath: String): AppResult<BackupImportResult>

    /** Delete a finished export artifact from cache after the picker hand-off. Best-effort. */
    suspend fun discardExportArtifact(archivePath: String)

    /** Request a cooperative stop; the in-flight item finishes to avoid torn writes. */
    fun stop()

    /** Reset the progress stream to the idle baseline (dialog dismissed). */
    fun clearProgress()
}
