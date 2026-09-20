package me.manga.kira.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.ListenableWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.identity.AcceptedSourceAliasRule
import me.manga.kira.data.identity.SourceAliasReadiness
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.data.local.MangaWriteTransaction
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.repository.DownloadsRepositoryImpl
import me.manga.kira.data.repository.LibraryRepositoryImpl
import me.manga.kira.data.repository.library.LibraryChapterWriter
import me.manga.kira.data.repository.library.LibraryMetadataWriter
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import me.manga.kira.data.repository.library.LibraryRemovalGuard
import me.manga.kira.data.repository.library.LibraryRemovalStorage
import me.manga.kira.data.repository.library.LibraryRemovalWriter
import me.manga.kira.data.repository.library.LibraryWriteDependencies
import me.manga.kira.data.repository.progress.ProgressStorage
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.service.FileService
import me.manga.kira.presentation.details.DetailsEffect
import me.manga.kira.presentation.details.DetailsIntent
import me.manga.kira.presentation.features.download.ui.test2.AndroidChallengeCase
import me.manga.kira.presentation.features.download.ui.test2.CancellationFixtureStorage
import me.manga.kira.presentation.features.download.ui.test2.DownloadWorkerCancellationRows
import me.manga.kira.presentation.features.download.ui.test2.NativeCommitGate
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SourceSelectionToken
import me.manga.kira.sources.runtime.DataStoreHeaderStore
import org.koin.core.context.GlobalContext
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal fun androidDetailsChallengeCase(block: suspend AndroidDetailsChallengeFixture.() -> Unit) = runBlocking {
    check(GlobalContext.getOrNull() == null) { "Refusing to replace another test's global Koin" }
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
        Dispatchers.setMain(main)
        try {
            CancellationFixtureStorage(RuntimeEnvironment.getApplication()).use { storage ->
                DownloadWorkerCancellationRows(storage, NativeCommitGate()).use { rows ->
                    rows.seed()
                    storage.settings.setUseCbzFormat(false)
                    AndroidChallengeCase(storage, rows).use { android ->
                        val fixture = AndroidDetailsChallengeFixture(android, main)
                        try {
                            withTimeout(CASE_TIMEOUT_MILLIS) {
                                fixture.start()
                                fixture.block()
                            }
                        } finally {
                            withContext(NonCancellable) { fixture.close() }
                        }
                    }
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}

/** Reuses real Android files/Room/worker and the existing production-backed Details test factory. */
internal class AndroidDetailsChallengeFixture(
    val android: AndroidChallengeCase,
    private val main: CoroutineDispatcher,
) {
    val queue = AndroidChallengeWorkQueue(android)
    private val rows = android.rows
    private val manga = rows.manga.let { Manga(it.api, it.language, it.title, it.url, it.imageUrl, null, it.genres) }
    private val chapterUrl = rows.original.saved.url
    private val headers = DataStoreHeaderStore(android.storage.settings)
    private val pageSource = AndroidChallengeSource(manga.api, chapterUrl, headers)
    val sourceRequests get() = pageSource.requests
    val metadataFetches = MutableStateFlow(0)
    val effects = MutableStateFlow<List<DetailsEffect>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val store = ViewModelStore()
    private val dispatchers = object : DispatcherProvider {
        override val main = this@AndroidDetailsChallengeFixture.main
        override val mainImmediate = main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val unconfined = Dispatchers.Unconfined
    }
    private val libraryRuntime = AndroidChallengeLibraryRuntime(android, dispatchers)
    private val library = libraryRuntime.repository
    private val metadata = object : MangaDetailsRepository {
        override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
            assertEquals(this@AndroidDetailsChallengeFixture.manga, manga)
            metadataFetches.value++
            return AppResult.Success(details())
        }
    }
    private val vm = createDetailsRoomViewModel(
        DetailsRoomEnvironment(
            rows.db, android.storage.fileSystem, rows.artifacts.ownership, dispatchers,
            owners = libraryRuntime.owners,
            operations = rows.operations,
        ),
        library, metadata, queue.engine,
    ).also { store.put("details", it) }
    private val observed = DownloadsRepositoryImpl(queue.engine, rows.realDao)

    suspend fun start() {
        headers.save(manga.api, capturedHeaders("expired"))
        withContext(main) {
            scope.launch { vm.effects.collect { effects.value = effects.value + it } }
            vm.submit(DetailsIntent.OnEnter(manga))
        }
        vm.state.first { !it.isLoading && it.details?.chapters?.any { chapter -> chapter.url == chapterUrl } == true }
        awaitDownloadState(DownloadState.QUEUED)
    }

    suspend fun runWorker() {
        assertEquals(ListenableWorker.Result.success(), android.runWorker(pageProvider = pageSource.provider))
    }

    suspend fun mappedDownload(): DownloadedChapter = observed.observeForManga(manga).first().single()

    suspend fun assertChallengeRow() {
        val mapped = mappedDownload()
        assertEquals(rows.original.saved.id, mapped.chapterId)
        assertEquals(DownloadState.FAILED, mapped.state)
        assertEquals(DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL, mapped.errorMsg)
        assertEquals(rows.download().errorMsg, mapped.errorMsg, "the production mapper must retain Android's actual stored marker")
        assertNull(rows.artifacts.ownership.currentClaim(mapped.chapterId), "worker must settle before the solver retry")
    }

    suspend fun awaitSolver(count: Int): String {
        effects.first { it.filterIsInstance<DetailsEffect.SolveCloudflareChallenge>().size >= count }
        assertEquals(
            DetailsEffect.SolveCloudflareChallenge(manga.url, manga.api),
            effects.value.filterIsInstance<DetailsEffect.SolveCloudflareChallenge>().last(),
        )
        return assertNotNull(recoveryRequestId())
    }

    suspend fun returnFromSolver(requestId: String, captured: Map<String, String>, count: Int) {
        // Model browser-capture output only. Storage, engine reload, page mapping and transfer are real.
        headers.save(manga.api, captured)
        assertEquals(captured, headers.headersFor(manga.api))
        withContext(main) { vm.submit(DetailsIntent.OnCloudflareSolverReturned(requestId)) }
        queue.enqueued.first { it.size >= count }
        assertEquals(List(count) { Triple(rows.original.saved.id, manga.title, manga.api) }, queue.enqueued.value)
        awaitDownloadState(DownloadState.QUEUED)
        queue.assertHeldRequests(count)
    }

    suspend fun awaitDownloadState(expected: DownloadState) {
        vm.state.first { it.chapterDownloads[chapterUrl]?.state == expected }
    }

    suspend fun awaitAbsentDownloadProgress() {
        vm.state.first { chapterUrl !in it.chapterDownloads }
    }

    suspend fun awaitExhaustedSolver() {
        effects.first { list -> list.filterIsInstance<DetailsEffect.ShowError>().any { it.error == AppError.Network.Http(403) } }
    }

    suspend fun recoveryRequestId(): String? = withContext(main) { vm.cloudflareRecoveryRequestId(manga.url, manga.api) }

    suspend fun assertNoSolver() = withContext(main) {
        assertTrue(effects.value.none { it is DetailsEffect.SolveCloudflareChallenge })
        assertNull(vm.cloudflareRecoveryRequestId(manga.url, manga.api))
    }

    suspend fun close() {
        val job = vm.viewModelScope.coroutineContext[Job]
        val effectsJob = scope.coroutineContext[Job]
        withContext(main) {
            store.clear()
            scope.cancel()
        }
        withTimeout(CLEANUP_TIMEOUT_MILLIS) {
            job?.join()
            effectsJob?.join()
        }
        queue.close()
    }

    private fun details(): MangaDetails = MangaDetails(
        api = manga.api,
        language = manga.language,
        title = manga.title,
        url = manga.url,
        coverUrl = manga.coverUrl,
        description = "",
        author = "",
        rating = "",
        status = "ongoing",
        genres = manga.genres,
        chapters = listOf(rows.original.saved.let { Chapter(it.number, it.name, it.url, null, false, it.isBookmarked) }),
    )
}

/**
 * Real typed Room library adapters with an explicit finite test policy, not runtime selection
 * bootstrap. These challenge tests exercise no library removal and grant no removal authority.
 */
private class AndroidChallengeLibraryRuntime(android: AndroidChallengeCase, dispatchers: DispatcherProvider) {
    private val db = android.rows.db
    private var insideWriter = false
    private val actual = RoomMangaWriteTransaction(db)
    private val writer = object : MangaWriteTransaction {
        override suspend fun <T> write(block: suspend () -> T): T = actual.write {
            check(!insideWriter)
            insideWriter = true
            try { block() } finally { insideWriter = false }
        }
    }
    private val policy = SourceAliasSnapshot(
        SourceSelectionToken(
            1L, SelectedCatalogIdentity(SelectedCatalogKind.SIGNED, 1L, "a".repeat(64)), "b".repeat(64),
        ),
        listOf(AcceptedSourceAliasRule(android.rows.manga.api, "https://example.test", emptyList())),
    )
    private val snapshots = object : SourceAliasSnapshotProvider {
        override val readiness = MutableStateFlow<SourceAliasReadiness>(SourceAliasReadiness.Ready(policy.token))

        override suspend fun readInTransaction(): SourceAliasSnapshot {
            check(insideWriter) { "Challenge fixture policy must be read inside the real writer" }
            return policy
        }
    }
    val owners = LibraryOwnerTransactions(writer, snapshots, db.mangaDao())
    private val progress = ProgressStorage(
        db.readerProgressDao(), db.readerLegacyCleanupDao(), db.mangaDao(), db.chapterDao(), db.backupDao(),
    )
    private val writes = LibraryWriteDependencies(
        owners,
        db.libraryDeo(),
        LibraryMetadataWriter(db.mangaDao(), db.libraryDeo()),
        LibraryChapterWriter(db.libraryDeo()),
        LibraryRemovalWriter(
            owners,
            LibraryRemovalStorage(db.libraryDeo(), progress, db.chapterDownloadingDao()),
            UnsupportedAndroidChallengeRemoval,
            FileService(android.storage.fileSystem),
            android.rows.artifacts.ownership,
        ),
    )
    val repository = LibraryRepositoryImpl(db.mangaDao(), writes, dispatchers)
}

private object UnsupportedAndroidChallengeRemoval : LibraryRemovalGuard {
    override suspend fun <T> withQuiescentWorks(owners: List<SavedWorkIdentity>, block: suspend () -> T): T =
        error("Library removal is outside the Android challenge fixture")

    override suspend fun checkInTransaction(owners: List<SavedWorkIdentity>): Unit =
        error("No removal authority was granted by the Android challenge fixture")
}

private const val CASE_TIMEOUT_MILLIS = 60_000L
private const val CLEANUP_TIMEOUT_MILLIS = 15_000L
