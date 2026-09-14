package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as SourceSelectionStore

// Independent of the production constant so a policy change cannot silently weaken the assertions.
internal const val EXPECTED_SEARCH_CONCURRENCY = 4
internal const val SEARCH_TEST_QUERY = "bounded query"
private const val TEST_SOURCE_COUNT = 8

internal typealias SearchConcurrencySnapshot = Map<String, AppResult<List<HomeFeedItem>>?>

internal enum class SearchTestOutcome {
    RETURNED_FAILURE,
    LOOKUP_FAILURE,
    MISSING_CLIENT,
    SEARCH_FAILURE,
}

internal data class SearchConcurrencyCall(
    val api: String,
    val query: String,
    val page: Int,
    val filters: FilterSelections,
)

internal data class SearchConcurrencyCollection(
    val job: Job,
    val snapshots: List<SearchConcurrencySnapshot>,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class SearchRepositoryConcurrencyTestFixture(
    private val scope: TestScope,
) {
    val apis = List(TEST_SOURCE_COUNT) { "source-$it" }
    val outcomes = mutableMapOf<String, SearchTestOutcome>()

    // An early transform lookup must be detected even when search bodies remain bounded.
    val lookups = mutableListOf<String>()
    val searches = mutableListOf<SearchConcurrencyCall>()
    val exits = mutableListOf<SearchConcurrencyCall>()
    val returnedFailure = AppResult.Failure(AppError.Network.Timeout())
    val lookupFailure = IllegalStateException("lookup exploded")
    val searchFailure = IllegalArgumentException("search exploded")
    var active = 0
        private set
    var peak = 0
        private set
    private val gates = mutableMapOf<Pair<String, String>, CompletableDeferred<Unit>>()

    val repository =
        SearchRepositoryImpl(
            sourcesRepository = sourceSelection(),
            dispatchers = dispatchers(),
            sourceRegistry = PilotRegistry(piloted = apis.toSet(), client = ::client),
        )

    fun collect(flow: Flow<SearchConcurrencySnapshot>): SearchConcurrencyCollection {
        val snapshots = mutableListOf<SearchConcurrencySnapshot>()
        val job = scope.backgroundScope.launch { flow.collect { snapshots += it } }
        return SearchConcurrencyCollection(job, snapshots)
    }

    fun release(
        query: String,
        api: String,
    ) {
        gate(query, api).complete(Unit)
    }

    fun releaseAll(query: String) {
        apis.forEach { release(query, it) }
    }

    private fun gate(
        query: String,
        api: String,
    ): CompletableDeferred<Unit> = gates.getOrPut(query to api) { CompletableDeferred() }

    private fun client(api: String): MangaSourceClient? {
        lookups += api
        return when (outcomes[api]) {
            SearchTestOutcome.LOOKUP_FAILURE -> throw lookupFailure
            SearchTestOutcome.MISSING_CLIENT -> null
            else -> GatedClient(api)
        }
    }

    private suspend fun runSearch(call: SearchConcurrencyCall): AppResult<List<HomeFeedItem>> {
        searches += call
        active++
        peak = maxOf(peak, active)
        return try {
            gate(call.query, call.api).await()
            when (outcomes[call.api]) {
                SearchTestOutcome.RETURNED_FAILURE -> returnedFailure
                SearchTestOutcome.SEARCH_FAILURE -> throw searchFailure
                else -> AppResult.Success(emptyList())
            }
        } finally {
            active--
            exits += call
        }
    }

    private fun sourceSelection(): SourceSelectionStore {
        val rows =
            apis
                .mapIndexed { priority, api ->
                    sourceRow(api, baseUrl = "https://$api.test", priority = priority)
                }.reversed()
        return SourceSelectionStore(
            sourcesDao = StatefulSourcesDao(rows),
            repos = emptySet(),
            prefs = SharedPrefsHelper(MapSettings()),
            applicationScope = scope.backgroundScope,
        )
    }

    private fun dispatchers(): DispatcherProvider {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return object : DispatcherProvider {
            override val main = dispatcher
            override val mainImmediate = dispatcher
            override val default = dispatcher
            override val io = dispatcher
            override val unconfined = dispatcher
        }
    }

    private inner class GatedClient(
        override val api: String,
    ) : MangaSourceClient {
        override suspend fun search(
            query: String,
            page: Int,
            filters: FilterSelections,
        ): AppResult<List<HomeFeedItem>> = runSearch(SearchConcurrencyCall(api, query, page, filters))

        override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> = error("not exercised")

        override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = error("not exercised")

        override suspend fun details(manga: Manga): AppResult<MangaDetails> = error("not exercised")

        override fun pages(
            manga: Manga,
            chapter: Chapter,
        ): Flow<AppResult<List<Page>>> = error("not exercised")
    }
}
