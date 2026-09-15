package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult

/** Import-picker artifact lifecycle, independent of whether a library import can start. */
interface BackupImportArtifactRepository {
    /**
     * Releases only an unclaimed, registered picker snapshot. Already-claimed or caller-owned
     * paths are no-ops, never prefix-matched deletions. Call from a non-cancellable cleanup scope.
     */
    suspend fun discardImportArtifact(archivePath: String): AppResult<Unit>
}
