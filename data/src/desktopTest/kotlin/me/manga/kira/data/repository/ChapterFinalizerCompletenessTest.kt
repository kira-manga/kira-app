package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real Room + shared iOS/coroutine finalizer; does not claim native encoder or scheduler execution. */
class ChapterFinalizerCompletenessTest {
    @Test
    fun codecFailureDoesNotBecomeLoosePageSuccessOrRewriteBookkeeping() =
        downloadRecoveryTest {
            val original = seed(state = DownloadingState.DOWNLOADED, sizeBytes = 456L)
            val pages = installValidPages(original)
            val (archive, previous) = installPreviousArchive(original)
            val writer = CbzCallerWriter { _, _ -> throw IOException("second page encode failed") }

            assertFailsWith<IOException> {
                finalizer(writer).finalize(original.download, original.saved.localImagePaths)
            }

            assertEquals(1, writer.requests.size)
            assertEquals(original.saved, saved(original))
            assertEquals(original.download.copy(state = DownloadingState.COMPRESSING), download(original))
            pages.forEach { (path, bytes) -> assertContentEquals(bytes, fs.read(path) { readByteArray() }) }
            assertContentEquals(previous, fs.read(archive) { readByteArray() })
            reopen()
            assertEquals(original.saved, saved(original))
            assertEquals(DownloadingState.COMPRESSING, download(original).state)
        }

    @Test
    fun emptyRosterIsNeverMarkedAsACompletedChapter() =
        downloadRecoveryTest {
            val original = seed(state = DownloadingState.DOWNLOADED, sizeBytes = 456L)
            val writer = CbzCallerWriter { _, _ -> error("empty roster must not enter writer") }

            assertFailsWith<IllegalArgumentException> { finalizer(writer).finalize(original.download, emptyList()) }
            assertFailsWith<IllegalArgumentException> { finalizer(writer).markReadable(original.download, emptyList()) }

            assertTrue(writer.requests.isEmpty())
            assertEquals(original.saved, saved(original))
            assertEquals(original.download, download(original))
        }

    @Test
    fun missingOrInvalidLoosePageCannotBeMarkedReadableOrCompletedWithoutCbz() =
        downloadRecoveryTest {
            listOf(false, true).forEach { corrupt ->
                val original = seed(state = DownloadingState.DOWNLOADED, sizeBytes = 456L)
                installValidPages(original)
                val bad =
                    original.saved.localImagePaths
                        .last()
                        .toPath()
                if (corrupt) fs.write(bad) { writeUtf8("not an image") } else fs.delete(bad)
                val dataStore = DataStoreHelper(MapSettings()).apply { setUseCbzFormat(false) }
                val writer = CbzCallerWriter { _, _ -> error("loose completion must not encode") }
                val finalizer = finalizer(writer, dataStore)

                assertFailsWith<IOException> {
                    finalizer.markReadable(original.download, original.saved.localImagePaths)
                }
                assertFailsWith<IOException> {
                    finalizer.finalize(original.download, original.saved.localImagePaths)
                }

                assertTrue(writer.requests.isEmpty())
                assertEquals(original.saved, saved(original))
                assertEquals(original.download, download(original))
            }
        }

    @Test
    fun cancellationWhileTheWriterIsActiveDoesNotCreateSuccess() =
        downloadRecoveryTest {
            val original = seed(state = DownloadingState.DOWNLOADED, sizeBytes = 456L)
            val pages = installValidPages(original)
            val entered = CompletableDeferred<Unit>()
            val writer =
                CbzCallerWriter { _, _ ->
                    entered.complete(Unit)
                    awaitCancellation()
                }
            coroutineScope {
                val conversion = async { finalizer(writer).finalize(original.download, original.saved.localImagePaths) }
                try {
                    // Room runs on Dispatchers.Default, outside runTest's virtual clock.
                    withContext(Dispatchers.Default) {
                        withTimeout(WRITER_ENTRY_TIMEOUT_MILLIS) { entered.await() }
                    }
                    conversion.cancel(CancellationException("download cancelled"))
                    assertFailsWith<CancellationException> { conversion.await() }
                } finally {
                    conversion.cancelAndJoin()
                }
            }
            assertEquals(original.saved, saved(original))
            assertEquals(original.download.copy(state = DownloadingState.COMPRESSING), download(original))
            pages.forEach { (path, bytes) -> assertContentEquals(bytes, fs.read(path) { readByteArray() }) }
        }

    @Test
    fun aCompleteLooseRosterCanStillFinishWhenCbzIsDisabled() =
        downloadRecoveryTest {
            val original = seed(state = DownloadingState.DOWNLOADED)
            val pages = installValidPages(original)
            val dataStore = DataStoreHelper(MapSettings()).apply { setUseCbzFormat(false) }
            val writer = CbzCallerWriter { _, _ -> error("CBZ is disabled") }

            finalizer(writer, dataStore).finalize(original.download, original.saved.localImagePaths)

            assertTrue(writer.requests.isEmpty())
            assertEquals(original.saved.localImagePaths, saved(original).localImagePaths)
            assertTrue(saved(original).isDownloaded)
            assertEquals(DownloadingState.SUCCESS, download(original).state)
            assertEquals(pages.values.sumOf { it.size.toLong() }, download(original).sizeBytes)
        }

    @Test
    fun anArchiveWithOneValidAndOneInvalidEntryCannotBeAdoptedAsComplete() =
        downloadRecoveryTest {
            val original = seed(state = DownloadingState.COMPRESSING, sizeBytes = 456L)
            val pages = installValidPages(original)
            val (archive, previous) =
                installPreviousArchive(original, listOf(pages.values.first(), "not an image".encodeToByteArray()))
            val writer = CbzCallerWriter { _, _ -> error("adoption never encodes") }

            assertFailsWith<IOException> {
                finalizer(writer).adoptExistingArchive(original.download, archive.toString())
            }

            assertEquals(original.saved, saved(original))
            assertEquals(original.download, download(original))
            assertContentEquals(previous, fs.read(archive) { readByteArray() })
        }

    @Test
    fun aReadableCompleteArchiveCanBeAdoptedWithoutReencoding() =
        downloadRecoveryTest {
            val original = seed(state = DownloadingState.COMPRESSING)
            val pages = installValidPages(original)
            val (archive, _) = installPreviousArchive(original, pages.values.toList())
            pages.keys.forEach { fs.delete(it) }
            val writer = CbzCallerWriter { _, _ -> error("adoption never encodes") }

            finalizer(writer).adoptExistingArchive(original.download, archive.toString())

            assertTrue(writer.requests.isEmpty())
            assertEquals(listOf(archive.toString()), saved(original).localImagePaths)
            assertTrue(saved(original).isDownloaded)
            assertEquals(DownloadingState.SUCCESS, download(original).state)
        }
}

private const val WRITER_ENTRY_TIMEOUT_MILLIS = 15_000L
