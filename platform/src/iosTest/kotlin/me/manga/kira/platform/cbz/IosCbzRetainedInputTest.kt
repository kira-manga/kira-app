package me.manga.kira.platform.cbz

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Actual iOS writer/native encoder over the existing small file-backed fixture; no Room or OS-kill claim. */
class IosCbzRetainedInputTest {
    @Test
    fun retainedSplittingPreservesEveryRederivedSourceAndPublishesEveryBand() =
        iosCbzTest { fixture ->
            val live = fixture.pages()
            val stored = live.map { "/old-container/Documents/${it.name}".toPath() }
            val originals = fixture.capture(live)
            val writer: CbzWriter = IosCbzWriter(fixture.fileSystem())

            val archive =
                writer.createCbzWithSplittingRetainingSources(
                    stored,
                    fixture.mangaId,
                    1L,
                    maxHeight = BAND_HEIGHT,
                )

            assertEquals(fixture.destination(), archive)
            fixture.assertWebpDimensions(
                List(live.size) {
                    listOf(PAGE_WIDTH to BAND_HEIGHT, PAGE_WIDTH to BAND_HEIGHT, PAGE_WIDTH to TAIL_HEIGHT)
                }.flatten(),
            )
            originals.forEach { (path, bytes) -> assertContentEquals(bytes, fixture.bytes(path)) }
            fixture.assertNoTemporary()
        }

    @Test
    fun staleInputRetryCannotReplaceTheSuccessfullyPublishedArchive() =
        iosCbzTest { fixture ->
            val live = fixture.pages()
            val stored = live.map { "/old-container/Documents/${it.name}".toPath() }
            val system = CountingPublication(fixture.system)
            val writer: CbzWriter = IosCbzWriter(fixture.fileSystem(system))
            writer.createCbzWithSplittingRetainingSources(stored, fixture.mangaId, 1L)
            assertTrue(live.all { fixture.system.exists(it) })
            val published = fixture.bytes(fixture.destination())
            // Reconstruct the old all-stale shape without pretending to repair metadata here.
            live.forEach { fixture.system.delete(it) }

            assertFailsWith<IOException> {
                writer.createCbzWithSplittingRetainingSources(stored, fixture.mangaId, 1L)
            }

            assertEquals(1, system.publications)
            assertContentEquals(published, fixture.bytes(fixture.destination()))
            fixture.assertWebpDimensions(List(live.size) { PAGE_WIDTH to PAGE_HEIGHT })
            fixture.assertNoTemporary()
        }

    private class CountingPublication(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        var publications = 0
            private set

        override fun atomicMove(
            source: Path,
            target: Path,
        ) {
            super.atomicMove(source, target)
            publications++
        }
    }

    private companion object {
        const val PAGE_WIDTH = 23
        const val PAGE_HEIGHT = 65
        const val BAND_HEIGHT = 24
        const val TAIL_HEIGHT = PAGE_HEIGHT - 2 * BAND_HEIGHT
    }
}
