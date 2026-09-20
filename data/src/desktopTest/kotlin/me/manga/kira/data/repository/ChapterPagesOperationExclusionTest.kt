package me.manga.kira.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.platform.cbz.CbzReader
import me.manga.kira.platform.cbz.DefaultCbzReader
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.DesktopPageMediaInspector
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real Room/reader calls with controlled suspension; returned page URLs are not lifetime pins. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterPagesOperationExclusionTest : ChapterOwnershipFixture() {
    private val inspector = DesktopPageMediaInspector()
    private val chapter = Chapter("1", "shared", CHAPTER_OWNERSHIP_URL, null, false, false)

    @Test
    fun localLookupWaitsBeforeItsFirstLocatorAndEmitsOutsideTheOperationContext() = runTest {
        archivedChapter()
        val operations = DownloadOperationExclusion()
        val io = StandardTestDispatcher(testScheduler)
        val chapters = ObservedChapterDao(db.chapterDao())
        val release = CompletableDeferred<Unit>()
        val exclusive = async(start = CoroutineStart.UNDISPATCHED) { operations.withExclusive { release.await() } }
        val reading = async { repository(operations, io, chapters = chapters).fetchPages(mangaA, chapter).first() }
        try {
            runCurrent()
            assertEquals(0, chapters.lookups.get())
            assertFalse(reading.isCompleted)
        } finally {
            release.complete(Unit)
            exclusive.join()
        }
        val pages = assertIs<AppResult.Success<List<Page>>>(reading.await()).value
        assertEquals(1, chapters.lookups.get())
        assertEquals(1, pages.size)
        assertTrue(pages.single().url.startsWith("file://"))
        operations.withExclusive {} // Successful extraction does not retain the reader's display lifetime.
    }

    @Test
    fun cancelledExtractionRetainsAdmissionUntilTheRealExtractorFinallyFinishes() = runTest {
        archivedChapter()
        val operations = DownloadOperationExclusion()
        val io = StandardTestDispatcher(testScheduler)
        val reader = HeldReader(realReader(io), cleanup = false)
        val reading = async { repository(operations, io, reader).fetchPages(mangaA, chapter).first() }
        try {
            reader.entered.await()
            reading.cancel()
            reader.finishing.await()
            assertFalse(reading.isCompleted)
            assertFailsWith<DownloadOperationBusy> { operations.withExclusive {} }
        } finally {
            reader.release.complete(Unit)
            reading.cancelAndJoin()
        }
        val extracted = reader.extracted.await()
        assertTrue(extracted.isNotEmpty())
        assertTrue(extracted.all(fs::exists), "The real extractor ran inside the cancellation finally")
        operations.withExclusive {}
    }

    @Test
    fun independentCleanupWaitsBeforeItsFirstLocalCapture() = runTest {
        val cached = writeFile(extractedPage(archivedChapter()), "previous extraction")
        val operations = DownloadOperationExclusion()
        val io = StandardTestDispatcher(testScheduler)
        val chapters = ObservedChapterDao(db.chapterDao())
        val reader = ObservedCleanupReader(realReader(io))
        val release = CompletableDeferred<Unit>()
        val exclusive = async(start = CoroutineStart.UNDISPATCHED) { operations.withExclusive { release.await() } }
        repository(operations, io, reader, chapters).clearExtractedPages(mangaA, chapter)
        try {
            runCurrent()
            assertEquals(0, chapters.lookups.get())
        } finally {
            release.complete(Unit)
            exclusive.join()
        }
        reader.finished.await()
        runCurrent()
        assertFalse(fs.exists(cached))
        operations.withExclusive {}
    }

    @Test
    fun cancelledIndependentCleanupRetainsAdmissionUntilItsRealFinallyFinishes() = runTest {
        val cached = writeFile(extractedPage(archivedChapter()), "previous extraction")
        val operations = DownloadOperationExclusion()
        val io = StandardTestDispatcher(testScheduler)
        val reader = HeldReader(realReader(io), cleanup = true)
        repository(operations, io, reader).clearExtractedPages(mangaA, chapter)
        val cleanup = reader.entered.await()
        try {
            cleanup.cancel()
            reader.finishing.await()
            assertTrue(fs.exists(cached))
            assertFailsWith<DownloadOperationBusy> { operations.withExclusive {} }
        } finally {
            reader.release.complete(Unit)
            cleanup.cancelAndJoin()
            runCurrent() // Drain the independent root's outer release after its withContext child.
        }
        assertFalse(fs.exists(cached))
        operations.withExclusive {}
    }

    private fun repository(
        operations: DownloadOperationExclusion,
        io: CoroutineDispatcher,
        reader: CbzReader = realReader(io),
        chapters: ChapterDao = db.chapterDao(),
    ) = ChapterPagesRepositoryImpl(
        dispatchers = ioDispatchers(io),
        chapterDao = chapters,
        cbzReader = reader,
        sourceRegistry = chapterOwnershipRegistry(OwnerPagesSource()),
        pageFiles = DownloadedPageFiles(appFs, inspector),
        artifacts = artifactRuntime.ownership,
        appFileSystem = appFs,
        operations = operations,
    )

    private fun ioDispatchers(io: CoroutineDispatcher): DispatcherProvider =
        object : DispatcherProvider by dispatchers {
            override val io: CoroutineDispatcher = io
        }

    private fun realReader(io: CoroutineDispatcher) = DefaultCbzReader(appFs, ioDispatchers(io), inspector)

    private suspend fun archivedChapter(): SavedChapterEntity {
        val saved = seed(mangaA).single()
        val directory = appFs.chapterDir(saved.mangaId, saved.id)
        val archive = directory / "chapter_${saved.id}.cbz"
        fs.createDirectories(directory)
        fs.write(archive) { write(cbzCallerArchiveBytes()) }
        return saved.copy(isDownloaded = true, localImagePaths = listOf(archive.toString())).also {
            db.chapterDao().updateChapter(it)
        }
    }

    private class ObservedChapterDao(private val delegate: ChapterDao) : ChapterDao by delegate {
        val lookups = AtomicInteger()

        override suspend fun getChapterIdByUrl(mangaUrl: String, url: String): Long? {
            assertNotNull(currentCoroutineContext()[DownloadOperationExclusion.Operation])
            lookups.incrementAndGet()
            return delegate.getChapterIdByUrl(mangaUrl, url)
        }
    }

    private class ObservedCleanupReader(private val real: CbzReader) : CbzReader by real {
        val finished = CompletableDeferred<Unit>()

        override suspend fun cleanupExtractedCache(mangaId: Long, chapterId: Long) {
            real.cleanupExtractedCache(mangaId, chapterId)
            finished.complete(Unit)
        }
    }

    /** Holds only a real suspend call's finally, never models an independently drained producer. */
    private class HeldReader(private val real: CbzReader, private val cleanup: Boolean) : CbzReader by real {
        val entered = CompletableDeferred<Job>()
        val finishing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val extracted = CompletableDeferred<List<Path>>()

        override suspend fun extractImages(cbzPath: Path, mangaId: Long, chapterId: Long): List<Path> =
            if (cleanup) real.extractImages(cbzPath, mangaId, chapterId) else finishAfterCancellation {
                extracted.complete(real.extractImages(cbzPath, mangaId, chapterId))
            }

        override suspend fun cleanupExtractedCache(mangaId: Long, chapterId: Long) {
            if (cleanup) finishAfterCancellation { real.cleanupExtractedCache(mangaId, chapterId) }
            else real.cleanupExtractedCache(mangaId, chapterId)
        }

        private suspend fun finishAfterCancellation(finish: suspend () -> Unit): Nothing {
            entered.complete(currentCoroutineContext().job)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    finishing.complete(Unit)
                    release.await()
                    finish()
                }
            }
        }
    }
}
