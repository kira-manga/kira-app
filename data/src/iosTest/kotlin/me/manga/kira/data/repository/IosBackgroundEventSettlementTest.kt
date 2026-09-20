package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.platform.download.BackgroundScheduler
import me.manga.kira.platform.download.BackgroundWorkSignal
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.notification.DownloadNotifier
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.BackgroundDownloadHost
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Room/manifest/receiver settlement; native transport-window custody is tested in :platform. */
class IosBackgroundEventSettlementTest {
    @Test
    fun pageReceiptWaitsForOriginalTokenRoomProgressWrite() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        val gate = HeldSettlementWrite()
        try {
            verifyProgressReceipt(fixture, CoroutineScope(coroutineContext + hostJob), gate)
        } finally {
            gate.release.complete(Unit)
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    private suspend fun verifyProgressReceipt(fixture: IosCbzFinalizationFixture, scope: CoroutineScope, gate: HeldSettlementWrite) {
        val chapter = fixture.seed()
        val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, pageCount = 2)
        fixture.dao.updateProgress(chapter.saved.id, 0)
        val transport = ArtifactTestTransport(fixture.operations)
        fixture.engine(scope, transport, downloads = fixture.dao.holdProgressUpdate(gate))
        val page = ReceiverPage(fixture, chapter, "progress", failPublication = false)
        val acknowledged = transport.deliverPage(chapter, claim.token, page.page)
        gate.entered.await()
        assertFalse(acknowledged.isCompleted)
        assertFailsWith<DownloadOperationBusy> { fixture.operations.withExclusive {} }
        assertEquals(0, fixture.download(chapter).progress)
        gate.release.complete(Unit)
        acknowledged.await()
        assertEquals(50, fixture.download(chapter).progress)
        assertEquals(DownloadingState.RUNNING, fixture.download(chapter).state)
        assertEquals(1, page.publications)
        assertFalse(fixture.system.exists(page.path))
        val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
        assertTrue(fixture.system.exists(directory / "image_0.png"))
    }

