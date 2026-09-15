package me.manga.kira.core.cbz

import me.manga.kira.platform.media.AndroidPageMediaInspector
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaInspector
import okio.Path
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Small host fixtures only; the real ZIP sink and host promotion do not qualify Android Os.rename. */
internal class CbzPolicyCase(
    private val fixture: CbzHostFixture,
    private val paths: List<String>,
    val decoder: CbzObservedDecoder = CbzObservedDecoder(),
) {
    val originals = paths.map { File(it).readBytes() }
    val progress = mutableListOf<Int>()
    val encodes = AtomicInteger()
    private val previous = fixture.priorArchive()
    private val publications = AtomicInteger()
    private val output =
        object : CbzHostArchiveOutput() {
            override fun publish(
                temporary: File,
                destination: File,
            ) {
                assertInputsAndPreviousPresent()
                publications.incrementAndGet()
                super.publish(temporary, destination)
            }
        }

    suspend fun convert(policy: CbzPagePolicy): String {
        val manager =
            OptimizedCbzManager(
                fixture.context,
                cbzTier(),
                decoder,
                output,
                pagePolicy = policy,
            ) { bitmap, format, quality, stream ->
                encodes.incrementAndGet()
                bitmap.compress(format, quality, stream)
            }
        return manager.createCbzParallel(paths, fixture.mangaId, 1L) { done, total ->
            assertEquals(paths.size, total)
            assertEquals(progress.size + 1, done)
            progress += done
            assertInputsAndPreviousPresent()
        }
    }

    fun assertCommitted(names: List<String>): List<ByteArray> {
        assertEquals(1, publications.get())
        assertEquals((1..paths.size).toList(), progress)
        assertTrue(paths.none { File(it).exists() })
        assertTrue(decoder.requested.none(File::exists))
        fixture.assertNoTemporary()
        return ZipFile(fixture.destination()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(names, entries.map { it.name })
            entries.map { entry -> zip.getInputStream(entry).use { it.readBytes() } }
        }
    }

    fun assertRolledBack() {
        assertEquals(0, publications.get())
        assertContentEquals(previous, fixture.destination().readBytes())
        paths.forEachIndexed { index, path -> assertContentEquals(originals[index], File(path).readBytes()) }
        assertTrue(decoder.requested.none(File::exists))
        fixture.assertNoTemporary()
    }

    fun assertNoTranscode() {
        assertTrue(decoder.requested.isEmpty())
        assertTrue(decoder.bitmaps.isEmpty())
        assertTrue(decoder.regions.isEmpty())
        assertEquals(0, encodes.get())
    }

    private fun assertInputsAndPreviousPresent() {
        assertTrue(paths.all { File(it).isFile })
        assertContentEquals(previous, fixture.destination().readBytes())
    }
}

/** Observes the private file, then delegates to real Android framing/sample inspection; no fake validity. */
internal class CbzSnapshotObserver(
    private val observe: (File) -> Unit = {},
) : PageMediaInspector {
    private val delegate = AndroidPageMediaInspector()
    val inspected = CopyOnWriteArrayList<File>()

    override fun inspect(encoded: ByteArray): PageInspection = error("CBZ manager must inspect its owned file snapshot")

    override fun inspect(path: Path): PageInspection {
        val snapshot = path.toFile()
        inspected += snapshot
        observe(snapshot)
        return delegate.inspect(path)
    }
}
