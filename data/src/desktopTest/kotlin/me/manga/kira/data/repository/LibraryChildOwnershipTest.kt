package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.mapper.toDomainManga
import me.manga.kira.data.repository.library.LibraryWriteException
import me.manga.kira.data.repository.library.LibraryWriteRejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shared Android/iOS ownership and rollback using generated Room DAOs, not URL-only DAO fakes. */
class LibraryChildOwnershipTest {
    @Test
    fun declared_parent_and_child_aliases_ignore_title_drift_and_isolate_other_parents() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent(libraryParent(LIBRARY_PREVIOUS_URL))
            val child = f.chapter(librarySavedChapter(parent).copy(isBookmarked = false, isRead = false))
            val other = f.parent(libraryParent("https://current.test/work/two"))
            val otherChild = f.chapter(librarySavedChapter(other, child.url))
            val request = parent.toDomainManga().copy(url = LIBRARY_CURRENT_URL, title = "Renamed", language = "ar")
            val chapterUrl = child.url.replace("old.test", "current.test")
            assertTrue(ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao()).toggleBookmark(request, chapterUrl))
            ChapterNewBadgeRepositoryImpl(f.owners, f.db.chapterDao()).clearNew(request, chapterUrl)
            val cleared = requireNotNull(f.db.chapterDao().getChapterByIdSuspend(child.id))
            assertEquals(child.copy(isBookmarked = true, isNew = false), cleared)
            MarkChapterReadRepositoryImpl(f.owners, f.db.chapterDao()).markRead(request, chapterUrl)
            val read = requireNotNull(f.db.chapterDao().getChapterByIdSuspend(child.id))
            assertTrue(read.isRead)
            assertFalse(read.isNew)
            assertTrue(read.lastReadDate > child.lastReadDate)
            assertEquals(otherChild, f.db.chapterDao().getChapterByIdSuspend(otherChild.id))
            assertEquals(listOf(parent, other), f.db.backupDao().getAllSavedManga())
        }
    }

    @Test
    fun cross_api_exact_parent_refuses_every_mutation_without_changing_the_occupant() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent(libraryParent(api = "other-source"))
            val child = f.chapter(librarySavedChapter(parent))
            val request = parent.toDomainManga().copy(api = LIBRARY_TEST_API)
            val bookmarks = ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao())
            val reads = MarkChapterReadRepositoryImpl(f.owners, f.db.chapterDao())
            val badge = ChapterNewBadgeRepositoryImpl(f.owners, f.db.chapterDao())
            val failure = assertFailsWith<LibraryWriteException> { bookmarks.toggleBookmark(request, child.url) }
            assertEquals(LibraryWriteRejection.OWNER_CONFLICT, failure.rejection)
            assertFailsWith<LibraryWriteException> { bookmarks.toggleBookmark(request, listOf(child.url)) }
            assertFailsWith<LibraryWriteException> { reads.markRead(request, child.url) }
            assertFailsWith<LibraryWriteException> { reads.markRead(request, listOf(child.url)) }
            assertFailsWith<LibraryWriteException> { reads.toggleRead(request, child.url) }
            assertFailsWith<LibraryWriteException> { badge.clearNew(request, child.url) }
            assertEquals(child, f.db.chapterDao().getChapterByIdSuspend(child.id))
            assertEquals(parent, f.db.mangaDao().getMangaById(parent.id))
        }
    }

    @Test
    fun exact_plus_alias_ambiguity_late_in_batch_refuses_before_any_toggle() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val first = f.chapter(librarySavedChapter(parent, "https://current.test/chapter/one"))
            val second = f.chapter(librarySavedChapter(parent, "https://current.test/chapter/two"))
            val alias = f.chapter(librarySavedChapter(parent, "https://old.test/chapter/two"))
            val bookmarks = ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao())
            val failure = assertFailsWith<LibraryWriteException> {
                bookmarks.toggleBookmark(parent.toDomainManga(), listOf(first.url, second.url))
            }
            assertEquals(LibraryWriteRejection.CHAPTER_ALIAS_REQUIRES_RECONCILIATION, failure.rejection)
            assertFailsWith<LibraryWriteException> { bookmarks.toggleBookmark(parent.toDomainManga(), second.url) }
            assertEquals(listOf(first, second, alias), f.db.backupDao().getChaptersForManga(parent.id))
        }
    }

    @Test
    fun duplicate_requests_and_aliases_toggle_once_and_bulk_read_keeps_existing_stamp_policy() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val child = f.chapter(librarySavedChapter(parent).copy(isBookmarked = false, isRead = false))
            val requests = listOf(child.url, child.url.replace("current.test", "old.test"), child.url)
            val manga = parent.toDomainManga()
            ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao()).toggleBookmark(manga, requests)
            val reads = MarkChapterReadRepositoryImpl(f.owners, f.db.chapterDao())
            reads.markRead(manga, requests)
            assertEquals(
                child.copy(isBookmarked = true, isRead = true),
                f.db.chapterDao().getChapterByIdSuspend(child.id),
            )
            reads.toggleRead(manga, requests[1])
            assertEquals(child.copy(isBookmarked = true), f.db.chapterDao().getChapterByIdSuspend(child.id))
        }
    }

    @Test
    fun exact_stored_child_remains_usable_with_verified_empty_alias_policy() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val child = f.chapter(librarySavedChapter(parent).copy(isBookmarked = false))
            f.snapshots.accept(SourceAliasSnapshot(libraryPolicy().token, emptyList()))
            val bookmarks = ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao())
            assertTrue(bookmarks.toggleBookmark(parent.toDomainManga(), child.url))
            assertEquals(child.copy(isBookmarked = true), f.db.chapterDao().getChapterByIdSuspend(child.id))
        }
    }

    @Test
    fun failed_new_clear_and_cancellation_before_commit_roll_back_read_stamp_and_flags() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val child = f.chapter(librarySavedChapter(parent).copy(isRead = false))
            val reads = MarkChapterReadRepositoryImpl(f.owners, f.db.chapterDao())
            f.execute(
                "CREATE TRIGGER fail_new BEFORE UPDATE OF isNew ON saved_chapters " +
                    "BEGIN SELECT RAISE(ABORT, 'controlled new clear'); END",
            )
            val failure = assertFails { reads.markRead(parent.toDomainManga(), child.url) }
            assertTrue(failure.message.orEmpty().contains("controlled new clear"))
            assertEquals(child, f.db.chapterDao().getChapterByIdSuspend(child.id))
            f.execute("DROP TRIGGER fail_new")
            f.transactions.beforeCommit = { throw CancellationException("controlled before commit") }
            assertFailsWith<CancellationException> { reads.markRead(parent.toDomainManga(), child.url) }
            assertEquals(child, f.db.chapterDao().getChapterByIdSuspend(child.id))
        }
    }

    @Test
    fun late_bulk_statement_failure_rolls_back_the_first_five_hundred_rows() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val rows = (1..501).map { number ->
                librarySavedChapter(parent, "https://current.test/chapter/$number").copy(isRead = false)
            }
            val ids = f.db.chapterDao().insertChapters(rows)
            assertEquals(rows.size, ids.size)
            assertTrue(ids.all { it > 0 })
            f.execute(
                "CREATE TRIGGER fail_tail BEFORE UPDATE OF isRead ON saved_chapters " +
                    "WHEN OLD.id = ${ids.last()} BEGIN SELECT RAISE(ABORT, 'controlled final chunk'); END",
            )
            val failure = assertFails {
                val reads = MarkChapterReadRepositoryImpl(f.owners, f.db.chapterDao())
                reads.markRead(parent.toDomainManga(), rows.map { it.url })
            }
            assertTrue(failure.message.orEmpty().contains("controlled final chunk"))
            val expected = rows.mapIndexed { index, row -> row.copy(id = ids[index]) }
            assertEquals(expected, f.db.backupDao().getChaptersForManga(parent.id))
        }
    }
}
