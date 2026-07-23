package me.manga.kira.presentation.details

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.repository.AdultContentClassifier
import me.manga.kira.domain.repository.AnalyticsPort
import me.manga.kira.domain.repository.ChapterBookmarkRepository
import me.manga.kira.domain.repository.ChapterDeletionRepository
import me.manga.kira.domain.repository.ChapterIdResolver
import me.manga.kira.domain.repository.ChapterNewBadgeRepository
import me.manga.kira.domain.repository.CompressionDeferralRepository
import me.manga.kira.domain.repository.ConnectivityRepository
import me.manga.kira.domain.repository.DownloadsActionRepository
import me.manga.kira.domain.repository.DownloadsRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.MarkChapterReadRepository
import me.manga.kira.domain.repository.SavedMangaDetailsRepository
import me.manga.kira.domain.usecase.analytics.LogMangaOpenUseCase
import me.manga.kira.domain.usecase.connectivity.ObserveConnectivityUseCase
import me.manga.kira.domain.usecase.details.ClearChapterNewUseCase
import me.manga.kira.domain.usecase.details.DeleteChapterUseCase
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.details.IsAdultContentUseCase
import me.manga.kira.domain.usecase.details.ObserveSavedMangaDetailsUseCase
import me.manga.kira.domain.usecase.details.ResolveChapterIdUseCase
import me.manga.kira.domain.usecase.downloads.CancelAllDownloadsUseCase
import me.manga.kira.domain.usecase.downloads.CancelChapterDownloadUseCase
import me.manga.kira.domain.usecase.downloads.CancelDownloadUseCase
import me.manga.kira.domain.usecase.downloads.CancelRunningDownloadUseCase
import me.manga.kira.domain.usecase.downloads.DeleteDownloadedChapterUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueAllChaptersDownloadUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueChapterDownloadUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase
import me.manga.kira.domain.usecase.downloads.ObserveCompressionDeferredUseCase
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.library.MarkMangaOpenedUseCase
import me.manga.kira.domain.usecase.library.ObserveInLibraryUseCase
import me.manga.kira.domain.usecase.library.PersistNewChaptersUseCase
import me.manga.kira.domain.usecase.library.ToggleInLibraryUseCase
import me.manga.kira.domain.usecase.reader.MarkChaptersReadUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterBookmarkUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterReadUseCase
import me.manga.kira.presentation.testing.FakeLibraryRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavioural reproduction + verification of the two reported runtime regressions (2026-05-31):
 *  - Bug 1: opening a saved manga from Library "looked fresh" — the rework never read the saved
 *    chapter list / read-state from Room, only the network fetch. These tests assert the Room-backed
 *    [ObserveSavedMangaDetailsUseCase] now drives [DetailsState.details] immediately (offline-first),
 *    that read-state survives a network refresh (merge), and that a fetch failure still leaves the
 *    saved list visible (no "fresh"/empty dead-end).
 *  - Bug 2: a Cloudflare/anti-bot failure surfaced as a dead-end "failed to load". These assert that
 *    the 403-family statuses route to [DetailsEffect.SolveCloudflareChallenge], not [ShowError].
 *
 * Driven through the real MVI `submit` surface against hand fakes; `UnconfinedTestDispatcher` as Main
 * for eager execution (same harness as HomeViewModelTest / LibraryViewModelApplyViewTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelRegressionTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ---- fakes -------------------------------------------------------------------------------

    private class FakeMangaDetailsRepository(
        var result: AppResult<MangaDetails>,
    ) : MangaDetailsRepository {
        var fetchCount = 0
            private set
        override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
            fetchCount++
            return result
        }
    }

    private class FakeSavedMangaDetailsRepository : SavedMangaDetailsRepository {
        val saved = MutableStateFlow<MangaDetails?>(null)
        override fun observeSavedDetails(api: String, title: String): Flow<MangaDetails?> = saved
    }

    private object NoAdultClassifier : AdultContentClassifier {
        override fun isAdultContent(api: String, genres: List<String>): Boolean = false
    }

    private object NullChapterIdResolver : ChapterIdResolver {
        override suspend fun resolveChapterId(chapterUrl: String): Long? = null
        override suspend fun resolveChapterIds(chapterUrls: List<String>): Map<String, Long> = emptyMap()
    }

    private class FixedChapterIdResolver(private val id: Long) : ChapterIdResolver {
        override suspend fun resolveChapterId(chapterUrl: String): Long = id
        override suspend fun resolveChapterIds(chapterUrls: List<String>): Map<String, Long> =
            chapterUrls.associateWith { id }
    }

    private object NoopDownloadsActionRepository : DownloadsActionRepository {
        override suspend fun enqueueDownload(chapterId: Long, mangaTitle: String, api: String) = Result.success(Unit)
        override suspend fun retryDownload(chapterId: Long) = Result.success(Unit)
        override suspend fun cancelDownload(chapterId: Long) = Result.success(Unit)
        override suspend fun cancelRunningDownload(chapterId: Long, mangaId: Long) = Result.success(Unit)
        override suspend fun cancelAllDownloads() = Result.success(Unit)
        override suspend fun deleteDownload(chapterId: Long) = Result.success(Unit)
        override suspend fun deleteDownloadedChapter(chapterId: Long) = Result.success(Unit)
        override suspend fun reconcileInterrupted() = Result.success(Unit)
    }

    private object NoopMarkChapterReadRepository : MarkChapterReadRepository {
        override suspend fun markRead(chapterUrl: String) = Unit
        override suspend fun toggleRead(chapterUrl: String) = Unit
        override suspend fun markRead(chapterUrls: List<String>) = Unit
    }

    /** Records every read-marking call so a test can assert opening a chapter does NOT mark it read. */
    private class RecordingMarkChapterReadRepository : MarkChapterReadRepository {
        val read = mutableListOf<String>()
        override suspend fun markRead(chapterUrl: String) { read += chapterUrl }
        override suspend fun toggleRead(chapterUrl: String) { read += chapterUrl }
        override suspend fun markRead(chapterUrls: List<String>) { read += chapterUrls }
    }

    private object NoopChapterBookmarkRepository : ChapterBookmarkRepository {
        override fun observeBookmark(chapterUrl: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun toggleBookmark(chapterUrl: String): Boolean = true
    }

    /** #4: drives DetailsState.isOnline. Default online so it never blocks the existing tests. */
    private class FakeConnectivityRepository(online: Boolean = true) : ConnectivityRepository {
        private val flow = MutableStateFlow(online)
        override fun observeIsOnline(): Flow<Boolean> = flow
    }

    private class FakeCompressionDeferralRepository(
        private val deferred: Flow<Boolean>,
    ) : CompressionDeferralRepository {
        override fun observeLowPowerDeferral(): Flow<Boolean> = deferred
    }

    /** #11: records manga_open events so a test can assert it fires once per identity. */
    private class RecordingAnalyticsPort : AnalyticsPort {
        val mangaOpens = mutableListOf<Pair<String, String>>()
        var appOpens = 0
            private set
        override fun logAppOpen() { appOpens++ }
        override fun logMangaOpen(api: String, title: String, sourceScreen: String) {
            mangaOpens += api to title
        }
    }

    private class RecordingChapterNewBadgeRepository : ChapterNewBadgeRepository {
        val cleared = mutableListOf<String>()
        override suspend fun clearNew(chapterUrl: String) { cleared += chapterUrl }
    }

    private class RecordingChapterDeletionRepository : ChapterDeletionRepository {
        val deleted = mutableListOf<Long>()
        override suspend fun deleteChapter(chapterId: Long) { deleted += chapterId }
    }

    private object EmptyDownloadsRepository : DownloadsRepository {
        override fun observeAll(): Flow<List<DownloadedChapter>> = MutableStateFlow(emptyList())
    }

    /**
     * PFIX-DLPROGRESS: a downloads repo whose emissions a test can push through `rows` to mimic the
     * reactive Room `chapter_downloads` flow ticking (each `updateProgress` / state transition the
     * worker writes re-emits here). Used to verify the Details row reflects live state+progress.
     */
    private class FakeDownloadsRepository : DownloadsRepository {
        val rows = MutableStateFlow<List<DownloadedChapter>>(emptyList())
        override fun observeAll(): Flow<List<DownloadedChapter>> = rows
    }

    /**
     * PFIX-DLPROGRESS: resolves a fixed set of chapter `url` → Room `id` mappings (unknown urls →
     * null), so the VM can join the displayed chapter list onto the active-download rows by id —
     * the join `NullChapterIdResolver` cannot exercise.
     */
    private class MapChapterIdResolver(private val byUrl: Map<String, Long>) : ChapterIdResolver {
        override suspend fun resolveChapterId(chapterUrl: String): Long? = byUrl[chapterUrl]
        override suspend fun resolveChapterIds(chapterUrls: List<String>): Map<String, Long> =
            chapterUrls.mapNotNull { url -> byUrl[url]?.let { url to it } }.toMap()
    }

    private fun downloadedChapter(
        url: String,
        state: me.manga.kira.domain.model.downloads.DownloadState,
        progress: Int,
        sizeBytes: Long = 0,
        chapterId: Long = 10L,
    ) =
        DownloadedChapter(
            chapterId = chapterId,
            mangaId = 1L,
            number = "1",
            mangaTitle = "Naruto",
            state = state,
            progress = progress,
            errorMsg = null,
            url = url,
            sizeBytes = sizeBytes,
        )

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val mainImmediate: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val unconfined: CoroutineDispatcher = dispatcher
    }

    private fun manga(api: String = "src", title: String = "Naruto") = Manga(
        api = api,
        language = "en",
        title = title,
        url = "https://x/naruto",
        coverUrl = "https://x/c.jpg",
        rating = null,
        genres = emptyList(),
    )

    private fun chapter(url: String, isRead: Boolean = false, isDownloaded: Boolean = false) = Chapter(
        number = url.substringAfterLast('/'),
        name = "",
        url = url,
        date = null,
        isDownloaded = isDownloaded,
        isBookmarked = false,
        isRead = isRead,
    )

    private fun details(chapters: List<Chapter>) = MangaDetails(
        api = "src",
        language = "en",
        title = "Naruto",
        url = "https://x/naruto",
        coverUrl = "https://x/c.jpg",
        description = "",
        author = "",
        rating = "",
        status = "",
        genres = emptyList(),
        chapters = chapters,
    )

    private fun vm(
        fetch: AppResult<MangaDetails>,
        saved: FakeSavedMangaDetailsRepository,
    ): DetailsViewModel = vmWithFetchFake(fetch, saved).first

    /** Builds the VM and also returns the fetch fake so a test can assert how often the network ran. */
    private fun vmWithFetchFake(
        fetch: AppResult<MangaDetails>,
        saved: FakeSavedMangaDetailsRepository,
        downloadsRepo: DownloadsRepository = EmptyDownloadsRepository,
        idResolver: ChapterIdResolver = NullChapterIdResolver,
        libraryRepo: FakeLibraryRepository = FakeLibraryRepository(),
        badgeRepo: ChapterNewBadgeRepository = RecordingChapterNewBadgeRepository(),
        deletionRepo: ChapterDeletionRepository = RecordingChapterDeletionRepository(),
        markReadRepo: MarkChapterReadRepository = NoopMarkChapterReadRepository,
        connectivity: ConnectivityRepository = FakeConnectivityRepository(online = true),
        analytics: AnalyticsPort = RecordingAnalyticsPort(),
        compressionDeferred: Flow<Boolean> = MutableStateFlow(false),
    ): Pair<DetailsViewModel, FakeMangaDetailsRepository> {
        val fetchFake = FakeMangaDetailsRepository(fetch)
        val vm = DetailsViewModel(
            fetchDetails = FetchMangaDetailsUseCase(fetchFake),
            isAdultContent = IsAdultContentUseCase(NoAdultClassifier),
            observeInLibrary = ObserveInLibraryUseCase(libraryRepo),
            observeSavedDetails = ObserveSavedMangaDetailsUseCase(saved),
            toggleInLibrary = ToggleInLibraryUseCase(libraryRepo),
            enqueueAllChaptersDownload = EnqueueAllChaptersDownloadUseCase(
                chapterIdResolver = NullChapterIdResolver,
                enqueueDownload = EnqueueDownloadUseCase(NoopDownloadsActionRepository),
                dispatchers = testDispatchers,
            ),
            toggleChapterRead = ToggleChapterReadUseCase(markReadRepo),
            toggleChapterBookmark = ToggleChapterBookmarkUseCase(NoopChapterBookmarkRepository),
            markChaptersRead = MarkChaptersReadUseCase(markReadRepo),
            enqueueChapterDownload = EnqueueChapterDownloadUseCase(
                chapterIdResolver = NullChapterIdResolver,
                enqueueDownload = EnqueueDownloadUseCase(NoopDownloadsActionRepository),
            ),
            cancelChapterDownload = CancelChapterDownloadUseCase(
                chapterIdResolver = NullChapterIdResolver,
                cancelDownload = CancelDownloadUseCase(NoopDownloadsActionRepository),
            ),
            cancelRunningDownload = CancelRunningDownloadUseCase(NoopDownloadsActionRepository),
            cancelAllDownloads = CancelAllDownloadsUseCase(NoopDownloadsActionRepository),
            deleteDownloadedChapter = DeleteDownloadedChapterUseCase(NoopDownloadsActionRepository),
            observeDownloads = ObserveDownloadsUseCase(downloadsRepo),
            resolveChapterId = ResolveChapterIdUseCase(idResolver),
            markMangaOpened = MarkMangaOpenedUseCase(libraryRepo),
            persistNewChapters = PersistNewChaptersUseCase(libraryRepo),
            clearChapterNew = ClearChapterNewUseCase(badgeRepo),
            deleteChapter = DeleteChapterUseCase(deletionRepo),
            observeConnectivity = ObserveConnectivityUseCase(connectivity),
            logMangaOpen = LogMangaOpenUseCase(analytics),
            observeCompressionDeferred = ObserveCompressionDeferredUseCase(
                FakeCompressionDeferralRepository(compressionDeferred),
            ),
        )
        return vm to fetchFake
    }

    // ---- Bug 1: saved manga opens local, read-state preserved -------------------------------

    @Test
    fun libraryOpen_rendersSavedReadStateImmediately_andMergesOverNetwork() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        // Saved (Room) chapters carry read/downloaded state; the network fetch does NOT.
        saved.saved.value = details(listOf(chapter("c/1", isRead = true, isDownloaded = true), chapter("c/2")))
        val networkOk = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2"))))

        val vm = vm(fetch = networkOk, saved = saved)
        vm.submit(DetailsIntent.OnEnter(manga()))

        val s = vm.state.value
        assertNotNull(s.details, "saved details must populate immediately (not look fresh)")
        assertEquals(false, s.isLoading, "spinner cleared once saved list is shown")
        val c1 = s.details!!.chapters.first { it.url == "c/1" }
        // The merge must preserve the Room read/downloaded flags even though the network list lacks them.
        assertTrue(c1.isRead, "read mark from Room survives the network refresh merge")
        assertTrue(c1.isDownloaded, "downloaded mark from Room survives the network refresh merge")
    }

    @Test
    fun libraryOpen_offline_keepsSavedListVisible_whenFetchFails() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1", isRead = true), chapter("c/2")))
        // Network fails with a non-challenge error (would otherwise be a "failed to load" dead-end).
        val vm = vm(fetch = AppResult.Failure(AppError.Network.Timeout()), saved = saved)

        vm.submit(DetailsIntent.OnEnter(manga()))

        val s = vm.state.value
        assertNotNull(s.details, "saved chapter list stays visible even when the source fetch fails (offline parity)")
        assertEquals(2, s.details!!.chapters.size)
        assertTrue(s.details!!.chapters.first { it.url == "c/1" }.isRead)
    }

    @Test
    fun freshOpen_notInLibrary_usesNetworkOnly() = runTest {
        val saved = FakeSavedMangaDetailsRepository() // stays null → not saved
        val networkOk = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2"), chapter("c/3"))))
        val vm = vm(fetch = networkOk, saved = saved)

        vm.submit(DetailsIntent.OnEnter(manga()))

        val s = vm.state.value
        assertNotNull(s.details)
        assertEquals(3, s.details!!.chapters.size, "non-saved manga still renders the full network list")
    }

    @Test
    fun compressionDeferral_isProjectedIntoDetailsState() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1")))
        val deferred = MutableStateFlow(false)
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1")))),
            saved = saved,
            compressionDeferred = deferred,
        )

        vm.submit(DetailsIntent.OnEnter(manga()))
        assertFalse(vm.state.value.compressionDeferred)

        deferred.value = true
        assertTrue(vm.state.value.compressionDeferred)
    }

    // ---- LAST_READ last-open bump fires on chapter-open, NOT on Details view (native parity) ----

    @Test
    fun lastOpen_bumpsOnChapterOpen_notOnDetailsOpen() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1"), chapter("c/2")))
        val libraryRepo = FakeLibraryRepository()
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2")))),
            saved = saved,
            libraryRepo = libraryRepo,
        )

        // Native (LibraryDetailsViewModel.loadMangaDetails) does NOT bump on Details open.
        vm.submit(DetailsIntent.OnEnter(manga()))
        assertEquals(
            0,
            libraryRepo.calls.count { it.startsWith("markOpened") },
            "viewing Details must not bump last-open (native bumps on chapter-open, not Details view)",
        )

        // Native (LibraryMangaRoute.onChapterClick → updateLastOpen) bumps when a chapter is opened.
        vm.submit(DetailsIntent.OnChapterClick(chapter("c/1")))
        assertEquals(
            1,
            libraryRepo.calls.count { it.startsWith("markOpened") },
            "opening a chapter bumps last-open exactly once",
        )
    }

    // ---- Cache-first open (native parity, 2026-06-01): no network fetch on open for in-library --

    @Test
    fun libraryOpen_withCachedChapters_doesNotFetchNetworkOnOpen() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        // In library + already has a saved chapter list → must render from cache, no fetch on open.
        saved.saved.value = details(listOf(chapter("c/1", isRead = true), chapter("c/2")))
        val (vm, fetchFake) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2")))),
            saved = saved,
        )

        vm.submit(DetailsIntent.OnEnter(manga()))

        assertEquals(0, fetchFake.fetchCount, "an in-library manga with cached chapters must NOT hit the network on open")
        val s = vm.state.value
        assertNotNull(s.details, "the cached chapter list still renders")
        assertEquals(2, s.details!!.chapters.size)
        assertEquals(false, s.isLoading, "spinner cleared from the cached render")
        assertTrue(s.details!!.chapters.first { it.url == "c/1" }.isRead, "cached read marks are shown")
    }

    @Test
    fun libraryOpen_savedButNoCachedChapters_fetchesOnce() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        // In library but the saved chapter list is empty (added before this fix) → fetch once so
        // the list isn't empty.
        saved.saved.value = details(emptyList())
        val (vm, fetchFake) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2"), chapter("c/3")))),
            saved = saved,
        )

        vm.submit(DetailsIntent.OnEnter(manga()))

        assertEquals(1, fetchFake.fetchCount, "an in-library manga with no cached chapters fetches once on open")
        assertEquals(3, vm.state.value.details!!.chapters.size, "the fetched list populates the empty cache view")
    }

    @Test
    fun onRetry_alwaysFetches_evenWhenCachePresent() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1"), chapter("c/2")))
        val (vm, fetchFake) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2")))),
            saved = saved,
        )

        vm.submit(DetailsIntent.OnEnter(manga()))
        assertEquals(0, fetchFake.fetchCount, "open is cache-only")

        vm.submit(DetailsIntent.OnRetry) // explicit refresh = pull-to-refresh parity
        assertEquals(1, fetchFake.fetchCount, "OnRetry forces a network fetch regardless of cache/membership")
    }

    @Test
    fun onRetry_emptySuccessfulPayload_keepsLastKnownGoodChaptersVisible() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(
            listOf(
                chapter("c/1", isRead = true),
                chapter("c/2", isDownloaded = true),
            ),
        )
        val (vm, fetchFake) = vmWithFetchFake(
            // Reproduces Azora's July 2026 API change: HTTP/parsing succeeds, but the details
            // payload contains an empty post.chapters unless the opt-in query is present.
            fetch = AppResult.Success(details(emptyList())),
            saved = saved,
        )

        vm.submit(DetailsIntent.OnEnter(manga()))
        assertEquals(2, vm.state.value.details!!.chapters.size)

        vm.submit(DetailsIntent.OnRetry)

        assertEquals(1, fetchFake.fetchCount)
        val chapters = vm.state.value.details!!.chapters
        assertEquals(listOf("c/1", "c/2"), chapters.map { it.url })
        assertTrue(chapters.first().isRead, "saved read state remains attached to the retained list")
        assertTrue(chapters.last().isDownloaded, "saved download state remains attached to the retained list")
    }

    // ---- PFIX-DLPROGRESS: live per-chapter download state+progress, then flip to downloaded ----

    @Test
    fun chapterRow_reflectsLiveDownloadProgress_thenFlipsToDownloadedOnCompletion() = runTest {
        // In-library manga with a cached chapter list (renders from the saved flow, no network).
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1"), chapter("c/2")))
        val downloads = FakeDownloadsRepository()
        // c/1 resolves to Room id 10; c/2 has no download.
        val resolver = MapChapterIdResolver(mapOf("c/1" to 10L, "c/2" to 20L))
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2")))),
            saved = saved,
            downloadsRepo = downloads,
            idResolver = resolver,
        )

        vm.submit(DetailsIntent.OnEnter(manga()))

        // Symptom 1: while RUNNING the row must carry the live state + progress (matching native's
        // determinate ring), not just a boolean "is downloading". Joined to the chapter by `url`.
        downloads.rows.value = listOf(
            downloadedChapter(url = "c/1", state = me.manga.kira.domain.model.downloads.DownloadState.RUNNING, progress = 42),
        )
        run {
            val s = vm.state.value
            val entry = s.chapterDownloads["c/1"]
            assertNotNull(entry, "the running chapter must have a live download entry")
            assertEquals(me.manga.kira.domain.model.downloads.DownloadState.RUNNING, entry.state)
            assertEquals(42, entry.progress, "the live RUNNING progress (42) must reach state")
            assertTrue("c/1" in s.downloadingChapterUrls, "the running chapter is an active download url")
            assertEquals(null, s.chapterDownloads["c/2"], "a chapter with no download has no entry")
        }

        // A later tick advances the percent — recomposition must follow (the DAO re-emits).
        downloads.rows.value = listOf(
            downloadedChapter(url = "c/1", state = me.manga.kira.domain.model.downloads.DownloadState.RUNNING, progress = 88),
        )
        assertEquals(88, vm.state.value.chapterDownloads["c/1"]?.progress, "a progress tick re-emits the new percent")

        // Symptom 2 (completion-freeze fix): the SUCCESS row arrives in the downloads flow ALONE —
        // the saved-details flow has NOT re-emitted isDownloaded yet. The row must STILL read
        // "downloaded" atomically from the SUCCESS entry (no leave/return, no dependency on the
        // separately-delivered saved flow), drop out of the active set, and carry the on-disk size.
        downloads.rows.value = listOf(
            downloadedChapter(
                url = "c/1",
                state = me.manga.kira.domain.model.downloads.DownloadState.SUCCESS,
                progress = 100,
                sizeBytes = 12L * 1024 * 1024,
            ),
        )

        val s = vm.state.value
        val done = s.chapterDownloads["c/1"]
        assertNotNull(done, "a completed (SUCCESS) chapter keeps an entry so the flip is atomic")
        assertTrue(done.isDownloaded, "the SUCCESS entry reads downloaded WITHOUT the saved flow re-emitting")
        assertTrue("c/1" !in s.downloadingChapterUrls, "the completed chapter is no longer an active download url")
        assertEquals("12.0 MB", s.chapterSizeLabel("c/1"), "the chapter size is shown from the SUCCESS entry")
        assertEquals("12.0 MB", s.totalDownloadedSizeLabel, "the total downloaded size sums the SUCCESS entries")
    }

    // ---- Bug 2: Cloudflare-family failures route to the WebView solver ------------------------

    @Test
    fun fetch403_emitsSolveCloudflareChallenge_notShowError() = challengeTest(AppError.Network.Http(statusCode = 403))

    @Test
    // 503 ("checking your browser") is a Cloudflare interstitial too — broadened trigger.
    fun fetch503_emitsSolveCloudflareChallenge_notShowError() = challengeTest(AppError.Network.Http(statusCode = 503))

    @Test
    fun fetch404_emitsShowError_notChallenge() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        val vm = vm(fetch = AppResult.Failure(AppError.Network.Http(statusCode = 404)), saved = saved)
        val effects = mutableListOf<DetailsEffect>()
        val job = launch(dispatcher) { vm.effects.collect { effects += it } }
        vm.submit(DetailsIntent.OnEnter(manga()))
        job.cancel()
        assertTrue(
            effects.any { it is DetailsEffect.ShowError } && effects.none { it is DetailsEffect.SolveCloudflareChallenge },
            "a genuine 404 stays a ShowError, not a Cloudflare challenge: $effects",
        )
    }

    private fun challengeTest(error: AppError) = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        val vm = vm(fetch = AppResult.Failure(error), saved = saved)
        val effects = mutableListOf<DetailsEffect>()
        val job = launch(dispatcher) { vm.effects.collect { effects += it } }
        vm.submit(DetailsIntent.OnEnter(manga()))
        job.cancel()
        assertTrue(
            effects.any { it is DetailsEffect.SolveCloudflareChallenge },
            "challenge status $error must route to the WebView solver, got: $effects",
        )
    }

    @Test
    fun cloudflareChallenge_isCappedThenSurfacesError() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        // Every fetch + retry returns the same persistent 403 (unsolvable challenge).
        val vm = vm(fetch = AppResult.Failure(AppError.Network.Http(statusCode = 403)), saved = saved)
        val effects = mutableListOf<DetailsEffect>()
        val job = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(DetailsIntent.OnEnter(manga())) // attempt 1 -> Solve
        vm.submit(DetailsIntent.OnRetry) // attempt 2 -> Solve
        vm.submit(DetailsIntent.OnRetry) // budget (MAX=2) exhausted -> ShowError, no further Solve
        job.cancel()

        val solves = effects.count { it is DetailsEffect.SolveCloudflareChallenge }
        assertEquals(2, solves, "auto-solve is capped at 2 round-trips, not an infinite loop: $effects")
        assertTrue(
            effects.any { it is DetailsEffect.ShowError },
            "once the solve budget is exhausted the error surfaces instead of re-looping: $effects",
        )
    }

    // ---- #3: persist refresh-discovered chapters + NEW-badge lifecycle ------------------------

    @Test
    fun refresh_persistsFetchedChapters_whenInLibrary() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1"), chapter("c/2")))
        val libraryRepo = FakeLibraryRepository().apply { emitInLibrary(true) }
        // Refresh discovers a new chapter c/3 on top of the saved two.
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2"), chapter("c/3")))),
            saved = saved,
            libraryRepo = libraryRepo,
        )

        vm.submit(DetailsIntent.OnEnter(manga())) // cached render, no fetch/persist on open
        assertEquals(0, libraryRepo.calls.count { it.startsWith("persistNewChapters") })

        vm.submit(DetailsIntent.OnRetry) // explicit refresh → fetch → persist (in library)

        assertEquals(
            1,
            libraryRepo.calls.count { it.startsWith("persistNewChapters") },
            "an in-library refresh must persist the fetched chapter list so new chapters survive nav-away",
        )
        assertEquals(3, libraryRepo.lastPersistedNewChapters.size, "the fetched chapters are handed to the persist use case")
    }

    // ---- #11: manga_open analytics fires once per identity ---------------------------------

    @Test
    fun mangaOpen_firesOncePerIdentity_notOnReEnter() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        val analytics = RecordingAnalyticsPort()
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1")))),
            saved = saved,
            analytics = analytics,
        )
        vm.submit(DetailsIntent.OnEnter(manga()))
        vm.submit(DetailsIntent.OnEnter(manga())) // same identity → onEnter early-returns
        assertEquals(
            listOf("src" to "Naruto"),
            analytics.mangaOpens,
            "manga_open fires exactly once per opened identity (api/title), not on a same-identity re-enter",
        )
    }

    // ---- #4: offline connectivity gate on the download actions -----------------------------

    @Test
    fun downloadChapter_offline_emitsNoConnectivity() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1")))
        val libraryRepo = FakeLibraryRepository().apply { emitInLibrary(true) }
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1")))),
            saved = saved,
            libraryRepo = libraryRepo,
            connectivity = FakeConnectivityRepository(online = false),
        )
        val effects = mutableListOf<DetailsEffect>()
        val job = launch(dispatcher) { vm.effects.collect { effects += it } }
        vm.submit(DetailsIntent.OnEnter(manga()))
        vm.submit(DetailsIntent.OnDownloadChapter(chapter("c/1")))
        job.cancel()
        assertTrue(
            effects.any { it is DetailsEffect.ShowError && it.error is AppError.Network.NoConnectivity },
            "offline single-chapter download emits a NoConnectivity error: $effects",
        )
    }

    @Test
    fun downloadAll_offline_emitsNoConnectivity() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1"), chapter("c/2")))
        val libraryRepo = FakeLibraryRepository().apply { emitInLibrary(true) }
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2")))),
            saved = saved,
            libraryRepo = libraryRepo,
            connectivity = FakeConnectivityRepository(online = false),
        )
        val effects = mutableListOf<DetailsEffect>()
        val job = launch(dispatcher) { vm.effects.collect { effects += it } }
        vm.submit(DetailsIntent.OnEnter(manga()))
        vm.submit(DetailsIntent.OnDownloadAllClick)
        job.cancel()
        assertTrue(
            effects.any { it is DetailsEffect.ShowError && it.error is AppError.Network.NoConnectivity },
            "offline download-all emits a NoConnectivity error: $effects",
        )
    }

    @Test
    fun downloadChapter_online_doesNotEmitNoConnectivity() = runTest {
        // #4 regression guard: with the default online connectivity the gate must NOT fire.
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1")))
        val libraryRepo = FakeLibraryRepository().apply { emitInLibrary(true) }
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1")))),
            saved = saved,
            libraryRepo = libraryRepo,
            connectivity = FakeConnectivityRepository(online = true),
        )
        val effects = mutableListOf<DetailsEffect>()
        val job = launch(dispatcher) { vm.effects.collect { effects += it } }
        vm.submit(DetailsIntent.OnEnter(manga()))
        vm.submit(DetailsIntent.OnDownloadChapter(chapter("c/1")))
        job.cancel()
        assertTrue(
            effects.none { it is DetailsEffect.ShowError && it.error is AppError.Network.NoConnectivity },
            "online download must not trip the offline gate: $effects",
        )
    }

    @Test
    fun refresh_doesNotPersist_whenNotInLibrary() = runTest {
        val saved = FakeSavedMangaDetailsRepository() // null → not saved/in-library
        val libraryRepo = FakeLibraryRepository() // inLibrary stays false
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2")))),
            saved = saved,
            libraryRepo = libraryRepo,
        )

        vm.submit(DetailsIntent.OnEnter(manga())) // fresh network open (not in library)

        assertEquals(
            0,
            libraryRepo.calls.count { it.startsWith("persistNewChapters") },
            "a not-in-library Details open must NOT create saved rows",
        )
    }

    @Test
    fun onDeleteChapter_deletesChapterRecord_whenInLibrary() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1"), chapter("c/2")))
        val libraryRepo = FakeLibraryRepository().apply { emitInLibrary(true) }
        val deletionRepo = RecordingChapterDeletionRepository()
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2")))),
            saved = saved,
            idResolver = FixedChapterIdResolver(42L),
            libraryRepo = libraryRepo,
            deletionRepo = deletionRepo,
        )

        vm.submit(DetailsIntent.OnEnter(manga()))
        vm.submit(DetailsIntent.OnDeleteChapter(chapter("c/1")))

        assertEquals(listOf(42L), deletionRepo.deleted, "the resolved chapter id is deleted from the DB")
    }

    @Test
    fun onDeleteChapter_isNoOp_whenNotInLibrary() = runTest {
        val saved = FakeSavedMangaDetailsRepository() // not saved → not in library
        val deletionRepo = RecordingChapterDeletionRepository()
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1")))),
            saved = saved,
            idResolver = FixedChapterIdResolver(42L),
            deletionRepo = deletionRepo,
        )

        vm.submit(DetailsIntent.OnEnter(manga()))
        vm.submit(DetailsIntent.OnDeleteChapter(chapter("c/1")))

        assertTrue(deletionRepo.deleted.isEmpty(), "a not-in-library manga has no DB row to delete")
    }

    @Test
    fun chapterOpen_clearsNewBadgeWithoutMarkingRead() = runTest {
        val saved = FakeSavedMangaDetailsRepository()
        saved.saved.value = details(listOf(chapter("c/1"), chapter("c/2")))
        val badgeRepo = RecordingChapterNewBadgeRepository()
        val markReadRepo = RecordingMarkChapterReadRepository() // would record reads if we marked read on open
        val (vm, _) = vmWithFetchFake(
            fetch = AppResult.Success(details(listOf(chapter("c/1"), chapter("c/2")))),
            saved = saved,
            badgeRepo = badgeRepo,
            markReadRepo = markReadRepo,
        )

        vm.submit(DetailsIntent.OnEnter(manga()))
        vm.submit(DetailsIntent.OnChapterClick(chapter("c/2")))

        assertEquals(listOf("c/2"), badgeRepo.cleared, "opening a chapter clears its NEW badge by url")
        // markRead is NOT invoked on open — opening != reading; the recording repo stays empty.
        assertTrue(markReadRepo.read.isEmpty(), "opening a chapter must not mark it read")
    }

    @Test
    fun expireNewBadges_hidesBadgeAfter4Days_keepsItWithinWindow() {
        val now = 1_000_000_000_000L
        val fresh = chapter("c/1").copy(isNew = true, fetchedAt = now - 1L * 24 * 60 * 60 * 1000) // 1 day old
        val stale = chapter("c/2").copy(isNew = true, fetchedAt = now - 5L * 24 * 60 * 60 * 1000) // 5 days old
        val unknown = chapter("c/3").copy(isNew = true, fetchedAt = 0L) // unknown discovery time
        val d = details(listOf(fresh, stale, unknown))

        val out = d.expireNewBadges(now)

        assertTrue(out.chapters.first { it.url == "c/1" }.isNew, "within 4 days → badge stays")
        assertEquals(false, out.chapters.first { it.url == "c/2" }.isNew, "older than 4 days → badge hidden")
        assertEquals(false, out.chapters.first { it.url == "c/3" }.isNew, "unknown discovery time → no badge")
    }
}
