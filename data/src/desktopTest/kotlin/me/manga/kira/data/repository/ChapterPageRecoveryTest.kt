package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.backup.RestoredChapterArchive
import me.manga.kira.data.backup.RestoredDownloadPublisher
import me.manga.kira.data.local.dao.ChapterRestoreOutcome
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.cbz.DefaultCbzReader
import me.manga.kira.platform.cbz.CbzReader
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.DesktopPageMediaInspector
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real local recovery through Room, the production reader and the native JVM codec test host. */
class ChapterPageRecoveryTest : ChapterOwnershipFixture() {
    private val inspector = DesktopPageMediaInspector()
    private val chapter = Chapter("1", "shared", CHAPTER_OWNERSHIP_URL, null, false, false)

    @Test
    fun allStaleLegacyFallbackBecomesDurableWithoutReencodingAndReaderStaysLocal() = runTest {
        val original = seed(mangaA, bookmarked = true).single()
        val directory = appFs.chapterDir(original.mangaId, original.id)
        val stale = original.copy(isDownloaded = true, localImagePaths = listOf("$directory/0.jpg", "$directory/1.jpg"))
        db.chapterDao().updateChapter(stale)
        val archive = DefaultCbzReader(appFs, dispatchers, inspector).cbzPath(stale.mangaId, stale.id)
        writeArchive(archive, "0.png" to recoveryTestPng(), "1.png" to recoveryTestPng())
        val bytes = fs.read(archive) { readByteArray() }
        artifactRuntime = ArtifactTestRuntime(db, appFs, inspector)
        val source = OwnerPagesSource()
        val reader = repository(source)
        assertEquals(2, assertIs<AppResult.Success<List<Page>>>(reader.fetchPages(mangaA, chapter).first()).value.size)
        assertEquals(stale, row(stale.id), "Reader fallback itself remains non-destructive")
        val writer = CbzCallerWriter { _, _ -> error("all-stale legacy repair must not call writer") }
        val converter = DownloadedChapterConversion(db.chapterDao(), writer, db.mangaDao(), db.chapterDownloadingDao(),
            appFs, artifactRuntime.ownership, artifactRuntime.commits, downloadOperations)
        assertTrue(converter.convert(stale))
        assertEquals(stale.copy(localImagePaths = listOf(archive.toString())), row(stale.id))
        assertEquals(2, assertIs<AppResult.Success<List<Page>>>(reader.fetchPages(mangaA, chapter).first()).value.size)
        assertTrue(writer.requests.isEmpty() && source.requests.isEmpty())
        kotlin.test.assertContentEquals(bytes, fs.read(archive) { readByteArray() })
    }

    @Test
    fun invalidLoosePageEscapesToSourceRepeatedlyWithoutReturningASubsetOrMutatingRoom() =
        runTest {
            val saved = seed(mangaA).single()
            val directory = appFs.chapterDir(saved.mangaId, saved.id)
            val good = write(directory / "0.jpg", recoveryTestPng())
            val bad = write(directory / "1.jpg", "<html>challenge</html>".encodeToByteArray())
            val downloaded = saved.copy(isDownloaded = true, localImagePaths = listOf(good.toString(), bad.toString()))
            db.chapterDao().updateChapter(downloaded)
            val source = OwnerPagesSource()
            val repository = repository(source)

            repeat(2) { assertEquals(networkPages(), repository.fetchPages(mangaA, chapter).first()) }
            assertEquals(2, source.requests.size, "retry must not reload the same rejected local body")
            assertEquals(downloaded, row(saved.id), "reader recovery is not a download-state mutation")
            assertTrue(fs.exists(good))
            assertTrue(fs.exists(bad))
        }

