package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.domain.model.identity.SavedWorkIdentity
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
        val owner = libraryOwner(original)
        val mangaId = original.saved.mangaId
        val directory = appFileSystem.mangaDir(mangaId)
        val failing = failDeleting(directory, IOException("injected root cleanup failure"))

        val first = library(listOf(owner), failing).removeFromLibrary(owner)

        assertFalse(first.isSuccess)
        assertTrue(fs.exists(directory), "the final root is still owned despite partial file cleanup")
        reopen()
        assertEquals(mangaId, db.mangaDao().getMangaById(owner.id)?.id)

        assertTrue(library(listOf(owner)).removeFromLibrary(owner).isSuccess)
        assertFalse(fs.exists(directory))
        assertNull(db.mangaDao().getMangaById(owner.id)?.id)
        assertNull(db.chapterDao().getChapterByIdSuspend(original.saved.id))
        assertNull(dao.getDownloadByChapter(original.saved.id))
    }

    @Test
    fun bulkRetryCountsOnlyTheRemainingOriginalParentAfterRootFailure() = downloadRecoveryTest {
        val first = seed()
        val second = seed()
        val firstOwner = libraryOwner(first)
        val secondOwner = libraryOwner(second)
        val owners = listOf(firstOwner, secondOwner)
        val firstDirectory = appFileSystem.mangaDir(first.saved.mangaId)
        val secondDirectory = appFileSystem.mangaDir(second.saved.mangaId)

        val failing = failDeleting(secondDirectory, IOException("injected root cleanup failure"))
        assertFalse(library(owners, failing).removeAllFromLibrary(owners).isSuccess)
        reopen()
        assertNull(db.mangaDao().getMangaById(firstOwner.id)?.id)
        assertFalse(fs.exists(firstDirectory))
        assertEquals(second.saved.mangaId, db.mangaDao().getMangaById(secondOwner.id)?.id)
        assertTrue(fs.exists(secondDirectory))

        assertFalse(library(owners).removeAllFromLibrary(owners).isSuccess, "typed stale selections must be refreshed")
        val remaining = listOf(secondOwner)
        assertEquals(1, library(remaining).removeAllFromLibrary(remaining).getOrNull())
        assertNull(db.mangaDao().getMangaById(secondOwner.id)?.id)
        assertFalse(fs.exists(secondDirectory))
    }

    @Test
    fun cancelledRootCleanupPropagatesAndRetainsOriginalParentAfterReopen() = downloadRecoveryTest {
        val original = seed()
        val owner = libraryOwner(original)
        val directory = appFileSystem.mangaDir(original.saved.mangaId)

        assertFailsWith<CancellationException> {
            library(listOf(owner), failDeleting(directory, CancellationException("cancel cleanup")))
                .removeFromLibrary(owner)
        }
        reopen()
        assertEquals(original.saved.mangaId, db.mangaDao().getMangaById(owner.id)?.id)
        assertTrue(fs.exists(directory))
        assertTrue(library(listOf(owner)).removeFromLibrary(owner).isSuccess)
        assertFalse(fs.exists(directory))
    }

    private suspend fun DownloadRecoveryFixture.libraryOwner(original: RetainedDownload): SavedWorkIdentity =
        assertNotNull(db.mangaDao().getMangaById(original.saved.mangaId)).savedIdentity()

    private fun DownloadRecoveryFixture.library(
        owners: List<SavedWorkIdentity>,
        fileSystem: AppFileSystem = appFileSystem,
    ) = LibraryTestRuntime(db, fileSystem, artifactRuntime.ownership).also { it.guard.grant(owners) }.repository

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

