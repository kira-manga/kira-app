package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.cbz.DefaultCbzReader
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.DesktopPageMediaInspector
import okio.Path
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real local recovery through Room, the production reader and the native JVM codec test host. */
class ChapterPageRecoveryTest : ChapterOwnershipFixture() {
    private val inspector = DesktopPageMediaInspector()
    private val chapter = Chapter("1", "shared", CHAPTER_OWNERSHIP_URL, null, false, false)

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
            writeArchive(reader.cbzPath(saved.mangaId, saved.id), "0.jpg" to recoveryTestPng(), "1.jpg" to recoveryTestPng())
            db.chapterDao().updateChapter(saved.copy(isDownloaded = true, localImagePaths = listOf("$good", "$missing")))
            val source = OwnerPagesSource()
            val result = repository(source).fetchPages(mangaA, chapter).first() as AppResult.Success
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
            val result = repository(source).fetchPages(mangaA, chapter).first() as AppResult.Success
            assertEquals(1, result.value.size)
            assertTrue(source.requests.isEmpty())
            assertTrue(fs.exists(canonical))
            assertTrue(fs.exists(stored))
        }

    private fun repository(source: OwnerPagesSource) =
        ChapterPagesRepositoryImpl(
            dispatchers,
            db.chapterDao(),
            DefaultCbzReader(appFs, dispatchers, inspector),
            chapterOwnershipRegistry(source),
            DownloadedPageFiles(appFs, inspector),
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
