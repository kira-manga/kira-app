package me.manga.kira.presentation.details

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.presentation.testing.sampleManga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsCloudflareRecoveryTest {
    private val manga = sampleManga(api = "source", title = "Same title", url = "https://owner.test/a")
    private val other = manga.copy(url = "https://owner.test/b")
    private val challenge = AppError.Network.Http(statusCode = 403)
    private val failed =
        download(101, 1, DownloadState.FAILED)
            .copy(errorMsg = DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL)

    @Test
    fun queuedRunningAndSuccessfulMetadataCannotRefillTheDownloadBudget() =
        withDetails { fixture, effects ->
            fixture.downloads.publish(manga, listOf(failed))
            repeat(2) { round ->
                val fetches = fixture.fetchRequests.size
                returnFromSolver(fixture)
                assertEquals(fetches, fixture.fetchRequests.size, "solver must retry downloads, not metadata")
                assertEquals(round + 1, fixture.actions.enqueued.size)
                fixture.downloads.publish(manga, emptyList()) // Removing/recreating a row is not recovery.
                fixture.downloads.publish(manga, listOf(failed.copy(state = DownloadState.QUEUED)))
                fixture.downloads.publish(manga, listOf(failed.copy(state = DownloadState.RUNNING)))
                fixture.vm.submit(DetailsIntent.OnRetry)
                assertEquals(fetches + 1, fixture.fetchRequests.size)
                fixture.downloads.publish(manga, listOf(failed))
            }
            assertEquals(2, effects.filterIsInstance<DetailsEffect.SolveCloudflareChallenge>().size)
            assertEquals(challenge, assertIs<DetailsEffect.ShowError>(effects.last()).error)
            assertNull(fixture.vm.cloudflareRecoveryRequestId(manga.url, manga.api))
            assertNotNull(fixture.vm.state.value.details)
            assertNull(fixture.vm.state.value.error, "failed downloads must not hide successful metadata")
        }

    @Test
    fun rotatingFailedChaptersDoesNotReplenishAnUnresolvedBatch() =
        withDetails { fixture, effects ->
            val second = failed.copy(chapterId = 102, url = SECOND_CHAPTER_URL)
            fixture.downloads.publish(manga, listOf(failed, second.copy(state = DownloadState.QUEUED)))
            returnFromSolver(fixture)
            fixture.downloads.publish(manga, listOf(failed.copy(state = DownloadState.RUNNING), second))
            returnFromSolver(fixture)
            fixture.downloads.publish(manga, listOf(failed, second.copy(state = DownloadState.RUNNING)))
            assertEquals(listOf(101L, 102L), fixture.actions.enqueued.map { it.first })
            assertEquals(2, effects.filterIsInstance<DetailsEffect.SolveCloudflareChallenge>().size)
            assertEquals(challenge, assertIs<DetailsEffect.ShowError>(effects.last()).error)
        }

    @Test
    fun completedBatchRestoresAllowanceAndRecoveredReturnCannotEnqueueItAgain() =
        withDetails { fixture, effects ->
            fixture.downloads.publish(manga, listOf(failed))
            returnFromSolver(fixture)
            fixture.downloads.publish(manga, listOf(failed.copy(state = DownloadState.QUEUED)))
            fixture.downloads.publish(manga, listOf(failed))
            val recoveredRequest = requestId(fixture)
            fixture.downloads.publish(manga, listOf(failed.copy(state = DownloadState.SUCCESS)))
            fixture.vm.submit(DetailsIntent.OnCloudflareSolverReturned(recoveredRequest))
            assertEquals(1, fixture.actions.enqueued.size)
            fixture.downloads.publish(manga, listOf(failed))
            assertNotEquals(recoveredRequest, requestId(fixture))
            returnFromSolver(fixture)
            fixture.downloads.publish(manga, listOf(failed.copy(state = DownloadState.QUEUED)))
            fixture.downloads.publish(manga, listOf(failed))
            assertEquals(4, effects.filterIsInstance<DetailsEffect.SolveCloudflareChallenge>().size)
        }

    @Test
    fun oldOwnerReturnCannotConsumeTheNewOwnersSameChapterChallenge() =
        withDetails { fixture, _ ->
            fixture.downloads.publish(manga, listOf(failed))
            val oldRequest = requestId(fixture)
            fixture.vm.submit(DetailsIntent.OnEnter(other))
            fixture.downloads.publish(other, listOf(failed.copy(chapterId = 201, mangaId = 2)))
            val newRequest = assertNotNull(fixture.vm.cloudflareRecoveryRequestId(other.url, other.api))
            assertNotEquals(oldRequest, newRequest)
            fixture.vm.submit(DetailsIntent.OnCloudflareSolverReturned(oldRequest))
            assertEquals(emptyList(), fixture.actions.enqueued)
            assertEquals(newRequest, fixture.vm.cloudflareRecoveryRequestId(other.url, other.api))
            fixture.vm.submit(DetailsIntent.OnCloudflareSolverReturned(newRequest))
            fixture.vm.submit(DetailsIntent.OnCloudflareSolverReturned(newRequest))
            fixture.assertEnqueued(other, listOf(201))
            assertEquals(2, fixture.fetchRequests.size)
        }

    @Test
    fun successfulDownloadsDoNotResetMetadataButActualMetadataRecoveryDoes() =
        withDetails { fixture, effects ->
            fixture.fetchFailure = challenge
            fixture.vm.submit(DetailsIntent.OnRetry)
            repeat(2) {
                fixture.downloads.publish(manga, listOf(failed.copy(state = DownloadState.SUCCESS)))
                returnFromSolver(fixture)
            }
            assertEquals(2, effects.filterIsInstance<DetailsEffect.SolveCloudflareChallenge>().size)
            assertEquals(challenge, assertIs<DetailsEffect.ShowError>(effects.last()).error)
            fixture.fetchFailure = null
            fixture.vm.submit(DetailsIntent.OnRetry)
            fixture.fetchFailure = challenge
            fixture.vm.submit(DetailsIntent.OnRetry)
            assertEquals(3, effects.filterIsInstance<DetailsEffect.SolveCloudflareChallenge>().size)
        }

    private fun requestId(fixture: DetailsOwnerFixture): String =
        assertNotNull(fixture.vm.cloudflareRecoveryRequestId(manga.url, manga.api))

    private fun returnFromSolver(fixture: DetailsOwnerFixture) {
        fixture.vm.submit(DetailsIntent.OnCloudflareSolverReturned(requestId(fixture)))
    }

    private fun withDetails(block: suspend (DetailsOwnerFixture, List<DetailsEffect>) -> Unit) =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val store = ViewModelStore()
            try {
                val chapters = listOf(sharedChapter(), sharedChapter().copy(url = SECOND_CHAPTER_URL, number = "2"))
                val fixture = DetailsOwnerFixture(mapOf(manga.url to 101L, other.url to 201L), dispatcher, chapters)
                store.put("details", fixture.vm)
                val effects = mutableListOf<DetailsEffect>()
                backgroundScope.launch(dispatcher) { fixture.vm.effects.collect { effects += it } }
                fixture.vm.submit(DetailsIntent.OnEnter(manga))
                block(fixture, effects)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
}
