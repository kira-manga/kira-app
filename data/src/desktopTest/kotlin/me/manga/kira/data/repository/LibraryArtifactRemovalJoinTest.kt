package me.manga.kira.data.repository

import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real artifact cleanup joins the typed aggregate; no engine/selection authority is supplied. */
class LibraryArtifactRemovalJoinTest {
    @Test
    fun finalRootCleanupFailureRetainsOwnerAndProgressForRetry() = runTest {
        LibraryIdentityFixture().use { f ->
            val seed = f.removalSeed()
            f.guard.grant(listOf(seed.owner))
            f.files.beforeDelete = { path -> if (path.name == "keep.cbz") throw IOException("controlled root failure") }
            assertTrue(f.repository.removeFromLibrary(seed.owner).isFailure)
            assertEquals(seed.parent, f.db.mangaDao().getMangaById(seed.owner.id))
            assertEquals(seed.progress, f.db.readerProgressDao().findSnapshot(seed.parent.api, seed.parent.url, seed.chapter.url))
            assertTrue(f.files.exists(seed.owner))
            assertFalse(f.guard.held)
            // Existing artifact settlement legitimately clears download state before deleting bytes.
            val chapter = assertNotNull(f.db.chapterDao().getChapterByIdSuspend(seed.chapter.id))
            assertFalse(chapter.isDownloaded)
            assertTrue(chapter.localImagePaths.isEmpty())
            assertNull(f.db.chapterDownloadingDao().getDownloadByChapter(seed.chapter.id))

            f.files.beforeDelete = {}
            assertTrue(f.repository.removeFromLibrary(seed.owner).isSuccess)
            assertNull(f.db.mangaDao().getMangaById(seed.owner.id))
            assertFalse(f.files.exists(seed.owner))
            assertFalse(f.db.readerProgressDao().savePosition(seed.progress, 9))
        }
    }

    @Test
    fun removeAllFromLibraryCountsUniqueCommittedOwnersNotSelectionEntries() = runTest {
        LibraryIdentityFixture().use { f ->
            val first = f.removalSeed()
            val second = f.removalSeed(libraryParent("https://current.test/work/two"))
            f.guard.grant(listOf(first.owner, second.owner))
            assertEquals(2, f.repository.removeAllFromLibrary(listOf(first.owner, second.owner, first.owner)).getOrNull())
            assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
            assertFalse(f.files.exists(first.owner))
            assertFalse(f.files.exists(second.owner))
        }
    }
}
