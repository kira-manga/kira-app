package me.manga.kira.platform.cbz

import co.touchlab.kermit.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.inspectPageArchive
import me.manga.kira.platform.media.visitValidatedArchivePages
import okio.Closeable
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use
import kotlin.random.Random

/**
 * Bounded, native-validated CBZ reading on every target. Neither names nor an existing extraction
 * cache establish readability. Each successful extraction publishes a new complete generation;
 * invalid/read/policy failures leave previous cache generations and the original archive untouched.
 * A legacy CBZ without an input/split manifest still cannot prove the original source-page roster.
 */
class DefaultCbzReader(
    private val fs: AppFileSystem,
    private val dispatchers: DispatcherProvider,
    private val mediaInspector: PageMediaInspector,
    private val bytePolicy: PageBytePolicy = PageBytePolicy(),
) : CbzReader {
    private val log = Logger.withTag("CbzReader")
    private val system: FileSystem get() = fs.fileSystem()

    override fun cbzPath(
        mangaId: Long,
        chapterId: Long,
    ): Path = fs.chapterDir(mangaId, chapterId) / "chapter_$chapterId.cbz"

    override fun cbzExists(
        mangaId: Long,
        chapterId: Long,
    ): Boolean = system.exists(cbzPath(mangaId, chapterId))

    override suspend fun pageCount(cbzPath: Path): Int =
        withContext(dispatchers.io) {
            recoverLegacyFailure(0, "Archive did not contain a complete validated image set") {
                inspectPageArchive(system, cbzPath, mediaInspector, bytePolicy)
            }
        }

    override suspend fun extractImages(
        cbzPath: Path,
        mangaId: Long,
        chapterId: Long,
    ): List<Path> =
        withContext(dispatchers.io) {
            recoverLegacyFailure(emptyList(), "Archive extraction rejected; no partial page set published") {
                extractValidated(cbzPath, extractRoot(mangaId, chapterId))
            }
        }

    private suspend fun extractValidated(
        archive: Path,
        root: Path,
    ): List<Path> {
        system.createDirectories(root)
        val generation = "${Random.nextLong().toULong()}-${Random.nextLong().toULong()}"
        val temporary = root / ".partial-$generation"
        val destination = root / "pages-$generation"
        if (system.exists(destination)) throw IOException("Extraction generation already exists")
        return ExtractionGeneration(system, temporary).use { generationOwner ->
            val names = writeValidatedPages(archive, temporary)
            currentCoroutineContext().ensureActive()
            generationOwner.publishTo(destination)
            currentCoroutineContext().ensureActive()
            names.map { destination / it }.also { generationOwner.accept() }
        }
    }

    private suspend fun writeValidatedPages(
        archive: Path,
        directory: Path,
    ): List<String> {
        val names = mutableListOf<String>()
        visitValidatedArchivePages(system, archive, mediaInspector, bytePolicy) { _, bytes, metadata ->
            // Never flatten attacker-controlled ZIP names: nested duplicate basenames cannot overwrite
            // each other. Ordering is the archive visitor's stable order; suffix is the actual format.
            val name = "image_${names.size.toString().padStart(PAGE_INDEX_WIDTH, '0')}.${metadata.format.extension}"
            system.sink(directory / name, mustCreate = true).buffer().use { it.write(bytes) }
            names += name
        }
        return names
    }

    override suspend fun deleteCbz(
        mangaId: Long,
        chapterId: Long,
    ): Boolean =
        withContext(dispatchers.io) {
            val target = cbzPath(mangaId, chapterId)
            recoverLegacyFailure(false, "Could not delete archive") {
                if (!system.exists(target)) {
                    false
                } else {
                    system.delete(target)
                    true
                }
            }
        }

    override suspend fun cleanupExtractedCache(
        mangaId: Long,
        chapterId: Long,
    ) = withContext(dispatchers.io) {
        recoverLegacyFailure(Unit, "Could not remove archive extraction cache") {
            system.deleteRecursively(extractRoot(mangaId, chapterId), mustExist = false)
        }
    }

    /**
     * Only these four legacy reader APIs convert failures to sentinels. Compatibility includes
     * every Exception, not merely IOException; cancellation must escape unchanged, as must every
     * non-Exception Throwable. The core primitive supplies cancellation-aware capture, not a new
     * public Result API or a general platform fallback policy. Logging failures still propagate.
     */
    private inline fun <T> recoverLegacyFailure(
        fallback: T,
        message: String,
        operation: () -> T,
    ): T =
        runCatchingCancellable(operation).getOrElse { failure ->
            if (failure !is Exception) throw failure
            log.w(failure) { message }
            fallback
        }

    private fun extractRoot(
        mangaId: Long,
        chapterId: Long,
    ): Path = fs.cacheDir / "cbz_extract" / mangaId.toString() / chapterId.toString()

    /** Exclusive ownership follows the successful move; use preserves primary/suppressed errors. */
    private class ExtractionGeneration(
        private val system: FileSystem,
        temporary: Path,
    ) : Closeable {
        private var directory = temporary
        private var accepted = false

        init {
            system.createDirectory(temporary, mustCreate = true)
        }

        fun publishTo(destination: Path) {
            system.atomicMove(directory, destination)
            directory = destination
        }

        fun accept() {
            accepted = true
        }

        override fun close() {
            if (!accepted) system.deleteRecursively(directory, mustExist = false)
        }
    }

    private companion object {
        const val PAGE_INDEX_WIDTH = 6
    }
}
