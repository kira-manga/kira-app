package me.manga.kira.presentation.complaint

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintNotice
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
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
class ComplaintDetailViewModelTest {
    private val fixtures = mutableListOf<ComplaintDetailViewModelFixture>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun constructionIsInertAndOnlyExplicitSelectionLoadsTheReturnedRead() =
        runTest {
            val detail = ownedDetail()
            val fixture = fixture { AppResult.Success(detail) }
            assertEquals(ComplaintDetailState(), fixture.model.state.value)
            assertTrue(fixture.reader.requests.isEmpty())
            fixture.model.submit(ComplaintDetailIntent.Retry)
            assertTrue(fixture.reader.requests.isEmpty())
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
            assertEquals(listOf(DETAIL_A), fixture.reader.requests)
            val state = fixture.model.state.value
            assertSame(detail, state.detail)
            assertTrue(state.hasContent)
            assertFalse(state.isLoading)
            assertFalse(state.isUnavailable)
            assertEquals("ComplaintDetailState(redacted)", state.toString())
            assertEquals("ComplaintDetailIntent.Select(redacted)", ComplaintDetailIntent.Select(DETAIL_A).toString())
        }

    @Test
    fun repeatedSelectionIsNotAnotherReadAndRefreshFailureKeepsGenuineContentStale() =
        runTest {
            val detail = ownedDetail()
            val fixture = fixture { AppResult.Success(detail) }
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
            assertEquals(listOf(DETAIL_A), fixture.reader.requests)
            val release = CompletableDeferred<Unit>()
            fixture.reader.load = {
                release.await()
                AppResult.Failure(AppError.Network.Http(503))
            }
            fixture.model.submit(ComplaintDetailIntent.Retry)
            assertTrue(fixture.model.state.value.isLoading)
            assertSame(detail, fixture.model.state.value.detail)
            assertFalse(fixture.model.state.value.isStale)
            release.complete(Unit)
            val state = fixture.model.state.value
            assertTrue(state.isStale)
            assertSame(detail, state.detail)
            assertEquals(503, assertIs<AppError.Network.Http>(state.error).statusCode)
            assertFalse(state.isLoading)
        }

    @Test
    fun unavailableReplacesOldContentButAnUnavailableObservationIsNeverStaleContent() =
        runTest {
            val fixture = fixture { AppResult.Success(ownedDetail()) }
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
            fixture.reader.load = { AppResult.Success(ComplaintDetail.Unavailable) }
            fixture.model.submit(ComplaintDetailIntent.Retry)
            val unavailable = fixture.model.state.value
            assertSame(ComplaintDetail.Unavailable, unavailable.detail)
            assertTrue(unavailable.isUnavailable)
            assertFalse(unavailable.hasContent)
            assertNull(unavailable.error)
            fixture.reader.load = { AppResult.Failure(AppError.Network.NoConnectivity()) }
            fixture.model.submit(ComplaintDetailIntent.Retry)
            val failed = fixture.model.state.value
            assertSame(ComplaintDetail.Unavailable, failed.detail)
            assertIs<AppError.Network.NoConnectivity>(failed.error)
            assertFalse(failed.hasContent)
            assertFalse(failed.isStale)
        }

    @Test
    fun selectingAnotherTargetDropsOldContentBeforeTheNewReadCanFail() =
        runTest {
            val fixture = fixture { AppResult.Success(ownedDetail()) }
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
            val release = CompletableDeferred<Unit>()
            fixture.reader.load = {
                release.await()
                AppResult.Failure(AppError.Network.NoConnectivity())
            }
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_B))
            assertEquals(DETAIL_B, fixture.model.state.value.selectedId)
            assertNull(fixture.model.state.value.detail)
            assertTrue(fixture.model.state.value.isLoading)
            release.complete(Unit)
            assertEquals(listOf(DETAIL_A, DETAIL_B), fixture.reader.requests)
            val state = fixture.model.state.value
            assertNull(state.detail)
            assertFalse(state.isUnavailable)
            assertFalse(state.isStale)
            assertIs<AppError.Network.NoConnectivity>(state.error)
        }

    @Test
    fun noticeAndUnknownKindStayDistinctReadShapesWithRedactedState() =
        runTest {
            val notice = ComplaintDetail.Notice(ComplaintNotice(DETAIL_A, "unknown.notice.key", DETAIL_TIME, DETAIL_TIME, 1))
            val unknown = UnknownComplaintItem(DETAIL_B, "FUTURE_KIND_RAW_TOKEN", DETAIL_TIME, DETAIL_TIME)
            val fixture = fixture { AppResult.Success(notice) }
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
            assertSame(notice, fixture.model.state.value.detail)
            fixture.reader.load = { AppResult.Success(ComplaintDetail.Owned(unknown)) }
            fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_B))
            val state = fixture.model.state.value
            assertSame(unknown, assertIs<ComplaintDetail.Owned>(state.detail).item)
            assertFalse(unknown.isContractRecognized)
            assertFalse(state.isUnavailable)
            assertEquals("ComplaintDetailState(redacted)", state.toString())
        }

    @Test
    fun unexpectedReaderFailureIsBoundedAndDoesNotInventAnUnavailableObservation() =
        runTest {
            val failures = listOf(IllegalStateException("Synthetic raw response"), AssertionError("Synthetic raw failure"))
            failures.forEach { failure ->
                val fixture = fixture { throw failure }
                fixture.model.submit(ComplaintDetailIntent.Select(DETAIL_A))
                val state = fixture.model.state.value
                assertEquals("complaint_detail_failed", assertIs<AppError.Unexpected>(state.error).message)
                assertNull(state.error?.cause)
                assertNull(state.detail)
                assertFalse(state.isUnavailable)
                assertFalse(state.isStale)
                assertFalse(state.isLoading)
            }
        }

    private fun fixture(load: suspend (String) -> AppResult<ComplaintDetail>): ComplaintDetailViewModelFixture =
        ComplaintDetailViewModelFixture(load).also { fixtures += it }
}
