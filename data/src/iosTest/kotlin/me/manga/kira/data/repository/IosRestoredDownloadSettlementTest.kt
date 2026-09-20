package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same-engine recovery of a genuinely committed Room repair; no synthetic settlement or OS proof. */
class IosRestoredDownloadSettlementTest {
    @Test
    fun nextHealthyReconcileRecoversCommittedRepairAfterLostIoOrCancellationReturn() = runTest {
        for (cancelledReturn in listOf(false, true)) withLostRestoredRepairReturn(cancelledReturn) {
            assertUnknownRetryRetainsCustody()
            engine.reconcileInterruptedDownloads()
            faults.reads.receive()
            assertRetainedFailure()
            val failed = fixture.download(chapter)
            faults.readbackUnavailable = false
            engine.reconcileInterruptedDownloads()
            released.await() // Actual engine release, not another startup or test-invoked settlement.
            assertNull(runtime.ownership.currentClaim(chapter.saved.id))
            assertEquals(failed, fixture.download(chapter))
            assertRetainedFiles()
            assertTrue(transport.enqueued.isEmpty(), "Readback/settlement cannot itself restart downloads")
            assertTrue(engine.retryChapterDownload(failed))
            assertReplacementTransfer()
            assertFalse(engine.retryChapterDownload(failed), "The old ledger cannot reserve a second Retry")
        }
    }

    @Test
    fun explicitRetryRecoversCommittedRepairAfterLostIoOrCancellationReturnWithoutAnotherReconcile() = runTest {
        for (cancelledReturn in listOf(false, true)) withLostRestoredRepairReturn(cancelledReturn) {
            assertUnknownRetryRetainsCustody()
            val failed = fixture.download(chapter)
            faults.readbackUnavailable = false
            assertTrue(engine.retryChapterDownload(failed), "A healthy Retry must not need an engine reopen or pump")
            assertTrue(released.isCompleted)
            assertReplacementTransfer()
            assertFalse(engine.retryChapterDownload(failed))
        }
    }

    @Test
    fun uncommittedRepairExceptionCannotBecomeFailedOrAuthorizeReleaseAndReplacement() = runTest {
        withLostRestoredRepairReturn(cancelledReturn = false, beforeRepair = { faults.failBeforeCommit = true }) {
            faults.readbackUnavailable = false
            val staleFailure = fixture.download(chapter).copy(state = DownloadingState.FAILED, progress = 0, sizeBytes = 0)
            assertFalse(engine.retryChapterDownload(staleFailure))
            engine.reconcileInterruptedDownloads()
            assertEquals(2, faults.writes)
            assertUncommittedRepair()
        }
    }

    @Test
    fun readbackCannotTakeOverActiveUnrevokedMismatchedCancelledOrFailedCleanupCustody() = runTest {
        for (change in listOf("active", "unrevoked", "ledger", "token", "owner", "cancel", "cleanup")) {
            withLostRestoredRepairReturn(cancelledReturn = false) {
                val failed = fixture.download(chapter)
                changeRetainedFailure(change)
                val expectedRow = fixture.download(chapter)
                val expectedRecord = fixture.db.chapterArtifactDao().get(chapter.saved.id)
                val expectedSaved = fixture.saved(chapter)
                faults.readbackUnavailable = false
                engine.reconcileInterruptedDownloads()
                assertFalse(engine.retryChapterDownload(failed), change)
                host.cancelAndJoin() // Join all real scheduled attempts before checking non-mutation.
                assertEquals(expectedRow, fixture.download(chapter), change)
                assertEquals(expectedRecord, fixture.db.chapterArtifactDao().get(chapter.saved.id), change)
                assertEquals(expectedSaved, fixture.saved(chapter), change)
                assertEquals(0, releases, change)
                assertRetainedFiles()
                assertTrue(transport.enqueued.isEmpty(), change)
                assertTrue(transport.cancelled.isEmpty(), change)
            }
        }
    }