    @Test
    fun badCbzCannotReuseAnOldExtractionCacheAndCorrectedArchiveCanRecoverOffline() =
        runTest {
            val saved = seed(mangaA).single()
            val reader = DefaultCbzReader(appFs, dispatchers, inspector)
            val archive = reader.cbzPath(saved.mangaId, saved.id)
            val oldCache = write(extractedPage(saved), recoveryTestPng())
            writeArchive(archive, "0.jpg" to recoveryTestPng(), "1.jpg" to "<html>blocked</html>".encodeToByteArray())
            val downloaded = saved.copy(isDownloaded = true, localImagePaths = listOf(archive.toString()))
            db.chapterDao().updateChapter(downloaded)
            val source = OwnerPagesSource()
            val repository = repository(source)

            assertEquals(0, reader.pageCount(archive))
            repeat(2) { assertEquals(networkPages(), repository.fetchPages(mangaA, chapter).first()) }
            assertTrue(fs.exists(archive))
            assertTrue(fs.exists(oldCache), "failed replacement cannot delete a previous cache generation")
            assertFalse(fs.listRecursively(appFs.cacheDir).any { it.name.startsWith(".partial-") })
            assertEquals(downloaded, row(saved.id))

            writeArchive(archive, "nested/0.jpg" to recoveryTestPng(), "other/0.jpg" to recoveryTestPng())
            assertEquals(2, reader.pageCount(archive))
            val result = repository.fetchPages(mangaA, chapter).first() as AppResult.Success
            assertEquals(2, result.value.size)
            assertEquals(
                2,
                result.value
                    .map { it.url }
                    .distinct()
                    .size,
                "duplicate ZIP basenames must not overwrite",
            )
            assertTrue(result.value.all { it.url.startsWith("file://") && it.url.endsWith(".png") })
            assertEquals(2, source.requests.size, "corrected complete content recovers without a third network request")
        }

    @Test
    fun incompleteLooseRosterUsesACompleteValidatedCanonicalArchive() =
        runTest {
            val saved = seed(mangaA).single()
            val directory = appFs.chapterDir(saved.mangaId, saved.id)
            val good = write(directory / "0.jpg", recoveryTestPng())
            val missing = directory / "1.jpg"
            val reader = DefaultCbzReader(appFs, dispatchers, inspector)
            writeArchive(
                reader.cbzPath(saved.mangaId, saved.id),
                "0.jpg" to recoveryTestPng(),
                "1.jpg" to recoveryTestPng(),
            )
            db.chapterDao().updateChapter(
                saved.copy(isDownloaded = true, localImagePaths = listOf("$good", "$missing")),
            )
            val source = OwnerPagesSource()
            val fetchResult = repository(source).fetchPages(mangaA, chapter).first()
            val result =
                assertIs<AppResult.Success<List<Page>>>(
                    fetchResult,
                    "Expected complete canonical-archive recovery, got $fetchResult",
                )
            assertEquals(2, result.value.size)
            assertTrue(result.value.all { it.url.endsWith(".png") })
            assertTrue(source.requests.isEmpty())
            assertTrue(fs.exists(good))
        }

    @Test
    fun invalidCanonicalArchiveCanUseTheValidStoredArchiveAfterAContainerMove() =
        runTest {
            val saved = seed(mangaA).single()
            val reader = DefaultCbzReader(appFs, dispatchers, inspector)
            val canonical = reader.cbzPath(saved.mangaId, saved.id)
            val stored = root / "old-container" / "chapter.cbz"
            writeArchive(canonical, "0.jpg" to "<html>bad</html>".encodeToByteArray())
            writeArchive(stored, "0.jpg" to recoveryTestPng())
            db.chapterDao().updateChapter(saved.copy(isDownloaded = true, localImagePaths = listOf(stored.toString())))
            val source = OwnerPagesSource()
            val fetchResult = repository(source).fetchPages(mangaA, chapter).first()
            val result =
                assertIs<AppResult.Success<List<Page>>>(
                    fetchResult,
                    "Expected complete stored-archive recovery, got $fetchResult",
                )
            assertEquals(1, result.value.size)
            assertTrue(source.requests.isEmpty())
            assertTrue(fs.exists(canonical))
            assertTrue(fs.exists(stored))
        }

