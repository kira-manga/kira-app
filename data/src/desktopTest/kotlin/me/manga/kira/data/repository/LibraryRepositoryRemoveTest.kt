package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRepositoryRemoveTest {
    @Test
    fun removeFromLibrary_purges_download_rows_and_deletes_files() = runTest {
        LibraryIdentityFixture().use { f ->
            val seed = f.removalSeed()
            f.guard.grant(listOf(seed.owner))
            assertTrue(f.repository.removeFromLibrary(seed.owner).isSuccess)
            assertNull(f.db.mangaDao().getMangaById(seed.owner.id))
            assertTrue(f.db.backupDao().getChaptersForManga(seed.owner.id).isEmpty())
            assertNull(f.db.chapterDownloadingDao().getDownloadByChapter(seed.chapter.id))
            assertTrue(f.db.backupDao().getAllHistoryOnce().isEmpty())
            assertTrue(f.db.notificationDao().getAllNotifications().first().isEmpty())
            val progress = assertNotNull(f.db.readerProgressDao().findSnapshot(seed.parent.api, seed.parent.url, seed.chapter.url))
            assertEquals(seed.progress.workGeneration + 1L, progress.workGeneration)
            assertNull(progress.pageIndex)
            assertFalse(f.db.readerProgressDao().savePosition(seed.progress, 8), "the old writer cannot resurrect cleared progress")
            assertFalse(f.files.exists(seed.owner))
            assertEquals(2, f.guard.checks, "preflight and final writer both revalidate the held lease")
            assertFalse(f.guard.held)
        }
    }

    @Test
    fun removeAllFromLibrary_purges_each_manga_rows_and_files() = runTest {
        LibraryIdentityFixture().use { f ->
            val seeds = listOf(f.removalSeed(), f.removalSeed(libraryParent("https://current.test/work/two")))
            val owners = seeds.map { it.owner }
            f.guard.grant(owners)
            assertEquals(2, f.repository.removeAllFromLibrary(owners).getOrNull())
            assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
            seeds.forEach {
                assertNull(f.db.chapterDownloadingDao().getDownloadByChapter(it.chapter.id))
                assertFalse(f.files.exists(it.owner))
                assertFalse(f.db.readerProgressDao().savePosition(it.progress, 9))
            }
            assertTrue(f.db.backupDao().getAllHistoryOnce().isEmpty())
            assertTrue(f.db.notificationDao().getAllNotifications().first().isEmpty())
        }
    }

    @Test
    fun removeAllFromLibrary_stale_owner_rejects_the_whole_batch() = runTest {
        LibraryIdentityFixture().use { f ->
            val seed = f.removalSeed()
            val missing = SavedWorkIdentity(999L, WorkLocator(LIBRARY_TEST_API, "https://current.test/work/missing"))
            val owners = listOf(seed.owner, missing)
            f.guard.grant(owners)
            assertTrue(f.repository.removeAllFromLibrary(owners).isFailure)
            assertEquals(seed.parent, f.db.mangaDao().getMangaById(seed.owner.id))
            assertEquals(seed.progress, f.db.readerProgressDao().findSnapshot(seed.parent.api, seed.parent.url, seed.chapter.url))
            assertTrue(f.files.exists(seed.owner))
        }
    }

    @Test
    fun removal_preserves_other_source_url_only_history_and_notifications() = runTest {
        LibraryIdentityFixture().use { f ->
            val foreign = f.parent(libraryParent().copy(api = "another-source"))
            val foreignChapter = f.chapter(librarySavedChapter(foreign))
            f.db.historyDao().insertHistory(libraryHistory(foreign, linkedId = 0L))
            val inserted = f.db.notificationDao().insertNotificationsList(
                listOf(libraryNotification(foreign, foreignChapter).copy(mangaId = 0L)),
            )
            assertTrue(inserted.single() > 0L, "the foreign notification must pass chapterId uniqueness")
            // Detached notifications have no FK. Retain a real, distinct former chapter ID while
            // freeing the globally unique work URL for a later saved owner from another source.
            assertEquals(1, f.db.libraryDeo().deleteMangaForExactOwner(foreign.id, foreign.api, foreign.url))
            val seed = f.removalSeed()
            assertNotEquals(foreignChapter.id, seed.chapter.id)
            assertEquals(foreign.url, seed.parent.url)
            assertEquals(foreignChapter.url, seed.chapter.url)
            val history = f.db.backupDao().getAllHistoryOnce().single { it.api == foreign.api }
            val notification = f.db.notificationDao().getAllNotifications().first().single { it.api == foreign.api }
            f.guard.grant(listOf(seed.owner))
            assertTrue(f.repository.removeFromLibrary(seed.owner).isSuccess)
            assertEquals(listOf(history), f.db.backupDao().getAllHistoryOnce())
            assertEquals(listOf(notification), f.db.notificationDao().getAllNotifications().first())
        }
    }

    @Test
    fun active_queue_and_missing_engine_lease_never_delete_files_or_progress() = runTest {
        LibraryIdentityFixture().use { f ->
            val seed = f.removalSeed(state = DownloadingState.RUNNING)
            assertTrue(f.repository.removeFromLibrary(seed.owner).isFailure, "an empty/default lease is not permission")
            f.guard.grant(listOf(seed.owner))
            assertTrue(f.repository.removeFromLibrary(seed.owner).isFailure, "active queue is rechecked inside the writer")
            assertEquals(seed.parent, f.db.mangaDao().getMangaById(seed.owner.id))
            assertEquals(seed.progress, f.db.readerProgressDao().findSnapshot(seed.parent.api, seed.parent.url, seed.chapter.url))
            assertTrue(f.files.exists(seed.owner))
        }
    }

    @Test
    fun active_queue_is_rechecked_after_in_transaction_lease_validation() = runTest {
        LibraryIdentityFixture().use { f ->
            val seed = f.removalSeed()
            f.guard.grant(listOf(seed.owner))
            f.guard.onCheck = { f.db.chapterDownloadingDao().updateStateChId(seed.chapter.id, DownloadingState.QUEUED) }
            assertTrue(f.repository.removeFromLibrary(seed.owner).isFailure)
            assertEquals(seed.parent, f.db.mangaDao().getMangaById(seed.owner.id))
            assertEquals(DownloadingState.SUCCESS, f.db.chapterDownloadingDao().getDownloadByChapter(seed.chapter.id)?.state)
            assertEquals(seed.progress, f.db.readerProgressDao().findSnapshot(seed.parent.api, seed.parent.url, seed.chapter.url))
            assertTrue(f.files.exists(seed.owner))
        }
    }
}
