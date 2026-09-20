package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStore
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real iOS engine/Room/file fixture; these do not model or prove native URLSession termination. */
@OptIn(ExperimentalCoroutinesApi::class)
class IosBackgroundOperationExclusionTest {
    @Test
    fun deleteCapturesOnlyAfterExclusiveAndUsesTheThenCurrentHistoryState() = runTest {
        withFixture { fixture, host, scope ->
            val chapter = fixture.seed()
            fixture.dao.updateStateChId(chapter.saved.id, DownloadingState.FAILED)
            host.cancelAndJoin() // No startup/signal reader can obscure the public-entry capture.
            var reads = 0
            val downloads = object : ChapterDownloadDao by fixture.dao {
                override suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity? {
                    reads++
                    return fixture.dao.getDownloadByChapter(chapterId)
                }
            }
            val transport = ArtifactTestTransport(fixture.operations)
            val engine = fixture.engine(scope, transport, downloads = downloads)
            var deletion: Job? = null
            fixture.operations.withExclusive {
                deletion = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { engine.deleteDownload(chapter.saved.id) }
                assertEquals(0, reads)
                assertFalse(assertNotNull(deletion).isCompleted)
                fixture.dao.updateStateChId(chapter.saved.id, DownloadingState.SUCCESS)
            }
            assertNotNull(deletion).join()
            assertNull(fixture.dao.getDownloadByChapter(chapter.saved.id))
            assertTrue(transport.cancelled.isEmpty(), "The fresh SUCCESS snapshot deletes history, not its files")
            chapter.pages.keys.forEach { assertTrue(fixture.system.exists(it)) }
            fixture.operations.withExclusive {}
        }
    }

