package me.manga.kira.presentation.complaint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintDetailLifecycleTest {
    private val fixtures = mutableListOf<ComplaintDetailViewModelFixture>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun canceledIntermediateSelectionCannotOvertakeOldestCleanupOrPublishItsValue() =
        runTest {
            assertLatestSelectionIsFenced(LateDetailRead(ownedDetail()))
            assertLatestSelectionIsFenced(LateDetailRead { throw AssertionError("Synthetic old failure") })
        }

    @Test
    fun closeClearsImmediatelyAndReopeningWaitsForCanceledReadCleanup() =
        runTest {
            val old = LateDetailRead(ownedDetail())
            val latest = ownedDetail(DETAIL_B)
            val fixture = lateFixture(old, latest)
            try {
                fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
                fixture.model.submit(ComplaintDetailIntent.Close)
                old.closing.await()
                assertEquals(ComplaintDetailState(), fixture.model.state.value)
                fixture.model.submit(ComplaintDetailIntent.Retry)
                fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_B))
                runCurrent()
                assertEquals(listOf(DETAIL_A), fixture.reader.requests)
                assertSelectionLoading(fixture, DETAIL_B)
                old.release.complete(Unit)
                runCurrent()
                assertEquals(listOf(DETAIL_A, DETAIL_B), fixture.reader.requests)
                assertSame(latest, fixture.model.state.value.detail)
                assertNull(fixture.model.state.value.error)
            } finally {
                old.release.complete(Unit)
            }
        }

    @Test
    fun clearingTheOwnedViewModelStoreFencesLateResultsAndEveryLaterIntent() =
        runTest {
            val old = LateDetailRead(ownedDetail())
            val fixture = fixture { old.read() }
            try {
                fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
                fixture.close()
                old.closing.await()
                assertEquals(ComplaintDetailState(), fixture.model.state.value)
                fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_B))
                fixture.model.submit(ComplaintDetailIntent.Retry)
                fixture.model.submit(ComplaintDetailIntent.Close)
                old.release.complete(Unit)
                runCurrent()
                assertEquals(listOf(DETAIL_A), fixture.reader.requests)
                assertEquals(ComplaintDetailState(), fixture.model.state.value)
                assertTrue(old.cleaned)
            } finally {
                old.release.complete(Unit)
            }
        }

    @Test
    fun currentReadCancellationPropagatesWithoutLosingPriorContentOrInventingAbsence() =
        runTest {
            val detail = ownedDetail()
            val fixture = fixture { AppResult.Success(detail) }
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
            var readJob: Job? = null
            fixture.reader.load = {
                readJob = currentCoroutineContext()[Job]
                throw CancellationException("Synthetic cancellation must not escape")
            }
            fixture.model.submit(ComplaintDetailIntent.Retry)
            assertTrue(readJob?.isCancelled == true)
            val state = fixture.model.state.value
            assertIs<AppError.Cancelled>(state.error)
            assertNull(state.error?.cause)
            assertSame(detail, state.detail)
            assertTrue(state.isStale)
            assertFalse(state.isLoading)
            assertFalse(state.isUnavailable)
            assertEquals(listOf(DETAIL_A, DETAIL_A), fixture.reader.requests)
        }

    private suspend fun TestScope.assertLatestSelectionIsFenced(old: LateDetailRead) {
        val latest = ownedDetail(DETAIL_C)
        val releaseLatest = CompletableDeferred<Unit>()
        val fixture = lateFixture(old, latest, releaseLatest::await)
        try {
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_B))
            old.closing.await()
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_C))
            runCurrent()
            assertEquals(listOf(DETAIL_A), fixture.reader.requests)
            assertSelectionLoading(fixture, DETAIL_C)
            old.release.complete(Unit)
            runCurrent()
            assertEquals(listOf(DETAIL_A, DETAIL_C), fixture.reader.requests)
            assertSelectionLoading(fixture, DETAIL_C)
            releaseLatest.complete(Unit)
            runCurrent()
            assertSame(latest, fixture.model.state.value.detail)
            assertNull(fixture.model.state.value.error)
            assertFalse(fixture.model.state.value.isLoading)
        } finally {
            old.release.complete(Unit)
            releaseLatest.complete(Unit)
        }
    }

    private fun assertSelectionLoading(fixture: ComplaintDetailViewModelFixture, id: String) {
        val state = fixture.model.state.value
        assertEquals(id, state.selectedId)
        assertNull(state.detail)
        assertNull(state.error)
        assertTrue(state.isLoading)
    }

    private fun lateFixture(
        old: LateDetailRead,
        latest: ComplaintDetail,
        waitForLatest: suspend () -> Unit = {},
    ): ComplaintDetailViewModelFixture =
        fixture { id ->
            if (id == DETAIL_A) {
                old.read()
            } else {
                assertTrue(old.cleaned)
                waitForLatest()
                AppResult.Success(latest)
            }
        }

    private fun fixture(load: suspend (String) -> AppResult<ComplaintDetail>): ComplaintDetailViewModelFixture =
        ComplaintDetailViewModelFixture(load).also { fixtures += it }
}
