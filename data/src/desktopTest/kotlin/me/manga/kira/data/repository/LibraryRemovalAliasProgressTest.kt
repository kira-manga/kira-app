package me.manga.kira.data.repository

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.dao.ReaderProgressSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the library/progress transaction seam; no mocked writer or alias-resolution delegate. */
class LibraryRemovalAliasProgressTest {
    @Test
    fun removingCurrentSavedWorkClearsItsHistoricalAliasAnchorWithAdmissionAndCommitPolicyReads() = runTest {
        LibraryIdentityFixture().use { fixture ->
            val seed = fixture.removalSeed()
            val before = fixture.moveProgressToPreviousHost(seed)
            val reads = fixture.snapshots.readCount
            fixture.guard.grant(listOf(seed.owner))
            assertTrue(fixture.repository.removeFromLibrary(seed.owner).isSuccess)
            val dao = fixture.db.readerProgressDao()
            val current = assertNotNull(dao.findWork(seed.parent.api, seed.parent.url))
            assertEquals(before.workId, current.workId)
            assertEquals(before.workGeneration + 1L, current.workGeneration)
            assertNull(dao.findWork(seed.parent.api, LIBRARY_PREVIOUS_URL))
            assertEquals(1, dao.worksForApi(seed.parent.api).size)
            assertNull(dao.chaptersForWork(current.workId).single().pageIndex)
            assertFalse(dao.savePosition(before, 8))
            assertNull(fixture.db.mangaDao().getMangaById(seed.owner.id))
            assertFalse(fixture.files.exists(seed.owner))
            assertEquals(reads + 2, fixture.snapshots.readCount)
        }
    }

    @Test
    fun ambiguousProgressAnchorsRejectTheWholeBatchBeforeFileRemoval() = runTest {
        LibraryIdentityFixture().use { fixture ->
            val first = fixture.removalSeed()
            val second = fixture.removalSeed(libraryParent("https://current.test/work/two"))
            val dao = fixture.db.readerProgressDao()
            val previous = dao.ensureSnapshot(
                second.parent.api, "https://old.test/work/two", "https://old.test/work/two/chapter/one",
            )
            val owners = listOf(first.owner, second.owner)
            fixture.guard.grant(owners)
            assertTrue(fixture.repository.removeAllFromLibrary(owners).isFailure)
            listOf(first, second).forEach { seed ->
                assertEquals(seed.parent, fixture.db.mangaDao().getMangaById(seed.owner.id))
                assertEquals(seed.progress, dao.findSnapshot(seed.parent.api, seed.parent.url, seed.chapter.url))
                assertNotNull(fixture.db.chapterDownloadingDao().getDownloadByChapter(seed.chapter.id))
                assertTrue(fixture.files.exists(seed.owner))
            }
            assertEquals(
                previous,
                dao.findSnapshot(
                    second.parent.api, "https://old.test/work/two", "https://old.test/work/two/chapter/one",
                ),
            )
        }
    }

    @Test
    fun lateParentDeleteFailureRollsBackAliasMoveAndClearAfterOwnedFileCleanup() = runTest {
        LibraryIdentityFixture().use { fixture ->
            val seed = fixture.removalSeed()
            val before = fixture.moveProgressToPreviousHost(seed)
            fixture.execute(
                "CREATE TRIGGER reject_removal BEFORE DELETE ON saved_manga " +
                    "BEGIN SELECT RAISE(IGNORE); END",
            )
            fixture.guard.grant(listOf(seed.owner))
            assertTrue(fixture.repository.removeFromLibrary(seed.owner).isFailure)
            val dao = fixture.db.readerProgressDao()
            assertEquals(before, dao.findSnapshot(seed.parent.api, LIBRARY_PREVIOUS_URL, PREVIOUS_CHAPTER_URL))
            assertNull(dao.findWork(seed.parent.api, seed.parent.url))
            assertEquals(seed.parent, fixture.db.mangaDao().getMangaById(seed.owner.id))
            assertNull(fixture.db.chapterDownloadingDao().getDownloadByChapter(seed.chapter.id))
            assertEquals(
                seed.chapter.copy(isDownloaded = false, localImagePaths = emptyList()),
                fixture.db.chapterDao().getChapterByIdSuspend(seed.chapter.id),
            )
            assertFalse(fixture.files.exists(seed.owner), "owned file cleanup cannot be rolled back by SQLite")
        }
    }

    private suspend fun LibraryIdentityFixture.moveProgressToPreviousHost(
        seed: LibraryRemovalSeed,
    ): ReaderProgressSnapshot {
        transactions.write {
            val dao = db.readerProgressDao()
            val work = assertNotNull(dao.findWork(seed.parent.api, seed.parent.url))
            val chapter = dao.chaptersForWork(work.workId).single()
            assertTrue(dao.moveWork(work, LIBRARY_PREVIOUS_URL))
            assertTrue(dao.moveChapter(chapter, PREVIOUS_CHAPTER_URL))
        }
        return assertNotNull(
            db.readerProgressDao().findSnapshot(seed.parent.api, LIBRARY_PREVIOUS_URL, PREVIOUS_CHAPTER_URL),
        )
    }

    private companion object {
        const val PREVIOUS_CHAPTER_URL = "$LIBRARY_PREVIOUS_URL/chapter/one"
    }
}