    @Test
    fun cancelledHostKeepsResolverOperationUntilItsNonCancellableFinallyActuallyFinishes() = runTest {
        withFixture { fixture, host, scope ->
            val chapter = fixture.seed()
            fixture.prepareAttempt(chapter, DownloadingState.QUEUED)
            fixture.system.delete(fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "manifest.json")
            val entered = CompletableDeferred<Unit>()
            val finishing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val provider = heldResolver(entered, finishing, release)
            val catalog = IosCatalogAdmissionProbe(fixture.operations, ready = true)
            try {
                fixture.engine(scope, ArtifactTestTransport(fixture.operations, ready = true),
                    pageProvider = provider, catalog = catalog.admission)
                entered.await()
                catalog.ready = false // Invalidation cannot revoke the original resolver's cleanup.
                host.cancel()
                finishing.await()
                assertFalse(host.isCompleted)
                assertFailsWith<DownloadOperationBusy> { fixture.operations.withExclusive {} }
                release.complete(Unit)
                host.join()
                assertEquals(1, catalog.preparations)
                assertEquals(1, catalog.checks)
                fixture.operations.withExclusive {}
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun receiverReceiptDoesNotWaitForTheNextChapterNativeHandoff() = runTest {
        for (ready in listOf(false, true)) verifyReceiverFollowup(ready)
    }

    private suspend fun TestScope.verifyReceiverFollowup(ready: Boolean) {
        withFixture { fixture, host, scope ->
            val chapter = fixture.seed()
            val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2)
            val next = fixture.seed()
            fixture.prepareAttempt(next, DownloadingState.QUEUED)
            val queued = fixture.download(next)
            val catalog = IosCatalogAdmissionProbe(fixture.operations, ready)
            val handoff = HeldSettlementWrite()
            try {
                val transport = ArtifactTestTransport(fixture.operations)
                fixture.engine(scope, transport.holdNextEnqueue(handoff), catalog = catalog.admission)
                val page = ReceiverPage(fixture, chapter, "post-ack-next", beforeDiscard = {
                    assertEquals(0, catalog.preparations)
                    assertEquals(0, catalog.checks, "The original receiver must not require fresh selection")
                })
                transport.deliverPage(chapter, claim.token, page.page).await()
                assertTrue(page.discarded.isCompleted)
                assertEquals(DownloadingState.FAILED, fixture.download(chapter).state)
                if (ready) assertNextHandoff(fixture, transport, handoff)
                else {
                    assertRefusedWindow(fixture, host, chapter, queued, catalog, transport)
                    assertFalse(handoff.entered.isCompleted)
                }
            } finally {
                handoff.release.complete(Unit)
            }
        }
    }

    private suspend fun assertNextHandoff(
        fixture: IosCbzFinalizationFixture, transport: ArtifactTestTransport, handoff: HeldSettlementWrite,
    ) {
        handoff.entered.await()
        assertFalse(handoff.release.isCompleted, "The next session enqueue cannot delay this receiver ACK")
        assertTrue(transport.enqueued.isEmpty())
        assertFailsWith<DownloadOperationBusy> { fixture.operations.withExclusive {} }
    }

    private suspend fun TestScope.assertRefusedWindow(
        fixture: IosCbzFinalizationFixture,
        host: Job,
        chapter: IosCbzChapter,
        queued: ChapterDownloadEntity,
        catalog: IosCatalogAdmissionProbe,
        transport: ArtifactTestTransport,
    ) {
        catalog.assertParked(this)
        assertEquals(queued, fixture.dao.getDownloadByChapter(queued.chapterId))
        assertTrue(transport.enqueued.isEmpty())
        host.cancelAndJoin() // Joins the actual retained settlement, not a synthetic test cleanup.
        assertNull(fixture.artifacts.ownership.currentClaim(chapter.saved.id))
        assertEquals(3, fixture.manifest(chapter).pages.single().attempts)
        assertEquals(DownloadingState.FAILED, fixture.download(chapter).state)
        fixture.operations.withExclusive {}
    }

    @Test
    fun delayedRetryReentersAdmissionBeforeReadingItsFreshRowAndManifest() = runTest {
        withFixture { fixture, _, scope ->
            val chapter = fixture.seed()
            val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING)
            var reads = 0
            val downloads = object : ChapterDownloadDao by fixture.dao {
                override fun observeAllDownloads() = flow<List<ChapterDownloadEntity>> { awaitCancellation() }
                override suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity? {
                    reads++
                    return fixture.dao.getDownloadByChapter(chapterId)
                }
            }
            val transport = ArtifactTestTransport(fixture.operations)
            val engine = fixture.engine(scope, transport, downloads = downloads)
            deliverFailure(fixture, chapter, claim.token, engine)
            runCurrent()
            val beforeRetry = reads
            fixture.operations.withExclusive {
                advanceTimeBy(2_000)
                runCurrent()
                assertEquals(beforeRetry, reads, "The delayed job must not capture while a writer owns admission")
                val fresh = fixture.manifest(chapter)
                DownloadManifestStore(fixture.appFileSystem).write(fresh.copy(
                    pages = fresh.pages.map { it.copy(url = "https://example.test/fresh-page.png") },
                ))
            }
            assertEquals("https://example.test/fresh-page.png", transport.requests.receive().url)
        }
    }

    @Test
    fun completionBudgetIncludesAdmissionAndStillCompletesOnMainWithoutAnEarlyRead() = runTest {
        withFixture { fixture, host, scope ->
            host.cancelAndJoin()
            val read = CompletableDeferred<Unit>()
            val downloads = object : ChapterDownloadDao by fixture.dao {
                override fun observeAllDownloads() = flow<List<ChapterDownloadEntity>> {
                    read.complete(Unit)
                    emit(emptyList())
                }
            }
            val engine = fixture.engine(scope, ArtifactTestTransport(fixture.operations), downloads = downloads)
            val trace = EventSettlementTrace()
            val completed = CompletableDeferred<Unit>()
            fixture.operations.withExclusive {
                val completion = backgroundScope.launch(Dispatchers.Default) {
                    engine.completeBackgroundEventWindow(requestProcessing = { trace.record("schedule") }) {
                        trace.record("complete")
                        completed.complete(Unit)
                    }
                }
                pumpNativeMainUntil(completed)
                completion.join()
                assertFalse(completion.isCancelled, "The bounded admission timeout is not host cancellation")
                assertFalse(read.isCompleted, "A timed-out entrant cannot read through the exclusive writer")
                trace.assertNames("schedule", "complete")
            }
        }
    }

    private fun heldResolver(
        entered: CompletableDeferred<Unit>,
        finishing: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ) = object : ChapterPageProvider {
        override suspend fun pagesOrNull(
            api: String, mangaUrl: String, mangaLanguage: String, chapterUrl: String,
        ): List<DownloadPage> {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    finishing.complete(Unit)
                    release.await()
                }
            }
        }
    }

    private suspend fun deliverFailure(
        fixture: IosCbzFinalizationFixture,
        chapter: IosCbzChapter,
        token: String,
        engine: me.manga.kira.platform.download.TransferListener,
    ) {
        val acknowledged = CompletableDeferred<Unit>()
        fixture.operations.withOperation { operation ->
            engine.onPageFailed(chapter.saved.mangaId, chapter.saved.id, 0, token, "HTTP 500", operation) {
                acknowledged.complete(Unit)
            }
        }
        acknowledged.await()
    }

    private suspend fun TestScope.withFixture(block: suspend (IosCbzFinalizationFixture, Job, CoroutineScope) -> Unit) {
        val fixture = IosCbzFinalizationFixture()
        val host = SupervisorJob(coroutineContext[Job])
        try {
            block(fixture, host, CoroutineScope(coroutineContext + host))
        } finally {
            host.cancelAndJoin()
            fixture.close()
        }
    }
}
