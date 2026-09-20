package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.kira.data.download.selection.DownloadCatalogAdmission
import me.manga.kira.data.download.selection.DownloadCatalogNotReady
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.platform.background.BackgroundExecutionGuard
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.platform.download.DownloadOperationExclusion.Operation
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.platform.notification.DownloadNotifier
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.ChapterDownloadStages
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageResolver
import me.manga.kira.presentation.features.download.domain.clean.CoroutineDownloadHost
import me.manga.kira.presentation.features.download.domain.clean.CoroutineDownloadRepositoryImpl
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import me.manga.kira.presentation.features.download.domain.clean.PageDownloadTransfer
import okio.Path.Companion.toPath
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** JVM witness of the actual shared iOS-rollback engine, not an OS/background-service test. */
class CoroutineDownloadStartupAdmissionTest {
    @Test
    fun unreadyStartupParksUntilExternalResumeAndEachChapterNeedsAdmission() = downloadRecoveryTest {
        val queued = listOf(seed(DownloadingState.QUEUED), seed(DownloadingState.QUEUED))
        val worker = CoroutineStartupCase(this)
        val owner = Job(currentCoroutineContext()[Job])
        try {
            val repository = worker.repository(CoroutineScope(currentCoroutineContext() + owner))
            worker.releasePreparation(expected = 1)
            assertIs<DownloadCatalogNotReady>(worker.catalog.finished.receiveBounded())
            worker.catalog.ready.set(true) // Readiness alone must not manufacture a new wake-up.
            worker.assertParked(queued)
            repository.reconcileInterruptedDownloads()
            worker.releasePreparation(expected = 2)
            worker.drain(queued)
            worker.assertCompleted(queued)
            worker.assertNoWake()
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.Default) {
                    withTimeout(COROUTINE_WORKER_TIMEOUT_MILLIS) { owner.cancelAndJoin() }
                }
            } finally {
                worker.close()
            }
        }
    }
}

