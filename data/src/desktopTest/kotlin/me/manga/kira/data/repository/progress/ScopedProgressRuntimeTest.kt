package me.manga.kira.data.repository.progress

import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.identity.ProgressHandle
import me.manga.kira.domain.model.progress.ProgressWriteResult
import me.manga.kira.domain.usecase.reader.BeginReadProgressSessionUseCase
import me.manga.kira.domain.usecase.reader.ClearScopedChapterProgressUseCase
import me.manga.kira.domain.usecase.reader.ClearScopedWorkProgressUseCase
import me.manga.kira.domain.usecase.reader.ReadScopedPagePositionUseCase
import me.manga.kira.domain.usecase.reader.SaveScopedPagePositionUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopedProgressRuntimeTest {
    @Test
    fun scoped_usecases_keep_absence_distinct_from_zero_without_opening_read_only_sessions() = runTest {
        ProgressRuntimeFixture().use { f ->
            val read = ReadScopedPagePositionUseCase(f.native)
            assertNull(read(PROGRESS_CHAPTER).progressValue())
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
            val opened = BeginReadProgressSessionUseCase(f.native)(PROGRESS_CHAPTER).progressValue()
            assertNull(opened.pageIndex)
            assertNull(opened.handle.retainedOwner)
            assertEquals(
                ProgressWriteResult.WRITTEN,
                SaveScopedPagePositionUseCase(f.native)(opened.handle, 0).progressValue(),
            )
            assertEquals(0, read(PROGRESS_CHAPTER).progressValue())
            assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
        }
    }

    @Test
    fun empty_work_clear_fences_unknown_chapters_without_letting_stale_save_create_them() = runTest {
        ProgressRuntimeFixture().use { f ->
            ClearScopedWorkProgressUseCase(f.native)(PROGRESS_WORK).progressValue()
            val work = assertNotNull(f.db.readerProgressDao().findWork(PROGRESS_WORK.api, PROGRESS_WORK.url))
            assertEquals(1L, work.workGeneration)
            assertNull(f.native.readPosition(PROGRESS_CHAPTER).progressValue())
            val inventedOldHandle = ProgressHandle(PROGRESS_CHAPTER, 0, 0)
            assertEquals(ProgressWriteResult.STALE, f.native.save(inventedOldHandle, 8).progressValue())
            assertTrue(f.db.readerProgressDao().chaptersForWork(work.workId).isEmpty())
            val fresh = f.native.beginSession(PROGRESS_CHAPTER).progressValue()
            assertEquals(1L, fresh.handle.workGeneration)
            assertNull(fresh.pageIndex)
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(fresh.handle, 4).progressValue())
            f.reopen()
            assertEquals(4, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
        }
    }

    @Test
    fun chapter_clear_rejects_old_handle_but_keeps_sibling_and_deliberate_new_session_writable() = runTest {
        ProgressRuntimeFixture().use { f ->
            val sibling = PROGRESS_CHAPTER.copy(chapterUrl = "${PROGRESS_WORK.url}/chapter/two")
            val first = f.native.beginSession(PROGRESS_CHAPTER).progressValue().handle
            val second = f.native.beginSession(sibling).progressValue().handle
            f.native.save(first, 7).progressValue()
            ClearScopedChapterProgressUseCase(f.native)(PROGRESS_CHAPTER).progressValue()
            assertEquals(ProgressWriteResult.STALE, f.native.save(first, 9).progressValue())
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(second, 0).progressValue())
            val fresh = f.native.beginSession(PROGRESS_CHAPTER).progressValue()
            assertEquals(0L, fresh.handle.workGeneration)
            assertEquals(1L, fresh.handle.chapterGeneration)
            assertNull(fresh.pageIndex)
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(fresh.handle, 3).progressValue())
            assertEquals(0, f.native.readPosition(sibling).progressValue())
        }
    }

    @Test
    fun saved_work_with_unsaved_chapter_retains_parent_but_never_invents_a_saved_child() = runTest {
        ProgressRuntimeFixture().use { f ->
            val parent = f.parent()
            val opened = f.native.beginSession(PROGRESS_CHAPTER).progressValue()
            assertEquals(parent.id, opened.handle.retainedOwner?.work?.id)
            assertNull(opened.handle.retainedOwner?.chapterId)
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(opened.handle, 9).progressValue())
            assertTrue(f.db.chapterDao().getChaptersByMangaIdR(parent.id).isEmpty())
            assertEquals(1, f.db.libraryDeo().deleteMangaById(parent.id))
            assertEquals(ProgressWriteResult.STALE, f.native.save(opened.handle, 10).progressValue())
            val deliberateUnsaved = f.native.beginSession(PROGRESS_CHAPTER).progressValue()
            assertNull(deliberateUnsaved.handle.retainedOwner)
            assertEquals(9, deliberateUnsaved.pageIndex)
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(deliberateUnsaved.handle, 11).progressValue())
        }
    }

    @Test
    fun runtime_work_clear_and_checked_parent_delete_roll_back_as_one_owning_writer() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            val handle = f.native.beginSession(family.locator).progressValue().handle
            f.native.save(handle, 5).progressValue()
            f.execute(
                "CREATE TRIGGER reject_runtime_delete BEFORE DELETE ON saved_manga " +
                    "BEGIN SELECT RAISE(ABORT, 'no'); END",
            )
            val result = progressStorageResult {
                f.owners.write {
                    clearWork(family.locator.work)
                    check(f.db.libraryDeo().deleteMangaById(family.work.id) == 1)
                }
            }
            assertTrue(result.isFailure)
            assertEquals(family.work, f.db.mangaDao().getMangaById(family.work.id))
            assertEquals(5, f.native.readPosition(family.locator).progressValue())
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(handle, 6).progressValue())
        }
    }
}
