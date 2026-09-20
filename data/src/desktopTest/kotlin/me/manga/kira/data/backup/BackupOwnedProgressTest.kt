package me.manga.kira.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.local.entity.ReaderLegacyCleanupState
import me.manga.kira.data.repository.libraryHistory
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.libraryPolicy
import me.manga.kira.data.repository.progress.CapturedLegacyProgress
import me.manga.kira.data.repository.progress.PROGRESS_CHAPTER
import me.manga.kira.data.repository.progress.PROGRESS_PREVIOUS
import me.manga.kira.data.repository.progress.PROGRESS_WORK
import me.manga.kira.data.repository.progress.ProgressRuntimeFixture
import me.manga.kira.data.repository.progress.ProgressSavedFamily
import me.manga.kira.data.repository.progress.afterWriterWait
import me.manga.kira.data.repository.progress.family
import me.manga.kira.data.repository.progress.progressValue
import me.manga.kira.data.repository.progress.seedLegacy
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.model.identity.WorkLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Production progress/receipt logic over real Room; fake Settings ownership is NOT platform proof. */
class BackupOwnedProgressTest {
    @Test
    fun resume_uses_date_before_merge_and_absence_not_max_page() = runTest {
        val cases = listOf(Triple(null, 302L, 7), Triple(0, 303L, 0), Triple(99, 304L, 7), Triple(8, 302L, 8))
        for ((localPage, incomingDate, expected) in cases) ProgressRuntimeFixture().use { f ->
            val family = f.family()
            if (localPage != null) f.saveNative(localPage)
            f.mergeBackup(backupDocument(page = 7, readAt = incomingDate))
            assertEquals(expected, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
            val stored = f.db.backupDao().getChaptersForManga(family.work.id).single()
            assertEquals(maxOf(303L, incomingDate), stored.lastReadDate)
        }
    }

    @Test
    fun clears_and_unsaved_native_pages_survive_new_saved_children_and_readded_parents() = runTest {
        for (initial in NativeInitial.entries) ProgressRuntimeFixture().use { f ->
            f.prepareInitial(initial)
            f.mergeBackup(backupDocument(page = 7))
            val expected = when (initial) {
                NativeInitial.ZERO -> 0
                NativeInitial.PAGE -> 4
                else -> null
            }
            assertEquals(expected, f.native.readPosition(PROGRESS_CHAPTER).progressValue(), initial.name)
            assertEquals(1, f.db.backupDao().getAllSavedManga().size)
            if (initial == NativeInitial.WORK_CLEAR) {
                val work = assertNotNull(f.db.readerProgressDao().findWork(PROGRESS_WORK.api, PROGRESS_WORK.url))
                assertEquals(1L, work.workGeneration)
                assertTrue(f.db.readerProgressDao().chaptersForWork(work.workId).isEmpty())
            }
        }
    }

    @Test
    fun unowned_or_unknown_legacy_cannot_gain_saved_proof_on_a_later_import() = runTest {
        for (pageText in listOf("6", "unknown")) ProgressRuntimeFixture().use { f ->
            val captured = f.seedLegacy(pageText = pageText)
            f.ownership.granted = true
            repeat(2) {
                val failure = assertFailsWith<BackupOwnershipException> { f.mergeBackup(backupDocument(page = 7)) }
                assertEquals("backup_legacy_owner_unproven", failure.message)
                assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
                assertNull(f.db.chapterDao().getChapterIdByUrl(PROGRESS_WORK.url, PROGRESS_CHAPTER.chapterUrl))
                assertTrue(f.db.backupDao().getAllHistoryOnce().isEmpty())
                assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
                assertTrue(f.db.readerLegacyCleanupDao().allReceipts().isEmpty())
                assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
                assertEquals(0, f.settings.removeAttempts)
            }
        }
    }

    @Test
    fun native_export_is_read_only_until_explicit_zero_import() = runTest {
        ProgressRuntimeFixture().use { f ->
            f.family()
            val exported = assertNotNull(f.backupWriter().export(BackupScope.FullLibrary) { false })
            assertNull(exported.single().chapters.single().pageIndex)
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
            f.mergeBackup(backupDocument(page = 0))
            assertEquals(0, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
        }
    }

    @Test
    fun observed_old_raw_zero_receipt_survives_reopen_and_readded_parent_without_recopy() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family(libraryParent(PROGRESS_PREVIOUS.work.url), PROGRESS_PREVIOUS.chapterUrl)
            val captured = f.seedLegacy(PROGRESS_PREVIOUS, pageText = "0")
            f.ownership.granted = true
            f.mergeBackup(backupDocument())
            assertEquals(0, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
            assertEquals(ReaderLegacyCleanupState.ACKED, f.db.readerLegacyCleanupDao().allReceipts().single().state)
            assertNull(f.settings.getStringOrNull(captured.key))
            f.removeForFixture(family.work.id, PROGRESS_PREVIOUS.work)
            f.saveNative(8)
            f.settings.putString(captured.key, captured.payload)
            f.reopen()
            f.mergeBackup(backupDocument())
            assertEquals(8, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
            assertEquals(1, f.db.readerLegacyCleanupDao().allReceipts().size)
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
            f.mergeBackup(backupDocument())
            assertNull(f.settings.getStringOrNull(captured.key))
        }
    }

    @Test
    fun late_group_failure_rolls_back_saved_history_native_and_receipts_before_cleanup() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family(libraryParent().copy(isLiked = false, isWatchingNow = false))
            val captured = f.seedLegacy()
            f.ownership.granted = true
            val document = backupDocument(page = 7).copy(history = listOf(libraryHistory(family.work).toBackup()))
            val writer = f.backupWriter()
            val batch = writer.batch(document)
            var commits = 0
            f.transactions.beforeCommit = { if (++commits == 2) error("late group failure") }
            assertFailsWith<IllegalStateException> { writer.importGroup(batch, batch.groups.single()) }
            f.transactions.beforeCommit = {}
            f.assertRolledBack(family, captured)
        }
    }