/** Composition only: the existing recovery fixture owns Room, files and the sole artifact runtime. */
private class CoroutineStartupCase(
    private val fixture: DownloadRecoveryFixture,
) {
    val catalog = CoroutineStartupCatalog(fixture.downloadOperations)
    private val captures = CopyOnWriteArrayList<Operation>()
    private val requests = AtomicInteger()
    private val pagesEntered = Channel<ChapterEntry>(Channel.UNLIMITED)
    private val releasePages = Channel<Unit>()
    private val client = HttpClient(MockEngine {
        requests.incrementAndGet()
        respond(CBZ_CALLER_PNG, headers = headersOf(HttpHeaders.ContentType, "image/png"))
    })
    private val downloads = object : ChapterDownloadDao by fixture.dao {
        override suspend fun getQueuedChaptersForWorker(queuedState: DownloadingState): List<ChapterDownloadEntity> {
            val operation = assertNotNull(currentCoroutineContext()[Operation])
            assertSame(catalog.admitted.last(), operation, "Queue capture must be inside this admission")
            captures += operation
            return fixture.dao.getQueuedChaptersForWorker(queuedState)
        }
    }
    private val provider = object : ChapterPageProvider {
        override suspend fun pagesOrNull(
            api: String,
            mangaUrl: String,
            mangaLanguage: String,
            chapterUrl: String,
        ): List<DownloadPage> {
            val operation = assertNotNull(currentCoroutineContext()[Operation])
            // Nested retention also checks that the producer owns this fixture's actual graph.
            fixture.downloadOperations.withOperation {}
            pagesEntered.send(ChapterEntry(chapterUrl, operation))
            releasePages.receive()
            return listOf(DownloadPage("https://images.example/page.png", emptyMap()))
        }
    }

    suspend fun repository(scope: CoroutineScope): CoroutineDownloadRepositoryImpl {
        val settings = DataStoreHelper(MapSettings()).apply { setUseCbzFormat(false) }
        val finalizer = fixture.finalizer(CbzCallerWriter { _, _ -> error("Loose-page test must not invoke CBZ") }, settings)
        return CoroutineDownloadRepositoryImpl(
            dao = downloads,
            appFileSystem = fixture.appFileSystem,
            pageTransfer = PageDownloadTransfer(client, DesktopPageMediaInspector(system = fixture.fs)),
            host = CoroutineDownloadHost(scope, BackgroundExecutionGuard.PassThrough, DownloadNotifier.NoOp),
            stages = ChapterDownloadStages(ChapterPageResolver(fixture.db.mangaDao(), provider), finalizer),
            artifacts = fixture.chapterArtifactsForTest(),
            operations = fixture.downloadOperations,
            catalog = catalog,
        )
    }

    suspend fun releasePreparation(expected: Int) {
        assertEquals(expected, catalog.preparing.receiveBounded())
        // Preparation is suspended outside operation ownership, including after explicit resume.
        fixture.downloadOperations.withExclusive {}
        catalog.releasePreparation.send(Unit)
    }

    suspend fun assertParked(queued: List<RetainedDownload>) {
        assertNoWake()
        assertEquals(1, catalog.preparations.get(), "Exactly one constructor wake-up")
        assertEquals(1, catalog.attempts.get())
        assertTrue(catalog.admitted.isEmpty())
        assertTrue(captures.isEmpty(), "NotReady must precede the first queued-row capture")
        assertEquals(0, requests.get())
        queued.forEach { original ->
            assertEquals(original.download, fixture.download(original), "Refusal is not a chapter failure")
            assertEquals(original.saved, fixture.saved(original))
            fixture.assertRetainedFiles(original)
            assertNull(fixture.artifactRuntime.dao.get(original.saved.id), "Refusal must not reserve an artifact")
        }
        fixture.downloadOperations.withExclusive {}
    }

    suspend fun drain(queued: List<RetainedDownload>) {
        val remaining = queued.associateBy { it.saved.url }.toMutableMap()
        repeat(queued.size) { index ->
            val entry = pagesEntered.receiveBounded()
            val original = assertNotNull(remaining.remove(entry.chapterUrl), "Each chapter runs exactly once")
            assertEquals(index + 1, captures.size)
            assertEquals(original.download.copy(state = DownloadingState.RUNNING), fixture.download(original))
            assertNotSame(captures.last(), entry.operation, "The detached producer must retain its own handle")
            assertFailsWith<DownloadOperationBusy> { fixture.downloadOperations.withExclusive {} }
            releasePages.send(Unit)
            assertNull(catalog.finished.receiveBounded(), "The real chapter admission must complete normally")
        }
        assertTrue(remaining.isEmpty())
        assertNull(catalog.finished.receiveBounded(), "The final empty scan also has its own admission")
    }

    suspend fun assertCompleted(queued: List<RetainedDownload>) {
        assertEquals(2, catalog.preparations.get(), "Prepare once for startup, once for the external resume")
        assertEquals(4, catalog.attempts.get(), "One refusal, two chapters, then one empty scan")
        assertEquals(3, captures.size)
        assertEquals(catalog.admitted.toList(), captures.toList())
        assertEquals(3, captures.distinct().size, "Never hold one admission over the backlog")
        assertEquals(queued.size, requests.get())
        queued.forEach { original ->
            assertEquals(DownloadingState.SUCCESS, fixture.download(original).state)
            val saved = fixture.saved(original)
            assertTrue(saved.isDownloaded)
            assertContentEquals(CBZ_CALLER_PNG, fixture.fs.read(saved.localImagePaths.single().toPath()) { readByteArray() })
            assertNull(fixture.artifactRuntime.ownership.currentClaim(saved.id))
        }
        fixture.downloadOperations.withExclusive {} // Producer and parent handles really settled.
    }

    suspend fun assertNoWake() {
        // The real worker uses Dispatchers.Default: never let runTest fast-forward this quiet window.
        val unexpected = withContext(Dispatchers.Default) {
            withTimeoutOrNull(COROUTINE_WORKER_QUIET_MILLIS) { catalog.preparing.receive() }
        }
        assertNull(unexpected, "A parked drain must not prepare again without an external wake-up")
    }

    fun close() = client.close()
}

private class CoroutineStartupCatalog(operations: DownloadOperationExclusion) : DownloadCatalogAdmission {
    val ready = AtomicBoolean(false)
    val preparations = AtomicInteger()
    val attempts = AtomicInteger()
    val preparing = Channel<Int>(Channel.UNLIMITED)
    val releasePreparation = Channel<Unit>()
    val finished = Channel<Throwable?>(Channel.UNLIMITED)
    val admitted = CopyOnWriteArrayList<Operation>()
    private val delegate = TestDownloadCatalogAdmission(
        operations,
        prepare = {
            assertNull(currentCoroutineContext()[Operation], "Preparation cannot inherit an operation")
            preparing.send(preparations.incrementAndGet())
            releasePreparation.receive()
        },
        checkReady = {
            attempts.incrementAndGet()
            if (!ready.get()) throw DownloadCatalogNotReady()
        },
    )

    override suspend fun prepareLocal() = delegate.prepareLocal()

    override suspend fun <T> withAdmittedOperation(block: suspend (Operation) -> T): T {
        var failure: Throwable? = null
        try {
            return delegate.withAdmittedOperation { operation ->
                admitted += operation
                block(operation)
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            // Signal only after the real exclusion helper has released this admission's handle.
            finished.trySend(failure)
        }
    }
}

private data class ChapterEntry(val chapterUrl: String, val operation: Operation)

private suspend fun <T> Channel<T>.receiveBounded(): T = withContext(Dispatchers.Default) {
    withTimeout(COROUTINE_WORKER_TIMEOUT_MILLIS) { receive() }
}

private const val COROUTINE_WORKER_TIMEOUT_MILLIS = 15_000L
private const val COROUTINE_WORKER_QUIET_MILLIS = 250L
