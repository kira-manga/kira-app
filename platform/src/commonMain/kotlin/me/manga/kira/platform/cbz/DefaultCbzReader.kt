package me.manga.kira.platform.cbz

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.inspectPageArchive
import me.manga.kira.platform.media.visitValidatedArchivePages
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
            try {
                inspectPageArchive(system, cbzPath, mediaInspector, bytePolicy)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.w(failure) { "Archive did not contain a complete validated image set" }
                0
            }
        }

    override suspend fun extractImages(
        cbzPath: Path,
        mangaId: Long,
        chapterId: Long,
    ): List<Path> =
        withContext(dispatchers.io) {
            try {
                extractValidated(cbzPath, extractRoot(mangaId, chapterId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.w(failure) { "Archive extraction rejected; no partial page set published" }
                emptyList()
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
        system.createDirectory(temporary, mustCreate = true)
        var ownedDirectory = temporary
        try {
            val names = writeValidatedPages(archive, temporary)
            currentCoroutineContext().ensureActive()
            system.atomicMove(temporary, destination)
            ownedDirectory = destination
            currentCoroutineContext().ensureActive()
            return names.map { destination / it }
        } catch (failure: Throwable) {
            discardOwnedGeneration(ownedDirectory, failure)
            throw failure
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

    private fun discardOwnedGeneration(
        path: Path,
        failure: Throwable,
    ) {
        try {
            system.deleteRecursively(path, mustExist = false)
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
    }

    override suspend fun deleteCbz(
        mangaId: Long,
        chapterId: Long,
    ): Boolean =
        withContext(dispatchers.io) {
            val target = cbzPath(mangaId, chapterId)
            try {
                if (!system.exists(target)) {
                    false
                } else {
                    system.delete(target)
                    true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.w(failure) { "Could not delete archive" }
                false
            }
        }

    override suspend fun cleanupExtractedCache(
        mangaId: Long,
        chapterId: Long,
    ) = withContext(dispatchers.io) {
        try {
            system.deleteRecursively(extractRoot(mangaId, chapterId), mustExist = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.w(failure) { "Could not remove archive extraction cache" }
        }
    }

    private fun extractRoot(
        mangaId: Long,
        chapterId: Long,
    ): Path = fs.cacheDir / "cbz_extract" / mangaId.toString() / chapterId.toString()

    private companion object {
        const val PAGE_INDEX_WIDTH = 6
    }
}
