package me.manga.kira.presentation.features.download.ui.test2

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.cbz.OptimizedCbzManager
import me.manga.kira.core.cbz.cbzTier
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.AndroidPageMediaInspector
import me.manga.kira.presentation.features.download.data.DownloadState
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.ChapterDownloadArchive
import me.manga.kira.presentation.features.download.domain.ChapterDownloadPersistence
import me.manga.kira.presentation.features.download.domain.ChapterDownloadService
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.DownloadHttpStatusFailure
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import me.manga.kira.presentation.features.download.domain.clean.PageDownloadTransfer
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual worker/service and existing generated Android Room fixture; no live HTTP/WorkManager claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DownloadLocaleTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidDownloadChallengeTest {
    @Test
    fun workerClassifiesResolveFailuresAndPreservesOrdinaryMessages() = challengeCase {
        // RegistryChapterPageProviderTest separately pins the real generic provider to this interface.
        val failures = listOf(
            ResolveHttpFailure(403, "source request rejected") to CHALLENGE,
            ResolveHttpFailure(404, "source /cloudflare/statusCode=403 was not found") to
                "source /cloudflare/statusCode=403 was not found",
            Exception("generic pages() failed: Http(statusCode=403, cause=null)") to CHALLENGE,
            Exception("No images for chapter") to "No images for chapter",
        )
        for ((failure, expected) in failures) {
            resetRow()
            assertEquals(ListenableWorker.Result.success(), runWorker(failure))
            assertFailed(expected)
        }
        assertEquals(0, requests.get(), "resolution failure must not enter page transport")
    }

    @Test
    fun servicePersistsHttpChallengesAndWorkerCannotOverwriteThemWithRawText() = challengeCase {
        for (status in listOf(403, 429, 503, 520, 521, 522, 523, 524, 400, 401, 404, 500)) {
            responseStatus.set(status)
            val expected = if (status in listOf(400, 401, 404, 500)) "Image download HTTP $status" else CHALLENGE

            resetRow()
            val states = downloadService()
            val error = assertIs<DownloadState.Error>(states.last())
            assertEquals(status, assertIs<DownloadHttpStatusFailure>(error.exception).httpStatusCode)
            assertEquals("Image download HTTP $status", error.exception.message)
            assertTrue(states.none { it is DownloadState.Complete })
            assertFailed(expected) // Observe the service's own write, before any worker can repair it.

            resetRow()
            assertEquals(ListenableWorker.Result.success(), runWorker())
            assertFailed(expected) // Actual service → Error collection → worker's token-guarded failure handling.
        }
        assertEquals(24, requests.get())
    }

    @Test
    fun resolveAndTransferCancellationNeverPersistAChallengeOrOrdinaryFailure() = challengeCase {
        val cancelled = CancellationException("HTTP 403 Cloudflare")
        assertFailsWith<CancellationException> { runWorker(cancelled) }
        assertEquals(rows.original.download, rows.download())
        assertEquals(0, requests.get())

        transportCancellation.set(cancelled)
        val states = mutableListOf<DownloadState>()
        assertFailsWith<CancellationException> { downloadService(states) }
        assertTrue(states.none { it is DownloadState.Error || it is DownloadState.Complete })
        assertEquals(rows.original.download, rows.download())

        assertFailsWith<CancellationException> { runWorker() }
        assertEquals(rows.original.download, rows.download(), "worker cancellation requeues, never stamps a challenge")
        assertEquals(rows.original.saved, rows.saved())
    }
}

private fun challengeCase(block: suspend AndroidChallengeCase.() -> Unit) = runBlocking {
    check(GlobalContext.getOrNull() == null) { "Refusing to replace another test's global Koin" }
    CancellationFixtureStorage(RuntimeEnvironment.getApplication()).use { storage ->
        DownloadWorkerCancellationRows(storage, NativeCommitGate()).use { rows ->
            withTimeout(60_000) {
                rows.seed()
                storage.settings.setUseCbzFormat(false)
                AndroidChallengeCase(storage, rows).use { it.block() }
            }
        }
    }
}

