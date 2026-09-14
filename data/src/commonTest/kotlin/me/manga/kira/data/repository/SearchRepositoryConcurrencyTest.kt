package me.manga.kira.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterSelections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val FRESH_QUERY = "fresh query"

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRepositoryConcurrencyTest {
    @Test
    fun boundsAdmissionAndStreamsImmutableSnapshots() =
        runTest {
            val fixture = SearchRepositoryConcurrencyTestFixture(this)
            val flow = fixture.repository.searchAllRepos(SEARCH_TEST_QUERY)
            assertTrue(fixture.lookups.isEmpty())
            assertTrue(fixture.searches.isEmpty())
            val observed = fixture.collect(flow)
            runCurrent()
            assertInitialAdmission(fixture, observed)
            val seed = observed.snapshots.single()
            val fastApi = fixture.apis[1]
            fixture.release(SEARCH_TEST_QUERY, fastApi)
            runCurrent()
            val partial = observed.snapshots.last()
            assertEquals(2, observed.snapshots.size)
            assertAdmission(fixture, fixture.apis.take(EXPECTED_SEARCH_CONCURRENCY + 1))
            assertTrue(partial[fastApi] is AppResult.Success)
            assertTrue(partial.filterKeys { it != fastApi }.values.all { it == null })
            fixture.releaseAll(SEARCH_TEST_QUERY)
            runCurrent()
            observed.job.join()
            assertCompleted(fixture, observed)
            assertTrue(seed.values.all { it == null })
            assertEquals(listOf(fastApi), partial.filterValues { it != null }.keys.toList())
        }

    @Test
    fun isolatesLookupAndSearchFailuresWhileReusingSlots() =
        runTest {
            val fixture = mixedFailureFixture(this)
            val observed = fixture.collect(fixture.repository.searchAllRepos(SEARCH_TEST_QUERY))
            runCurrent()
            val rejected = setOf(fixture.apis[2], fixture.apis[3])
            val initiallyResolved = fixture.apis.take(EXPECTED_SEARCH_CONCURRENCY + rejected.size)
            assertAdmission(fixture, initiallyResolved, initiallyResolved.filterNot { it in rejected })
            assertEquals(
                rejected,
                observed.snapshots
                    .last()
                    .filterValues { it != null }
                    .keys,
            )
            fixture.release(SEARCH_TEST_QUERY, fixture.apis[1])
            runCurrent()
            assertEquals(fixture.returnedFailure, observed.snapshots.last()[fixture.apis[1]])
            val nextResolved = fixture.apis.take(initiallyResolved.size + 1)
            assertAdmission(fixture, nextResolved, nextResolved.filterNot { it in rejected })
            fixture.release(SEARCH_TEST_QUERY, fixture.apis[4])
            runCurrent()
            assertAdmission(fixture, fixture.apis, fixture.apis.filterNot { it in rejected })
            fixture.releaseAll(SEARCH_TEST_QUERY)
            runCurrent()
            observed.job.join()
            assertMixedResults(fixture, observed)
        }

    @Test
    fun cancelsAdmittedSourcesWithoutResolvingQueuedClients() =
        runTest {
            val fixture = SearchRepositoryConcurrencyTestFixture(this)
            val first = fixture.collect(fixture.repository.searchAllRepos(SEARCH_TEST_QUERY))
            runCurrent()
            assertInitialAdmission(fixture, first)
            first.job.cancelAndJoin()
            runCurrent()
            assertCancelled(fixture, first, SEARCH_TEST_QUERY)
            assertEquals(EXPECTED_SEARCH_CONCURRENCY, fixture.lookups.size)
            val fresh = fixture.collect(fixture.repository.searchAllRepos(FRESH_QUERY))
            runCurrent()
            assertEquals(
                fixture.apis,
                fresh.snapshots
                    .single()
                    .keys
                    .toList(),
            )
            assertTrue(
                fresh.snapshots
                    .single()
                    .values
                    .all { it == null },
            )
            assertEquals(EXPECTED_SEARCH_CONCURRENCY, fixture.active)
            val admitted = fixture.apis.take(EXPECTED_SEARCH_CONCURRENCY)
            val freshSearches = fixture.searches.filter { it.query == FRESH_QUERY }
            assertEquals(admitted.toSet(), freshSearches.map { it.api }.toSet())
            assertEquals(EXPECTED_SEARCH_CONCURRENCY, freshSearches.size)
            assertEquals(admitted.associateWith { 2 }, fixture.lookups.groupingBy { it }.eachCount())
            fresh.job.cancelAndJoin()
            runCurrent()
            assertCancelled(fixture, fresh, FRESH_QUERY)
            assertTrue(fixture.peak <= EXPECTED_SEARCH_CONCURRENCY)
        }

    private fun assertInitialAdmission(
        fixture: SearchRepositoryConcurrencyTestFixture,
        observed: SearchConcurrencyCollection,
    ) {
        val seed = observed.snapshots.single()
        assertEquals(fixture.apis, seed.keys.toList())
        assertTrue(seed.values.all { it == null })
        assertAdmission(fixture, fixture.apis.take(EXPECTED_SEARCH_CONCURRENCY))
    }

    private fun assertAdmission(
        fixture: SearchRepositoryConcurrencyTestFixture,
        resolved: List<String>,
        searched: List<String> = resolved,
    ) {
        assertEquals(resolved.toSet(), fixture.lookups.toSet())
        assertEquals(resolved.size, fixture.lookups.size)
        assertEquals(searched.toSet(), fixture.searches.map { it.api }.toSet())
        assertEquals(searched.size, fixture.searches.size)
        assertEquals(EXPECTED_SEARCH_CONCURRENCY, fixture.active)
        assertTrue(fixture.peak <= EXPECTED_SEARCH_CONCURRENCY)
    }

    private fun assertCompleted(
        fixture: SearchRepositoryConcurrencyTestFixture,
        observed: SearchConcurrencyCollection,
    ) {
        assertTrue(observed.job.isCompleted)
        assertEquals(0, fixture.active)
        assertTrue(fixture.peak <= EXPECTED_SEARCH_CONCURRENCY)
        assertEquals(fixture.apis.associateWith { 1 }, fixture.lookups.groupingBy { it }.eachCount())
        assertEquals(fixture.apis.associateWith { 1 }, fixture.searches.groupingBy { it.api }.eachCount())
        assertEquals(fixture.searches.toSet(), fixture.exits.toSet())
        assertEquals(fixture.searches.size, fixture.exits.size)
        assertTrue(
            observed.snapshots
                .last()
                .values
                .all { it is AppResult.Success },
        )
        assertTerminalDelivery(fixture.apis, observed.snapshots)
        fixture.searches.forEach { call ->
            assertEquals(SEARCH_TEST_QUERY, call.query)
            assertEquals(1, call.page)
            assertEquals(FilterSelections.EMPTY, call.filters)
        }
    }

    private fun assertTerminalDelivery(
        apis: List<String>,
        snapshots: List<SearchConcurrencySnapshot>,
    ) {
        assertEquals(apis.size + 1, snapshots.size)
        assertEquals(apis, snapshots.first().keys.toList())
        assertTrue(snapshots.first().values.all { it == null })
        val updates =
            snapshots.zipWithNext { before, after ->
                assertEquals(apis, after.keys.toList())
                val changed = apis.filter { before[it] != after[it] }
                assertEquals(1, changed.size)
                val api = changed.single()
                assertNull(before[api])
                assertNotNull(after[api])
                api
            }
        assertEquals(apis.toSet(), updates.toSet())
    }

    private fun mixedFailureFixture(scope: TestScope): SearchRepositoryConcurrencyTestFixture =
        SearchRepositoryConcurrencyTestFixture(scope).apply {
            outcomes[apis[1]] = SearchTestOutcome.RETURNED_FAILURE
            outcomes[apis[2]] = SearchTestOutcome.LOOKUP_FAILURE
            outcomes[apis[3]] = SearchTestOutcome.MISSING_CLIENT
            outcomes[apis[4]] = SearchTestOutcome.SEARCH_FAILURE
        }

    private fun assertMixedResults(
        fixture: SearchRepositoryConcurrencyTestFixture,
        observed: SearchConcurrencyCollection,
    ) {
        val apis = fixture.apis
        val last = observed.snapshots.last()
        assertEquals(fixture.returnedFailure, last[apis[1]])
        assertUnexpected(last[apis[2]]?.errorOrNull(), fixture.lookupFailure)
        assertEquals(AppError.Validation.SourceUnavailable(apis[3]), last[apis[3]]?.errorOrNull())
        assertUnexpected(last[apis[4]]?.errorOrNull(), fixture.searchFailure)
        val successes = apis.filterNot { it in fixture.outcomes }
        successes.forEach { assertTrue(last[it] is AppResult.Success) }
        val searched = apis.filterNot { it == apis[2] || it == apis[3] }
        assertEquals(apis.associateWith { 1 }, fixture.lookups.groupingBy { it }.eachCount())
        assertEquals(searched.associateWith { 1 }, fixture.searches.groupingBy { it.api }.eachCount())
        assertEquals(fixture.searches.toSet(), fixture.exits.toSet())
        assertEquals(fixture.searches.size, fixture.exits.size)
        assertEquals(0, fixture.active)
        assertTrue(fixture.peak <= EXPECTED_SEARCH_CONCURRENCY)
        assertTrue(observed.job.isCompleted)
        assertTerminalDelivery(apis, observed.snapshots)
    }

    private fun assertUnexpected(
        actual: AppError?,
        cause: Throwable,
    ) {
        val unexpected = assertIs<AppError.Unexpected>(actual)
        assertEquals(cause.message, unexpected.message)
        assertSame(cause, unexpected.cause)
    }

    private fun assertCancelled(
        fixture: SearchRepositoryConcurrencyTestFixture,
        observed: SearchConcurrencyCollection,
        query: String,
    ) {
        assertTrue(observed.job.isCancelled)
        assertEquals(0, fixture.active)
        val admitted = fixture.apis.take(EXPECTED_SEARCH_CONCURRENCY).toSet()
        val searches = fixture.searches.filter { it.query == query }
        val exits = fixture.exits.filter { it.query == query }
        assertEquals(admitted, searches.map { it.api }.toSet())
        assertEquals(EXPECTED_SEARCH_CONCURRENCY, searches.size)
        assertEquals(searches.toSet(), exits.toSet())
        assertEquals(EXPECTED_SEARCH_CONCURRENCY, exits.size)
        assertTrue(fixture.lookups.none { it in fixture.apis.drop(EXPECTED_SEARCH_CONCURRENCY) })
        assertTrue(
            observed.snapshots
                .single()
                .values
                .all { it == null },
        )
    }
}
