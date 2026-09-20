package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.mapper.savedIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryRepositoryPersistNewTest {
    @Test
    fun persists_only_new_urls_flagged_isNew_and_reversed() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val existing = f.chapter(librarySavedChapter(parent, "c/1"))
            val request = libraryRequest(parent, listOf("c/3", "c/2", "c/1").map { libraryChapter(it) })
            val receipt = assertNotNull(f.repository.refresh(listOf(request), notify = false).getOrNull()).single()
            val rows = f.db.backupDao().getChaptersForManga(parent.id)
            assertEquals(2, receipt.addedChapters)
            assertEquals(listOf("c/1", "c/2", "c/3"), rows.map { it.url })
            assertEquals(existing, rows.first())
            assertTrue(rows.drop(1).all { it.isNew && it.fetchedAt > 0 && !it.isRead })
            assertTrue(f.db.notificationDao().getAllNotifications().first().isEmpty())
        }
    }

    @Test
    fun andNotify_writesNotificationRowPerNewChapter_andNoneOnRefresh() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val request = libraryRequest(parent, listOf("c/2", "c/1").map { libraryChapter(it) })
            val receipts = assertNotNull(f.repository.refresh(listOf(request), notify = true).getOrNull())
            val notifications = f.db.notificationDao().getAllNotifications().first()
            val chapters = f.db.backupDao().getChaptersForManga(parent.id)
            assertEquals(2, notifications.size)
            assertEquals(chapters.map { it.id }.toSet(), notifications.map { it.chapterId }.toSet())
            assertEquals(notifications.map { it.id }.toSet(), receipts.single().notifications.map { it.notificationId }.toSet())
            assertTrue(notifications.all { it.mangaId == parent.id && it.mangaTitle == request.fetched.details.title })
            assertTrue(receipts.single().notifications.all { it.manga.title == request.fetched.details.title })
            val second = assertNotNull(f.repository.refresh(listOf(request), notify = true).getOrNull()).single()
            assertEquals(0, second.addedChapters)
            assertTrue(second.notifications.isEmpty())
            assertEquals(notifications, f.db.notificationDao().getAllNotifications().first())
        }
    }

    @Test
    fun andNotify_neverAttachesToAnotherMangasChapterRow() = runTest {
        LibraryIdentityFixture().use { f ->
            val target = f.parent()
            val other = f.parent(libraryParent("https://current.test/work/two"))
            val foreignChapter = f.chapter(librarySavedChapter(other, "shared/chapter/1"))
            val request = libraryRequest(target, listOf(libraryChapter(foreignChapter.url)))
            val receipt = assertNotNull(f.repository.refresh(listOf(request), notify = true).getOrNull()).single()
            val chapter = f.db.backupDao().getChaptersForManga(target.id).single()
            val notification = f.db.notificationDao().getAllNotifications().first().single()
            assertNotEquals(foreignChapter.id, chapter.id)
            assertEquals(target.id, notification.mangaId)
            assertEquals(chapter.id, notification.chapterId)
            assertEquals(chapter.id, receipt.notifications.single().chapterId)
            assertEquals(foreignChapter, f.db.chapterDao().getChapterByIdSuspend(foreignChapter.id))
        }
    }

    @Test
    fun is_idempotent_on_second_run() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val request = libraryRequest(parent, listOf(libraryChapter("c/1"), libraryChapter("c/1")))
            assertEquals(1, f.repository.refresh(listOf(request), false).getOrNull()?.single()?.addedChapters)
            assertEquals(0, f.repository.refresh(listOf(request), false).getOrNull()?.single()?.addedChapters)
            assertEquals(1, f.db.backupDao().getChaptersForManga(parent.id).size)
        }
    }

    @Test
    fun missing_retained_owner_fails_without_creating_chapters() = runTest {
        LibraryIdentityFixture().use { f ->
            val missing = libraryParent().copy(id = 99L)
            val result = f.repository.refresh(listOf(libraryRequest(missing, listOf(libraryChapter("c/1")))), true)
            assertTrue(result.isFailure)
            assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
            assertTrue(f.db.backupDao().getChaptersForManga(missing.id).isEmpty())
            assertTrue(f.db.notificationDao().getAllNotifications().first().isEmpty())
            assertTrue(f.repository.markOpened(missing.savedIdentity()).isFailure)
        }
    }

    @Test
    fun andNotify_delegatesExactParentAndCountsOnlyCommittedRows() = runTest {
        LibraryIdentityFixture().use { f ->
            val target = f.parent()
            val other = f.parent(libraryParent("https://current.test/work/two"))
            val request = libraryRequest(target, listOf("c/3", "c/3", "c/2", "c/1").map { libraryChapter(it) })
            f.chapter(librarySavedChapter(target, "c/1"))
            val receipt = assertNotNull(f.repository.refresh(listOf(request), true).getOrNull()).single()
            val notifications = f.db.notificationDao().getAllNotifications().first().sortedBy { it.id }
            assertEquals(target.savedIdentity(), receipt.owner)
            assertEquals(2, receipt.addedChapters)
            assertEquals(listOf("c/2", "c/3"), notifications.map { it.chapterUrl })
            assertEquals(notifications.map { it.id }, receipt.notifications.map { it.notificationId })
            assertEquals(other, f.db.mangaDao().getMangaById(other.id), "same-title metadata is never an owner key")
            assertTrue(f.db.backupDao().getChaptersForManga(other.id).isEmpty())
        }
    }

    @Test
    fun andNotify_emptyCommittedOutcomeDoesNotCountStaleCandidates() = runTest {
        LibraryIdentityFixture().use { f ->
            val target = f.parent()
            val captured = libraryRequest(target, listOf(libraryChapter("c/1")))
            assertEquals(1, f.repository.refresh(listOf(captured), true).getOrNull()?.single()?.addedChapters)
            val committed = f.db.notificationDao().getAllNotifications().first()
            val late = assertNotNull(f.repository.refresh(listOf(captured), true).getOrNull()).single()
            assertEquals(0, late.addedChapters)
            assertTrue(late.notifications.isEmpty())
            assertEquals(committed, f.db.notificationDao().getAllNotifications().first())
            assertEquals(1, f.db.backupDao().getChaptersForManga(target.id).size)
        }
    }
}