    @Test
    fun restoredGenerationSurvivesHistoryDeletionAndSandboxMoveButNeverFallsBackToOlderCanonical() = runTest {
        val saved = seed(mangaA).single()
        val restored = restoreSinglePage(saved)
        val reader = DefaultCbzReader(appFs, dispatchers, inspector)
        writeArchive(reader.cbzPath(saved.mangaId, saved.id), "0.png" to recoveryTestPng(), "1.png" to recoveryTestPng())
        val relative = assertNotNull(db.chapterArtifactDao().get(saved.id)?.committedRelativePath)
        db.chapterDao().updateChapterLocalPaths(saved.id, listOf("/old-container/$relative"))
        db.chapterDownloadingDao().deleteByChapterId(saved.id)
        db.close()
        db = openDatabase()
        artifactRuntime = ArtifactTestRuntime(db, appFs)
        val source = OwnerPagesSource()
        val repository = repository(source)

        val local = assertIs<AppResult.Success<List<Page>>>(repository.fetchPages(mangaA, chapter).first())
        assertEquals(1, local.value.size, "The one-page generation, not the two-page old canonical, is authoritative")
        assertTrue(source.requests.isEmpty())
        fs.delete(restored)
        assertEquals(networkPages(), repository.fetchPages(mangaA, chapter).first())
        assertEquals(1, source.requests.size, "A missing explicit generation must use recovery, not stale canonical bytes")
    }

    @Test
    fun fullDeletionWaitsForActualRestoredArchiveExtraction() = runTest {
        val saved = seed(mangaA).single()
        val restored = restoreSinglePage(saved)
        val real = DefaultCbzReader(appFs, dispatchers, inspector)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reader = object : CbzReader by real {
            override suspend fun extractImages(cbzPath: Path, mangaId: Long, chapterId: Long): List<Path> {
                entered.complete(Unit)
                release.await()
                assertTrue(fs.exists(cbzPath))
                return real.extractImages(cbzPath, mangaId, chapterId)
            }
        }
        val reading = async { repository(OwnerPagesSource(), reader).fetchPages(mangaA, chapter).first() }
        entered.await()
        val deletion = async(start = CoroutineStart.UNDISPATCHED) { artifactRuntime.ownership.removeChapter(ChapterArtifactOwner.of(saved)) }
        assertFalse(deletion.isCompleted)
        assertTrue(fs.exists(restored))
        release.complete(Unit)
        assertIs<AppResult.Success<List<Page>>>(reading.await())
        assertTrue(deletion.await())
        assertFalse(fs.exists(restored))
    }

    private suspend fun restoreSinglePage(saved: SavedChapterEntity): Path {
        val source = root / "validated-${saved.id}.cbz"
        writeArchive(source, "0.png" to recoveryTestPng())
        val runtime = artifactRuntime
        assertEquals(ChapterRestoreOutcome.COMMITTED, RestoredDownloadPublisher(
            runtime.ownership, runtime.dao, runtime.commits, appFs, runtime.recovery,
        ).publish(RestoredChapterArchive(source, assertNotNull(fs.metadata(source).size)), saved, mangaA.api, mangaA.title))
        return assertNotNull(row(saved.id).localImagePaths.singleOrNull()).toPath()
    }

    private fun repository(source: OwnerPagesSource, reader: CbzReader = DefaultCbzReader(appFs, dispatchers, inspector)) =
        ChapterPagesRepositoryImpl(
            dispatchers,
            db.chapterDao(),
            reader,
            chapterOwnershipRegistry(source),
            DownloadedPageFiles(appFs, inspector),
            artifactRuntime.ownership,
            appFs,
            downloadOperations,
        )

    private fun networkPages() = AppResult.Success(listOf(Page("${mangaA.url}/network-page", emptyMap())))

    private fun write(
        path: Path,
        bytes: ByteArray,
    ): Path {
        fs.createDirectories(requireNotNull(path.parent))
        fs.write(path) { write(bytes) }
        return path
    }

    private fun writeArchive(
        path: Path,
        vararg entries: Pair<String, ByteArray>,
    ) {
        fs.createDirectories(requireNotNull(path.parent))
        fs.sink(path).buffer().use { sink ->
            BackupZipWriter(sink).apply {
                entries.forEach { (name, bytes) -> writeEntryBytes(name, bytes) }
                finish()
            }
        }
    }
}
