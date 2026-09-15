package me.manga.kira.presentation.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.reader.Page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderCloudflareRecoveryTest {
    private val challenge = AppError.Network.Http(statusCode = 403)

    @Test
    fun persistentAppendRetriesItselfAndAnchorSuccessCannotRefillItsBudget() =
        withReader { fixture, effects ->
            failChapter(fixture, 1)
            fixture.dispatch(ReaderIntent.OnAppendNextChapter)
            repeat(2) {
                val anchorFetches = fixture.pages.requested.count { it.second == fixture.chapters[0] }
                returnFromSolver(fixture, 1)
                assertEquals(anchorFetches, fixture.pages.requested.count { it.second == fixture.chapters[0] })
                fixture.dispatch(ReaderIntent.OnRetry) // A real, unrelated successful anchor refresh.
            }
            fixture.dispatch(ReaderIntent.OnAppendNextChapter)
            assertEquals(2, effects.filterIsInstance<ReaderEffect.SolveCloudflareChallenge>().size)
            assertEquals(challenge, assertIs<ReaderEffect.ShowError>(effects.last()).error)
            assertEquals(activeActionPages(fixture.chapters[0]), fixture.vm.state.value.pages)
            assertNull(fixture.vm.state.value.error, "append exhaustion must leave the anchor readable")
            assertNull(fixture.vm.cloudflareRecoveryRequestId(fixture.chapters[1].url, fixture.manga.api))
        }

    @Test
    fun genuineAppendRecoveryRestoresItsAllowanceForALaterChallenge() =
        withReader { fixture, effects ->
            failChapter(fixture, 1)
            fixture.dispatch(ReaderIntent.OnAppendNextChapter)
            returnFromSolver(fixture, 1)
            fixture.pages.results[fixture.chapters[1].url] = flowOf(AppResult.Success(activeActionPages(fixture.chapters[1])))
            returnFromSolver(fixture, 1)
            assertEquals(fixture.chapters.take(2).map { it.url }, fixture.vm.state.value.loadedChapterUrls)
            fixture.dispatch(ReaderIntent.OnRetry)
            failChapter(fixture, 1)
            fixture.dispatch(ReaderIntent.OnAppendNextChapter)
            returnFromSolver(fixture, 1)
            assertEquals(4, effects.filterIsInstance<ReaderEffect.SolveCloudflareChallenge>().size)
        }

    @Test
    fun aNewAppendChapterReceivesItsOwnFullAllowance() =
        withReader { fixture, effects ->
            for (index in 1..2) {
                failChapter(fixture, index)
                fixture.dispatch(ReaderIntent.OnAppendNextChapter)
                returnFromSolver(fixture, index)
                assertEquals(index * 2, effects.filterIsInstance<ReaderEffect.SolveCloudflareChallenge>().size)
                val chapter = fixture.chapters[index]
                fixture.pages.results[chapter.url] = flowOf(AppResult.Success(activeActionPages(chapter)))
                returnFromSolver(fixture, index)
            }
            assertEquals(fixture.chapters.map { it.url }, fixture.vm.state.value.loadedChapterUrls)
        }

    @Test
    fun oldAppendReturnCannotRetryTheSameUrlAfterAnExplicitAnchorReplacement() =
        withReader { fixture, _ ->
            failChapter(fixture, 1)
            fixture.dispatch(ReaderIntent.OnAppendNextChapter)
            val oldRequest = requestId(fixture, 1)
            fixture.dispatch(ReaderIntent.OnNextChapter)
            val currentRequest = requestId(fixture, 1)
            assertNotEquals(oldRequest, currentRequest)
            val fetches = fixture.pages.requested.size
            fixture.dispatch(ReaderIntent.OnCloudflareSolverReturned(oldRequest))
            assertEquals(fetches, fixture.pages.requested.size)
            assertEquals(currentRequest, requestId(fixture, 1))
            fixture.dispatch(ReaderIntent.OnCloudflareSolverReturned(currentRequest))
            fixture.dispatch(ReaderIntent.OnCloudflareSolverReturned(currentRequest))
            assertEquals(fetches + 1, fixture.pages.requested.size, "one-shot must not consume the next attempt")
            assertNotEquals(currentRequest, requestId(fixture, 1))
        }

    @Test
    fun streamingRecoveryInvalidatesOldReturnAndAChallengedPartialAppendRetriesDirectly() =
        withReader { fixture, effects ->
            val stream = MutableSharedFlow<AppResult<List<Page>>>()
            val chapter = fixture.chapters[1]
            fixture.pages.results[chapter.url] = stream
            fixture.dispatch(ReaderIntent.OnAppendNextChapter)
            stream.emit(AppResult.Failure(challenge))
            val recoveredRequest = requestId(fixture, 1)
            stream.emit(AppResult.Success(activeActionPages(chapter)))
            val fetches = fixture.pages.requested.size
            fixture.dispatch(ReaderIntent.OnCloudflareSolverReturned(recoveredRequest))
            assertEquals(fetches, fixture.pages.requested.size)
            stream.emit(AppResult.Failure(challenge))
            returnFromSolver(fixture, 1)
            assertEquals(fetches + 1, fixture.pages.requested.size)
            stream.emit(AppResult.Success(activeActionPages(chapter)))
            assertEquals(fixture.chapters.take(2).flatMap(::activeActionPages), fixture.vm.state.value.pages)
            assertEquals(2, effects.filterIsInstance<ReaderEffect.SolveCloudflareChallenge>().size)
        }

    @Test
    fun unrelatedAppendSuccessDoesNotResetTheAnchorFailureStreak() =
        withReader { fixture, effects ->
            failChapter(fixture, 0)
            fixture.dispatch(ReaderIntent.OnRetry)
            fixture.dispatch(ReaderIntent.OnAppendNextChapter)
            returnFromSolver(fixture, 0)
            returnFromSolver(fixture, 0)
            assertEquals(2, effects.filterIsInstance<ReaderEffect.SolveCloudflareChallenge>().size)
            assertEquals(challenge, assertIs<ReaderEffect.ShowError>(effects.last()).error)
            fixture.pages.results[fixture.chapters[0].url] = flowOf(AppResult.Success(activeActionPages(fixture.chapters[0])))
            fixture.dispatch(ReaderIntent.OnRetry)
            failChapter(fixture, 0)
            fixture.dispatch(ReaderIntent.OnRetry)
            assertEquals(3, effects.filterIsInstance<ReaderEffect.SolveCloudflareChallenge>().size)
        }

    private fun failChapter(
        fixture: ReaderActiveActionFixture,
        index: Int,
    ) {
        fixture.pages.results[fixture.chapters[index].url] = flowOf(AppResult.Failure(challenge))
    }

    private fun requestId(
        fixture: ReaderActiveActionFixture,
        index: Int,
    ): String =
        assertNotNull(fixture.vm.cloudflareRecoveryRequestId(fixture.chapters[index].url, fixture.manga.api))

    private fun returnFromSolver(
        fixture: ReaderActiveActionFixture,
        index: Int,
    ) {
        fixture.dispatch(ReaderIntent.OnCloudflareSolverReturned(requestId(fixture, index)))
    }

    private fun withReader(block: suspend (ReaderActiveActionFixture, List<ReaderEffect>) -> Unit) =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val fixture = ReaderActiveActionFixture(testScheduler)
            try {
                val effects = mutableListOf<ReaderEffect>()
                backgroundScope.launch(dispatcher) { fixture.vm.effects.collect { effects += it } }
                fixture.dispatch(ReaderIntent.OnEnter(fixture.manga, fixture.chapters[0]))
                block(fixture, effects)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }
}
