package me.manga.kira.platform.cbz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.manga.kira.platform.backup.Crc32
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.readPageSnapshot
import me.manga.kira.platform.media.requireValid
import okio.BufferedSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
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
            EncodingOptions(quality, Int.MAX_VALUE, CbzWriter.DEFAULT_MAX_MEMORY_BYTES),
        )

    override suspend fun createCbzWithSplitting(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long,
    ): Path = archive(imagePaths, mangaId, chapterId, EncodingOptions(quality, maxHeight, maxMemoryBytes))

    private suspend fun archive(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        encoding: EncodingOptions,
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
        encoding: EncodingOptions,
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
        // Ownership starts only after exclusive creation succeeds; a collision/open failure must
        // not delete a pre-existing file at this path.
        val rawSink = system.sink(temporary, mustCreate = true)
        try {
            val entries = rawSink.buffer().use { sink -> writeArchive(sink, sources, encoding) }
            validateArchive(temporary, entries)
            currentCoroutineContext().ensureActive()
            // POSIX rename replaces an existing target atomically. A failed move is terminal: never
            // delete the previous archive to retry the move, and never fall back to a partial copy.
            system.atomicMove(temporary, destination)
        } finally {
            deleteOwnedFileQuietly(temporary)
        }
        // No fallible logging/callback after publication can turn this into an apparent loose-page
        // fallback. Cleanup is best-effort only after the complete archive is the durable artifact.
        deleteSourcesAfterCommit(sources)
        return destination
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
        encoding: EncodingOptions,
    ): List<ArchivedEntry> {
        val zip = StoreZipWriter(sink)
        val entries = mutableListOf<ArchivedEntry>()
        var acceptedInputs = 0
        sources.forEachIndexed { index, path ->
            currentCoroutineContext().ensureActive()
            if (index % YIELD_EVERY_N_PAGES == 0) yield()
            if (system.metadataOrNull(path)?.isRegularFile != true) {
                throw IOException("Missing CBZ source: ${path.name}")
            }
            val bytes = readPageSnapshot(system, path, sourceBytePolicy)
            val metadata = inspector.inspect(bytes).requireValid()
            val firstEntry = entries.size
            encoder.encode(
                bytes,
                metadata,
                encoding.quality,
                encoding.maxHeight,
                encoding.maxMemoryBytes,
            ) { extension, page ->
                currentCoroutineContext().ensureActive()
                entries += writeEncodedEntry(zip, entries.size, extension, page)
            }
            currentCoroutineContext().ensureActive()
            if (entries.size == firstEntry) throw IOException("CBZ input produced no entries")
            acceptedInputs++
        }
        check(acceptedInputs == sources.size) { "CBZ input count mismatch" }
        zip.finish()
        return entries
    }

    private fun writeEncodedEntry(
        zip: StoreZipWriter,
        index: Int,
        extension: String,
        page: ByteArray,
    ): ArchivedEntry {
        if (page.isEmpty()) throw IOException("CBZ encoder produced an empty page")
        val name = "page_${index.toString().padStart(PAGE_NUMBER_PAD_WIDTH, '0')}.$extension"
        val checksum = crc32(page)
        zip.writeEntry(name, page)
        return ArchivedEntry(name, page.size.toLong(), checksum)
    }

    /** Reopen and read the staged payloads; a readable directory alone cannot prove a complete ZIP. */
    private suspend fun validateArchive(
        temporary: Path,
        expected: List<ArchivedEntry>,
    ) {
        system.openZip(temporary).use { zip ->
            val root = "/".toPath()
            val names = zip.list(root).map { it.name }.toSet()
            if (names != expected.map { it.name }.toSet()) throw IOException("CBZ entry count mismatch")
            val buffer = ByteArray(VALIDATION_BUFFER_SIZE)
            expected.forEach { entry ->
                currentCoroutineContext().ensureActive()
                validateEntry(zip, root / entry.name, entry, buffer)
            }
        }
    }

    private suspend fun validateEntry(
        zip: FileSystem,
        path: Path,
        entry: ArchivedEntry,
        buffer: ByteArray,
    ) {
        val checksum = Crc32()
        var size = 0L
        zip.source(path).buffer().use { source ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read == -1) break
                size += read
                if (size > entry.size) throw IOException("CBZ entry size mismatch")
                checksum.update(buffer, length = read)
            }
        }
        if (size != entry.size || checksum.value != entry.checksum) throw IOException("CBZ entry payload mismatch")
    }

    private data class EncodingOptions(
        val quality: Int,
        val maxHeight: Int,
        val maxMemoryBytes: Long,
    )

    private data class ArchivedEntry(
        val name: String,
        val size: Long,
        val checksum: Int,
    )

    private companion object {
        const val YIELD_EVERY_N_PAGES = 2
        const val PAGE_NUMBER_PAD_WIDTH = 4
        const val VALIDATION_BUFFER_SIZE = 8192
    }
}

/** A normal return represents the entire input, never merely the bands written before a failure. */
internal fun interface IosCbzPageEncoder {
    suspend fun encode(
        source: ByteArray,
        metadata: PageImageMetadata,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long,
        emit: suspend (extension: String, bytes: ByteArray) -> Unit,
    )
}

internal object DefaultIosCbzPageEncoder : IosCbzPageEncoder by IosCbzPageTranscoder()

/** The toggle selects an encoder, never a more permissive validation or error-recovery policy. */
internal class IosCbzPageTranscoder(
    private val useLibWebp: Boolean = IosWebpEncoderFlags.USE_LIBWEBP,
    private val native: IosCbzNativeCodec = IosCbzNativeCodec(),
) : IosCbzPageEncoder {
    override suspend fun encode(
        source: ByteArray,
        metadata: PageImageMetadata,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long,
        emit: suspend (extension: String, bytes: ByteArray) -> Unit,
    ) {
        val result =
            if (useLibWebp) {
                IosLibWebpEncoder.encodeValidatedPage(source, metadata, quality, maxHeight, maxMemoryBytes, native) {
                    emit("webp", it)
                }
            } else {
                SkiaWebpEncoder.encodeValidatedPage(source, metadata, quality, maxHeight, maxMemoryBytes) {
                    emit("webp", it)
                }
            }
        currentCoroutineContext().ensureActive()
        when (result) {
            is CbzPageEncoding.Encoded -> check(result.bandCount > 0) { "CBZ codec produced no bands" }
            is CbzPageEncoding.PreserveOriginal -> emit(metadata.format.extension, source)
        }
    }
}
