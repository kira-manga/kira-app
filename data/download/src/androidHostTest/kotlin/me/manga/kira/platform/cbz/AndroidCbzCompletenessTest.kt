package me.manga.kira.platform.cbz

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import me.manga.kira.core.cbz.CbzEncodeGate
import me.manga.kira.core.cbz.CbzHostArchiveOutput
import me.manga.kira.core.cbz.CbzHostFixture
import me.manga.kira.core.cbz.cbzHostTest
import me.manga.kira.platform.filesystem.AndroidAppFileSystem
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.AndroidPageMediaInspector
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaInspector
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real Android bitmap/ZIP path with explicit I/O faults; host rename is not Android Os.rename proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidCbzCompletenessTest {
    @Test
    fun missingUnreadableAndCorruptSourcesNeverPublishPartialArchive() =
        cbzHostTest { fixture ->
            SourceFault.entries.forEach { fault ->
                val chapter = fault.ordinal + 1L
                val paths = fixture.pages(chapter = chapter)
                val bad = File(paths.last())
                when (fault) {
                    SourceFault.MISSING -> check(bad.delete())
                    SourceFault.CORRUPT -> bad.writeText("not an image")
                    SourceFault.UNREADABLE -> Unit
                }
                val originals = captureSources(paths)
                val previous = fixture.priorArchive(chapter)
                val fileSystem = unreadableFileSystem(fixture, bad, fault == SourceFault.UNREADABLE)
                var encoded = 0
                val writer =
                    AndroidCbzWriter(
                        fileSystem,
                        output = CbzHostArchiveOutput(),
                        encode = { bitmap, format, quality, output ->
                            encoded++
                            bitmap.compress(format, quality, output)
                        },
                    )

                assertFailsWith<IOException> {
                    if (fault == SourceFault.UNREADABLE) {
                        writer.createCbzWithSplitting(
                            paths.map { it.toPath() },
                            fixture.mangaId,
                            chapter,
                            maxHeight = 16,
                        )
                    } else {
                        writer.createCbz(paths.map { it.toPath() }, fixture.mangaId, chapter)
                    }
                }

                assertTrue(encoded > 0, "the first input must reach the staged ZIP before the later failure")
                assertRetained(fixture, chapter, originals, previous)
            }
        }

    @Test
    fun falseEncodeAndLateCropFailureAbortAnAlreadyWrittenBand() =
        cbzHostTest { fixture ->
            listOf(false, true).forEachIndexed { index, failCrop ->
                val chapter = index + 1L
                val paths = fixture.pages(count = 1, height = 67, chapter = chapter)
                val originals = captureSources(paths)
                val previous = fixture.priorArchive(chapter)
                val bitmaps = mutableListOf<Bitmap>()
                val decoder = cropFailureDecoder(failCrop, bitmaps)
                var encoded = 0
                val writer =
                    AndroidCbzWriter(
                        AndroidAppFileSystem(fixture.context),
                        decoder = decoder,
                        output = CbzHostArchiveOutput(),
                        encode = { bitmap, format, quality, output ->
                            encoded++
                            val written = bitmap.compress(format, quality, output)
                            written && (failCrop || encoded != 2)
                        },
                    )

                assertFailsWith<IOException> {
                    writer.createCbzWithSplitting(paths.map { it.toPath() }, fixture.mangaId, chapter, maxHeight = 32)
                }

                assertTrue(encoded >= 1)
                assertTrue(bitmaps.isNotEmpty() && bitmaps.all { it.isRecycled })
                assertRetained(fixture, chapter, originals, previous)
            }
        }

    @Test
    fun oneInputCanProduceSeveralEntriesWithoutEarlySourceDeletion() =
        cbzHostTest { fixture ->
            val paths = fixture.pages(count = 1, width = SPLIT_PAGE_WIDTH, height = SPLIT_PAGE_HEIGHT)
            var published = false
            val output =
                object : CbzHostArchiveOutput() {
                    override fun publish(
                        temporary: File,
                        destination: File,
                    ) {
                        assertTrue(paths.all { File(it).isFile })
                        published = true
                        super.publish(temporary, destination)
                    }
                }
            val writer = AndroidCbzWriter(AndroidAppFileSystem(fixture.context), output = output)

            val archive =
                writer.createCbzWithSplitting(
                    paths.map { it.toPath() },
                    fixture.mangaId,
                    1L,
                    maxHeight = SPLIT_BAND_HEIGHT,
                )

            assertTrue(published)
            assertEquals(fixture.destination().absolutePath, archive.toString())
            fixture.assertArchive(
                listOf(
                    SPLIT_PAGE_WIDTH to SPLIT_BAND_HEIGHT,
                    SPLIT_PAGE_WIDTH to SPLIT_BAND_HEIGHT,
                    SPLIT_PAGE_WIDTH to SPLIT_TAIL_HEIGHT,
                ),
            )
            assertTrue(paths.none { File(it).exists() })
            fixture.assertNoTemporary()
        }

    @Test
    fun decodingUsesTheExactValidatedSnapshotRatherThanReopeningTheSource() =
        cbzHostTest { fixture ->
            val paths = fixture.pages(count = 1)
            var inspected: ByteArray? = null
            var decoded = false
            val nativeInspector = AndroidPageMediaInspector()
            val inspector =
                object : PageMediaInspector by nativeInspector {
                    override fun inspect(encoded: ByteArray): PageInspection = nativeInspector.inspect(encoded).also { inspected = encoded }
                }
            val decoder =
                object : AndroidCbzImageDecoder() {
                    override fun decode(source: ByteArray): Bitmap? {
                        assertSame(assertNotNull(inspected), source)
                        decoded = true
                        return super.decode(source)
                    }
                }
            val writer =
                AndroidCbzWriter(
                    AndroidAppFileSystem(fixture.context),
                    decoder,
                    CbzHostArchiveOutput(),
                    inspector = inspector,
                )

            writer.createCbz(paths.map { it.toPath() }, fixture.mangaId, 1L)

            assertTrue(decoded)
            fixture.assertNoTemporary()
        }

    @Test
    fun wrongCropDimensionsCannotRepresentARequestedBand() =
        cbzHostTest { fixture ->
            val paths = fixture.pages(count = 1, height = 67)
            val originals = captureSources(paths)
            val previous = fixture.priorArchive()
            val bitmaps = mutableListOf<Bitmap>()
            val decoder =
                object : AndroidCbzImageDecoder() {
                    override fun decode(source: ByteArray): Bitmap? = super.decode(source)?.also { bitmaps += it }

                    override fun crop(
                        parent: Bitmap,
                        region: Rect,
                    ): Bitmap = parent
                }
            val writer = AndroidCbzWriter(AndroidAppFileSystem(fixture.context), decoder, CbzHostArchiveOutput())

            assertFailsWith<IOException> {
                writer.createCbzWithSplitting(paths.map { it.toPath() }, fixture.mangaId, 1L, maxHeight = 24)
            }

            assertTrue(bitmaps.isNotEmpty() && bitmaps.all { it.isRecycled })
            assertRetained(fixture, 1L, originals, previous)
        }

    @Test
    fun failedAtomicReplacementPreservesThePreviousArchiveAndSources() =
        cbzHostTest { fixture ->
            val paths = fixture.pages()
            val originals = captureSources(paths)
            val previous = fixture.priorArchive()
            val output =
                object : CbzHostArchiveOutput() {
                    override fun publish(
                        temporary: File,
                        destination: File,
                    ): Unit = throw IOException("rename denied")
                }
            val writer = AndroidCbzWriter(AndroidAppFileSystem(fixture.context), output = output)

            assertFailsWith<IOException> { writer.createCbz(paths.map { it.toPath() }, fixture.mangaId, 1L) }

            assertRetained(fixture, 1L, originals, previous)
        }

    @Test
    fun cancellationAfterNativeEncodeReturnsCannotPublishOrDeleteSources() =
        cbzHostTest { fixture ->
            val paths = fixture.pages(count = 1)
            val originals = captureSources(paths)
            val previous = fixture.priorArchive()
            CbzEncodeGate().use { gate ->
                var bitmap: Bitmap? = null
                val writer =
                    AndroidCbzWriter(
                        AndroidAppFileSystem(fixture.context),
                        output = CbzHostArchiveOutput(),
                        encode = { page, format, quality, output ->
                            bitmap = page
                            val written = page.compress(format, quality, output)
                            gate.hold()
                            written
                        },
                    )
                val conversion = async { writer.createCbz(paths.map { it.toPath() }, fixture.mangaId, 1L) }
                try {
                    gate.awaitEntry()
                    conversion.cancel(CancellationException("conversion cancelled"))
                    gate.close()
                    assertFailsWith<CancellationException> { conversion.await() }
                } finally {
                    gate.close()
                    conversion.cancelAndJoin()
                }
                assertTrue(assertNotNull(bitmap).isRecycled)
            }
            assertRetained(fixture, 1L, originals, previous)
        }

    @Test
    fun validatedOverBudgetPageIsPreservedExactlyWithoutAFullTranscodeDecode() =
        cbzHostTest { fixture ->
            val paths = fixture.pages(count = 1)
            val original = File(paths.single()).readBytes()
            val decoder =
                object : AndroidCbzImageDecoder() {
                    override fun decode(source: ByteArray): Bitmap? = error("budget denial must precede full decode")
                }
            val writer = AndroidCbzWriter(AndroidAppFileSystem(fixture.context), decoder, CbzHostArchiveOutput())

            writer.createCbzWithSplitting(paths.map { it.toPath() }, fixture.mangaId, 1L, maxMemoryBytes = 1)

            ZipFile(fixture.destination()).use { zip ->
                assertEquals(1, zip.size())
                val entry = zip.entries().nextElement()
                assertEquals("page_0000.png", entry.name)
                assertContentEquals(original, zip.getInputStream(entry).use { it.readBytes() })
            }
            assertFalse(File(paths.single()).exists())
            fixture.assertNoTemporary()
        }

    @Test
    fun invalidOverBudgetPageCannotBeBlessedAsVerbatim() =
        cbzHostTest { fixture ->
            val paths = fixture.pages(count = 1)
            File(paths.single()).writeText("invalid PNG payload")
            val originals = captureSources(paths)
            val previous = fixture.priorArchive()
            val writer = AndroidCbzWriter(AndroidAppFileSystem(fixture.context), output = CbzHostArchiveOutput())

            assertFailsWith<IOException> {
                writer.createCbzWithSplitting(paths.map { it.toPath() }, fixture.mangaId, 1L, maxMemoryBytes = 1)
            }

            assertRetained(fixture, 1L, originals, previous)
        }
}

