package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import me.manga.kira.data.local.dao.ArtifactRepairSnapshot
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.dao.ChapterArtifactDao
import me.manga.kira.data.local.dao.ChapterDownloadOutcome
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Loss-of-return cases commit real Room first; one explicit negative control fails before entry. */
internal class LostRestoredRepairReturn(
    private val real: ChapterArtifactCommitDao,
    private val cancelledReturn: Boolean,
) : ChapterArtifactCommitDao by real {
    var failBeforeCommit = false
    var readbackUnavailable = true
    var writes = 0
        private set
    val reads = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun failMissingRestoredDownload(claim: ChapterArtifactClaim, expected: ArtifactRepairSnapshot): Boolean {
        writes++
        if (failBeforeCommit) throw IOException("Repair transaction could not start")
        assertEquals(1, writes, "A failed ledger must not be repaired a second time")
        assertTrue(real.failMissingRestoredDownload(claim, expected), "Commit the actual generated Room transaction")
        if (cancelledReturn) throw CancellationException("Lost committed repair return")
        throw IOException("Lost committed repair return")
    }

    override suspend fun downloadOutcome(claim: ChapterArtifactClaim): ChapterDownloadOutcome {
        if (readbackUnavailable) {
            reads.trySend(Unit)
            throw IOException("Original-claim readback temporarily unavailable")
        }
        return real.downloadOutcome(claim).also { reads.trySend(Unit) }
    }
}

/** One engine and one coordinator after fixture reopen; tests never settle the token themselves. */
internal class RestoredSettlementCase(
    val fixture: IosCbzFinalizationFixture,
    val chapter: IosCbzChapter,
    val original: ChapterArtifactClaim,
    scope: CoroutineScope,
    cancelledReturn: Boolean,
) {
    val host = assertNotNull(scope.coroutineContext[Job])
    val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
    val retainedManifest = fixture.manifest(chapter)
    val released = CompletableDeferred<Unit>()
    val settling = CompletableDeferred<Unit>()
    val releaseUser = CompletableDeferred<Unit>()
    var releases = 0
        private set
    private val realRecords = fixture.db.chapterArtifactDao()
    private val observed = object : ChapterArtifactDao by realRecords {
        override suspend fun revoke(chapterId: Long, token: String): Int = realRecords.revoke(chapterId, token).also {
            if (chapterId == chapter.saved.id && token == original.token && it == 1) settling.complete(Unit)
        }
        override suspend fun release(chapterId: Long, token: String): Int = realRecords.release(chapterId, token).also {
            if (chapterId == chapter.saved.id && token == original.token && it == 1) {
                releases++
                released.complete(Unit)
            }
        }
    }
    val faults = LostRestoredRepairReturn(fixture.db.chapterArtifactCommitDao(), cancelledReturn)
    val runtime = ArtifactTestRuntime(observed, faults, fixture.appFileSystem, IosPageMediaInspector(system = fixture.system))
    val transport = ArtifactTestTransport(fixture.operations, ready = true)
    val catalog = IosCatalogAdmissionProbe(fixture.operations, ready = false)
    val engine = fixture.engine(scope, transport, downloadArtifacts = runtime.downloads, catalog = catalog.admission)

    suspend fun assertRetainedFailure() {
        val row = fixture.download(chapter)
        assertEquals(original.downloadId, row.id)
        assertEquals(DownloadingState.FAILED, row.state)
        assertEquals(0, row.progress)
        assertEquals(0L, row.sizeBytes)
        assertNull(row.errorMsg)
        assertEquals(original, runtime.ownership.currentClaim(chapter.saved.id))
        assertTrue(assertNotNull(realRecords.get(chapter.saved.id)).retiring)
        assertFalse(released.isCompleted)
        assertEquals(chapter.saved.copy(isDownloaded = false, localImagePaths = emptyList()), fixture.saved(chapter))
        assertRetainedFiles()
        assertTrue(transport.enqueued.isEmpty())
        assertTrue(transport.cancelled.isEmpty())
    }

    suspend fun assertUncommittedRepair() {
        assertEquals(chapter.download.copy(state = DownloadingState.RUNNING), fixture.download(chapter))
        assertEquals(chapter.saved, fixture.saved(chapter))
        assertEquals(original, runtime.ownership.currentClaim(chapter.saved.id))
        assertFalse(assertNotNull(realRecords.get(chapter.saved.id)).retiring)
        assertFalse(released.isCompleted)
        assertRetainedFiles()
        assertTrue(transport.enqueued.isEmpty())
        assertTrue(transport.cancelled.isEmpty())
    }

    fun assertRetainedFiles() {
        assertEquals(retainedManifest, fixture.manifest(chapter))
        assertContentEquals(chapter.pages.values.first(), fixture.system.read(directory / "image_0.png") { readByteArray() })
        assertFalse(fixture.system.exists(directory / "image_1.png"))
    }

    suspend fun assertUnknownRetryRetainsCustody() {
        assertFalse(engine.retryChapterDownload(fixture.download(chapter)))
        faults.reads.receive() // The real public entry reached original-claim readback, not startup recovery.
        assertRetainedFailure()
    }

    suspend fun assertReplacementTransfer() {
        val request = transport.requests.receive()
        val row = fixture.download(chapter)
        assertEquals(1, releases)
        assertEquals(1, request.pageIndex)
        assertNotEquals(original.token, request.attemptToken)
        assertNotEquals(original.downloadId, row.id)
        assertEquals(DownloadingState.RUNNING, row.state)
        assertEquals(request.attemptToken, runtime.ownership.currentClaim(chapter.saved.id)?.token)
        assertEquals(request.attemptToken, fixture.manifest(chapter).attemptToken)
        assertEquals(listOf(0, 0), fixture.manifest(chapter).pages.map { it.attempts })
        assertEquals(listOf(request), transport.enqueued)
        assertContentEquals(chapter.pages.values.first(), fixture.system.read(directory / "image_0.png") { readByteArray() })
        assertTrue(transport.cancelled.isEmpty())
    }
}

internal suspend fun TestScope.withLostRestoredRepairReturn(
    cancelledReturn: Boolean,
    beforeRepair: suspend RestoredSettlementCase.() -> Unit = {},
    action: suspend RestoredSettlementCase.() -> Unit,
) {
    val fixture = IosCbzFinalizationFixture()
    val host = SupervisorJob(coroutineContext[Job])
    try {
        val case = fixture.lostRepairCase(CoroutineScope(coroutineContext + host), cancelledReturn)
        try {
            case.catalog.assertParked(this)
            case.catalog.ready = true
            case.beforeRepair()
            if (cancelledReturn) {
                val cancelled = assertFailsWith<CancellationException> { case.engine.reconcileInterruptedDownloads() }
                assertEquals("Lost committed repair return", cancelled.message)
            } else case.engine.reconcileInterruptedDownloads()
            assertEquals(1, case.faults.writes)
            if (case.faults.failBeforeCommit) case.assertUncommittedRepair() else case.assertRetainedFailure()
            case.action()
        } finally {
            case.releaseUser.complete(Unit)
        }
    } finally {
        host.cancelAndJoin()
        fixture.close()
    }
}

private suspend fun IosCbzFinalizationFixture.lostRepairCase(scope: CoroutineScope, cancelledReturn: Boolean): RestoredSettlementCase {
    val chapter = downloadNamed(seed())
    val original = prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2, pageCount = 2)
    system.delete(appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "image_1.png")
    reopen()
    return RestoredSettlementCase(this, chapter, original, scope, cancelledReturn)
}
