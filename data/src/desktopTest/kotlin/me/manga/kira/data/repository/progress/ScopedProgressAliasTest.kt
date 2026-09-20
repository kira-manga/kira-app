package me.manga.kira.data.repository.progress

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.libraryPolicy
import me.manga.kira.data.repository.librarySavedChapter
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.progress.ProgressWriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopedProgressAliasTest {
    @Test
    fun adoption_preserves_unsaved_anchor_ids_epochs_and_page_and_read_only_alias_lookup_does_not_move() = runTest {
        ProgressRuntimeFixture().use { f ->
            f.native.clearChapter(PROGRESS_PREVIOUS).progressValue()
            val old = f.native.beginSession(PROGRESS_PREVIOUS).progressValue().handle
            f.native.save(old, 3).progressValue()
            val before = assertNotNull(f.oldSnapshot())
            val family = f.family()
            assertEquals(3, f.native.readPosition(family.locator).progressValue())
            assertNull(f.db.readerProgressDao().findWork(PROGRESS_WORK.api, PROGRESS_WORK.url))
            val adopted = f.native.beginSession(family.locator).progressValue()
            assertEquals(family.owner, adopted.handle.retainedOwner)
            assertEquals(1L, adopted.handle.chapterGeneration)
            assertEquals(3, adopted.pageIndex)
            assertEquals(before, f.currentSnapshot())
            assertNull(f.oldSnapshot())
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(old, 4).progressValue())
        }
    }

    @Test
    fun exact_plus_alias_work_anchors_reject_even_when_the_saved_owner_is_exact() = runTest {
        ProgressRuntimeFixture().use { f ->
            f.family()
            val current = f.db.readerProgressDao().ensureSnapshot(
                PROGRESS_WORK.api, PROGRESS_WORK.url, PROGRESS_CHAPTER.chapterUrl,
            )
            val previous = f.db.readerProgressDao().ensureSnapshot(
                PROGRESS_WORK.api, PROGRESS_PREVIOUS.work.url, PROGRESS_PREVIOUS.chapterUrl,
            )
            assertTrue(f.native.beginSession(PROGRESS_CHAPTER).isFailure)
            assertTrue(f.native.readPosition(PROGRESS_CHAPTER).isFailure)
            assertTrue(f.native.clearWork(PROGRESS_WORK).isFailure)
            assertEquals(current, f.currentSnapshot())
            assertEquals(previous, f.oldSnapshot())
            assertEquals(2, f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).size)
        }
    }

    @Test
    fun chapter_anchor_collision_is_preflighted_before_a_work_alias_can_move() = runTest {
        ProgressRuntimeFixture().use { f ->
            f.family()
            val dao = f.db.readerProgressDao()
            val previous = dao.ensureSnapshot(
                PROGRESS_WORK.api, PROGRESS_PREVIOUS.work.url, PROGRESS_PREVIOUS.chapterUrl,
            )
            dao.ensureSnapshot(PROGRESS_WORK.api, PROGRESS_PREVIOUS.work.url, PROGRESS_CHAPTER.chapterUrl)
            assertTrue(f.native.beginSession(PROGRESS_CHAPTER).isFailure)
            assertTrue(f.native.clearChapter(PROGRESS_CHAPTER).isFailure)
            assertEquals(previous, f.oldSnapshot())
            assertNull(dao.findWork(PROGRESS_WORK.api, PROGRESS_WORK.url))
            assertEquals(2, dao.chaptersForWork(previous.workId).size)
        }
    }

    @Test
    fun exact_saved_chapter_does_not_hide_an_alias_saved_chapter() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            f.chapter(librarySavedChapter(family.work, PROGRESS_PREVIOUS.chapterUrl))
            assertTrue(f.native.beginSession(PROGRESS_CHAPTER).isFailure)
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
        }
    }

    @Test
    fun a_late_chapter_move_failure_rolls_back_the_earlier_work_move() = runTest {
        ProgressRuntimeFixture().use { f ->
            val old = f.native.beginSession(PROGRESS_PREVIOUS).progressValue().handle
            f.native.save(old, 9).progressValue()
            val before = f.oldSnapshot()
            f.family()
            f.execute(
                "CREATE TRIGGER reject_alias_chapter BEFORE UPDATE OF chapterUrl ON reader_chapter_state " +
                    "BEGIN SELECT RAISE(ABORT, 'no'); END",
            )
            assertTrue(f.native.beginSession(PROGRESS_CHAPTER).isFailure)
            assertEquals(before, f.oldSnapshot())
            assertNull(f.db.readerProgressDao().findWork(PROGRESS_WORK.api, PROGRESS_WORK.url))
        }
    }

    @Test
    fun same_saved_ids_follow_accepted_alias_moves_while_receipt_original_capture_stays_unchanged() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family(libraryParent(PROGRESS_PREVIOUS.work.url), PROGRESS_PREVIOUS.chapterUrl)
            f.seedLegacy(family.locator)
            f.ownership.granted = true
            f.legacy.prepare(family.locator).progressValue()
            val receipt = f.db.readerLegacyCleanupDao().allReceipts().single()
            val old = f.native.beginSession(family.locator).progressValue().handle
            f.transactions.write {
                f.db.mangaDao().update(family.work.copy(url = PROGRESS_WORK.url))
                f.db.chapterDao().updateChapter(family.chapter.copy(url = PROGRESS_CHAPTER.chapterUrl))
            }
            val adopted = f.native.beginSession(PROGRESS_CHAPTER).progressValue()
            assertEquals(old.retainedOwner?.work?.id, adopted.handle.retainedOwner?.work?.id)
            assertEquals(old.retainedOwner?.chapterId, adopted.handle.retainedOwner?.chapterId)
            assertEquals(old.workGeneration, adopted.handle.workGeneration)
            assertEquals(old.chapterGeneration, adopted.handle.chapterGeneration)
            assertEquals(receipt, f.db.readerLegacyCleanupDao().allReceipts().single())
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(old, 4).progressValue())
            assertEquals(4, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
        }
    }

    @Test
    fun empty_committed_policy_allows_only_existing_exact_recovery_not_unowned_work_creation() = runTest {
        ProgressRuntimeFixture().use { f ->
            val handle = f.native.beginSession(PROGRESS_CHAPTER).progressValue().handle
            f.native.save(handle, 0).progressValue()
            f.snapshots.accept(SourceAliasSnapshot(libraryPolicy(2).token, emptyList()))
            assertEquals(0, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(handle, 2).progressValue())
            assertTrue(f.native.beginSession(PROGRESS_PREVIOUS).isFailure)
            val unknown = PROGRESS_CHAPTER.copy(work = WorkLocator(PROGRESS_WORK.api, "https://current.test/new"))
            assertTrue(f.native.beginSession(unknown).isFailure)
            assertEquals(1, f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).size)
        }
    }
}

private suspend fun ProgressRuntimeFixture.oldSnapshot() = db.readerProgressDao().findSnapshot(
    PROGRESS_PREVIOUS.work.api, PROGRESS_PREVIOUS.work.url, PROGRESS_PREVIOUS.chapterUrl,
)

private suspend fun ProgressRuntimeFixture.currentSnapshot() = db.readerProgressDao().findSnapshot(
    PROGRESS_WORK.api, PROGRESS_WORK.url, PROGRESS_CHAPTER.chapterUrl,
)
