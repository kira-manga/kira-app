package me.manga.kira.presentation.details

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.ChapterDeletionRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The typed saved-owner writer and visit-local chapter retractions must compose, not replace each other. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsJoinOwnershipRegressionTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val mainImmediate = dispatcher
        override val default = dispatcher
        override val io = dispatcher
        override val unconfined = dispatcher
    }
    private val fresh = chapter("c/1")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun fixture(cached: MangaDetails, options: VmFixtureOptions = VmFixtureOptions()) =
        DetailsIdentityFixture(
            dispatchers,
            savedOwner(41L),
            cached,
            AppResult.Success(details(listOf(fresh))),
            options.apply { idResolver = FixedChapterIdResolver(10L) },
        ).also {
            store.put("details", it.vm)
            it.vm.submit(DetailsIntent.OnEnter(manga()))
        }

    @Test
    fun rediscoveryDoesNotReuseFlagsFromADeletedSavedRow() = runTest {
        val old = fresh.copy(isRead = true, isDownloaded = true, isBookmarked = true)
        val f = fixture(details(listOf(old)))
        f.vm.submit(DetailsIntent.OnDeleteChapter(old))
        f.saved.saved.value = details(listOf(old.copy(lastReadAtEpochMillis = 77L)))
        assertTrue(assertNotNull(f.vm.state.value.details).chapters.isEmpty())

        f.vm.submit(DetailsIntent.OnRetry)

        assertEquals(listOf(fresh), assertNotNull(f.vm.state.value.details).chapters)
        assertEquals(listOf(fresh), f.library.lastPersistedNewChapters)
        assertEquals(f.saved.owner, f.library.lastRefreshRequests.single().owner)
    }

    @Test
    fun failedWriterCannotReleaseRetractionThroughALaterSavedEmission() = runTest {
        val f = fixture(details(listOf(fresh)))
        f.vm.submit(DetailsIntent.OnDeleteChapter(fresh))
        val failure = AppError.Storage.Io()
        f.library.refreshResult = AppResult.Failure(failure)
        f.vm.submit(DetailsIntent.OnRetry)
        assertEquals(failure, f.vm.state.value.libraryError)

        f.saved.saved.value = details(listOf(fresh.copy(isRead = true)))
        assertTrue(assertNotNull(f.vm.state.value.details).chapters.isEmpty())
        f.library.refreshResult = null
        f.vm.submit(DetailsIntent.OnRetry)

        assertEquals(listOf(fresh), assertNotNull(f.vm.state.value.details).chapters)
    }

    @Test
    fun delayedOldParentDeletionCannotRetractTheReplacementAtTheSameAddress() = runTest {
        val deletion = CompletableDeferred<Unit>()
        val deleted = mutableListOf<Long>()
        val options = VmFixtureOptions().apply {
            deletionRepo = object : ChapterDeletionRepository {
                override suspend fun deleteChapter(chapterId: Long) {
                    deleted += chapterId
                    deletion.await()
                }
            }
        }
        val f = fixture(details(listOf(fresh)), options)
        f.vm.submit(DetailsIntent.OnDeleteChapter(fresh))
        assertEquals(listOf(10L), deleted)
        val replacement = f.saved.owner.copy(id = 42L)
        f.saved.currentOwner.value = replacement
        f.saved.saved.value = details(listOf(fresh.copy(isBookmarked = true))).copy(title = "Replacement")
        runCurrent()
        val replacementState = f.vm.state.value
        assertEquals(replacement, replacementState.savedOwner)

        deletion.complete(Unit)
        runCurrent()

        assertEquals(replacementState, f.vm.state.value)
        assertEquals(listOf(fresh.copy(isBookmarked = true)), assertNotNull(replacementState.details).chapters)
    }

    @Test
    fun lateFetchFailureCannotRepaintAReplacementAtTheSameAddress() =
        assertLateFetchFailureKeepsReplacement(AppError.Network.Http(statusCode = 404))

    @Test
    fun lateFetchChallengeCannotOpenASolverForAReplacementAtTheSameAddress() =
        assertLateFetchFailureKeepsReplacement(AppError.Network.Http(statusCode = 503))

    private fun assertLateFetchFailureKeepsReplacement(failure: AppError) = runTest {
        val f = fixture(details(listOf(fresh)))
        val release = CompletableDeferred<Unit>()
        f.fetch.respond = {
            release.await()
            AppResult.Failure(failure)
        }
        f.vm.submit(DetailsIntent.OnRetry)
        val replacement = f.saved.owner.copy(id = 42L)
        f.saved.currentOwner.value = replacement
        f.saved.saved.value = details(listOf(fresh.copy(isBookmarked = true))).copy(title = "Replacement")
        runCurrent()
        val replacementState = f.vm.state.value
        assertEquals(replacement, replacementState.savedOwner)

        f.vm.effects.test {
            release.complete(Unit)
            runCurrent()
            assertEquals(replacementState.copy(isLoading = false), f.vm.state.value)
            assertTrue(f.library.lastRefreshRequests.isEmpty())
            expectNoEvents()
        }
    }
}
