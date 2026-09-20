package me.manga.kira.presentation.features.download.ui.test2

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import me.manga.kira.core.cbz.OptimizedCbzManager
import me.manga.kira.core.util.heap.DeviceTier
import me.manga.kira.data.download.selection.DownloadCatalogAdmission
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.repository.LibraryMetadataRepository
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.device.DeviceTierProbe
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.AndroidPageMediaInspector
import me.manga.kira.presentation.features.download.data.DownloadState
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.ChapterDownloadArchive
import me.manga.kira.presentation.features.download.domain.ChapterDownloadPersistence
import me.manga.kira.presentation.features.download.domain.ChapterDownloadService
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import me.manga.kira.presentation.features.download.domain.clean.PageDownloadTransfer
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Composition only: reuses App75's real files/Room/JNI fixture, never reimplements persistence. */
internal class AndroidChallengeCase(
    val storage: CancellationFixtureStorage,
    val rows: DownloadWorkerCancellationRows,
) : AutoCloseable {
    val responseStatus = AtomicInteger(403)
    val transportCancellation = AtomicReference<CancellationException?>()
    val requests = AtomicInteger()
    val workerQueueReads = AtomicInteger()
    val workerServiceResolutions = AtomicInteger()
    val requestHeaders = CopyOnWriteArrayList<Headers>()
    val pages = listOf(DownloadPage("https://images.example/page.png", emptyMap()))
    private val client = HttpClient(
        MockEngine { request ->
            requests.incrementAndGet()
            requestHeaders += request.headers
            transportCancellation.get()?.let { throw it }
            val status = HttpStatusCode.fromValue(responseStatus.get())
            if (status == HttpStatusCode.OK) {
                respond(PAGE_PNG, status, headersOf(HttpHeaders.ContentType, "image/png"))
            } else {
                respond("not an image", status)
            }
        },
    )
    private val files = FileService(storage.fileSystem)
    private val deviceTierProbe = object : DeviceTierProbe {
        override fun detect(): DeviceTier = DeviceTier.LOW
    }
    private val library = LibraryRepository(
        mangaDao = rows.db.mangaDao(),
        chapterDao = rows.db.chapterDao(),
        libraryDeo = rows.db.libraryDeo(),
        metadata = DownloadFixtureUnusedCoverMetadata,
        fileService = files,
    )
    val service = ChapterDownloadService(
        storage.context,
        ChapterDownloadPersistence(library, rows.db.notificationDao(), rows.realDao, files),
        PageDownloadTransfer(client, AndroidPageMediaInspector()),
        ChapterDownloadArchive(OptimizedCbzManager(storage.context, deviceTierProbe), storage.settings),
        artifacts = rows.artifacts,
    )
    private val workerDao = object : ChapterDownloadDao by rows.realDao {
        override suspend fun getQueuedChaptersForWorker(queuedState: DownloadingState): List<ChapterDownloadEntity> {
            workerQueueReads.incrementAndGet()
            return rows.realDao.getQueuedChaptersForWorker(queuedState)
        }
    }

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

    suspend fun runWorker(
        resolveFailure: Throwable? = null,
        pageProvider: ChapterPageProvider? = null,
    ): ListenableWorker.Result {
        val provider = pageProvider ?: workerPageProvider(resolveFailure)
        check(GlobalContext.getOrNull() == null)
        val owned = startKoin { modules(workerModule(provider)) }
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

    private fun workerPageProvider(resolveFailure: Throwable?) = object : ChapterPageProvider {
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

    private fun workerModule(provider: ChapterPageProvider) = module {
        single<ChapterDownloadDao> { workerDao }
        single<MangaDao> { rows.db.mangaDao() }
        single<ChapterDownloadService> { service.also { workerServiceResolutions.incrementAndGet() } }
        single { rows.artifacts }
        single { rows.operations }
        single<DownloadCatalogAdmission> { rows.catalogAdmission }
        single<ChapterPageProvider> { provider }
        single<AppFileSystem> { storage.fileSystem }
    }

    override fun close() = client.close()
}

/** The real service uses this facade only for chapter paths; cover reconciliation is out of scope. */
internal object DownloadFixtureUnusedCoverMetadata : LibraryMetadataRepository {
    override suspend fun updateCoverIfChanged(
        owner: SavedWorkIdentity,
        fetched: WorkLocator,
        newCoverUrl: String,
    ): Nothing = error("Download fixture must not reconcile manga covers")
}