    @Test
    fun ignored_child_insert_rolls_back_earlier_merges_and_legacy_transfer_without_relookup() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family(libraryParent().copy(isLiked = false, isWatchingNow = false))
            val captured = f.seedLegacy()
            f.ownership.granted = true
            val refusedUrl = "${PROGRESS_CHAPTER.chapterUrl}-refused"
            f.execute(
                """
                CREATE TRIGGER backup_refuse_child BEFORE INSERT ON saved_chapters
                WHEN NEW.url = '$refusedUrl' BEGIN SELECT RAISE(IGNORE); END
                """.trimIndent(),
            )
            val original = backupDocument(page = 7).mangas.single()
            val incoming = original.copy(chapters = original.chapters + BackupChapter(url = refusedUrl))
            val failure = assertFailsWith<BackupOwnershipException> {
                f.mergeBackup(BackupFile(mangas = listOf(incoming)))
            }
            assertEquals("backup_chapter_insert_conflict", failure.message)
            f.assertRolledBack(family, captured)
        }
    }

    @Test
    fun accepted_selection_is_read_after_the_actual_writer_wait_and_changed_token_stops_import() = runTest {
        ProgressRuntimeFixture().use { f ->
            val writer = f.backupWriter()
            val batch = writer.batch(backupDocument(page = 0))
            f.afterWriterWait(
                operation = {
                    assertFailsWith<BackupOwnershipException> { writer.importGroup(batch, batch.groups.single()) }
                },
                change = { f.snapshots.accept(libraryPolicy(revision = 2)) },
            )
            assertEquals(2, f.snapshots.readCount)
            assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
        }
    }

    @Test
    fun save_clear_and_replaced_ids_are_rechecked_after_settings_capture_wait() = runTest {
        for (change in PendingChange.entries) ProgressRuntimeFixture().use { f ->
            val family = f.family()
            val writer = f.backupWriter()
            val document = backupDocument(page = 7, readAt = family.chapter.lastReadDate)
            val batch = writer.batch(document)
            val operation: suspend () -> BackupOwnedMerge = { writer.importGroup(batch, batch.groups.single()) }
            val mutate: suspend () -> Unit = { f.changePending(change, family.work.id) }
            if (change == PendingChange.REPLACE) {
                assertFailsWith<BackupOwnershipException> { f.afterCaptureObservation(operation, mutate) }
                assertTrue(f.db.backupDao().getAllSavedManga().single().id != family.work.id)
            } else {
                f.afterCaptureObservation(operation, mutate)
                assertEquals(
                    if (change == PendingChange.SAVE) 0 else null,
                    f.native.readPosition(PROGRESS_CHAPTER).progressValue(),
                )
                f.saveNative(8)
                f.mergeBackup(document)
                assertEquals(8, f.native.readPosition(PROGRESS_CHAPTER).progressValue())
                f.native.clearWork(PROGRESS_WORK).progressValue()
                f.mergeBackup(document)
            }
            assertNull(f.native.readPosition(PROGRESS_CHAPTER).progressValue())
        }
    }

    @Test
    fun progress_only_exact_anchor_recovery_cannot_authorize_new_saved_owner_insertion() = runTest {
        ProgressRuntimeFixture().use { f ->
            val url = "https://unproved.test/work/one"
            val snapshot = f.db.readerProgressDao().ensureSnapshot(PROGRESS_WORK.api, url, "$url/chapter")
            assertTrue(f.db.readerProgressDao().savePosition(snapshot, 0))
            val incoming = backupDocument().mangas.single().copy(url = url, chapters = emptyList())
            assertFailsWith<BackupOwnershipException> { f.mergeBackup(BackupFile(mangas = listOf(incoming))) }
            assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
            assertEquals(0, f.db.readerProgressDao().findSnapshot(PROGRESS_WORK.api, url, "$url/chapter")?.pageIndex)
        }
    }
}

