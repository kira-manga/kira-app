package me.manga.kira.data.repository

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.manga.kira.data.local.dao.ChapterConversionOutcome
import me.manga.kira.data.local.entity.ChapterConversionRoster
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.claimOrNull
import okio.IOException
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Persisted-boundary reconstruction with fresh Room/runtime, not an OS-kill/fsync claim. */
class CbzConversionRecoveryTest {
    @Test
    fun conversionCapturePrepareAndStartupSettlementLeaveTheCallingUiDispatcher() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val pages = installValidPages(original)
        val mirror = conversionMirror(original)
        val (archive, bytes) = installPreviousArchive(original, pages.values.toList())
        Executors.newSingleThreadExecutor { Thread(it, "conversion-caller-ui") }.asCoroutineDispatcher().use { ui ->
            withContext(ui) {
                val guarded = ConversionIoGuard(fs, Thread.currentThread())
                conversionFaults(storage = guarded)
                val claim = assertNotNull(artifactRuntime.ownership.beginConversion(original.saved))
                assertTrue(artifactRuntime.ownership.convertFiles(claim, write = { archive }, commit = { path, size ->
                    db.chapterArtifactCommitDao().commitConversion(claim, original.saved, listOf(path.toString()), size)
                }) == true)
                reopen() // No local settlement: the new runtime must settle on its first UI admission.
                conversionFaults(storage = guarded)
                artifactRuntime.ownership.read(original.saved.id) { assertNull(it?.token) }
                assertTrue(guarded.operations.containsAll(setOf("stat", "canonicalize", "open", "delete")))
            }
        }
        assertConverted(original, mirror, archive)
        pages.keys.forEach { assertFalse(fs.exists(it)) }
        assertContentEquals(bytes, fs.read(archive) { readByteArray() })
    }

    @Test
    fun committedButUnverifiableCanonicalKeepsOriginalsAndCustodyWithoutSuccessCredit() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val pages = installValidPages(original)
        val mirror = conversionMirror(original)
        val (archive, bytes) = installPreviousArchive(original, pages.values.toList())
        val invalid = ByteArray(bytes.size)
        conversionFaults(ConversionCommitFault(db.chapterArtifactCommitDao(), after = {
            fs.write(archive) { write(invalid) }
        }))
        val repository = settingsConverter(CbzCallerWriter { _, _ -> archive })
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertEquals(0, repository.observeCbzConversion().first().convertedChapters)
        assertEquals(1, repository.observeCbzConversion().first().failedChapters)
        assertConverted(original, mirror, archive) // Room committed, but those bytes are not readable.
        val pending = assertNotNull(artifactRuntime.dao.get(original.saved.id))
        assertTrue(pending.retiring && pending.conversionSourceRoster != null)
        reopen()
        artifactRuntime.ownership.read(original.saved.id) { assertEquals(pending, it) }
        pages.forEach { (path, content) -> assertContentEquals(content, fs.read(path) { readByteArray() }) }
        assertContentEquals(invalid, fs.read(archive) { readByteArray() })
    }

    @Test fun unknownRealCommitRetainsBothCopiesUntilFreshRuntimeReadback() = unknownCommit(committed = true)
    @Test fun unknownNoncommitRetainsBothCopiesUntilFreshRuntimeReadback() = unknownCommit(committed = false)

    private fun unknownCommit(committed: Boolean) = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val pages = installValidPages(original)
        val mirror = conversionMirror(original)
        val faults = ConversionCommitFault(db.chapterArtifactCommitDao(),
            before = { if (!committed) throw IOException("before Room") }, unreadableOutcome = true)
        conversionFaults(faults)
        val (archive, bytes) = installPreviousArchive(original, pages.values.toList())
        val repository = settingsConverter(CbzCallerWriter { _, _ -> archive })
        assertTrue(repository.compressExistingDownloads().isSuccess)
        val pending = assertNotNull(artifactRuntime.dao.get(original.saved.id))
        assertTrue(pending.retiring)
        assertEquals(original.saved.localImagePaths, ChapterConversionRoster.decode(pending.conversionSourceRoster!!).map { it.storedPath })
        assertNull(artifactRuntime.ownership.beginConversion(original.saved))
        assertEquals(0, repository.observeCbzConversion().first().convertedChapters)
        assertEquals(1, repository.observeCbzConversion().first().failedChapters)
        pages.forEach { (path, content) -> assertContentEquals(content, fs.read(path) { readByteArray() }) }
        reopen()
        artifactRuntime.ownership.read(original.saved.id) { assertNull(it?.token) }
        if (committed) assertConverted(original, mirror, archive) else {
            assertEquals(original.saved, saved(original)); assertEquals(original.download, download(original))
            assertEquals(mirror, conversionMirror(original.saved.id))
        }
        pages.keys.forEach { assertEquals(!committed, fs.exists(it)) }
        assertContentEquals(bytes, fs.read(archive) { readByteArray() })
    }

    @Test fun firstSourceCleanupFailureRetainsCustodyAndRestartRetriesExactRoster() = cleanupFailure(index = 0)
    @Test fun laterSourceCleanupFailureRetainsCustodyAndRestartSkipsAlreadyDeletedSource() = cleanupFailure(index = 1)

    private fun cleanupFailure(index: Int) = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val pages = installValidPages(original)
        val mirror = conversionMirror(original)
        val faults = ConversionFileFaults(fs).apply { deleteFailure = pages.keys.elementAt(index) }
        conversionFaults(storage = faults)
        val (archive, bytes) = installPreviousArchive(original, pages.values.toList())
        val unowned = archive.parent!! / "unlisted.png"
        fs.write(unowned) { write(CBZ_CALLER_PNG) }
        val repository = settingsConverter(CbzCallerWriter { _, _ -> archive })
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertEquals(1, repository.observeCbzConversion().first().convertedChapters)
        assertConverted(original, mirror, archive)
        val pending = assertNotNull(artifactRuntime.dao.get(original.saved.id))
        assertTrue(pending.retiring && pending.conversionSourceRoster != null)
        pages.keys.forEachIndexed { i, path -> assertEquals(i >= index, fs.exists(path)) }
        assertEquals(pages.keys.take(index + 1), faults.deleteAttempts)
        reopen()
        artifactRuntime.ownership.read(original.saved.id) { assertNull(it?.conversionSourceRoster); assertNull(it?.token) }
        pages.keys.forEach { assertFalse(fs.exists(it)) }
        assertContentEquals(CBZ_CALLER_PNG, fs.read(unowned) { readByteArray() })
        assertContentEquals(bytes, fs.read(archive) { readByteArray() })
        assertConverted(original, mirror, archive)
        assertEquals(0, settingsConverter(CbzCallerWriter { _, _ -> error("already committed") }).also {
            assertTrue(it.compressExistingDownloads().isSuccess)
        }.observeCbzConversion().first().totalChapters)
    }

    @Test
    fun restartAfterPublicationBeforeRoomDoesNotAdoptOrDeleteEitherCopy() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val pages = installValidPages(original)
        val mirror = conversionMirror(original)
        val claim = assertNotNull(artifactRuntime.ownership.beginConversion(original.saved))
        val (archive, bytes) = installPreviousArchive(original, pages.values.toList())
        // Model the persisted cut: publication scope unwound, but no Room commit or settlement ran.
        assertTrue(artifactRuntime.ownership.files(claim) { fs.write(archive) { write(bytes) }; true } == true)
        assertEquals(claim, artifactRuntime.dao.get(original.saved.id)?.claimOrNull())
        reopen()
        artifactRuntime.ownership.read(original.saved.id) { assertNull(it?.token) }
        assertEquals(original.saved, saved(original))
        assertEquals(original.download, download(original))
        assertEquals(mirror, conversionMirror(original.saved.id))
        pages.forEach { (path, content) -> assertContentEquals(content, fs.read(path) { readByteArray() }) }
        assertContentEquals(bytes, fs.read(archive) { readByteArray() })
    }

    @Test
    fun explicitRetryDoesNotRevokeLiveConversionAndRereadsAfterRetiringCleanup() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val pages = installValidPages(original)
        val live = assertNotNull(artifactRuntime.ownership.beginConversion(original.saved))
        artifactRuntime.ownership.recoverConversions()
        assertTrue(artifactRuntime.dao.canPublish(live))
        assertEquals(ChapterConversionOutcome.NOT_COMMITTED, artifactRuntime.ownership.settleConversion(live))
        val faults = ConversionFileFaults(fs).apply { deleteFailure = pages.keys.first() }
        conversionFaults(storage = faults)
        val (archive, bytes) = installPreviousArchive(original, pages.values.toList())
        val writer = CbzCallerWriter { _, _ -> archive }
        val repository = settingsConverter(writer)
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertNotNull(artifactRuntime.dao.get(original.saved.id)?.token)
        faults.deleteFailure = null
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertEquals(0, repository.observeCbzConversion().first().totalChapters)
        assertEquals(1, writer.requests.size, "Recovery must not count/reencode its old loose snapshot")
        assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
        assertContentEquals(bytes, fs.read(archive) { readByteArray() })
    }

    @Test
    fun allStaleLegacySuccessAndHistoryAbsentRepairWithoutWriterThenBecomeNoOps() = downloadRecoveryTest {
        for (historyAbsent in listOf(false, true)) {
            val original = seed(isDownloaded = true)
            val pages = installValidPages(original)
            val mirror = conversionMirror(original)
            val (archive, bytes) = installPreviousArchive(original, pages.values.toList())
            pages.keys.forEach { fs.delete(it) }
            if (historyAbsent) dao.deleteByChapterId(original.saved.id)
            val writer = CbzCallerWriter { _, _ -> error("all-stale repair must not re-enter the encoder") }
            val repository = settingsConverter(writer)
            assertTrue(repository.compressExistingDownloads().isSuccess)
            assertEquals(1, repository.observeCbzConversion().first().convertedChapters)
            assertConverted(original, mirror, archive, historyAbsent)
            assertTrue(writer.requests.isEmpty())
            assertContentEquals(bytes, fs.read(archive) { readByteArray() })
            assertTrue(repository.compressExistingDownloads().isSuccess)
            assertEquals(0, repository.observeCbzConversion().first().totalChapters)
            assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
        }
    }

    @Test
    fun preRosterLegacyReceiptReleasesWithoutDeletingThenAllStaleRepairUsesFreshExactCustody() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val pages = installValidPages(original)
        val mirror = conversionMirror(original)
        val (archive, bytes) = installPreviousArchive(original, pages.values.toList())
        pages.keys.forEach { fs.delete(it) }
        artifactRuntime.dao.insert(ChapterArtifactEntity(
            original.saved.id, original.saved.mangaId, original.saved.url,
            token = "11111111-1111-4111-8111-111111111111", operation = ChapterArtifactOperation.CONVERT,
            downloadId = original.download.id, retiring = true,
        ))
        reopen()
        val writer = CbzCallerWriter { _, _ -> error("pre-roster repair must not reencode stale inputs") }
        val repository = settingsConverter(writer)
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertEquals(1, repository.observeCbzConversion().first().convertedChapters)
        assertTrue(writer.requests.isEmpty())
        assertConverted(original, mirror, archive)
        assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
        assertContentEquals(bytes, fs.read(archive) { readByteArray() })
    }
}
