package me.manga.kira.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.identity.SourceAliasReadiness
import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.data.mapper.toDomainManga
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Consumer invalidations with real Room/writer reads; not production selection-publication proof. */
class LibraryChildObservationTest {
    @Test
    fun one_subscription_rebinds_after_late_save_removal_reinsert_and_source_change() = runTest {
        LibraryIdentityFixture().use { f ->
            val request = libraryParent().toDomainManga()
            val chapterUrl = "${request.url}/chapter/one"
            val bookmarks = ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao())
            bookmarks.observeBookmark(request, chapterUrl).test {
                assertFalse(awaitItem())
                val parent = f.parent()
                f.chapter(librarySavedChapter(parent, chapterUrl))
                assertTrue(awaitItem())
                assertEquals(1, f.db.libraryDeo().deleteMangaById(parent.id))
                assertFalse(awaitItem())
                val replacement = f.parent()
                assertTrue(replacement.id != parent.id)
                f.chapter(librarySavedChapter(replacement, chapterUrl))
                assertTrue(awaitItem())
                f.db.mangaDao().update(replacement.copy(api = "other-source"))
                assertFalse(awaitItem())
                f.db.mangaDao().update(replacement)
                assertTrue(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun unchanged_chapter_metrics_still_recheck_alias_changes_and_ambiguity() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val child = f.chapter(librarySavedChapter(parent))
            val aliasUrl = child.url.replace("current.test", "old.test")
            val bookmarks = ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao())
            bookmarks.observeBookmark(parent.toDomainManga(), child.url).test {
                assertTrue(awaitItem())
                f.db.chapterDao().updateChapter(child.copy(url = "https://current.test/unrelated"))
                assertFalse(awaitItem())
                f.db.chapterDao().updateChapter(child.copy(url = aliasUrl))
                assertTrue(awaitItem())
                val duplicate = f.chapter(librarySavedChapter(parent, child.url))
                assertFalse(awaitItem())
                f.db.chapterDao().deleteChapterById(duplicate.id)
                assertTrue(awaitItem())
                f.snapshots.accept(libraryPolicy(revision = 2, previousHosts = emptyList()))
                assertFalse(awaitItem())
                f.snapshots.accept(libraryPolicy(revision = 3))
                assertTrue(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun repeated_ready_token_rechecks_after_a_writer_observed_refusal() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val child = f.chapter(librarySavedChapter(parent))
            val signals = MutableSharedFlow<SourceAliasReadiness>(replay = 1)
            val ready = f.snapshots.readiness.value
            signals.emit(ready)
            // Only control invalidation delivery. Authority still delegates to the real writer fixture.
            val provider = object : SourceAliasSnapshotProvider by f.snapshots {
                override val readiness = signals
            }
            val owners = LibraryOwnerTransactions(f.transactions, provider, f.db.mangaDao())
            ChapterBookmarkRepositoryImpl(owners, f.db.chapterDao()).observeBookmark(parent.toDomainManga(), child.url).test {
                assertTrue(awaitItem())
                f.snapshots.invalidate()
                signals.emit(ready)
                assertFalse(awaitItem())
                val readsAfterRefusal = f.snapshots.readCount
                f.snapshots.accept(libraryPolicy())
                signals.emit(ready)
                assertTrue(awaitItem())
                assertTrue(f.snapshots.readCount > readsAfterRefusal)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun unexpected_writer_failure_and_cancellation_are_not_false_bookmarks() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val child = f.chapter(librarySavedChapter(parent))
            val flow = ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao())
                .observeBookmark(parent.toDomainManga(), child.url)
            val failure = IllegalStateException("controlled unexpected writer failure")
            f.transactions.beforeCommit = { throw failure }
            assertSame(failure, assertFailsWith<IllegalStateException> { flow.first() })
            val cancelled = CancellationException("controlled writer cancellation")
            f.transactions.beforeCommit = { throw cancelled }
            assertSame(cancelled, assertFailsWith<CancellationException> { flow.first() })
        }
    }
}
