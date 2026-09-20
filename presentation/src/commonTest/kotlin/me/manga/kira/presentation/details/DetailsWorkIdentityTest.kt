package me.manga.kira.presentation.details

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.SavedWorkDetails
import me.manga.kira.domain.repository.SavedMangaDetailsRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Drives the actual MVI/use-case path; data transaction/alias proofs live in real-Room tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsWorkIdentityTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val mainImmediate = dispatcher
        override val default = dispatcher
        override val io = dispatcher
        override val unconfined = dispatcher
    }
    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun fixture(
        cached: MangaDetails? = null,
        network: MangaDetails = details(listOf(chapter("c/1"))),
        owner: SavedWorkIdentity = savedOwner(41L),
        options: VmFixtureOptions = VmFixtureOptions(),
    ): DetailsIdentityFixture = DetailsIdentityFixture(
        dispatchers, owner, cached, AppResult.Success(network), options,
    ).also { store.put("details", it.vm) }

    @Test
    fun fetchedRename_retainsMembershipOriginalOwnerAndPortableExport() = runTest {
        val cached = details(listOf(chapter("c/1", isRead = true)))
        val renamed = cached.copy(title = "Renamed", language = "ar")
        val f = fixture(cached, renamed)
        f.vm.submit(DetailsIntent.OnEnter(manga()))
        f.vm.submit(DetailsIntent.OnRetry)
        assertTrue(f.vm.state.value.isInLibrary)
        assertEquals(f.saved.owner, f.vm.state.value.savedOwner)
        assertEquals("Renamed", f.vm.state.value.manga?.title)
        assertEquals(f.saved.owner, f.library.lastRefreshRequests.single().owner)
        assertEquals(f.saved.owner.locator, f.library.lastRefreshRequests.single().fetched.requested)
        f.saved.saved.value = renamed.copy(chapters = renamed.chapters.map { it.copy(isRead = false) })
        runCurrent()
        assertEquals(f.saved.owner, f.vm.state.value.savedOwner)
        assertFalse(assertNotNull(f.vm.state.value.details).chapters.single().isRead)
        f.vm.submit(DetailsIntent.OnEnter(manga(title = "Another nav title")))
        assertEquals(1, f.fetch.fetchCount, "a title change is not a new work")
        f.vm.submit(DetailsIntent.OnExportManga)
        assertEquals(DetailsEffect.NavigateToBackupExport(f.saved.owner.locator), f.vm.effects.first())
        f.vm.submit(DetailsIntent.OnToggleInLibrary)
        assertEquals(f.saved.owner, f.library.lastRemovedOwner)
        assertTrue(f.library.calls.none { it.startsWith("get(") || it.startsWith("addToLibrary") })
    }

    @Test
    fun sameTitleDifferentAddress_doesNotReuseSavedMembershipOrChapters() = runTest {
        val f = fixture(details(listOf(chapter("c/old"))))
        f.vm.submit(DetailsIntent.OnEnter(manga()))
        val other = manga().copy(url = "https://x/another-work")
        f.fetch.result = AppResult.Success(details(listOf(chapter("c/other"))).copy(url = other.url))
        f.vm.submit(DetailsIntent.OnEnter(other))
        assertFalse(f.vm.state.value.isInLibrary)
        assertEquals(null, f.vm.state.value.savedOwner)
        assertEquals(listOf("c/other"), assertNotNull(f.vm.state.value.details).chapters.map { it.url })
        assertEquals(other, f.fetch.requests.single())
        assertTrue(f.library.lastRefreshRequests.isEmpty())
    }

    @Test
    fun newWork_cancelsThePreviousSuspendedCacheProbe() = runTest {
        val first = savedOwner().locator
        var cancelled = false
        val saved = object : SavedMangaDetailsRepository {
            override fun observeSavedDetails(work: WorkLocator): Flow<AppResult<SavedWorkDetails?>> = flow {
                if (work == first) {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                emit(AppResult.Success(null))
            }
        }
        val other = manga().copy(url = "https://x/work-b")
        val fetched = details(listOf(chapter("c/b"))).copy(url = other.url)
        val (vm, fetch) = createVmWithFetchFake(AppResult.Success(fetched), saved, VmFixtureOptions(), dispatchers)
        store.put("details", vm)
        vm.submit(DetailsIntent.OnEnter(manga()))
        assertTrue(vm.state.value.isLoading)
        vm.submit(DetailsIntent.OnEnter(other))
        runCurrent()
        assertTrue(cancelled)
        assertEquals(listOf(other), fetch.requests)
        assertEquals(fetched, vm.state.value.details)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun aToBToA_dropsLateFirstFetchBeforePersistenceOrRendering() = runTest {
        val f = fixture(details(emptyList()))
        val release = CompletableDeferred<Unit>()
        var aRequests = 0
        f.fetch.respond = { requested ->
            if (requested.url == manga().url && ++aRequests == 1) {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(details(listOf(chapter("c/stale"))).copy(title = "Stale"))
            } else AppResult.Success(details(listOf(chapter("c/current"))).copy(url = requested.url))
        }
        f.vm.submit(DetailsIntent.OnEnter(manga()))
        f.vm.submit(DetailsIntent.OnEnter(manga().copy(url = "https://x/work-b")))
        f.vm.submit(DetailsIntent.OnEnter(manga()))
        val current = f.vm.state.value
        assertEquals(listOf("c/current"), assertNotNull(current.details).chapters.map { it.url })
        assertEquals(1, f.library.calls.count { it.startsWith("refresh(") })
        release.complete(Unit)
        runCurrent()
        assertEquals(current, f.vm.state.value)
        assertEquals(1, f.library.calls.count { it.startsWith("refresh(") })
        assertFalse(f.vm.state.value.isLoading)
    }

    @Test
    fun refreshAwaitingWriter_keepsCachedStateUntilSuccess() = runTest {
        val cached = details(listOf(chapter("c/1", isRead = true)))
        val network = details(listOf(chapter("c/1"), chapter("c/2"))).copy(title = "Committed")
        val f = fixture(cached, network)
        val release = CompletableDeferred<Unit>()
        f.library.beforeRefresh = { release.await() }
        f.vm.submit(DetailsIntent.OnEnter(manga()))
        f.vm.submit(DetailsIntent.OnRetry)
        assertEquals(cached, f.vm.state.value.details)
        assertTrue(f.vm.state.value.isLoading)
        assertEquals(f.saved.owner, f.library.lastRefreshRequests.single().owner)
        release.complete(Unit)
        runCurrent()
        val committed = assertNotNull(f.vm.state.value.details)
        assertEquals("Committed", committed.title)
        assertEquals(2, committed.chapters.size)
        assertTrue(committed.chapters.first().isRead)
        assertFalse(f.vm.state.value.isLoading)
    }

    @Test
    fun writerFailure_doesNotPublishFetchedMetadataAsSuccess() = runTest {
        val cached = details(listOf(chapter("c/1")))
        val f = fixture(cached, cached.copy(title = "Must not render"))
        val release = CompletableDeferred<Unit>()
        val error = AppError.Storage.Constraint("stale retained owner")
        f.library.beforeRefresh = { release.await() }
        f.library.refreshResult = AppResult.Failure(error)
        f.vm.submit(DetailsIntent.OnEnter(manga()))
        f.vm.submit(DetailsIntent.OnRetry)
        assertEquals(cached, f.vm.state.value.details)
        release.complete(Unit)
        runCurrent()
        assertEquals(cached, f.vm.state.value.details)
        assertEquals(error, f.vm.state.value.libraryError)
        assertEquals(DetailsEffect.ShowError(error), f.vm.effects.first())
        assertFalse(f.vm.state.value.isLoading)
        f.vm.submit(DetailsIntent.OnToggleInLibrary)
        assertEquals(null, f.library.lastRemovedOwner)
    }

    @Test
    fun successfulWriterResultAfterObservedReplacement_doesNotPublishOldOwnerFetch() =
        assertLateWriterResultKeepsReplacement()

    @Test
    fun failedWriterResultAfterObservedReplacement_doesNotInvalidateNewOwner() =
        assertLateWriterResultKeepsReplacement(AppError.Storage.Constraint("stale retained owner"))

    private fun assertLateWriterResultKeepsReplacement(failure: AppError? = null) = runTest {
        val cached = details(listOf(chapter("c/old")))
        val f = fixture(cached, cached.copy(title = "Old owner fetch"))
        val release = CompletableDeferred<Unit>()
        f.library.beforeRefresh = { release.await() }
        if (failure != null) f.library.refreshResult = AppResult.Failure(failure)
        f.vm.submit(DetailsIntent.OnEnter(manga()))
        f.vm.submit(DetailsIntent.OnRetry)
        val replacement = f.saved.owner.copy(id = f.saved.owner.id + 1L)
        f.saved.currentOwner.value = replacement
        val replacementDetails = details(listOf(chapter("c/new"))).copy(title = "Re-added")
        f.saved.saved.value = replacementDetails
        runCurrent()
        val replacementState = f.vm.state.value
        assertEquals(replacement, replacementState.savedOwner)
        assertEquals(replacementDetails, replacementState.details)
        assertTrue(replacementState.isInLibrary)
        assertTrue(replacementState.isLoading)
        f.vm.effects.test {
            release.complete(Unit)
            runCurrent()
            assertEquals(replacementState.copy(isLoading = false), f.vm.state.value)
            assertEquals(f.saved.owner, f.library.lastRefreshRequests.single().owner)
            expectNoEvents()
        }
    }

    @Test
    fun savedFlagResetsAfterRefresh_overrideTransportFlagsWithoutLosingOwner() = runTest {
        val savedChapter = chapter("c/1", isRead = true, isDownloaded = true).copy(isBookmarked = true)
        val cached = details(listOf(savedChapter))
        val f = fixture(cached, cached.copy(title = "Remote rename"))
        f.vm.submit(DetailsIntent.OnEnter(manga()))
        f.vm.submit(DetailsIntent.OnRetry)
        f.saved.saved.value = cached.copy(chapters = listOf(chapter("c/1")))
        val state = f.vm.state.value
        val chapter = assertNotNull(state.details).chapters.single()
        assertFalse(chapter.isRead)
        assertFalse(chapter.isDownloaded)
        assertFalse(chapter.isBookmarked)
        assertEquals("Remote rename", state.details?.title)
        assertEquals(f.saved.owner, state.savedOwner)
    }

    @Test
    fun membershipFailure_blocksFetchAndMutationUntilScopedRecovery() = runTest {
        val f = fixture()
        val error = AppError.Storage.Constraint("ambiguous owner")
        f.saved.failure.value = error
        f.library.emitMembershipFailure(f.saved.owner.locator, error)
        f.vm.submit(DetailsIntent.OnEnter(manga()))
        f.vm.submit(DetailsIntent.OnToggleInLibrary)
        assertEquals(0, f.fetch.fetchCount)
        assertEquals(error, f.vm.state.value.libraryError)
        assertTrue(f.library.calls.none { it.startsWith("addToLibrary") || it.startsWith("removeFromLibrary") })
        f.saved.saved.value = details(listOf(chapter("c/recovered")))
        f.saved.failure.value = null
        assertEquals(null, f.vm.state.value.libraryError)
        assertEquals(f.saved.owner, f.vm.state.value.savedOwner)
    }

    @Test
    fun overlappingCloudflareRetries_reserveLoadingBeforeDownloadRetrySuspends() = runTest {
        val downloads = FakeDownloadsRepository()
        val actions = RecordingDownloadsActionRepository()
        val options = VmFixtureOptions().apply {
            downloadsRepo = downloads
            downloadActions = actions
            idResolver = FixedChapterIdResolver(10L)
        }
        val f = fixture(details(listOf(chapter("c/1"))), options = options)
        f.vm.submit(DetailsIntent.OnEnter(manga()))
        downloads.rows.value = listOf(cloudflareFailure())
        val release = CompletableDeferred<Unit>()
        actions.beforeEnqueue = { release.await() }
        f.vm.submit(DetailsIntent.OnRetry)
        assertTrue(f.vm.state.value.isLoading)
        f.vm.submit(DetailsIntent.OnRetry)
        release.complete(Unit)
        runCurrent()
        assertEquals(1, actions.enqueued.size)
        assertEquals(1, f.fetch.fetchCount)
        assertFalse(f.vm.state.value.isLoading)
    }

    private fun cloudflareFailure() = DownloadedChapter(
        chapterId = 10L,
        mangaId = 41L,
        number = "1",
        mangaTitle = "Naruto",
        state = DownloadState.FAILED,
        progress = 0,
        errorMsg = DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL,
        url = "c/1",
    )
}