    @Test
    fun failureReceiptWaitsForManifestAndOwnedStageButNotForRetryTransfer() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        val manifest = HeldSettlementWrite()
        val disposal = HeldSettlementWrite()
        val retry = HeldSettlementWrite()
        try {
            verifyFailureReceipt(fixture, CoroutineScope(Dispatchers.Default + hostJob), manifest, disposal, retry)
        } finally {
            manifest.release.complete(Unit)
            disposal.release.complete(Unit)
            hostJob.cancelAndJoin()
            retry.release.complete(Unit)
            fixture.close()
        }
    }

    private suspend fun verifyFailureReceipt(
        fixture: IosCbzFinalizationFixture,
        scope: CoroutineScope,
        manifest: HeldSettlementWrite,
        disposal: HeldSettlementWrite,
        retry: HeldSettlementWrite,
    ) {
        val chapter = fixture.seed()
        val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING)
        val transport = ArtifactTestTransport(fixture.operations)
        fixture.engine(scope, transport.holdNextEnqueue(retry), files = fixture.holdManifestMove(manifest))
        val page = ReceiverPage(fixture, chapter, "retry", beforeDiscard = disposal::awaitRelease)
        val acknowledged = scope.async { transport.deliverPage(chapter, claim.token, page.page).await() }
        manifest.entered.await()
        assertEquals(0, fixture.manifest(chapter).pages.single().attempts)
        assertFalse(acknowledged.isCompleted)
        manifest.release.complete(Unit)
        disposal.entered.await()
        assertEquals(1, fixture.manifest(chapter).pages.single().attempts)
        assertTrue(fixture.system.exists(page.path))
        assertFalse(acknowledged.isCompleted)
        assertFailsWith<DownloadOperationBusy> { fixture.operations.withExclusive {} }
        disposal.release.complete(Unit)
        acknowledged.await()
        assertTrue(page.discarded.isCompleted)
        assertFalse(fixture.system.exists(page.path))
        retry.entered.await() // Separate retry cannot finish its next handoff, but receipt is settled.
        assertFalse(retry.release.isCompleted)
        assertTrue(transport.enqueued.isEmpty())
        assertEquals(DownloadingState.RUNNING, fixture.download(chapter).state)
    }

    @Test
    fun coldWindowRequeriesRoomBeforeSchedulingAndMainCompletion() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.seed()
            fixture.dao.updateStateChId(chapter.saved.id, DownloadingState.QUEUED)
            fixture.reopen()
            verifyColdCompletion(fixture, CoroutineScope(Dispatchers.Default + hostJob))
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    private suspend fun verifyColdCompletion(fixture: IosCbzFinalizationFixture, scope: CoroutineScope) {
        val observation = HeldFirstDownloadObservation(fixture.dao)
        val signal = BackgroundWorkSignal()
        val trace = EventSettlementTrace()
        val host = BackgroundDownloadHost(scope, BackgroundScheduler.NoOp, signal, DownloadNotifier.NoOp)
        val engine = fixture.engine(scope, ArtifactTestTransport(fixture.operations), downloads = observation, host = host)
        observation.collecting.await()
        assertFalse(signal.hasPendingWork, "The cold advisory signal has never observed the stored queue")
        val completed = CompletableDeferred<Unit>()
        val completion = scope.launch {
            engine.completeBackgroundEventWindow(requestProcessing = { trace.record("schedule") }) {
                trace.record("complete")
                completed.complete(Unit)
            }
        }
        pumpNativeMainUntil(completed)
        completion.join()
        assertFalse(observation.release.isCompleted, "No ordinary collector wake-up supplied the pending snapshot")
        assertTrue(signal.hasPendingWork)
        trace.assertNames("schedule", "complete")
    }

    @Test
    fun cancelledReceiverScopeDisposesAndAcknowledgesWithoutPublication() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.seed()
            val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING)
            hostJob.cancel()
            val transport = ArtifactTestTransport(fixture.operations)
            fixture.engine(CoroutineScope(coroutineContext + hostJob), transport)
            val page = ReceiverPage(fixture, chapter, "cancelled-entry", failPublication = false)
            transport.deliverPage(chapter, claim.token, page.page).await()
            val failed = CompletableDeferred<Unit>()
            fixture.operations.withOperation { operation ->
                transport.receiver.onPageFailed(chapter.saved.mangaId, chapter.saved.id, 0, claim.token, "network", operation) {
                    failed.complete(Unit)
                }
            }
            failed.await()
            assertEquals(0, page.publications)
            assertTrue(page.discarded.isCompleted)
            assertFalse(fixture.system.exists(page.path))
            assertEquals(0, fixture.manifest(chapter).pages.single().attempts)
            assertEquals(DownloadingState.RUNNING, fixture.download(chapter).state)
            hostJob.join()
            fixture.operations.withExclusive {} // Both cancelled-before-start receiver children really ended.
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun failedRefreshAndFailedSchedulerStillCompleteOnceOnMain() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job]).apply { cancel() }
        try {
            val dao = object : ChapterDownloadDao by fixture.dao {
                override fun observeAllDownloads() = flow<List<ChapterDownloadEntity>> { throw IOException("Injected refresh failure") }
            }
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), ArtifactTestTransport(fixture.operations), downloads = dao)
            val trace = EventSettlementTrace()
            val completed = CompletableDeferred<Unit>()
            val completion = launch(Dispatchers.Default) {
                assertFailsWith<IOException> {
                    engine.completeBackgroundEventWindow(requestProcessing = {
                        trace.record("schedule")
                        throw IOException("Injected scheduler failure")
                    }) {
                        trace.record("complete")
                        completed.complete(Unit)
                    }
                }
            }
            pumpNativeMainUntil(completed)
            completion.join()
            trace.assertNames("schedule", "complete")
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun cancelledRefreshStillSchedulesAndCompletesOnceOnMain() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job]).apply { cancel() }
        try {
            val observation = HeldFirstDownloadObservation(fixture.dao)
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), ArtifactTestTransport(fixture.operations), downloads = observation)
            val trace = EventSettlementTrace()
            val completed = CompletableDeferred<Unit>()
            val completion = launch(Dispatchers.Default) {
                engine.completeBackgroundEventWindow(requestProcessing = { trace.record("schedule") }) {
                    trace.record("complete")
                    completed.complete(Unit)
                }
            }
            observation.collecting.await()
            completion.cancel()
            pumpNativeMainUntil(completed)
            completion.join()
            assertFalse(observation.release.isCompleted)
            trace.assertNames("schedule", "complete")
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun neverEmittingRefreshTimesOutAndReleasesItsCollectionBeforeMainCompletion() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job]).apply { cancel() }
        val exited = CompletableDeferred<Unit>()
        try {
            val dao = object : ChapterDownloadDao by fixture.dao {
                override fun observeAllDownloads() = flow<List<ChapterDownloadEntity>> {
                    try { awaitCancellation() } finally { exited.complete(Unit) }
                }
            }
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), ArtifactTestTransport(fixture.operations), downloads = dao)
            val trace = EventSettlementTrace()
            val completed = CompletableDeferred<Unit>()
            val completion = launch(Dispatchers.Default) {
                engine.completeBackgroundEventWindow(requestProcessing = { trace.record("schedule") }) {
                    trace.record(if (exited.isCompleted) "complete" else "collectionStillRunning")
                    completed.complete(Unit)
                }
            }
            pumpNativeMainUntil(completed)
            completion.join()
            assertFalse(completion.isCancelled, "The refresh timeout is not parent cancellation")
            assertTrue(exited.isCompleted, "The timed-out collection cannot linger beyond completion")
            trace.assertNames("schedule", "complete")
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }
}
