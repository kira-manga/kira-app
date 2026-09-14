package me.manga.kira.platform.cbz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.PageMediaTestImages
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.buffer
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real ZIP/native-codec extraction: a successful result always includes every image candidate. */
class DefaultCbzReaderValidationTest {
    private val system = FileSystem.SYSTEM
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "cbz-validation-${Random.nextLong().toULong()}"
    private val cache = root / "cache" / "cbz_extract" / "1" / "2"
    private val archive = root / "chapter.cbz"
    private val inspector = DesktopPageMediaInspector()

    @AfterTest
    fun cleanup() = system.deleteRecursively(root, mustExist = false)

    @Test
    fun nestedDuplicateBasenamesPublishDistinctPagesWithNativeExtensionsAndNewGeneration() =
        runTest {
            writeArchive("a/page.jpg" to PageMediaTestImages.png(), "b/page.jpg" to PageMediaTestImages.png())
            val reader = reader()
            assertEquals(2, reader.pageCount(archive))
            val first = reader.extractImages(archive, 1, 2)
            val second = reader.extractImages(archive, 1, 2)
            assertEquals(2, first.size)
            assertEquals(2, first.distinct().size)
            assertTrue(first.all { it.name.endsWith(".png") })
            assertTrue(second.none { it in first }, "existence of a previous cache cannot bypass validation")
            (first + second).forEach { assertContentEquals(PageMediaTestImages.png(), system.read(it) { readByteArray() }) }
            assertNoPartials()
        }

    @Test
    fun oneInvalidImageRejectsTheWholeArchiveAndLeavesPreviousCacheAndSourceUntouched() =
        runTest {
            writeArchive("0.jpg" to PageMediaTestImages.png(), "1.jpg" to PageMediaTestImages.html())
            val source = system.read(archive) { readByteArray() }
            val previous = writeOldCache()
            val reader = reader()
            assertEquals(0, reader.pageCount(archive))
            repeat(2) { assertTrue(reader.extractImages(archive, 1, 2).isEmpty()) }
            assertContentEquals(source, system.read(archive) { readByteArray() })
            assertContentEquals(PageMediaTestImages.png(), system.read(previous) { readByteArray() })
            assertEquals(listOf(previous), system.listRecursively(cache).filter { system.metadata(it).isRegularFile }.toList())
            assertNoPartials()
        }

    @Test
    fun renameFailureCannotPublishASubsetOrDeletePreviousGenerations() =
        runTest {
            writeArchive("0.jpg" to PageMediaTestImages.png(), "1.jpg" to PageMediaTestImages.png())
            val previous = writeOldCache()
            val failing =
                object : ForwardingFileSystem(system) {
                    override fun atomicMove(
                        source: Path,
                        target: Path,
                    ): Unit = throw IOException("injected extraction publication failure")
                }
            assertTrue(reader(failing).extractImages(archive, 1, 2).isEmpty())
            assertTrue(system.exists(previous))
            assertTrue(system.exists(archive))
            assertNoPartials()
            assertEquals(listOf(previous), system.listRecursively(cache).filter { system.metadata(it).isRegularFile }.toList())
        }

    @Test
    fun cancellationAfterAnEarlierPageWasWrittenDeletesOnlyTheOwnedPartialGeneration() =
        runTest {
            writeArchive("0.jpg" to PageMediaTestImages.png(), "1.jpg" to PageMediaTestImages.png())
            val previous = writeOldCache()
            var calls = 0
            val cancelling =
                object : PageMediaInspector by inspector {
                    override fun inspect(encoded: ByteArray): PageInspection {
                        if (++calls == 2) throw CancellationException("cancel during next page")
                        return inspector.inspect(encoded)
                    }
                }
            assertFailsWith<CancellationException> { reader(media = cancelling).extractImages(archive, 1, 2) }
            assertEquals(2, calls)
            assertTrue(system.exists(previous))
            assertTrue(system.exists(archive))
            assertNoPartials()
        }

    @Test
    fun bytePolicyOrNoImagesNeverMakesACacheReadable() =
        runTest {
            writeArchive("0.jpg" to PageMediaTestImages.png())
            val reader = reader(policy = PageBytePolicy(PageMediaTestImages.png().size - 1L))
            assertEquals(0, reader.pageCount(archive))
            assertTrue(reader.extractImages(archive, 1, 2).isEmpty())
            writeArchive("ComicInfo.xml" to "metadata only".encodeToByteArray())
            assertEquals(0, reader().pageCount(archive))
            assertTrue(reader().extractImages(archive, 1, 2).isEmpty())
            assertNoPartials()
        }

    private fun reader(
        fileSystem: FileSystem = system,
        media: PageMediaInspector = inspector,
        policy: PageBytePolicy = PageBytePolicy(),
    ) = DefaultCbzReader(
        object : AppFileSystem {
            override val filesDir: Path = root / "files"
            override val cacheDir: Path = root / "cache"

            override fun fileSystem(): FileSystem = fileSystem
        },
        ReaderTestDispatchers,
        media,
        policy,
    )

    private fun writeOldCache(): Path {
        system.createDirectories(cache)
        val path = cache / "old-page.png"
        system.write(path) { write(PageMediaTestImages.png()) }
        return path
    }

    private fun writeArchive(vararg entries: Pair<String, ByteArray>) {
        system.createDirectories(root)
        system.sink(archive).buffer().use { sink ->
            BackupZipWriter(sink).apply {
                entries.forEach { (name, bytes) -> writeEntryBytes(name, bytes) }
                finish()
            }
        }
    }

    private fun assertNoPartials() {
        assertTrue(system.listRecursively(root).none { it.name.startsWith(".partial-") })
    }
}

private object ReaderTestDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
