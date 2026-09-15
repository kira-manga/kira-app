package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.download.BackgroundScheduler
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.download.BackgroundWorkSignal
import me.manga.kira.platform.download.StagedDownloadPage
import me.manga.kira.platform.download.TransferListener
import me.manga.kira.platform.download.TransferRequest
import me.manga.kira.platform.filesystem.AppFileSystem
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
import me.manga.kira.presentation.features.download.domain.clean.ManifestPage
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.assertNotNull

internal suspend fun IosCbzFinalizationFixture.prepareAttempt(
    chapter: IosCbzChapter,
    state: DownloadingState,
    failures: Int = 0,
    pageCount: Int = 1,
): ChapterArtifactClaim {
    dao.updateStateChId(chapter.saved.id, state)
    val claim = assertNotNull(artifacts.claim(download(chapter)))
    writeManifest(chapter, claim, failures, pageCount)
    return claim
}

internal suspend fun IosCbzFinalizationFixture.writeManifest(
    chapter: IosCbzChapter,
    claim: ChapterArtifactClaim,
    failures: Int = 0,
    pageCount: Int = 1,
) {
    assertNotNull(artifacts.ownership.files(claim) {
        DownloadManifestStore(appFileSystem).write(DownloadManifest(
            mangaId = chapter.saved.mangaId,
            chapterId = chapter.saved.id,
            api = "test",
            pages = List(pageCount) { index ->
                ManifestPage(index, "https://example.test/page.png", emptyMap(), attempts = failures)
            },
            attemptToken = claim.token,
        ))
    })
}

internal fun IosCbzFinalizationFixture.manifest(chapter: IosCbzChapter): DownloadManifest =
    assertNotNull(DownloadManifestStore(appFileSystem).read(chapter.saved.mangaId, chapter.saved.id))

internal fun IosCbzFinalizationFixture.engine(
    scope: CoroutineScope,
    transport: BackgroundTransport,
    downloads: ChapterDownloadDao = dao,
    files: AppFileSystem = appFileSystem,
    host: BackgroundDownloadHost = BackgroundDownloadHost(
        scope, BackgroundScheduler.NoOp, BackgroundWorkSignal(), DownloadNotifier.NoOp,
    ),
    downloadArtifacts: ChapterDownloadArtifacts = artifacts,
) =
    BackgroundUrlSessionDownloadRepository(
        storage = BackgroundDownloadStorage(downloads, DownloadManifestStore(files), files),
        stages = ChapterDownloadStages(
            ChapterPageResolver(db.mangaDao(), object : ChapterPageProvider {
                override suspend fun pagesOrNull(api: String, mangaUrl: String, mangaLanguage: String, chapterUrl: String): List<DownloadPage> =
                    error("Persisted manifests must avoid a new resolve")
            }),
            finalizer(UnusedReceiverCbzWriter),
        ),
        pageTransfer = BackgroundPageTransfer(transport, IosPageMediaInspector(system = system)),
        host = host,
        dataStoreHelper = DataStoreHelper(MapSettings()),
        artifacts = downloadArtifacts,
    )

/** Hold startup reconciliation independently so only the callback/reopen under test can pump. */
internal class ArtifactTestTransport(private val ready: Boolean = false) : BackgroundTransport {
    lateinit var receiver: TransferListener
    private val startup = CompletableDeferred<Unit>()
    val requests = Channel<TransferRequest>(Channel.UNLIMITED)
    val enqueued = mutableListOf<TransferRequest>()
    val cancelled = mutableListOf<Pair<Long, String>>()
    override fun setListener(listener: TransferListener) { receiver = listener }
    override suspend fun ensureReady() { if (!ready) startup.await() }
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

/** Default injects a live-move failure; optional gates still forward real staging/file operations. */
internal class ReceiverPage(
    fixture: IosCbzFinalizationFixture,
    chapter: IosCbzChapter,
    name: String,
    failPublication: Boolean = true,
    beforeDiscard: (() -> Unit)? = null,
) {
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
                    if (failPublication) throw IOException("Injected receiver publication failure")
                }
                super.atomicMove(source, target)
            }
            override fun delete(path: Path, mustExist: Boolean) {
                if (path == this@ReceiverPage.path) beforeDiscard?.invoke()
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

internal fun ArtifactTestTransport.deliverPage(
    chapter: IosCbzChapter,
    token: String,
    page: StagedDownloadPage,
): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { acknowledged ->
    receiver.onPageComplete(chapter.saved.mangaId, chapter.saved.id, 0, token, page) {
        acknowledged.complete(Unit)
    }
}
