package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import me.manga.kira.core.dispatchers.DefaultDispatcherProvider
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.mangaDir
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Room reopen + real recursive deletion, with only the final root deletion faulted. */
class LibraryRemovalRecoveryTest {
    @Test
    fun finalRootFailureRetainsOriginalLibraryKeyForRetryAfterReopen() = downloadRecoveryTest {
        val original = seed()
        val key = libraryKey(original)
        val mangaId = original.saved.mangaId
        val directory = appFileSystem.mangaDir(mangaId)
        val failing = failDeleting(directory, IOException("injected root cleanup failure"))

        val first = library(failing).removeFromLibrary(key.api, key.language, key.title)

        assertFalse(first.isSuccess)
        assertTrue(fs.exists(directory), "the final root is still owned despite partial file cleanup")
        reopen()
        assertEquals(mangaId, db.mangaDao().getIdByApiAndTitle(key.api, key.title))

        assertTrue(library().removeFromLibrary(key.api, key.language, key.title).isSuccess)
        assertFalse(fs.exists(directory))
        assertNull(db.mangaDao().getIdByApiAndTitle(key.api, key.title))
        assertNull(db.chapterDao().getChapterByIdSuspend(original.saved.id))
        assertNull(dao.getDownloadByChapter(original.saved.id))
    }

    @Test
    fun bulkRetryCountsOnlyTheRemainingOriginalParentAfterRootFailure() = downloadRecoveryTest {
        val first = seed()
        val second = seed()
        val firstKey = libraryKey(first)
        val secondKey = libraryKey(second)
        val keys = listOf(firstKey, secondKey)
        val firstDirectory = appFileSystem.mangaDir(first.saved.mangaId)
        val secondDirectory = appFileSystem.mangaDir(second.saved.mangaId)

        val failing = failDeleting(secondDirectory, IOException("injected root cleanup failure"))
        assertFalse(library(failing).removeAllFromLibrary(keys).isSuccess)
        reopen()
        assertNull(db.mangaDao().getIdByApiAndTitle(firstKey.api, firstKey.title))
        assertFalse(fs.exists(firstDirectory))
        assertEquals(second.saved.mangaId, db.mangaDao().getIdByApiAndTitle(secondKey.api, secondKey.title))
        assertTrue(fs.exists(secondDirectory))

        assertEquals(1, library().removeAllFromLibrary(keys).getOrNull())
        assertNull(db.mangaDao().getIdByApiAndTitle(secondKey.api, secondKey.title))
        assertFalse(fs.exists(secondDirectory))
    }

    @Test
    fun cancelledRootCleanupPropagatesAndRetainsOriginalParentAfterReopen() = downloadRecoveryTest {
        val original = seed()
        val key = libraryKey(original)
        val directory = appFileSystem.mangaDir(original.saved.mangaId)

        assertFailsWith<CancellationException> {
            library(failDeleting(directory, CancellationException("cancel cleanup")))
                .removeFromLibrary(key.api, key.language, key.title)
        }
        reopen()
        assertEquals(original.saved.mangaId, db.mangaDao().getIdByApiAndTitle(key.api, key.title))
        assertTrue(fs.exists(directory))
        assertTrue(library().removeFromLibrary(key.api, key.language, key.title).isSuccess)
        assertFalse(fs.exists(directory))
    }

    private suspend fun DownloadRecoveryFixture.libraryKey(original: RetainedDownload): MangaKey {
        val manga = assertNotNull(db.mangaDao().getMangaById(original.saved.mangaId))
        return MangaKey(manga.api, manga.language, manga.title)
    }

    private fun DownloadRecoveryFixture.library(fileSystem: AppFileSystem = appFileSystem) =
        LibraryRepositoryImpl(
            mangaDao = db.mangaDao(),
            libraryDeo = db.libraryDeo(),
            chapterDao = db.chapterDao(),
            notificationDao = db.notificationDao(),
            historyDao = db.historyDao(),
            chapterDownloadDao = dao,
            downloadRepository = FakeDownloadRepository(),
            fileService = FileService(fileSystem),
            readProgress = RecordingReadProgressRepository(),
            dispatchers = DefaultDispatcherProvider(),
            artifacts = ArtifactTestRuntime(db, fileSystem).ownership,
        )

    private fun DownloadRecoveryFixture.failDeleting(blocked: Path, failure: Throwable): AppFileSystem {
        val failing = object : ForwardingFileSystem(fs) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (path == blocked) throw failure
                super.delete(path, mustExist)
            }
        }
        return object : AppFileSystem by appFileSystem {
            override fun fileSystem() = failing
        }
    }
}
