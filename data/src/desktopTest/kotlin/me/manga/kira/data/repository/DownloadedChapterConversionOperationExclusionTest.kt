package me.manga.kira.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.dao.ChapterArtifactDao
import me.manga.kira.data.local.dao.ChapterConversionOutcome
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.DesktopPageMediaInspector
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual Room/file settlement with a controlled writer boundary, not a native encoder/drain test. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedChapterConversionOperationExclusionTest : ChapterOwnershipFixture() {
    @Test
    fun recoveryWaitsBeforeItsFirstUnsettledReceiptCapture() = runTest {
        val operations = DownloadOperationExclusion()
        val records = ObservedArtifacts(db.chapterArtifactDao())
        val conversion = converter(operations, CbzCallerWriter { _, _ -> error("Recovery cannot encode") }, records)
        val release = CompletableDeferred<Unit>()
        val exclusive = async(start = CoroutineStart.UNDISPATCHED) { operations.withExclusive { release.await() } }
        val recovering = async { conversion.recover() }
        try {
            runCurrent()
            assertEquals(0, records.scans.get())
            assertFalse(recovering.isCompleted)
        } finally {
            release.complete(Unit)
            exclusive.join()
        }
        recovering.await()
        assertTrue(records.scans.get() > 0)
        operations.withExclusive {}
    }

    @Test
    fun conversionWaitsBeforeArtifactCaptureThenCommitsAndCleansUnderOneAdmission() = runTest {
        val chapter = looseChapter()
        val operations = DownloadOperationExclusion()
        val records = ObservedArtifacts(db.chapterArtifactDao())
        val writer = CbzCallerWriter { _, _ -> publishArchive(chapter) }
        val conversion = converter(operations, writer, records)
        val release = CompletableDeferred<Unit>()
        val exclusive = async(start = CoroutineStart.UNDISPATCHED) { operations.withExclusive { release.await() } }
        val converting = async { conversion.convert(chapter) }
        try {
            runCurrent()
            assertEquals(0, records.scans.get())
            assertTrue(writer.requests.isEmpty())
            assertFalse(converting.isCompleted)
        } finally {
            release.complete(Unit)
            exclusive.join()
        }
        assertTrue(converting.await())
        assertEquals(1, writer.requests.size)
        assertEquals(listOf(archive(chapter).toString()), row(chapter.id).localImagePaths)
        assertTrue(chapter.localImagePaths.none { fs.exists(it.toPath()) })
        operations.withExclusive {}
    }

    @Test
    fun batchAdmissionSurvivesNestedRecoveryThroughRosterCaptureAndConversion() = runTest {
        val chapter = looseChapter()
        val operations = DownloadOperationExclusion()
        val conversion = converter(operations, CbzCallerWriter { _, _ -> publishArchive(chapter) })
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val batch = async {
            conversion.withConversionOperation {
                conversion.recover()
                assertNotNull(currentCoroutineContext()[DownloadOperationExclusion.Operation])
                val current = conversion.chapters.getAllDownloadedChapters().single()
                captured.complete(Unit)
                release.await()
                conversion.convert(current)
            }
        }
        try {
            captured.await()
            assertFailsWith<DownloadOperationBusy> { operations.withExclusive {} }
        } finally {
            release.complete(Unit)
        }
        assertTrue(batch.await())
        operations.withExclusive {}
    }

    @Test
    fun cancellationRetainsAdmissionThroughRealWriterFinallyAndDurableSettlement() = runTest {
        val chapter = looseChapter()
        val operations = DownloadOperationExclusion()
        val writer = HeldWriter { publishArchive(chapter) }
        val settlement = HeldSettlement(db.chapterArtifactCommitDao())
        val conversion = converter(operations, writer.writer, commits = settlement)
        val converting = async { conversion.convert(chapter) }
        try {
            writer.entered.await()
            converting.cancel()
            writer.finishing.await()
            assertFalse(converting.isCompleted)
            assertFailsWith<DownloadOperationBusy> { operations.withExclusive {} }
            writer.release.complete(Unit)
            settlement.entered.await()
            assertFalse(converting.isCompleted)
            assertFailsWith<DownloadOperationBusy> { operations.withExclusive {} }
        } finally {
            writer.release.complete(Unit)
            settlement.release.complete(Unit)
            converting.cancelAndJoin()
        }
        assertEquals(chapter, row(chapter.id), "The canceled writer did not reach the Room publication")
        assertTrue(chapter.localImagePaths.all { fs.exists(it.toPath()) })
        assertTrue(fs.exists(archive(chapter)), "The real archive write completed in the writer's finally")
        assertNull(db.chapterArtifactDao().get(chapter.id)?.token)
        operations.withExclusive {}
    }

    /** Replaces the fixture's unused runtime once, before any artifact admission in this test. */
    private fun converter(
        operations: DownloadOperationExclusion,
        writer: CbzWriter,
        records: ChapterArtifactDao = db.chapterArtifactDao(),
        commits: ChapterArtifactCommitDao = db.chapterArtifactCommitDao(),
    ): DownloadedChapterConversion {
        artifactRuntime = ArtifactTestRuntime(records, commits, appFs, DesktopPageMediaInspector())
        return DownloadedChapterConversion(
            chapters = db.chapterDao(),
            archives = writer,
            manga = db.mangaDao(),
            downloads = db.chapterDownloadingDao(),
            files = appFs,
            artifacts = artifactRuntime.ownership,
            commits = commits,
            operations = operations,
        )
    }

    private suspend fun looseChapter(): SavedChapterEntity {
        val saved = seed(mangaA).single()
        val directory = appFs.chapterDir(saved.mangaId, saved.id)
        fs.createDirectories(directory)
        val paths = listOf(directory / "0.png", directory / "1.png")
        paths.forEach { path -> fs.write(path) { write(CBZ_CALLER_PNG) } }
        return saved.copy(isDownloaded = true, localImagePaths = paths.map(Path::toString)).also {
            db.chapterDao().updateChapter(it)
        }
    }

    private fun archive(chapter: SavedChapterEntity): Path =
        appFs.chapterDir(chapter.mangaId, chapter.id) / "chapter_${chapter.id}.cbz"

    private fun publishArchive(chapter: SavedChapterEntity): Path = archive(chapter).also { path ->
        fs.write(path) { write(cbzCallerArchiveBytes(List(chapter.localImagePaths.size) { CBZ_CALLER_PNG })) }
    }

    private class ObservedArtifacts(private val delegate: ChapterArtifactDao) : ChapterArtifactDao by delegate {
        val scans = AtomicInteger()

        override suspend fun getUnsettled(): List<ChapterArtifactEntity> {
            assertNotNull(currentCoroutineContext()[DownloadOperationExclusion.Operation])
            scans.incrementAndGet()
            return delegate.getUnsettled()
        }
    }

    private class HeldWriter(publish: () -> Path) {
        val entered = CompletableDeferred<Unit>()
        val finishing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = CbzCallerWriter { _, _ ->
            assertNotNull(currentCoroutineContext()[DownloadOperationExclusion.Operation])
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    finishing.complete(Unit)
                    release.await()
                    publish()
                }
            }
        }
    }

    private class HeldSettlement(private val delegate: ChapterArtifactCommitDao) : ChapterArtifactCommitDao by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun readConversionOutcome(
            claim: ChapterArtifactClaim,
            canonicalPath: String,
            sizeBytes: Long?,
        ): ChapterConversionOutcome {
            assertNotNull(currentCoroutineContext()[DownloadOperationExclusion.Operation])
            entered.complete(Unit)
            release.await()
            return delegate.readConversionOutcome(claim, canonicalPath, sizeBytes)
        }
    }
}
