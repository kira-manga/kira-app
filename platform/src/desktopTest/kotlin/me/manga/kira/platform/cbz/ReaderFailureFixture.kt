package me.manga.kira.platform.cbz

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.PageMediaTestImages
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal fun readerFailureTest(block: suspend CoroutineScope.(ReaderFailureFixture) -> Unit) =
    runTest {
        val fixture = ReaderFailureFixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

/** Real two-page ZIP and a previous cache generation; only the named failure boundary is replaced. */
internal class ReaderFailureFixture {
    val system = FileSystem.SYSTEM
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "reader-failure-${Random.nextLong().toULong()}"
    private val cache = root / "cache" / "cbz_extract" / "1" / "2"
    private val previous = cache / "pages-previous" / "previous.png"
    val archive = root / "files" / "manga" / "1" / "chapter_2" / "chapter_2.cbz"
    private val native = DesktopPageMediaInspector()
    private val archiveBytes: ByteArray

    init {
        writeArchive()
        archiveBytes = system.read(archive) { readByteArray() }
        system.createDirectories(requireNotNull(previous.parent))
        system.write(previous) { write(PageMediaTestImages.png()) }
    }

    fun reader(
        access: () -> FileSystem = { system },
        media: PageMediaInspector = native,
    ): DefaultCbzReader =
        DefaultCbzReader(
            object : AppFileSystem {
                override val filesDir: Path = root / "files"
                override val cacheDir: Path = root / "cache"

                override fun fileSystem(): FileSystem = access()
            },
            ReaderFailureDispatchers,
            media,
        )

    fun failSecondInspection(failure: Throwable): PageMediaInspector =
        object : PageMediaInspector by native {
            private var calls = 0

            override fun inspect(encoded: ByteArray): PageInspection {
                if (++calls == 2) {
                    assertEquals(1, system.listRecursively(cache).count { it.name == "image_000000.png" })
                    throw failure
                }
                return native.inspect(encoded)
            }
        }

    fun assertOriginalsRetained() {
        assertContentEquals(archiveBytes, system.read(archive) { readByteArray() })
        assertContentEquals(PageMediaTestImages.png(), system.read(previous) { readByteArray() })
    }

    fun assertOnlyPreviousGeneration() {
        assertOriginalsRetained()
        assertEquals(listOf(requireNotNull(previous.parent)), system.list(cache))
    }

    private fun writeArchive() {
        system.createDirectories(requireNotNull(archive.parent))
        system.sink(archive).buffer().use { sink ->
            BackupZipWriter(sink).apply {
                repeat(2) { writeEntryBytes("$it.png", PageMediaTestImages.png()) }
                finish()
            }
        }
    }

    fun close() = system.deleteRecursively(root, mustExist = false)
}

internal class AfterReaderMoveFileSystem(
    delegate: FileSystem,
    private val afterMove: () -> Unit,
) : ForwardingFileSystem(delegate) {
    var destination: Path? = null
        private set

    override fun atomicMove(
        source: Path,
        target: Path,
    ) {
        super.atomicMove(source, target)
        destination = target
        afterMove()
    }
}

internal class ReaderRollbackFailureFileSystem(
    delegate: FileSystem,
    private val failure: Throwable,
) : ForwardingFileSystem(delegate) {
    val deletions = mutableListOf<Path>()

    override fun delete(
        path: Path,
        mustExist: Boolean,
    ) {
        deletions += path
        throw failure
    }
}

private object ReaderFailureDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
