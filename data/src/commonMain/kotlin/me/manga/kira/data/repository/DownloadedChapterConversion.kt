package me.manga.kira.data.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.Path.Companion.toPath

/**
 * The I/O dependencies of Settings' existing-download conversion, separate from preferences/cache.
 * Chapters select the loose-page inputs, manga supplies progress titles, downloads protects active
 * work and stores refreshed sizes, and archives/files publish and measure the converted output.
 * The repository retains progress, cancellation, per-chapter error isolation, and all write ordering.
 */
class DownloadedChapterConversion(
    val chapters: ChapterDao,
    val archives: CbzWriter,
    val manga: MangaDao,
    val downloads: ChapterDownloadDao,
    val files: AppFileSystem,
    private val artifacts: ChapterArtifacts,
    private val commits: ChapterArtifactCommitDao,
) {
    /** Recheck admission, then pin the existing codec and its checked metadata publication. */
    suspend fun convert(chapter: SavedChapterEntity): Boolean {
        val claim = artifacts.beginConversion(chapter) ?: return false
        try {
            return artifacts.files(claim) {
                val archive = archives.createCbzWithSplitting(
                    imagePaths = chapter.localImagePaths.map { it.toPath() },
                    mangaId = chapter.mangaId,
                    chapterId = chapter.id,
                )
                val metadata = files.fileSystem().metadata(archive)
                val size = checkNotNull(metadata.size)
                check(metadata.isRegularFile && size > 0)
                artifacts.publish(claim) { commits.commitConversion(claim, chapter, listOf(archive.toString()), size) } == true
            } == true
        } finally {
            withContext(NonCancellable) {
                try {
                    // Never infer rollback from a thrown/cancelled Room return or delete output.
                    // App57's codec/metadata atomicity is intentionally not redesigned here.
                    artifacts.settle(claim) { true }
                } catch (_: Exception) {
                    // Retained custody is settled at startup; no speculative compensation.
                }
            }
        }
    }
}
