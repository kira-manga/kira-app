package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaInspector
import okio.Path
import okio.Path.Companion.toPath

/**
 * Resolves a saved loose-page roster only when EVERY member is a bounded, native-validated image.
 * Current-container files are preferred; a bad current file can fall back to its valid stored path.
 * Missing/invalid/ambiguous input yields no local roster, never a filtered partial chapter. The caller
 * can then try a validated CBZ or source recovery without repeatedly returning the same bad files.
 */
class DownloadedPageFiles(
    private val appFileSystem: AppFileSystem,
    private val mediaInspector: PageMediaInspector,
    private val bytePolicy: PageBytePolicy = PageBytePolicy(),
) {
    /** Performs no deletion or Room mutation; originals remain available for explicit recovery. */
    suspend fun resolve(mangaId: Long, chapterId: Long, storedPaths: List<String>): List<Path>? {
        if (storedPaths.isEmpty()) return null
        val directory = appFileSystem.chapterDir(mangaId, chapterId)
        val resolved = mutableListOf<Path>()
        for (stored in storedPaths) {
            currentCoroutineContext().ensureActive()
            if (stored.isBlank()) return null
            val path = try {
                stored.toPath()
            } catch (_: IllegalArgumentException) {
                return null
            }
            val current = directory / path.name
            val readable = listOf(current, path).distinct().firstOrNull(::readable) ?: return null
            if (readable in resolved) return null
            resolved += readable
        }
        return resolved
    }

    private fun readable(path: Path): Boolean = try {
        val metadata = appFileSystem.fileSystem().metadataOrNull(path)
        if (metadata?.isRegularFile == true) {
            bytePolicy.checkFileSize(metadata.size)
            mediaInspector.inspect(path) is PageInspection.Valid
        } else {
            false
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // An unreadable local page is not a reason to hide the network/recovery path.
        false
    }
}