/** Composition only: reuses App75's real files/Room/JNI fixture, never reimplements persistence. */
private class AndroidChallengeCase(
    private val storage: CancellationFixtureStorage,
    val rows: DownloadWorkerCancellationRows,
) : AutoCloseable {
    val responseStatus = AtomicInteger(403)
    val transportCancellation = AtomicReference<CancellationException?>()
    val requests = AtomicInteger()
    val pages = listOf(DownloadPage("https://images.example/page.png", emptyMap()))
    private val client = HttpClient(
        MockEngine {
            requests.incrementAndGet()
            transportCancellation.get()?.let { throw it }
            respond(ByteReadChannel("not an image".encodeToByteArray()), HttpStatusCode.fromValue(responseStatus.get()))
        },
    )
    private val files = FileService(storage.fileSystem)
    private val library = LibraryRepository(
        rows.db.mangaDao(),
        rows.db.chapterDao(),
        rows.db.libraryDeo(),
        rows.db.notificationDao(),
        rows.db.historyDao(),
        files,
    )
    val service = ChapterDownloadService(
        storage.context,
        ChapterDownloadPersistence(library, rows.db.notificationDao(), rows.realDao, files),
        PageDownloadTransfer(client, AndroidPageMediaInspector()),
        ChapterDownloadArchive(OptimizedCbzManager(storage.context, cbzTier()), storage.settings),
        artifacts = rows.artifacts,
    )

    suspend fun resetRow() {
        assertNull(rows.artifacts.ownership.currentClaim(rows.original.saved.id), "never reset an unsettled attempt")
        rows.realDao.updateStateAndProgress(rows.original.saved.id, DownloadingState.QUEUED, 0, rows.original.download.errorMsg)
    }

    suspend fun downloadService(states: MutableList<DownloadState> = mutableListOf()): List<DownloadState> {
        val claim = assertNotNull(rows.artifacts.claim(rows.download()))
        var cancelled = false
        try {
            return service.downloadChapterC(rows.original.saved, pages, claim).toList(states)
        } catch (failure: CancellationException) {
            cancelled = true
            throw failure
        } finally {
            // Collection/flowOn has fully unwound; exercise real token-bound settlement, not a fake release.
            assertTrue(rows.artifacts.settle(claim, requeue = cancelled, retainFailedPages = !cancelled))
            assertNull(rows.artifacts.ownership.currentClaim(rows.original.saved.id))
        }
    }

    suspend fun assertFailed(message: String) {
        assertEquals(rows.original.download.copy(state = DownloadingState.FAILED, errorMsg = message), rows.download())
        assertEquals(rows.original.saved, rows.saved())
        assertTrue(storage.mangaDirectory.walkTopDown().none { it.isFile }, "HTTP refusal must not publish image bytes")
        assertNull(rows.artifacts.ownership.currentClaim(rows.original.saved.id), "the actual producer must settle its custody")
    }

    suspend fun runWorker(resolveFailure: Throwable? = null): ListenableWorker.Result {
        val provider = object : ChapterPageProvider {
            override suspend fun pagesOrNull(
                api: String,
                mangaUrl: String,
                mangaLanguage: String,
                chapterUrl: String,
            ): List<DownloadPage> {
                resolveFailure?.let { throw it }
                return pages
            }
        }
        check(GlobalContext.getOrNull() == null)
        val owned = startKoin {
            modules(
                module {
                    single<ChapterDownloadDao> { rows.realDao }
                    single<MangaDao> { rows.db.mangaDao() }
                    single<ChapterDownloadService> { service }
                    single { rows.artifacts }
                    single<ChapterPageProvider> { provider }
                    single<AppFileSystem> { storage.fileSystem }
                },
            )
        }
        try {
            val foregroundCalls = AtomicInteger()
            val worker = TestListenableWorkerBuilder<DownloadWorkerV2>(storage.context)
                .setForegroundUpdater(ImmediateFixtureForegroundUpdater(foregroundCalls))
                .build()
            // Execute the real worker body; foreground IPC uses the existing bounded test updater.
            return worker.doWork().also { assertTrue(foregroundCalls.get() > 0) }
        } finally {
            check(GlobalContext.getOrNull() === owned.koin) { "Global Koin ownership changed" }
            stopKoin()
        }
    }

    override fun close() = client.close()
}

private class ResolveHttpFailure(
    override val httpStatusCode: Int,
    message: String,
) : Exception(message), DownloadHttpStatusFailure

private const val CHALLENGE = DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL
