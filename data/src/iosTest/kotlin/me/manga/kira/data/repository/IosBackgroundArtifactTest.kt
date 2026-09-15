package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.download.BackgroundScheduler
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.download.BackgroundWorkSignal
import me.manga.kira.platform.download.StagedDownloadPage
import me.manga.kira.platform.download.TransferListener
import me.manga.kira.platform.download.TransferRequest
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.media.requireValid
import me.manga.kira.platform.notification.DownloadNotifier
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.BackgroundDownloadHost
import me.manga.kira.presentation.features.download.domain.clean.BackgroundDownloadStorage
import me.manga.kira.presentation.features.download.domain.clean.BackgroundPageTransfer
import me.manga.kira.presentation.features.download.domain.clean.BackgroundUrlSessionDownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.ChapterDownloadStages
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageResolver
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifest
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStore
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.ManifestPage
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual iOS receiver/window over the existing file-backed Room fixture; no OS scheduling claim. */
class IosBackgroundArtifactTest {
    @Test
    fun receiverPublicationFailureExhaustsOriginalAttemptAndCannotChargeReplacementOrCancellation() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        try {
            val original = fixture.seed()
            val next = fixture.seed()
            val claim = fixture.prepareAttempt(original, DownloadingState.RUNNING, failures = 2)
            val nextClaim = fixture.prepareAttempt(next, DownloadingState.QUEUED)
            val transport = ArtifactTestTransport()
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), transport)
            val failed = ReceiverPage(fixture, original, "failed")
            transport.receiver.onPageComplete(original.saved.mangaId, original.saved.id, 0, claim.token, failed.page)
            failed.discarded.await() // Includes the asynchronous receiver's failure handling/finally.

            assertEquals(1, failed.publications)
            assertFalse(fixture.system.exists(failed.path))
            assertEquals(DownloadingState.FAILED, fixture.download(original).state)
            assertEquals(3, fixture.manifest(original).pages.single().attempts)
            assertTrue(original.saved.id to claim.token in transport.cancelled)
            val transfer = transport.requests.receive()
            assertEquals(next.saved.id, transfer.chapterId, "The exhausted receiver releases the transfer slot")
            assertEquals(nextClaim.token, transfer.attemptToken)
            assertEquals(DownloadingState.RUNNING, fixture.download(next).state)
            assertEquals(listOf(next.saved.id), transport.enqueued.map { it.chapterId })
            fixture.artifacts.settle(claim)
            assertNull(fixture.artifacts.ownership.currentClaim(original.saved.id))
            assertEquals(3, fixture.manifest(original).pages.single().attempts, "Ordinary failure retains retry work")

            engine.onCancel(next.saved.id)
            val replacement = assertNotNull(fixture.artifacts.enqueue(
                fixture.saved(original), original.download.copy(state = DownloadingState.QUEUED),
            ))
            assertNotEquals(claim.token, replacement.token)
            assertEquals(1, fixture.artifacts.ownership.publish(replacement) {
                fixture.dao.claimQueuedAsRunning(original.saved.id)
            })
            fixture.writeManifest(original, replacement)
            val stale = ReceiverPage(fixture, original, "stale")
            transport.receiver.onPageComplete(original.saved.mangaId, original.saved.id, 0, claim.token, stale.page)
            stale.discarded.await()
            assertEquals(0, stale.publications)
            assertEquals(0, fixture.manifest(original).pages.single().attempts)
            assertEquals(DownloadingState.RUNNING, fixture.download(original).state)

            assertNotNull(fixture.artifacts.cancel(original.saved.id, "__cancelled_by_user__"))
            val cancelled = ReceiverPage(fixture, original, "cancelled")
            transport.receiver.onPageComplete(original.saved.mangaId, original.saved.id, 0, replacement.token, cancelled.page)
            cancelled.discarded.await()
            assertEquals(0, cancelled.publications)
            assertEquals(0, fixture.manifest(original).pages.single().attempts)
            assertEquals("__cancelled_by_user__", fixture.download(original).errorMsg)
            assertEquals(listOf(next.saved.id), transport.enqueued.map { it.chapterId })
            assertTrue(fixture.artifacts.settle(replacement))
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun parentReopenRefillsIosWindowWithoutExternalPump() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        val releaseRead = CompletableDeferred<Unit>()
        try {
            val completed = fixture.seed()
            fixture.dao.updateStateChId(completed.saved.id, DownloadingState.SUCCESS)
            fixture.db.backupDao().updateChapterRow(completed.saved.copy(isDownloaded = true))
            val waiting = fixture.seed(mangaId = completed.saved.mangaId)
            val claim = fixture.prepareAttempt(waiting, DownloadingState.QUEUED)
            val transport = ArtifactTestTransport()
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), transport)
            val reading = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val actions = DownloadsActionRepositoryImpl(
                legacy = object : DownloadRepository by engine {
                    override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) {
                        engine.cancelARunningChapter(chapterId, mangaId)
                        closed.complete(Unit) // Actual onCancel filled the window while parent M was closed.
                    }
                },
                chapterDownloadDao = fixture.dao,
                chapterDao = fixture.db.chapterDao(),
                appFileSystem = fixture.appFileSystem,
                artifacts = fixture.artifacts.ownership,
            )
            coroutineScope {
                val pin = launch {
                    fixture.artifacts.ownership.read(completed.saved.id) {
                        reading.complete(Unit)
                        releaseRead.await()
                    }
                }
                try {
                    reading.await()
                    val deletion = async { actions.deleteDownloadedChapter(completed.saved.id) }
                    closed.await()
                    assertFalse(deletion.isCompleted)
                    assertEquals(DownloadingState.QUEUED, fixture.download(waiting).state)
                    assertTrue(transport.enqueued.isEmpty())
                    releaseRead.complete(Unit)
                    pin.join()
                    assertTrue(deletion.await().isSuccess)
                    val transfer = transport.requests.receive() // No enqueue/pump/lifecycle call wakes it.
                    assertEquals(waiting.saved.id, transfer.chapterId)
                    assertEquals(claim.token, transfer.attemptToken)
                    assertEquals(waiting.download.id, fixture.download(waiting).id)
                    assertEquals(DownloadingState.RUNNING, fixture.download(waiting).state)
                    assertNull(fixture.dao.getDownloadByChapter(completed.saved.id))
                    assertFalse(fixture.system.exists(fixture.appFileSystem.chapterDir(completed.saved.mangaId, completed.saved.id)))
                } finally {
                    releaseRead.complete(Unit)
                }
            }
        } finally {
            releaseRead.complete(Unit)
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }
}

private suspend fun IosCbzFinalizationFixture.prepareAttempt(
    chapter: IosCbzChapter,
    state: DownloadingState,
    failures: Int = 0,
): ChapterArtifactClaim {
    dao.updateStateChId(chapter.saved.id, state)
    val claim = assertNotNull(artifacts.claim(download(chapter)))
    writeManifest(chapter, claim, failures)
    return claim
}

private suspend fun IosCbzFinalizationFixture.writeManifest(chapter: IosCbzChapter, claim: ChapterArtifactClaim, failures: Int = 0) {
    assertNotNull(artifacts.ownership.files(claim) {
        DownloadManifestStore(appFileSystem).write(DownloadManifest(
            mangaId = chapter.saved.mangaId,
            chapterId = chapter.saved.id,
            api = "test",
            pages = listOf(ManifestPage(0, "https://example.test/page.png", emptyMap(), attempts = failures)),
            attemptToken = claim.token,
        ))
    })
}

private fun IosCbzFinalizationFixture.manifest(chapter: IosCbzChapter): DownloadManifest =
    assertNotNull(DownloadManifestStore(appFileSystem).read(chapter.saved.mangaId, chapter.saved.id))

private fun IosCbzFinalizationFixture.engine(scope: CoroutineScope, transport: ArtifactTestTransport) =
    BackgroundUrlSessionDownloadRepository(
        storage = BackgroundDownloadStorage(dao, DownloadManifestStore(appFileSystem), appFileSystem),
        stages = ChapterDownloadStages(
            ChapterPageResolver(db.mangaDao(), object : ChapterPageProvider {
                override suspend fun pagesOrNull(api: String, mangaUrl: String, mangaLanguage: String, chapterUrl: String): List<DownloadPage> =
                    error("Persisted manifests must avoid a new resolve")
            }),
            finalizer(UnusedReceiverCbzWriter),
        ),
        pageTransfer = BackgroundPageTransfer(transport, IosPageMediaInspector(system = system)),
        host = BackgroundDownloadHost(scope, BackgroundScheduler.NoOp, BackgroundWorkSignal(), DownloadNotifier.NoOp),
        dataStoreHelper = DataStoreHelper(MapSettings()),
        artifacts = artifacts,
    )

/** Hold startup reconciliation independently so only the callback/reopen under test can pump. */
private class ArtifactTestTransport : BackgroundTransport {
    lateinit var receiver: TransferListener
    private val startup = CompletableDeferred<Unit>()
    val requests = Channel<TransferRequest>(Channel.UNLIMITED)
    val enqueued = mutableListOf<TransferRequest>()
    val cancelled = mutableListOf<Pair<Long, String>>()
    override fun setListener(listener: TransferListener) { receiver = listener }
    override suspend fun ensureReady() { startup.await() }
    override suspend fun enqueue(requests: List<TransferRequest>) {
        enqueued += requests
        requests.forEach { this.requests.send(it) }
    }
    override suspend fun cancelChapter(chapterId: Long, attemptToken: String) { cancelled += chapterId to attemptToken }
    override suspend fun cancelAll() = Unit
    override suspend fun inFlightPages(chapterId: Long, attemptToken: String): Set<Int> =
        enqueued.filter { it.chapterId == chapterId && it.attemptToken == attemptToken }.map { it.pageIndex }.toSet()
    override fun setSystemCompletionHandler(handler: () -> Unit) = Unit
}

/** Only the final live move fails; manifests and native inspection use the normal filesystem. */
private class ReceiverPage(fixture: IosCbzFinalizationFixture, chapter: IosCbzChapter, name: String) {
    val discarded = CompletableDeferred<Unit>()
    var publications = 0
        private set
    val path = fixture.appFileSystem.cacheDir / "$name.png"
    val page: StagedDownloadPage
    init {
        val bytes = chapter.pages.values.last()
        fixture.system.createDirectories(fixture.appFileSystem.cacheDir)
        fixture.system.write(path) { write(bytes) }
        val forwarding = object : ForwardingFileSystem(fixture.system) {
            override fun atomicMove(source: Path, target: Path) {
                if (source == path) {
                    publications++
                    throw IOException("Injected receiver publication failure")
                }
                super.atomicMove(source, target)
            }
            override fun delete(path: Path, mustExist: Boolean) {
                super.delete(path, mustExist)
                if (path == this@ReceiverPage.path) discarded.complete(Unit)
            }
        }
        page = StagedDownloadPage(forwarding, path, IosPageMediaInspector(system = fixture.system).inspect(bytes).requireValid())
    }
}

private object UnusedReceiverCbzWriter : CbzWriter {
    override suspend fun createCbz(imagePaths: List<Path>, mangaId: Long, chapterId: Long, quality: Int): Path =
        error("No complete page roster in this receiver test")
    override suspend fun createCbzWithSplitting(
        imagePaths: List<Path>, mangaId: Long, chapterId: Long, quality: Int, maxHeight: Int, maxMemoryBytes: Long,
    ): Path = error("No complete page roster in this receiver test")
}
