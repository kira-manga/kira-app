package me.manga.kira.platform.cbz

import android.app.Application
import me.manga.kira.core.cbz.CbzHostArchiveOutput
import me.manga.kira.core.cbz.cbzHostTest
import me.manga.kira.platform.filesystem.AndroidAppFileSystem
import okio.Path.Companion.toPath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Existing native bitmap/ZIP fixture; host atomic move is not an Android device rename receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidCbzRetainedInputTest {
    @Test
    fun retainedSplittingKeepsEverySourceByteAndPublishesEveryBand() =
        cbzHostTest { fixture ->
            val paths = fixture.pages(width = PAGE_WIDTH, height = PAGE_HEIGHT)
            val originals = paths.associateWith { File(it).readBytes() }
            val writer: CbzWriter = AndroidCbzWriter(AndroidAppFileSystem(fixture.context), output = CbzHostArchiveOutput())

            val archive =
                writer.createCbzWithSplittingRetainingSources(
                    paths.map { it.toPath() },
                    fixture.mangaId,
                    1L,
                    maxHeight = BAND_HEIGHT,
                )

            assertEquals(fixture.destination().absolutePath, archive.toString())
            fixture.assertArchive(
                listOf(
                    PAGE_WIDTH to BAND_HEIGHT,
                    PAGE_WIDTH to PAGE_HEIGHT - BAND_HEIGHT,
                    PAGE_WIDTH + 1 to BAND_HEIGHT,
                    PAGE_WIDTH + 1 to PAGE_HEIGHT - BAND_HEIGHT,
                ),
            )
            originals.forEach { (path, bytes) -> assertContentEquals(bytes, File(path).readBytes()) }
            fixture.assertNoTemporary()
        }

    @Test
    fun staleInputRetryCannotReplaceTheSuccessfullyPublishedArchive() =
        cbzHostTest { fixture ->
            val paths = fixture.pages(width = PAGE_WIDTH, height = PAGE_HEIGHT)
            val output = CountingPublication()
            val writer: CbzWriter = AndroidCbzWriter(AndroidAppFileSystem(fixture.context), output = output)
            writer.createCbzWithSplittingRetainingSources(paths.map { it.toPath() }, fixture.mangaId, 1L)
            assertTrue(paths.all { File(it).isFile })
            val published = fixture.destination().readBytes()
            // Reconstruct stale saved inputs only, not a Room commit or a process-death claim.
            paths.forEach { check(File(it).delete()) }

            assertFailsWith<IOException> {
                writer.createCbzWithSplittingRetainingSources(paths.map { it.toPath() }, fixture.mangaId, 1L)
            }

            assertEquals(1, output.publications)
            assertContentEquals(published, fixture.destination().readBytes())
            fixture.assertArchive(listOf(PAGE_WIDTH to PAGE_HEIGHT, PAGE_WIDTH + 1 to PAGE_HEIGHT))
            fixture.assertNoTemporary()
        }

    private class CountingPublication : CbzHostArchiveOutput() {
        var publications = 0
            private set

        override fun publish(
            temporary: File,
            destination: File,
        ) {
            super.publish(temporary, destination)
            publications++
        }
    }

    private companion object {
        const val PAGE_WIDTH = 17
        const val PAGE_HEIGHT = 25
        const val BAND_HEIGHT = 16
    }
}