private suspend fun ProgressRuntimeFixture.saveNative(page: Int) {
    native.save(native.beginSession(PROGRESS_CHAPTER).progressValue().handle, page).progressValue()
}

private suspend fun ProgressRuntimeFixture.assertRolledBack(
    family: ProgressSavedFamily,
    captured: CapturedLegacyProgress,
) {
    assertEquals(listOf(family.work), db.backupDao().getAllSavedManga())
    assertEquals(listOf(family.chapter), db.backupDao().getChaptersForManga(family.work.id))
    assertTrue(db.backupDao().getAllHistoryOnce().isEmpty())
    assertTrue(db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
    assertTrue(db.readerLegacyCleanupDao().allReceipts().isEmpty())
    assertEquals(captured.payload, settings.getStringOrNull(captured.key))
    assertEquals(0, settings.removeAttempts)
}

private enum class NativeInitial { ZERO, PAGE, WORK_CLEAR, CHAPTER_CLEAR, REMOVED_PARENT }
private enum class PendingChange { SAVE, CLEAR_CHAPTER, CLEAR_WORK, REPLACE }

private suspend fun ProgressRuntimeFixture.prepareInitial(initial: NativeInitial) {
    when (initial) {
        NativeInitial.ZERO -> saveNative(0)
        NativeInitial.PAGE -> saveNative(4)
        NativeInitial.WORK_CLEAR -> native.clearWork(PROGRESS_WORK).progressValue()
        NativeInitial.CHAPTER_CLEAR -> native.clearChapter(PROGRESS_CHAPTER).progressValue()
        NativeInitial.REMOVED_PARENT -> removeForFixture(family().work.id)
    }
}

private suspend fun ProgressRuntimeFixture.removeForFixture(
    id: Long,
    work: WorkLocator = PROGRESS_WORK,
) = owners.write {
    // Same-writer durable clear/removal state, NOT a claim about the platform engine-exclusion lease.
    clearWork(work)
    check(db.libraryDeo().deleteMangaForExactOwner(id, work.api, work.url) == 1)
}

private suspend fun ProgressRuntimeFixture.changePending(change: PendingChange, id: Long) {
    when (change) {
        PendingChange.SAVE -> saveNative(0)
        PendingChange.CLEAR_CHAPTER -> native.clearChapter(PROGRESS_CHAPTER).progressValue()
        PendingChange.CLEAR_WORK -> native.clearWork(PROGRESS_WORK).progressValue()
        PendingChange.REPLACE -> {
            removeForFixture(id)
            parent()
        }
    }
}

/** Gate holder and competing Room writer are different coroutines; no Settings callback takes Room. */
private suspend fun <T> ProgressRuntimeFixture.afterCaptureObservation(
    operation: suspend () -> T,
    change: suspend () -> Unit,
): T = coroutineScope {
    val held = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val observed = CompletableDeferred<Unit>()
    val holder = launch(Dispatchers.Default) { gate.withAccess { held.complete(Unit); release.await() } }
    held.await()
    transactions.beforeCommit = { observed.complete(Unit) }
    val pending = async(Dispatchers.Default) { runCatchingCancellable { operation() } }
    try {
        observed.await()
        change()
    } finally {
        transactions.beforeCommit = {}
        release.complete(Unit)
    }
    holder.join()
    pending.await().getOrThrow()
}
