package me.manga.kira.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.dao.ChapterArtifactRepairDao
import me.manga.kira.platform.filesystem.AppFileSystem

/** Missing bytes are not active-work policy. Unknown I/O/format/ownership always defers repair. */
internal class MissingDownloadMetadataRepair(
    private val records: ChapterArtifactRepairDao,
    files: AppFileSystem,
    private val artifacts: ChapterArtifacts,
) {
    private val missingFiles = MissingDownloadedFiles(files)

    suspend fun reconcile(): Int {
        val candidates = runCatchingCancellable { records.candidateIds() }.getOrElse { return 1 }
        var failures = 0
        for (chapterId in candidates) {
            currentCoroutineContext().ensureActive()
            val result = runCatchingCancellable {
                artifacts.read(chapterId) { pinned ->
                    val expected = records.snapshot(chapterId) ?: return@read
                    if (expected.artifact != pinned) return@read
                    if (withContext(platformIoDispatcher) { missingFiles.provenMissing(expected) }) {
                        currentCoroutineContext().ensureActive()
                        records.clearMissing(expected)
                    }
                }
            }
            if (result.isFailure) failures++
        }
        return failures
    }
}
