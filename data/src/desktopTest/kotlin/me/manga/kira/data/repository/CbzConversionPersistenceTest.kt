package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shared Settings caller on real file-backed Room; platform codecs/atomic IO are tested separately. */
class CbzConversionPersistenceTest {
    @Test
    fun missingSecondSourceKeepsTheFullRoomRosterDownloadedFlagSizeAndConvertedCount() =
        downloadRecoveryTest {
            val original = seed(isDownloaded = true, sizeBytes = 987L)
            val pages = installValidPages(original)
            val (archive, previous) = installPreviousArchive(original)
            fs.delete(pages.keys.last())
            val writer =
                CbzCallerWriter { paths, _ ->
                    paths.forEach { path -> fs.read(path) { readByteArray() } }
                    error("the absent source must fail before a complete conversion can return")
                }
            val repository = settingsConverter(writer)

            assertTrue(repository.compressExistingDownloads().isSuccess)

            assertEquals(listOf(original.saved.id to original.saved.localImagePaths.map { it.toPath() }), writer.requests)
            assertEquals(original.saved, saved(original))
            assertEquals(original.download, download(original))
            assertContentEquals(pages.values.first(), fs.read(pages.keys.first()) { readByteArray() })
            assertContentEquals(previous, fs.read(archive) { readByteArray() })
            val progress = repository.observeCbzConversion().first()
            assertFalse(progress.isConverting)
            assertEquals(1, progress.totalChapters)
            assertEquals(0, progress.convertedChapters)
            reopen()
            assertEquals(original.saved, saved(original))
            assertEquals(original.download, download(original))
        }

    @Test
    fun readDecodeEncodeAndPublicationErrorsAreNotSuccessfulConversions() =
        downloadRecoveryTest {
            val originals = List(4) { seed(isDownloaded = true, sizeBytes = 1234L) }
            val pages = originals.flatMap { installValidPages(it).entries }.associate { it.toPair() }
            val archives = originals.map { installPreviousArchive(it) }
            val failures =
                originals
                    .mapIndexed { index, original ->
                        original.saved.id to listOf("read denied", "decode failed", "encode failed", "rename failed")[index]
                    }.toMap()
            val writer =
                CbzCallerWriter { paths, chapterId ->
                    assertEquals(2, paths.size)
                    throw IOException(failures.getValue(chapterId))
                }
            val repository = settingsConverter(writer)

            assertTrue(repository.compressExistingDownloads().isSuccess)

            assertEquals(4, writer.requests.size)
            assertEquals(0, repository.observeCbzConversion().first().convertedChapters)
            pages.forEach { (path, bytes) -> assertContentEquals(bytes, fs.read(path) { readByteArray() }) }
            archives.forEach { (path, bytes) -> assertContentEquals(bytes, fs.read(path) { readByteArray() }) }
            reopen()
            originals.forEach { original ->
                assertEquals(original.saved, saved(original))
                assertEquals(original.download, download(original))
            }
        }

    @Test
    fun cancellationKeepsThePersistedChapterAndClearsConvertingProgress() =
        downloadRecoveryTest {
            val original = seed(isDownloaded = true, sizeBytes = 678L)
            val pages = installValidPages(original)
            val entered = CompletableDeferred<Unit>()
            val writer =
                CbzCallerWriter { _, _ ->
                    entered.complete(Unit)
                    awaitCancellation()
                }
            val repository = settingsConverter(writer)
            coroutineScope {
                val conversion = async { repository.compressExistingDownloads() }
                try {
                    withTimeout(15_000) { entered.await() }
                    conversion.cancel(CancellationException("settings closed"))
                    assertFailsWith<CancellationException> { conversion.await() }
                } finally {
                    conversion.cancelAndJoin()
                }
            }
            assertFalse(repository.observeCbzConversion().first().isConverting)
            assertEquals(0, repository.observeCbzConversion().first().convertedChapters)
            pages.forEach { (path, bytes) -> assertContentEquals(bytes, fs.read(path) { readByteArray() }) }
            reopen()
            assertEquals(original.saved, saved(original))
            assertEquals(original.download, download(original))
        }
}
