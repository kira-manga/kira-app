package me.manga.kira.platform.cbz

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.media.PageMediaTestImages
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the production common archive reader with actual iOS ImageIO, not a codec fake. */
class IosCbzReaderValidationTest {
    private val system = FileSystem.SYSTEM
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ios-cbz-media-${Random.nextLong().toULong()}"
    private val files =
        object : AppFileSystem {
            override val filesDir: Path = root / "files"
            override val cacheDir: Path = root / "cache"

            override fun fileSystem(): FileSystem = system
        }
    private val reader = DefaultCbzReader(files, IosReaderTestDispatchers, IosPageMediaInspector())
    private val archive get() = reader.cbzPath(1, 2)

    @AfterTest
    fun cleanup() = system.deleteRecursively(root, mustExist = false)

    @Test
    fun invalidMemberCannotBecomeAPartialChapterOrMakeAnExistingCacheAuthoritative() =
        runTest {
            val old = files.cacheDir / "cbz_extract" / "1" / "2" / "old.jpg"
            system.createDirectories(requireNotNull(old.parent))
            system.write(old) { write(PageMediaTestImages.png()) }
            archive("0.jpg" to PageMediaTestImages.png(), "1.jpg" to PageMediaTestImages.corruptPngPixels())
            val original = system.read(archive) { readByteArray() }
            assertEquals(0, reader.pageCount(archive))
            assertTrue(reader.extractImages(archive, 1, 2).isEmpty())
            assertTrue(system.exists(old))
            assertContentEquals(original, system.read(archive) { readByteArray() })
            assertTrue(system.listRecursively(files.cacheDir).none { it.name.startsWith(".partial-") })
        }

    @Test
    fun validatedArchiveUsesActualSuffixAndNeverFlattensDuplicateZipBasenames() =
        runTest {
            archive("a/page.jpg" to PageMediaTestImages.png(), "b/page.jpg" to PageMediaTestImages.png())
            assertEquals(2, reader.pageCount(archive))
            val pages = reader.extractImages(archive, 1, 2)
            assertEquals(2, pages.distinct().size)
            assertTrue(pages.all { it.name.endsWith(".png") })
            pages.forEach { assertContentEquals(PageMediaTestImages.png(), system.read(it) { readByteArray() }) }
            assertTrue(system.listRecursively(files.cacheDir).none { it.name.startsWith(".partial-") })
        }

    private fun archive(vararg entries: Pair<String, ByteArray>) {
        system.createDirectories(requireNotNull(archive.parent))
        system.sink(archive).buffer().use { sink ->
            BackupZipWriter(sink).apply {
                entries.forEach { (name, bytes) -> writeEntryBytes(name, bytes) }
                finish()
            }
        }
    }
}

private object IosReaderTestDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