private const val SPLIT_PAGE_WIDTH = 23
private const val SPLIT_PAGE_HEIGHT = 65
private const val SPLIT_BAND_HEIGHT = 24
private const val SPLIT_TAIL_HEIGHT = 17

private enum class SourceFault { MISSING, UNREADABLE, CORRUPT }

private fun unreadableFileSystem(
    fixture: CbzHostFixture,
    bad: File,
    failRead: Boolean,
): AppFileSystem {
    val base = AndroidAppFileSystem(fixture.context)
    val system =
        object : ForwardingFileSystem(base.fileSystem()) {
            override fun source(file: Path): Source {
                if (failRead && file.toString() == bad.absolutePath) throw IOException("source read denied")
                return super.source(file)
            }
        }
    return object : AppFileSystem by base {
        override fun fileSystem(): FileSystem = system
    }
}

private fun cropFailureDecoder(
    failCrop: Boolean,
    bitmaps: MutableList<Bitmap>,
): AndroidCbzImageDecoder =
    object : AndroidCbzImageDecoder() {
        private var crops = 0

        override fun decode(source: ByteArray): Bitmap? = super.decode(source)?.also { bitmaps += it }

        override fun crop(
            parent: Bitmap,
            region: Rect,
        ): Bitmap {
            crops++
            if (failCrop && crops == 2) throw IOException("second band failed")
            return super.crop(parent, region).also { bitmaps += it }
        }
    }

private fun captureSources(paths: List<String>): Map<String, ByteArray?> =
    paths.associateWith { File(it).takeIf(File::isFile)?.readBytes() }

private fun assertRetained(
    fixture: CbzHostFixture,
    chapter: Long,
    originals: Map<String, ByteArray?>,
    previous: ByteArray,
) {
    originals.forEach { (path, bytes) ->
        if (bytes == null) assertFalse(File(path).exists()) else assertContentEquals(bytes, File(path).readBytes())
    }
    assertContentEquals(previous, fixture.destination(chapter).readBytes())
    fixture.assertNoTemporary(chapter)
}
