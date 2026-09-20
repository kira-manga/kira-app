package me.manga.kira.data.repository.progress

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.libraryPolicy
import me.manga.kira.data.repository.librarySavedChapter
import me.manga.kira.domain.model.identity.SavedProgressOwner
import me.manga.kira.domain.model.progress.ProgressWriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScopedProgressOwnershipTest {
    @Test
    fun retained_work_id_rejects_delete_readd_even_when_progress_epochs_did_not_change() = runTest {
        ProgressRuntimeFixture().use { f ->
            val original = f.family()
            val handle = f.native.beginSession(original.locator).progressValue().handle
            f.native.save(handle, 3).progressValue()
            assertEquals(1, f.db.libraryDeo().deleteMangaById(original.work.id))
            val replacement = f.family()
            assertNotEquals(original.work.id, replacement.work.id)
            assertEquals(ProgressWriteResult.STALE, f.native.save(handle, 8).progressValue())
            val fresh = f.native.beginSession(replacement.locator).progressValue()
            assertEquals(handle.workGeneration, fresh.handle.workGeneration)
            assertEquals(handle.chapterGeneration, fresh.handle.chapterGeneration)
            assertEquals(replacement.owner, fresh.handle.retainedOwner)
            assertEquals(3, fresh.pageIndex)
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(fresh.handle, 5).progressValue())
        }
    }

    @Test
    fun retained_chapter_id_rejects_same_parent_same_url_replacement() = runTest {
        ProgressRuntimeFixture().use { f ->
            val original = f.family()
            val handle = f.native.beginSession(original.locator).progressValue().handle
            f.native.save(handle, 3).progressValue()
            f.execute("DELETE FROM saved_chapters WHERE id = ${original.chapter.id}")
            val replacement = f.chapter(librarySavedChapter(original.work, original.chapter.url))
            assertNotEquals(original.chapter.id, replacement.id)
            assertEquals(ProgressWriteResult.STALE, f.native.save(handle, 8).progressValue())
            val fresh = f.native.beginSession(original.locator).progressValue()
            assertEquals(replacement.id, fresh.handle.retainedOwner?.chapterId)
            assertEquals(3, fresh.pageIndex)
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(fresh.handle, 4).progressValue())
        }
    }

    @Test
    fun foreign_retained_chapter_and_mismatched_retained_work_locator_cannot_write() = runTest {
        ProgressRuntimeFixture().use { f ->
            val first = f.family()
            val second = f.family(libraryParent("https://current.test/work/two"), first.chapter.url)
            val handle = f.native.beginSession(first.locator).progressValue().handle
            val foreignChild = handle.copy(retainedOwner = SavedProgressOwner(first.owner.work, second.chapter.id))
            assertEquals(ProgressWriteResult.STALE, f.native.save(foreignChild, 8).progressValue())
            val foreignWork = first.owner.work.copy(locator = second.locator.work)
            val mismatched = handle.copy(retainedOwner = SavedProgressOwner(foreignWork, first.chapter.id))
            assertEquals(ProgressWriteResult.STALE, f.native.save(mismatched, 8).progressValue())
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(handle, 0).progressValue())
            assertEquals(0, f.native.readPosition(first.locator).progressValue())
        }
    }

    @Test
    fun foreign_api_exact_saved_occupancy_is_not_bypassed_by_an_existing_unsaved_anchor() = runTest {
        ProgressRuntimeFixture().use { f ->
            val handle = f.native.beginSession(PROGRESS_CHAPTER).progressValue().handle
            f.native.save(handle, 2).progressValue()
            f.parent(libraryParent(api = "foreign"))
            assertTrue(f.native.beginSession(PROGRESS_CHAPTER).isFailure)
            assertTrue(f.native.readPosition(PROGRESS_CHAPTER).isFailure)
            assertTrue(f.native.save(handle, 9).isFailure)
            assertTrue(f.native.clearWork(PROGRESS_WORK).isFailure)
            val current = f.db.readerProgressDao().findSnapshot(
                PROGRESS_WORK.api, PROGRESS_WORK.url, PROGRESS_CHAPTER.chapterUrl,
            )
            assertEquals(2, current?.pageIndex)
            assertEquals(0L, current?.workGeneration)
        }
    }

    @Test
    fun distinct_retained_alias_address_rechecks_its_own_global_exact_occupancy() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            val opened = f.native.beginSession(family.locator).progressValue().handle
            val retainedAlias = family.owner.work.copy(locator = PROGRESS_PREVIOUS.work)
            val handle = opened.copy(retainedOwner = SavedProgressOwner(retainedAlias, family.chapter.id))
            f.parent(libraryParent(PROGRESS_PREVIOUS.work.url, api = "foreign"))
            assertTrue(f.native.save(handle, 8).isFailure)
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(opened, 0).progressValue())
            assertEquals(0, f.native.readPosition(family.locator).progressValue())
        }
    }

    @Test
    fun accepted_policy_is_acquired_only_after_waiting_for_the_actual_room_writer() = runTest {
        ProgressRuntimeFixture().use { f ->
            f.family()
            val result = f.afterWriterWait(
                operation = { f.native.beginSession(PROGRESS_PREVIOUS) },
                change = {
                    assertEquals(0, f.snapshots.readCount)
                    f.snapshots.accept(libraryPolicy(revision = 2L, previousHosts = emptyList()))
                },
            )
            assertTrue(result.isFailure)
            assertEquals(1, f.snapshots.readCount)
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
        }
    }

    @Test
    fun provider_failure_is_not_replaced_with_an_empty_policy_even_for_an_exact_anchor() = runTest {
        ProgressRuntimeFixture().use { f ->
            val handle = f.native.beginSession(PROGRESS_CHAPTER).progressValue().handle
            f.native.save(handle, 4).progressValue()
            val unavailable = object : SourceAliasSnapshotProvider {
                override val readiness = f.snapshots.readiness
                override suspend fun readInTransaction(): SourceAliasSnapshot {
                    check(f.transactions.insideWriter)
                    error("Controlled selection not ready")
                }
            }
            val owners = ProgressOwnerTransactions(f.transactions, unavailable, f.storage)
            val repository = RoomScopedReadProgressRepository(owners)
            assertTrue(repository.beginSession(PROGRESS_CHAPTER).isFailure)
            assertTrue(repository.save(handle, 7).isFailure)
            assertTrue(repository.clearWork(PROGRESS_WORK).isFailure)
            assertEquals(4, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
        }
    }

    @Test
    fun cancellation_after_native_write_escapes_boundary_and_rolls_back_the_page() = runTest {
        ProgressRuntimeFixture().use { f ->
            val handle = f.native.beginSession(PROGRESS_CHAPTER).progressValue().handle
            f.native.save(handle, 3).progressValue()
            f.transactions.beforeCommit = { throw CancellationException("Controlled cancellation") }
            assertFailsWith<CancellationException> { f.native.save(handle, 8) }
            f.transactions.beforeCommit = {}
            assertEquals(3, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(handle, 0).progressValue())
        }
    }
}
