package me.manga.kira.platform.cbz

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import okio.ByteString.Companion.decodeBase64
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSUUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun iosCbzTest(block: suspend CoroutineScope.(IosCbzTestFixture) -> Unit) =
    runTest {
        val fixture = IosCbzTestFixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

/** Tiny real images/files/ZIPs; native decoding is used only for modest test outputs. */
internal class IosCbzTestFixture {
    val system = FileSystem.SYSTEM
    val mangaId = 7L
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kira-cbz-${NSUUID().UUIDString}"

    init {
        system.createDirectories(root / "files")
    }

    fun fileSystem(delegate: FileSystem = system): AppFileSystem =
        object : AppFileSystem {
            override val filesDir: Path = root / "files"
            override val cacheDir: Path = root / "cache"

            override fun fileSystem(): FileSystem = delegate
        }

    fun directory(chapterId: Long = 1L): Path =
        fileSystem().chapterDir(mangaId, chapterId).also { system.createDirectories(it) }

    fun destination(chapterId: Long = 1L): Path = directory(chapterId) / "chapter_$chapterId.cbz"

    fun pages(count: Int = 2, chapterId: Long = 1L): List<Path> =
        List(count) { index ->
            (directory(chapterId) / "input_$index.png").also { path ->
                system.write(path) { write(IOS_CBZ_PNG) }
            }
        }

    fun previousArchive(chapterId: Long = 1L): ByteArray {
        system.sink(destination(chapterId)).buffer().use { sink ->
            StoreZipWriter(sink).apply {
                writeEntry("page_0000.png", IOS_CBZ_PNG)
                finish()
            }
        }
        return bytes(destination(chapterId))
    }

    fun bytes(path: Path): ByteArray = system.read(path) { readByteArray() }

    fun capture(paths: List<Path>): Map<Path, ByteArray?> =
        paths.associateWith { if (system.metadataOrNull(it)?.isRegularFile == true) bytes(it) else null }

    fun assertRetained(originals: Map<Path, ByteArray?>, previous: ByteArray, chapterId: Long = 1L) {
        originals.forEach { (path, before) ->
            if (before == null) assertFalse(system.exists(path)) else assertContentEquals(before, bytes(path))
        }
        assertContentEquals(previous, bytes(destination(chapterId)))
        assertNoTemporary(chapterId)
    }

    fun archiveEntries(chapterId: Long = 1L): List<Pair<String, ByteArray>> {
        val zip = system.openZip(destination(chapterId))
        return zip.list("/".toPath()).sortedBy { it.name }.map { path ->
            path.name to zip.read(path) { readByteArray() }
        }
    }

    fun assertWebpDimensions(expected: List<Pair<Int, Int>>, chapterId: Long = 1L) {
        val entries = archiveEntries(chapterId)
        assertEquals(expected.size, entries.size)
        entries.forEachIndexed { index, (name, data) ->
            assertEquals("page_${index.toString().padStart(4, '0')}.webp", name)
            val image = Image.makeFromEncoded(data)
            try {
                assertEquals(expected[index], image.width to image.height)
                // Force native pixels, not only an encoded-image wrapper or central-directory read.
                val decoded = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
                try {
                    assertTrue(decoded.bytes.isNotEmpty())
                } finally {
                    decoded.close()
                }
            } finally {
                image.close()
            }
        }
    }

    fun assertNoTemporary(chapterId: Long = 1L) {
        assertFalse(system.list(directory(chapterId)).any { it.name.endsWith(".cbz.tmp") })
    }

    fun close() = system.deleteRecursively(root)
}

/** Authored opaque 23x65 RGB PNG (lossless solid color); no bundled/user image data. */
internal val IOS_CBZ_PNG: ByteArray = checkNotNull(
    "iVBORw0KGgoAAAANSUhEUgAAABcAAABBCAIAAAC1n6gdAAAAMElEQVR4nO3MMQ0AAAgDsElCClLwfyGC8DXp3VTPXSwWi8VisVgsFovFYrFYLK/LAsCOedyN4Wo0AAAAAElFTkSuQmCC"
        .decodeBase64(),
).toByteArray()
