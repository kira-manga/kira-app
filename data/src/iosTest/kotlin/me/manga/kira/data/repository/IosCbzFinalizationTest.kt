package me.manga.kira.data.repository

import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.cbz.IosCbzWriter
import me.manga.kira.platform.media.PageImageFormat
import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real iOS Room→finalizer→native writer. This is not a jetsam/BG-task or process-kill simulation. */
class IosCbzFinalizationTest {
    @Test
    fun budgetPreservationCompletesTheExpectedRosterAndReopensWithoutReencoding() =
        runTest {
            val fixture = IosCbzFinalizationFixture()
            try {
                val original = fixture.seed()
                assertEquals(2, original.pages.size)
                val paths = original.pages.keys.map { it.toString() }
                val nativeWriter = IosCbzWriter(fixture.appFileSystem)
                val writer = CountingIosCbzWriter(nativeWriter)
                val finalizer = fixture.finalizer(writer)

                assertTrue(finalizer.markReadable(original.download, paths))
                assertEquals(paths, fixture.saved(original).localImagePaths)
                assertTrue(original.pages.keys.all { fixture.system.exists(it) })
                finalizer.finalize(original.download, paths)

                val archive = fixture.archive(original)
                assertEquals(1, writer.conversions)
                assertEquals(listOf(archive.toString()), fixture.saved(original).localImagePaths)
                assertTrue(fixture.saved(original).isDownloaded)
                assertEquals(DownloadingState.SUCCESS, fixture.download(original).state)
                assertEquals(100, fixture.download(original).progress)
                assertEquals(fixture.system.metadata(archive).size, fixture.download(original).sizeBytes)
                assertTrue(original.pages.keys.none { fixture.system.exists(it) })
                assertFalse(fixture.system.list(checkNotNull(archive.parent)).any { it.name.endsWith(".cbz.tmp") })
                fixture.assertArchive(
                    original,
                    expected =
                        listOf(
                            PageImageMetadata(PageImageFormat.PNG, 512, 16_384),
                            PageImageMetadata(PageImageFormat.WEBP, 8, 8),
                        ),
                )

                fixture.reopen()
                assertEquals(DownloadingState.SUCCESS, fixture.download(original).state)
                assertEquals(listOf(archive.toString()), fixture.saved(original).localImagePaths)

                // Persist the existing post-publication recovery shape: complete archive, removed loose
                // files, stale loose references/COMPRESSING checkpoint. This models the on-disk shape,
                // not a real process kill or the background repository's scheduling behavior.
                fixture.db.backupDao().updateChapterRow(original.saved.copy(isDownloaded = true))
                fixture.dao.updateStateChId(original.download.chapterId, DownloadingState.COMPRESSING)
                fixture.reopen()
                fixture.finalizer(writer).adoptExistingArchive(original.download, archive.toString())

                assertEquals(1, writer.conversions, "archive adoption must not enter the native encoder again")
                assertEquals(DownloadingState.SUCCESS, fixture.download(original).state)
                assertTrue(fixture.saved(original).isDownloaded)
                assertEquals(listOf(archive.toString()), fixture.saved(original).localImagePaths)
                assertEquals(fixture.system.metadata(archive).size, fixture.download(original).sizeBytes)
            } finally {
                fixture.close()
            }
        }
}

/** Observation only: every conversion goes to the real production IosCbzWriter with default limits. */
private class CountingIosCbzWriter(
    private val delegate: CbzWriter,
) : CbzWriter by delegate {
    var conversions = 0
        private set

    override suspend fun createCbzWithSplitting(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long,
    ): Path {
        conversions++
        return delegate.createCbzWithSplitting(imagePaths, mangaId, chapterId, quality, maxHeight, maxMemoryBytes)
    }
}
