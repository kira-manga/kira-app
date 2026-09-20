package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.mapper.savedIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real SQL failures roll back their owning writer; ignored discovery candidates are not receipts. */
class LibraryIdentityRollbackTest {
    @Test
    fun cancellation_before_commit_rolls_back_and_is_rethrown_not_mapped_to_failure() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            f.transactions.beforeCommit = { throw CancellationException("controlled cancellation") }

            assertFailsWith<CancellationException> {
                f.repository.refresh(listOf(libraryRequest(parent, listOf(libraryChapter("c/1")))), true)
            }

            assertEquals(listOf(parent), f.db.backupDao().getAllSavedManga())
            assertTrue(f.db.backupDao().getChaptersForManga(parent.id).isEmpty())
            assertTrue(f.db.notificationDao().getAllNotifications().first().isEmpty())
        }
    }

    @Test
    fun second_metadata_statement_abort_rolls_back_the_entire_refresh_batch() = runTest {
        assertMetadataBatchRollback("RAISE(ABORT, 'controlled second update')")
    }

    @Test
    fun second_metadata_zero_count_rolls_back_the_entire_refresh_batch() = runTest {
        assertMetadataBatchRollback("RAISE(IGNORE)")
    }

    private suspend fun assertMetadataBatchRollback(rejection: String) {
        LibraryIdentityFixture().use { f ->
            val first = f.removalSeed()
            val second = f.removalSeed(libraryParent("https://current.test/work/two"))
            val history = f.db.backupDao().getAllHistoryOnce()
            val notifications = f.db.notificationDao().getAllNotifications().first()
            f.execute("CREATE TRIGGER test_metadata BEFORE UPDATE ON saved_manga WHEN OLD.id = ${second.owner.id} BEGIN SELECT $rejection; END")
            val requests = listOf(first, second).map { libraryRequest(it.parent, listOf(libraryChapter("new/${it.owner.id}"))) }
            assertTrue(f.repository.refresh(requests, notify = true).isFailure)
            assertEquals(listOf(first.parent, second.parent), f.db.backupDao().getAllSavedManga())
            assertEquals(history, f.db.backupDao().getAllHistoryOnce())
            assertEquals(notifications, f.db.notificationDao().getAllNotifications().first())
            listOf(first, second).forEach { seed ->
                assertEquals(listOf(seed.chapter), f.db.backupDao().getChaptersForManga(seed.owner.id))
                assertEquals(seed.progress, f.db.readerProgressDao().findSnapshot(seed.parent.api, seed.parent.url, seed.chapter.url))
            }
        }
    }

    @Test
    fun zero_count_related_cover_write_rolls_back_parent_metadata() = runTest {
        LibraryIdentityFixture().use { f ->
            val seed = f.removalSeed()
            val history = f.db.backupDao().getAllHistoryOnce()
            f.execute("CREATE TRIGGER test_cover BEFORE UPDATE OF mangaImageUrl ON history_items BEGIN SELECT RAISE(IGNORE); END")
            assertTrue(f.repository.refresh(listOf(libraryRequest(seed.parent, listOf(libraryChapter("c/2")))), true).isFailure)
            assertEquals(seed.parent, f.db.mangaDao().getMangaById(seed.owner.id))
            assertEquals(history, f.db.backupDao().getAllHistoryOnce())
            assertEquals(listOf(seed.chapter), f.db.backupDao().getChaptersForManga(seed.owner.id))
        }
    }

    @Test
    fun ignored_child_insert_commits_only_real_siblings_and_their_notifications() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            f.execute("CREATE TRIGGER test_discovery BEFORE INSERT ON saved_chapters WHEN NEW.number = '2' BEGIN SELECT RAISE(IGNORE); END")
            val request = libraryRequest(parent, listOf(libraryChapter("c/2"), libraryChapter("c/1")))
            val receipt = assertNotNull(f.repository.refresh(listOf(request), true).getOrNull()).single()
            val inserted = f.db.backupDao().getChaptersForManga(parent.id).single()
            val notification = f.db.notificationDao().getAllNotifications().first().single()
            assertEquals(request.fetched.details.title, f.db.mangaDao().getMangaById(parent.id)?.title)
            assertEquals("c/1", inserted.url)
            assertEquals(1, receipt.addedChapters)
            assertEquals(inserted.id, notification.chapterId)
            assertEquals(notification.id, receipt.notifications.single().notificationId)
        }
    }

    @Test
    fun second_notification_insert_failure_rolls_back_all_chapters_and_metadata() = runTest {
        assertDiscoveryRollback("notifications", "chapterNumber", "RAISE(ABORT, 'controlled notification insert')")
    }

    private suspend fun assertDiscoveryRollback(table: String, number: String, rejection: String) {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            f.execute("CREATE TRIGGER test_discovery BEFORE INSERT ON $table WHEN NEW.$number = '2' BEGIN SELECT $rejection; END")
            val request = libraryRequest(parent, listOf(libraryChapter("c/2"), libraryChapter("c/1")))
            assertTrue(f.repository.refresh(listOf(request), notify = true).isFailure)
            assertEquals(parent, f.db.mangaDao().getMangaById(parent.id))
            assertTrue(f.db.backupDao().getChaptersForManga(parent.id).isEmpty())
            assertTrue(f.db.notificationDao().getAllNotifications().first().isEmpty())
        }
    }

    @Test
    fun second_parent_delete_zero_count_retains_its_progress_but_not_completed_parent_or_files() = runTest {
        assertRemovalRollback { second ->
            "CREATE TRIGGER test_delete BEFORE DELETE ON saved_manga WHEN OLD.id = ${second.owner.id} BEGIN SELECT RAISE(IGNORE); END"
        }
    }

    @Test
    fun failed_clearWork_retains_its_generation_and_parent_after_prior_parent_commit() = runTest {
        assertRemovalRollback { second ->
            "CREATE TRIGGER test_progress BEFORE UPDATE OF pageIndex ON reader_chapter_state " +
                "WHEN OLD.workId = ${second.progress.workId} BEGIN SELECT RAISE(IGNORE); END"
        }
    }

    private suspend fun assertRemovalRollback(trigger: (LibraryRemovalSeed) -> String) {
        LibraryIdentityFixture().use { f ->
            val first = f.removalSeed()
            val second = f.removalSeed(libraryParent("https://current.test/work/two"))
            val seeds = listOf(first, second)
            val history = f.db.backupDao().getAllHistoryOnce()
            val notifications = f.db.notificationDao().getAllNotifications().first()
            f.execute(trigger(second))
            f.guard.grant(seeds.map { it.owner })
            assertTrue(f.repository.removeAllFromLibrary(seeds.map { it.owner }).isFailure)
            assertEquals(listOf(second.parent), f.db.backupDao().getAllSavedManga())
            assertEquals(history.filter { it.mangaUrl == second.parent.url }, f.db.backupDao().getAllHistoryOnce())
            val retainedNotifications = notifications.filter { it.mangaId == second.owner.id }
                .map { it.copy(isDownloaded = false, localImagePaths = emptyList()) }
            assertEquals(retainedNotifications, f.db.notificationDao().getAllNotifications().first())
            assertTrue(f.db.backupDao().getChaptersForManga(first.owner.id).isEmpty())
            assertFalse(f.db.readerProgressDao().savePosition(first.progress, 9), "completed parent invalidated its old writer")
            assertEquals(
                listOf(second.chapter.copy(isDownloaded = false, localImagePaths = emptyList())),
                f.db.backupDao().getChaptersForManga(second.owner.id),
            )
            assertEquals(second.progress, f.db.readerProgressDao().findSnapshot(second.parent.api, second.parent.url, second.chapter.url))
            seeds.forEach { seed ->
                assertNull(f.db.chapterDownloadingDao().getDownloadByChapter(seed.chapter.id))
                assertFalse(f.files.exists(seed.owner), "completed artifact/file cleanup is irreversible")
            }
            assertFalse(f.guard.held)
        }
    }

    @Test
    fun related_row_with_conflicting_positive_owner_rejects_without_writes() = runTest {
        LibraryIdentityFixture().use { f ->
            val target = f.parent()
            val unrelated = f.parent(libraryParent("https://current.test/work/two"))
            f.db.historyDao().insertHistory(libraryHistory(target, unrelated.id))
            val history = f.db.backupDao().getAllHistoryOnce()
            assertTrue(f.covers.updateCoverIfChanged(target.savedIdentity(), target.savedIdentity().locator, "new").isFailure)
            f.guard.grant(listOf(target.savedIdentity()))
            assertTrue(f.repository.removeFromLibrary(target.savedIdentity()).isFailure)
            assertEquals(listOf(target, unrelated), f.db.backupDao().getAllSavedManga())
            assertEquals(history, f.db.backupDao().getAllHistoryOnce())
        }
    }
}
