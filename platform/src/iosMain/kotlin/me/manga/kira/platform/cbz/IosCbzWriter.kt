package me.manga.kira.platform.cbz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.readPageSnapshot
import me.manga.kira.platform.media.requireValid
import okio.BufferedSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use
import platform.Foundation.NSUUID

/**
 * Writes every requested page to an owned sibling ZIP before atomically replacing the final CBZ.
 * Missing/read/decode/encode failures abort the whole attempt, retaining the originals and any old
 * archive. Container-path rederivation remains supported; an absent page is never silently omitted.
 * The internal codec boundary permits deterministic faults without replacing filesystem/ZIP work.
 */
class IosCbzWriter internal constructor(
    private val fs: AppFileSystem,
    private val encoder: IosCbzPageEncoder,
    private val inspector: PageMediaInspector = IosPageMediaInspector(),
    private val sourceBytePolicy: PageBytePolicy = PageBytePolicy(),
) : CbzWriter {
    constructor(fs: AppFileSystem, inspector: PageMediaInspector = IosPageMediaInspector()) :
        this(fs, DefaultIosCbzPageEncoder, inspector)

    private val conversionMutex = Mutex()
    private val system: FileSystem get() = fs.fileSystem()

    override suspend fun createCbz(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
    ): Path =
        archive(
            imagePaths,
            mangaId,
            chapterId,
            CbzEncodingOptions(quality, Int.MAX_VALUE, CbzWriter.DEFAULT_MAX_MEMORY_BYTES),
        )

    override suspend fun createCbzWithSplitting(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long,
    ): Path = archive(imagePaths, mangaId, chapterId, CbzEncodingOptions(quality, maxHeight, maxMemoryBytes))

    private suspend fun archive(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        encoding: CbzEncodingOptions,
    ): Path =
        withContext(Dispatchers.Default) {
            conversionMutex.withLock {
                currentCoroutineContext().ensureActive()
                createArchive(imagePaths, mangaId, chapterId, encoding)
            }
        }

    private suspend fun createArchive(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        encoding: CbzEncodingOptions,
    ): Path {
        require(imagePaths.isNotEmpty()) { "No images to archive" }
        require(encoding.maxHeight > 0 && encoding.maxMemoryBytes > 0) { "Invalid CBZ splitting limits" }
        val chapterDir = fs.chapterDir(mangaId, chapterId)
        system.createDirectories(chapterDir)
        val destination = chapterDir / "chapter_$chapterId.cbz"
        val sources =
            imagePaths.map { path ->
                PagePathRederivation.resolveSourcePage(stored = path, chapterDir = chapterDir, exists = system::exists)
            }
        val temporary = chapterDir / ".chapter_$chapterId-${NSUUID().UUIDString}.cbz.tmp"
        publishArchive(sources, encoding, temporary, destination)
        // No fallible logging/callback after publication can turn this into an apparent loose-page
        // fallback. Cleanup is best-effort only after the complete archive is the durable artifact.
        deleteSourcesAfterCommit(sources)
        return destination
    }

    private suspend fun publishArchive(
        sources: List<Path>,
        encoding: CbzEncodingOptions,
        temporary: Path,
        destination: Path,
    ) {
        // Ownership starts only after exclusive creation succeeds; a collision/open failure must
        // not delete a pre-existing file at this path.
        val rawSink = system.sink(temporary, mustCreate = true)
        try {
            val entries = rawSink.buffer().use { sink -> writeArchive(sink, sources, encoding) }
            IosCbzArchiveVerifier.validate(system, temporary, entries)
            currentCoroutineContext().ensureActive()
            // POSIX rename replaces an existing target atomically. A failed move is terminal: never
            // delete the previous archive to retry the move, and never fall back to a partial copy.
            system.atomicMove(temporary, destination)
        } finally {
            deleteOwnedFileQuietly(temporary)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun deleteSourcesAfterCommit(sources: List<Path>) {
        try {
            sources.forEach { deleteOwnedFileQuietly(it) }
        } catch (_: Throwable) {
            // Even cleanup iteration must not turn a committed archive into an apparent failure.
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun deleteOwnedFileQuietly(path: Path) {
        try {
            system.delete(path, mustExist = false)
        } catch (_: Throwable) {
            // Only our temp or post-commit loose inputs, never the previous/final archive.
        }
    }

    private suspend fun writeArchive(
        sink: BufferedSink,
        sources: List<Path>,
        encoding: CbzEncodingOptions,
    ): List<ArchivedCbzEntry> {
        val zip = StoreZipWriter(sink)
        val entries = mutableListOf<ArchivedCbzEntry>()
        var acceptedInputs = 0
        sources.forEachIndexed { index, path ->
            currentCoroutineContext().ensureActive()
            if (index % YIELD_EVERY_N_PAGES == 0) yield()
            writeSourceEntries(path, zip, entries, encoding)
            acceptedInputs++
        }
        check(acceptedInputs == sources.size) { "CBZ input count mismatch" }
        zip.finish()
        return entries
    }

    private suspend fun writeSourceEntries(
        path: Path,
        zip: StoreZipWriter,
        entries: MutableList<ArchivedCbzEntry>,
        encoding: CbzEncodingOptions,
    ) {
        if (system.metadataOrNull(path)?.isRegularFile != true) {
            throw IOException("Missing CBZ source: ${path.name}")
        }
        val bytes = readPageSnapshot(system, path, sourceBytePolicy)
        val page = ValidatedCbzPage(bytes, inspector.inspect(bytes).requireValid())
        val firstEntry = entries.size
        encoder.encode(page, encoding) { extension, encoded ->
            currentCoroutineContext().ensureActive()
            entries += writeEncodedEntry(zip, entries.size, extension, encoded)
        }
        currentCoroutineContext().ensureActive()
        if (entries.size == firstEntry) throw IOException("CBZ input produced no entries")
    }

    private fun writeEncodedEntry(
        zip: StoreZipWriter,
        index: Int,
        extension: String,
        page: ByteArray,
    ): ArchivedCbzEntry {
        if (page.isEmpty()) throw IOException("CBZ encoder produced an empty page")
        val name = "page_${index.toString().padStart(PAGE_NUMBER_PAD_WIDTH, '0')}.$extension"
        val checksum = crc32(page)
        zip.writeEntry(name, page)
        return ArchivedCbzEntry(name, page.size.toLong(), checksum)
    }

    private companion object {
        const val YIELD_EVERY_N_PAGES = 2
        const val PAGE_NUMBER_PAD_WIDTH = 4
    }
}
