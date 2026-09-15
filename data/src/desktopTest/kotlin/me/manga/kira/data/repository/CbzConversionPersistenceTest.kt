package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Shared Settings caller on real file-backed Room; platform codecs/atomic IO are tested separately. */
class CbzConversionPersistenceTest {
    @Test
    fun publicationThenStatFailureKeepsBothCopiesAndOriginalMetadata() = failedPublicationReturn(stat = true)

    @Test
    fun publicationThenRoomRefusalKeepsBothCopiesAndOriginalMetadata() = failedPublicationReturn(stat = false)

    private fun failedPublicationReturn(stat: Boolean) = downloadRecoveryTest {
        val original = seed(isDownloaded = true, sizeBytes = 987L)
        val pages = installValidPages(original)
        val mirror = conversionMirror(original)
        val (archive, bytes) = installPreviousArchive(original, pages.values.toList())
        val faults = ConversionFileFaults(fs)
        val commits = ConversionCommitFault(db.chapterArtifactCommitDao(), before = {
            if (!stat) throw IOException("Room rejected conversion before commit")
        })
        conversionFaults(commits, faults)
        val repository = settingsConverter(CbzCallerWriter { _, _ ->
            fs.write(archive) { write(bytes) }
            if (stat) faults.statFailure = archive
            archive
        })
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertEquals(1, repository.observeCbzConversion().first().failedChapters)
        assertEquals(0, repository.observeCbzConversion().first().convertedChapters)
        assertEquals(original.saved, saved(original))
        assertEquals(original.download, download(original))
        assertEquals(mirror, conversionMirror(original.saved.id))
        pages.forEach { (path, content) -> assertContentEquals(content, fs.read(path) { readByteArray() }) }
        assertContentEquals(bytes, fs.read(archive) { readByteArray() })
        assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
    }

    @Test
    fun committedRoomTransactionSurvivesItsFailingSuspendReturn() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val pages = installValidPages(original)
        val mirror = conversionMirror(original)
        val commits = ConversionCommitFault(db.chapterArtifactCommitDao(), after = { throw IOException("failed return") })
        conversionFaults(commits)
        val (archive, bytes) = installPreviousArchive(original, pages.values.toList())
        val repository = settingsConverter(CbzCallerWriter { _, _ -> archive })
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertEquals(1, repository.observeCbzConversion().first().convertedChapters)
        assertEquals(0, repository.observeCbzConversion().first().failedChapters)
        assertConverted(original, mirror, archive)
        pages.keys.forEach { assertFalse(fs.exists(it)) }
        assertContentEquals(bytes, fs.read(archive) { readByteArray() })
        assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
        reopen()
        assertConverted(original, mirror, archive)
    }

    @Test
    fun cancelledRoomReturnPropagatesUnchangedEvenWhenReadbackProvesCommit() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val pages = installValidPages(original)
        val mirror = conversionMirror(original)
        val cancellation = RoomReturnCancellation()
        conversionFaults(ConversionCommitFault(db.chapterArtifactCommitDao(), after = { throw cancellation }))
        val (archive, _) = installPreviousArchive(original, pages.values.toList())
        val repository = settingsConverter(CbzCallerWriter { _, _ -> archive })
        assertSame(cancellation, assertFailsWith<CancellationException> { repository.compressExistingDownloads() })
        assertFalse(repository.observeCbzConversion().first().isConverting)
        assertEquals(0, repository.observeCbzConversion().first().convertedChapters)
        assertConverted(original, mirror, archive)
        pages.keys.forEach { assertFalse(fs.exists(it)) }
        assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
    }

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

            assertEquals(
                listOf(original.saved.id to original.saved.localImagePaths.map { it.toPath() }),
                writer.requests,
            )
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
            val failureMessages = listOf("read denied", "decode failed", "encode failed", "rename failed")
            val originals = List(failureMessages.size) { seed(isDownloaded = true, sizeBytes = 1234L) }
            val pages = originals.flatMap { installValidPages(it).entries }.associate { it.toPair() }
            val archives = originals.map { installPreviousArchive(it) }
            val failures =
                originals
                    .mapIndexed { index, original ->
                        original.saved.id to failureMessages[index]
                    }.toMap()
            val writer =
                CbzCallerWriter { paths, chapterId ->
                    assertEquals(2, paths.size)
                    throw IOException(failures.getValue(chapterId))
                }
            val repository = settingsConverter(writer)

            assertTrue(repository.compressExistingDownloads().isSuccess)

            assertEquals(failureMessages.size, writer.requests.size)
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
                    // Room runs on Dispatchers.Default, outside runTest's virtual clock.
                    withContext(Dispatchers.Default) {
                        withTimeout(WRITER_ENTRY_TIMEOUT_MILLIS) { entered.await() }
                    }
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

/** Keep the identity assertion about application propagation, not coroutine debug-stack copies. */
@OptIn(ExperimentalCoroutinesApi::class)
private class RoomReturnCancellation :
    CancellationException("cancelled Room return"),
    CopyableThrowable<RoomReturnCancellation> {
    override fun createCopy(): RoomReturnCancellation? = null
}

private const val WRITER_ENTRY_TIMEOUT_MILLIS = 15_000L
