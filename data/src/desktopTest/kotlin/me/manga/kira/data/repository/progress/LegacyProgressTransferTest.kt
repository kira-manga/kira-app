package me.manga.kira.data.repository.progress

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.entity.ReaderLegacyCleanupState
import me.manga.kira.data.local.entity.ReaderLegacyDisposition
import me.manga.kira.domain.model.progress.LegacyProgressCleanup
import me.manga.kira.domain.model.progress.LegacyProgressDisposition
import me.manga.kira.domain.model.progress.ProgressWriteResult
import me.manga.kira.domain.usecase.reader.PrepareLegacyReadProgressUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyProgressTransferTest {
    @Test
    fun unique_saved_owner_commits_copy_and_full_receipt_before_settings_cleanup() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            val captured = f.seedLegacy(family.locator, pageText = "06")
            f.ownership.granted = true
            f.settings.beforeRemove = {
                assertFalse(f.transactions.insideWriter)
                assertTrue(f.ownership.held)
            }
            val prepared = PrepareLegacyReadProgressUseCase(f.legacy)(family.locator).progressValue()
            assertEquals(LegacyProgressDisposition.COPIED, prepared.disposition)
            assertEquals(LegacyProgressCleanup.ACKNOWLEDGED, prepared.cleanup)
            assertEquals(6, f.native.readPosition(family.locator).progressValue())
            val receipt = f.db.readerLegacyCleanupDao().allReceipts().single()
            assertEquals(captured.key, receipt.legacyKey)
            assertEquals(captured.payload, receipt.capturedPayload)
            assertEquals(ReaderLegacyDisposition.COPIED, receipt.capture.disposition)
            assertEquals(ReaderLegacyCleanupState.ACKED, receipt.state)
            assertNull(f.settings.getStringOrNull(captured.key))
        }
    }

    @Test
    fun explicit_native_page_zero_supersedes_legacy_even_at_generation_zero() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            val handle = f.native.beginSession(family.locator).progressValue().handle
            f.native.save(handle, 0).progressValue()
            f.seedLegacy(family.locator)
            f.ownership.granted = true
            val prepared = f.legacy.prepare(family.locator).progressValue()
            assertEquals(LegacyProgressDisposition.SUPERSEDED, prepared.disposition)
            assertEquals(0, f.native.readPosition(family.locator).progressValue())
            val capture = f.db.readerLegacyCleanupDao().allReceipts().single().capture
            assertEquals(0L, capture.capturedWorkGeneration)
            assertEquals(0L, capture.capturedChapterGeneration)
            assertEquals(ReaderLegacyDisposition.SUPERSEDED, capture.disposition)
        }
    }

    @Test
    fun work_clear_fence_supersedes_legacy_for_a_chapter_with_no_previous_progress_anchor() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            f.native.clearWork(family.locator.work).progressValue()
            f.seedLegacy(family.locator)
            f.ownership.granted = true
            val prepared = f.legacy.prepare(family.locator).progressValue()
            assertEquals(LegacyProgressDisposition.SUPERSEDED, prepared.disposition)
            assertNull(f.native.readPosition(family.locator).progressValue())
            val capture = f.db.readerLegacyCleanupDao().allReceipts().single().capture
            assertEquals(1L, capture.capturedWorkGeneration)
            assertEquals(0L, capture.capturedChapterGeneration)
            assertEquals(ReaderLegacyDisposition.SUPERSEDED, capture.disposition)
        }
    }

    @Test
    fun chapter_clear_blocks_legacy_but_a_deliberate_native_session_can_save_first_page() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            f.native.clearChapter(family.locator).progressValue()
            f.seedLegacy(family.locator)
            f.ownership.granted = true
            val prepared = f.legacy.prepare(family.locator).progressValue()
            assertEquals(LegacyProgressDisposition.SUPERSEDED, prepared.disposition)
            val fresh = f.native.beginSession(family.locator).progressValue()
            assertNull(fresh.pageIndex)
            assertEquals(1L, fresh.handle.chapterGeneration)
            assertEquals(ProgressWriteResult.WRITTEN, f.native.save(fresh.handle, 0).progressValue())
            val receipt = f.db.readerLegacyCleanupDao().allReceipts().single()
            assertEquals(ReaderLegacyDisposition.SUPERSEDED, receipt.capture.disposition)
        }
    }

    @Test
    fun failed_room_commit_rolls_back_alias_moves_copy_and_receipt_without_touching_settings() = runTest {
        ProgressRuntimeFixture().use { f ->
            f.family()
            val before = f.db.readerProgressDao().ensureSnapshot(
                PROGRESS_PREVIOUS.work.api, PROGRESS_PREVIOUS.work.url, PROGRESS_PREVIOUS.chapterUrl,
            )
            val captured = f.seedLegacy(PROGRESS_PREVIOUS)
            f.ownership.granted = true
            f.transactions.beforeCommit = { error("Controlled precommit failure") }
            assertTrue(f.legacy.prepare(PROGRESS_PREVIOUS).isFailure)
            f.transactions.beforeCommit = {}
            assertEquals(
                before,
                f.db.readerProgressDao().findSnapshot(
                    PROGRESS_PREVIOUS.work.api, PROGRESS_PREVIOUS.work.url, PROGRESS_PREVIOUS.chapterUrl,
                ),
            )
            assertNull(f.db.readerProgressDao().findWork(PROGRESS_WORK.api, PROGRESS_WORK.url))
            assertTrue(f.db.readerLegacyCleanupDao().allReceipts().isEmpty())
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
            assertEquals(0, f.settings.removeAttempts)
        }
    }

    @Test
    fun exact_existing_receipt_is_cleanup_only_even_when_page_is_absent_and_both_epochs_are_zero() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            val captured = f.seedLegacy(family.locator)
            f.ownership.granted = true
            f.legacy.prepare(family.locator).progressValue()
            val receipt = f.db.readerLegacyCleanupDao().allReceipts().single()
            f.execute("UPDATE reader_chapter_state SET pageIndex = NULL WHERE chapterId = ${receipt.chapterId}")
            f.settings.putString(captured.key, captured.payload)
            val repeated = f.legacy.prepare(family.locator).progressValue()
            assertEquals(LegacyProgressDisposition.CLEANUP_ONLY, repeated.disposition)
            assertNull(f.native.readPosition(family.locator).progressValue())
            assertEquals(receipt, f.db.readerLegacyCleanupDao().allReceipts().single())
            val fresh = f.native.beginSession(family.locator).progressValue()
            assertEquals(0L, fresh.handle.workGeneration)
            assertEquals(0L, fresh.handle.chapterGeneration)
        }
    }

    @Test
    fun changed_payload_gets_its_own_receipt_without_overwriting_already_native_progress() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family()
            f.seedLegacy(family.locator, pageText = "5")
            f.ownership.granted = true
            f.legacy.prepare(family.locator).progressValue()
            val original = f.db.readerLegacyCleanupDao().allReceipts().single()
            val changed = f.seedLegacy(family.locator, pageText = "9")
            val prepared = f.legacy.prepare(family.locator).progressValue()
            assertEquals(LegacyProgressDisposition.SUPERSEDED, prepared.disposition)
            val receipts = f.db.readerLegacyCleanupDao().allReceipts()
            assertEquals(2, receipts.size)
            assertTrue(original in receipts)
            assertNotNull(receipts.singleOrNull { it.capturedPayload == changed.payload })
            assertEquals(5, f.native.readPosition(family.locator).progressValue())
        }
    }
}
