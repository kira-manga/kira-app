package me.manga.kira.data.repository.progress

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.entity.ReaderLegacyCleanupState
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.librarySavedChapter
import me.manga.kira.domain.model.progress.LegacyProgressCleanup
import me.manga.kira.domain.model.progress.LegacyProgressDisposition
import me.manga.kira.domain.model.progress.LegacyProgressReconciliation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyProgressOwnershipTest {
    @Test
    fun unknown_legacy_work_is_retained_even_though_scoped_native_unsaved_reading_is_supported() = runTest {
        ProgressRuntimeFixture().use { f ->
            val captured = f.seedLegacy()
            val prepared = f.legacy.prepare(PROGRESS_CHAPTER).progressValue()
            assertEquals(LegacyProgressDisposition.RETAINED, prepared.disposition)
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
            assertTrue(f.db.readerLegacyCleanupDao().allReceipts().isEmpty())
            val handle = f.native.beginSession(PROGRESS_CHAPTER).progressValue().handle
            f.native.save(handle, 2).progressValue()
            assertEquals(2, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
        }
    }

    @Test
    fun saved_work_without_a_saved_chapter_is_not_unique_legacy_owner_proof() = runTest {
        ProgressRuntimeFixture().use { f ->
            val parent = f.parent()
            val captured = f.seedLegacy()
            val prepared = f.legacy.prepare(PROGRESS_CHAPTER).progressValue()
            assertEquals(LegacyProgressDisposition.RETAINED, prepared.disposition)
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
            val opened = f.native.beginSession(PROGRESS_CHAPTER).progressValue()
            assertEquals(parent.id, opened.handle.retainedOwner?.work?.id)
            assertNull(opened.handle.retainedOwner?.chapterId)
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
            assertTrue(f.db.readerLegacyCleanupDao().allReceipts().isEmpty())
        }
    }

    @Test
    fun same_raw_chapter_url_under_two_saved_works_is_not_attributed_to_either_next_caller() = runTest {
        ProgressRuntimeFixture().use { f ->
            val first = f.family()
            val second = f.family(libraryParent("https://current.test/work/two"), first.chapter.url)
            val captured = f.seedLegacy(first.locator)
            for (request in listOf(first.locator, second.locator)) {
                assertEquals(LegacyProgressDisposition.RETAINED, f.legacy.prepare(request).progressValue().disposition)
            }
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
            assertTrue(f.db.readerLegacyCleanupDao().allReceipts().isEmpty())
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
        }
    }

    @Test
    fun cross_api_saved_chapter_collision_is_retained_even_without_alias_rules_for_the_other_api() = runTest {
        ProgressRuntimeFixture().use { f ->
            val first = f.family()
            f.family(libraryParent("https://foreign.test/work/two", api = "foreign"), first.chapter.url)
            val captured = f.seedLegacy(first.locator)
            val prepared = f.legacy.prepare(first.locator).progressValue()
            assertEquals(LegacyProgressDisposition.RETAINED, prepared.disposition)
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
            assertTrue(f.db.readerLegacyCleanupDao().allReceipts().isEmpty())
        }
    }

    @Test
    fun accepted_alias_chapter_collision_is_retained_even_when_requested_saved_chapter_is_exact() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            f.chapter(librarySavedChapter(family.work, PROGRESS_PREVIOUS.chapterUrl))
            val captured = f.seedLegacy(family.locator)
            val prepared = f.legacy.prepare(family.locator).progressValue()
            assertEquals(LegacyProgressDisposition.RETAINED, prepared.disposition)
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
        }
    }

    @Test
    fun one_matching_chapter_does_not_hide_a_second_alias_saved_work_without_chapters() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            f.parent(libraryParent(PROGRESS_PREVIOUS.work.url))
            val captured = f.seedLegacy(family.locator)
            val prepared = f.legacy.prepare(family.locator).progressValue()
            assertEquals(LegacyProgressDisposition.RETAINED, prepared.disposition)
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
            assertTrue(f.db.readerLegacyCleanupDao().allReceipts().isEmpty())
        }
    }

    @Test
    fun unique_saved_alias_owner_can_transfer_only_for_its_proven_work_scope() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            val other = f.parent(libraryParent("https://current.test/work/two"))
            val captured = f.seedLegacy(PROGRESS_PREVIOUS)
            val wrongScope = PROGRESS_PREVIOUS.copy(work = family.locator.work.copy(url = other.url))
            assertEquals(LegacyProgressDisposition.RETAINED, f.legacy.prepare(wrongScope).progressValue().disposition)
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
            f.ownership.granted = true
            val prepared = f.legacy.prepare(PROGRESS_PREVIOUS).progressValue()
            assertEquals(LegacyProgressDisposition.COPIED, prepared.disposition)
            assertEquals(6, f.native.readPosition(family.locator).progressValue())
            val receipt = f.db.readerLegacyCleanupDao().allReceipts().single()
            assertEquals(captured.key, receipt.legacyKey)
            assertEquals(captured.payload, receipt.capturedPayload)
            assertNull(f.db.readerProgressDao().findWork(PROGRESS_WORK.api, other.url))
        }
    }

    @Test
    fun same_hash_other_raw_chapter_is_preserved_instead_of_using_its_saved_owner_for_the_request() = runTest {
        ProgressRuntimeFixture().use { f ->
            val first = "https://current.test/chapter/Aa"
            val collision = "https://current.test/chapter/BB"
            val family = f.family(chapterUrl = first)
            assertEquals(legacyProgressKey(first), legacyProgressKey(collision))
            val captured = f.seedLegacy(family.locator.copy(chapterUrl = collision))
            val prepared = f.legacy.prepare(family.locator).progressValue()
            assertEquals(LegacyProgressDisposition.RETAINED, prepared.disposition)
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
            assertTrue(f.db.readerLegacyCleanupDao().allReceipts().isEmpty())
        }
    }

    @Test
    fun still_active_ungated_old_writer_cannot_authorize_cleanup_or_overwrite_new_native_zero() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            val captured = f.seedLegacy(family.locator)
            val prepared = f.legacy.prepare(family.locator).progressValue()
            assertEquals(LegacyProgressCleanup.OWNERSHIP_UNAVAILABLE, prepared.cleanup)
            assertEquals(ReaderLegacyCleanupState.PENDING, f.db.readerLegacyCleanupDao().allReceipts().single().state)
            val handle = f.native.beginSession(family.locator).progressValue().handle
            f.native.save(handle, 0).progressValue()
            // Simulate an old external/ungated binary; no shipping URL-only writer is retained.
            val url = family.chapter.url
            f.settings.putString("reader.last_page." + url.hashCode().toUInt().toString(36), "$url|7")
            assertEquals(LegacyProgressReconciliation(0, 1, 0), f.legacy.reconcile().progressValue())
            assertEquals("${family.chapter.url}|7", f.settings.getStringOrNull(captured.key))
            assertEquals(0, f.native.readPosition(family.locator).progressValue())
            assertEquals(0, f.settings.removeAttempts)
        }
    }
}