    @Test
    fun retryDrainsOriginalUsersOutsideEngineMutexAndCancelledWaiterNeverStartsReplacement() = runTest {
        for (cancelWaiter in listOf(false, true)) {
            val receipt = CompletableDeferred<Unit>()
            var producer: Job? = null
            withLostRestoredRepairReturn(cancelledReturn = true, beforeRepair = {
                producer = holdOriginalUser(backgroundScope, receipt)
            }) {
                assertUnknownRetryRetainsCustody()
                val failed = fixture.download(chapter)
                faults.readbackUnavailable = false
                val retry = backgroundScope.async { engine.retryChapterDownload(failed) }
                settling.await()
                assertFalse(retry.isCompleted)
                assertFalse(released.isCompleted, "A revoked token still belongs to the actual original user")
                assertFailsWith<DownloadOperationBusy> { fixture.operations.withExclusive {} }
                if (cancelWaiter) retry.cancel()
                releaseUser.complete(Unit)
                receipt.await() // The user's old-token callback needs the engine mutex before it can finish.
                assertNotNull(producer).join()
                if (cancelWaiter) {
                    assertCancelledRetryReleasedWithoutReplacement(retry, failed)
                    assertTrue(engine.retryChapterDownload(failed))
                } else assertTrue(retry.await())
                assertReplacementTransfer()
            }
        }
    }

    @Test
    fun changedFailureIsRecheckedAfterTheActualOriginalUserDrains() = runTest {
        for (change in listOf("active", "cancel", "cleanup", "token")) {
            var producer: Job? = null
            withLostRestoredRepairReturn(cancelledReturn = false, beforeRepair = {
                producer = holdOriginalUser(backgroundScope)
            }) {
                faults.readbackUnavailable = false
                engine.reconcileInterruptedDownloads()
                settling.await() // The first exact readback succeeded and settlement now waits for the real user.
                assertFalse(released.isCompleted)
                changeRetainedFailure(change)
                val expectedRow = fixture.download(chapter)
                val expectedRecord = fixture.db.chapterArtifactDao().get(chapter.saved.id)
                releaseUser.complete(Unit)
                assertNotNull(producer).join()
                host.cancelAndJoin()
                assertEquals(expectedRow, fixture.download(chapter), change)
                assertEquals(expectedRecord, fixture.db.chapterArtifactDao().get(chapter.saved.id), change)
                assertEquals(0, releases, "The pre-drain snapshot cannot release changed $change custody")
                assertRetainedFiles()
                assertTrue(transport.enqueued.isEmpty())
            }
        }
    }
}

private suspend fun RestoredSettlementCase.assertCancelledRetryReleasedWithoutReplacement(
    retry: Deferred<Boolean>, failed: ChapterDownloadEntity,
) {
    retry.join()
    assertTrue(retry.isCancelled)
    assertTrue(released.isCompleted)
    assertEquals(failed, fixture.download(chapter))
    assertNull(runtime.ownership.currentClaim(chapter.saved.id))
    assertRetainedFiles()
    assertTrue(transport.enqueued.isEmpty(), "Cancelled Retry must rethrow instead of reserving work")
}

private suspend fun RestoredSettlementCase.changeRetainedFailure(change: String) {
    val records = fixture.db.chapterArtifactDao()
    val record = assertNotNull(records.get(chapter.saved.id))
    val row = fixture.download(chapter)
    when (change) {
        "active" -> fixture.dao.updateStateChId(chapter.saved.id, DownloadingState.RUNNING)
        "unrevoked" -> assertEquals(1, records.update(record.copy(retiring = false)))
        "ledger" -> fixture.dao.insert(row.copy(id = 0))
        "token" -> assertEquals(1, records.update(record.copy(token = "different-retained-download-token")))
        "owner" -> fixture.db.backupDao().updateChapterRow(fixture.saved(chapter).copy(url = "https://example.test/replaced-owner"))
        "cancel" -> assertNotNull(runtime.downloads.cancel(chapter.saved.id, DownloadedChapter.CANCELLED_BY_USER_SENTINEL))
        "cleanup" -> assertEquals(ChapterArtifactOperation.FAILED_CLEANUP,
            assertNotNull(runtime.ownership.beginFailedCleanup(row)).operation)
        else -> error("Unknown fixture mutation")
    }
}

private suspend fun RestoredSettlementCase.holdOriginalUser(
    scope: CoroutineScope,
    receipt: CompletableDeferred<Unit>? = null,
): Job {
    val entered = CompletableDeferred<Unit>()
    val producer = scope.launch {
        fixture.operations.withOperation { operation ->
            assertNotNull(runtime.ownership.producing(original) {
                entered.complete(Unit)
                releaseUser.await()
                if (receipt != null) {
                    engine.onPageFailed(chapter.saved.mangaId, chapter.saved.id, 1, original.token, "late old task", operation) {
                        receipt.complete(Unit)
                    }
                    receipt.await()
                }
            })
        }
    }
    entered.await()
    return producer
}
