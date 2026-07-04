package me.manga.kira.sources_repositry.es.olympusbiblioteca

/**
 * Migration note (Phase 7.3): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * This file inherits from `BaseMangaRepository` (NOT `BaseManga`) so the Coil3/Context image-
 * builder overrides from the Android source (`buildItemsImageRequest`, `buildImageRequest`)
 * have been dropped — see `BaseMangaRepository.kt` header for why (Coil3 lives in :composeApp,
 * android.content.Context is Android-only). Headers are still exposed via `defaultHeaders` so
 * the platform-side image loader can rebuild the requests.
 *
 * `withContext(Dispatchers.IO)` wrappers around Retrofit calls dropped — Ktor calls are already
 * main-safe via their engine, and Dispatchers.IO is JVM-only. The internal `kotlinx.coroutines
 * .delay(100/200)` calls between batched page fetches are preserved (they exist in commonMain).
 * The `java.time.LocalDate / LocalDateTime / DateTimeFormatter` parse fallback for chapter
 * publish dates is mapped to `kotlinx.datetime.LocalDate.parse(...)` — its ISO parser already
 * handles both `yyyy-MM-dd` and full ISO_DATE_TIME (`...T...`) via the `LocalDateTime.parse`
 * fallback below.
 */

import co.touchlab.kermit.Logger
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.Volatile
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import me.manga.kira.core.states.State
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.BaseMangaRepository
import me.manga.kira.sources_repositry.data.MangaSource
import me.manga.kira.sources_repositry.es.olympusbiblioteca.models.chapter_images.OlympusbibliotecaChapterImagesResponse
import me.manga.kira.sources_repositry.es.olympusbiblioteca.models.chapters.OlympusbibliotecaChaptersResponse
import me.manga.kira.sources_repositry.es.olympusbiblioteca.models.details.OlympusbibliotecaDetailsResponse
import me.manga.kira.sources_repositry.es.olympusbiblioteca.models.home.OlympusbibliotecaHomeResponse
import me.manga.kira.sources_repositry.es.olympusbiblioteca.models.popular.OlympusbibliotecaPopularResponse
import me.manga.kira.sources_repositry.es.olympusbiblioteca.models.popular.PopularComic
import me.manga.kira.sources_repositry.es.olympusbiblioteca.models.search.OlympusbibliotecaSearchResponse

@OptIn(ExperimentalTime::class)
open class OlympusbibliotecaRepository(
    private val api: ApiClient,
    private val dataStore: DataStoreHelper,
    private val sourcesRepository: SourcesDao,
) : BaseMangaRepository() {

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }

    override val BASE_URL: String
        get() = MangaSource.OLYMPUSBIBLIOTECA.BASEURL
    override val URL_VERSION: Int
        get() = 0
    override var baseUrl: String = ""
    override val API: String
        get() = MangaSource.OLYMPUSBIBLIOTECA.API
    override val LANGUAGE: String
        get() = MangaSource.OLYMPUSBIBLIOTECA.LANGUAGE.Language
    override val ICON: Int
        get() = MangaSource.OLYMPUSBIBLIOTECA.ICON
    override val PRIORITY = MangaSource.OLYMPUSBIBLIOTECA.PRIORITY

    private val apiUrl = "https://dashboard.olympusbiblioteca.com/api/"
    override var imgBaseUrl: String = "https://dashboard.olympusbiblioteca.com/"
    override var imgUrlVersion: Int = 0

    open val language: String = "es"
    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override suspend fun getBaseUrl(): String {
        val url = sourcesRepository.getBaseUrlFor(API) ?: apiUrl
        baseUrl = url
        return url
    }

    override val blackListGenres: Set<String>
        get() = setOf(
            "Adult",
            "Ecchi",
            "Harem",
            "Smut",
            "Yaoi",
            "Yuri"
        )

    override val sortTypes: Set<String>
        get() = setOf(
            "latest",
            "popular",
            "rating",
            "alphabetical"
        )

    override val allGenres: Set<String>
        get() = setOf(
            "Acción",
            "Aventura",
            "Comedia",
            "Drama",
            "Fantasía",
            "Horror",
            "Misterio",
            "Romance",
            "Sci-Fi",
            "Slice of Life",
            "Deportes",
            "Supernatural",
            "Thriller",
            "Artes Marciales",
            "Isekai",
            "Retornado"
        )

    // Main API methods

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchData(url) { html ->
            extractChapterImages(html)
        }

    override fun fetchMangaHomeF(baseUrl: String): Flow<State<MutableList<MangaItem>>> =
        fetchHome()

    fun fetchHome(page: Int = 1): Flow<State<MutableList<MangaItem>>> =
        fetchData("${apiUrl}new-chapters?page=$page") { html ->
            extractHomeMangaItems(html)
        }

    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> {
        return when (searchType) {
            is SearchType.Normal -> {
                fetchData("${apiUrl}search?name=${searchType.toNormalQuery()}") { html ->
                    extractSearchMangaItems(html).toMangaItems(API, LANGUAGE, apiUrl)
                }
            }
            is SearchType.GENRES -> {
                // Olympus doesn't seem to have genre-based search in the provided endpoints
                // You might need to implement this differently based on their actual API
                fetchData("${apiUrl}search?genre=${searchType.genres}") { html ->
                    extractSearchMangaItems(html).toMangaItems(API, LANGUAGE, apiUrl)
                }
            }
            is SearchType.SORT -> {
                fetchData("${apiUrl}search?name=${searchType.query}&sort=${searchType.sortType}") { html ->
                    extractSearchMangaItems(html).toMangaItems(API, LANGUAGE, apiUrl)
                }
            }
        }
    }

    override fun fetchMoreManga(
        page: Int,
        currentItems: List<MangaItem>?
    ): Flow<State<List<MangaItem>>> =
        flow {
            if (page > 51) return@flow // Based on the API response showing 51 total pages
            emit(State.Loading as State<List<MangaItem>>)
            fetchHome(page).collect { state ->
                when (state) {
                    is State.Success -> {
                        val newItems = state.toData() ?: emptyList()
                        val mergedList = (currentItems?.toMutableList() ?: mutableListOf()).apply {
                            addAll(newItems)
                        }
                        emit(
                            State.Success(
                                if (newItems.isEmpty()) (currentItems ?: emptyList()) else mergedList
                            )
                        )
                    }
                    is State.Error -> emit(state)
                    else -> Unit
                }
            }
        }.catch { e ->
            emit(State.Error(0, e.message ?: "Unknown error occurred"))
        }

    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        fetchData("${apiUrl}sf/home") { html ->
            extractPopularMangaItems(html).take(20).toPopularManga(API, LANGUAGE)
        }

    // Extraction methods
    fun extractPopularMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val response: OlympusbibliotecaPopularResponse = jsonParser.decodeFromString(json)
            val popularComicsJson = response.data?.popular_comics
            if (popularComicsJson.isNullOrBlank()) {
                Logger.withTag("OlympusRepository").w { "popular_comics field is null or empty" }
                return mutableListOf()
            }
            val popularComics: List<PopularComic> = jsonParser.decodeFromString(popularComicsJson)
            Logger.withTag("OlympusRepository").i { "Parsed ${popularComics.size} popular comics" }

            popularComics.map { comic ->
                comic.let {
                    MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = it.name.orEmpty(),
                        url = "${apiUrl}series/${it.slug}/",
                        imageUrl = it.cover.orEmpty(),
                        rating = null,
                        chapters = listOf(),
                        genres = listOf()
                    )
                }
            }.toMutableList()

        } catch (e: Exception) {
            Logger.withTag("OlympusRepository").e(e) { "Error parsing popular comics: ${e.message}" }
            mutableListOf()
        }
    }

    // You can also add this alterna
    fun sanitizeSlug(raw: String): String {
        // remove trailing timestamp like -20250923-081331527 (dash + 8 digits + optional - and more digits)
        val cleaned = raw.replace(Regex("(-\\d{8}(?:-\\d{6,})?)$"), "")
        return cleaned.ifBlank { raw }
    }
    fun extractHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val response: OlympusbibliotecaHomeResponse = jsonParser.decodeFromString(json)
            response.data?.mapNotNull { item ->
                item?.let {
                    MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = it.name.orEmpty(),
                        url = "${apiUrl}series/${it.slug}/",
                        imageUrl = it.cover.orEmpty(),
                        rating = null,
                        chapters = listOf(),
                        genres = listOf()
                    )
                }
            }?.toMutableList() ?: mutableListOf()
        } catch (e: Exception) {
            Logger.withTag("OlympusRepository").e(e) { "Error parsing home items: ${e.message}" }
            mutableListOf()
        }
    }

    fun extractSearchMangaItems(json: String): List<me.manga.kira.sources_repositry.es.olympusbiblioteca.models.search.Data> {
        return try {
            val response: OlympusbibliotecaSearchResponse = jsonParser.decodeFromString(json)
            response.data?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Logger.withTag("OlympusRepository").e(e) { "Error parsing search items: ${e.message}" }
            emptyList()
        }
    }

    fun extractMangaInfo(json: String): me.manga.kira.sources_repositry.es.olympusbiblioteca.models.details.Data? {
        return try {
            val response: OlympusbibliotecaDetailsResponse = jsonParser.decodeFromString(json)
            response.data
        } catch (e: Exception) {
            Logger.withTag("OlympusRepository").e(e) { "Error parsing manga info: ${e.message}" }
            null
        }
    }

    fun extractMangaChapters(json: String): List<me.manga.kira.sources_repositry.es.olympusbiblioteca.models.chapters.Data> {
        return try {
            val response: OlympusbibliotecaChaptersResponse = jsonParser.decodeFromString(json)
            response.data?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Logger.withTag("OlympusRepository").e(e) { "Error parsing chapters: ${e.message}" }
            emptyList()
        }
    }

    fun extractChapterImages(json: String): List<String> {
        return try {
            val response: OlympusbibliotecaChapterImagesResponse = jsonParser.decodeFromString(json)
            response.chapter?.pages?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Logger.withTag("OlympusRepository").e(e) { "Error parsing chapter images: ${e.message}" }
            emptyList()
        }
    }

    // Extension methods

    private fun List<me.manga.kira.sources_repositry.es.olympusbiblioteca.models.search.Data>.toMangaItems(
        api: String,
        language: String,
        baseUrl: String
    ): List<MangaItem> =
        map { item ->
            MangaItem(
                api = api,
                language = language,
                title = item.name.orEmpty(),
                url = "${baseUrl}series/${item.slug}/",
                imageUrl = item.cover.orEmpty(),
                rating = null,
                chapters = listOf(),
                genres = listOf()
            )
        }

    private fun me.manga.kira.sources_repositry.es.olympusbiblioteca.models.details.Data.toMangaInfo(
        api: String,
        language: String,
        url: String
    ): MangaInfo {
        return MangaInfo(
            api = api,
            language = language,
            url = url,
            title = name.orEmpty(),
            imageUrl = cover.orEmpty(),
            rating = rating?.toString().orEmpty(),
            description = summary.orEmpty(),
            author = "",
            genres = genres?.mapNotNull { it?.name } ?: emptyList(),
            status = status?.name.orEmpty(),
            chapters = mutableListOf()
        )
    }

    private fun List<me.manga.kira.sources_repositry.es.olympusbiblioteca.models.chapters.Data>.toChapterItems(
        seriesSlug: String
    ): List<ChapterItem> =
        map { chapter ->
            ChapterItem(
                number = chapter.name.orEmpty(),
                name = "",
                url = "https://olympusbiblioteca.com/api/capitulo/$seriesSlug/${chapter.id}?type=comic",
                date = chapter.published_at?.let { dateStr ->
                    try {
                        if (dateStr.contains("T")) {
                            LocalDateTime.parse(dateStr).date
                        } else {
                            LocalDate.parse(dateStr)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            )
        }

    private fun List<MangaItem>.toPopularManga(api: String, language: String): List<PopularManga> =
        map { manga ->
            PopularManga(
                api = api,
                language = language,
                title = manga.title,
                url = manga.url,
                imageUrl = manga.imageUrl
            )
        }

    private inline fun <T> fetchData(
        url: String,
        crossinline transform: suspend (htmlContent: String) -> T
    ): Flow<State<T>> = flow {
        emit(State.Loading)

        try {
            val response = api.get(url)
            if (response.status.isSuccess()) {
                val htmlContent = response.bodyAsText()
                val parsedData = transform(htmlContent)
                emit(State.Success(parsedData))
            } else {
                val errorMessage = "Unexpected error"
                emit(State.Error(0, errorMessage))
            }
        } catch (e: Exception) {
            emit(State.Error(0, e.message ?: "Unknown error occurred"))
        }
    }

    companion object {
        private const val TAG = "OlympusRepository"
    }


    // Replace your existing fetchMangaChaptersF method with this updated version
    override suspend fun fetchMangaChaptersF(url: String): Flow<State<MangaInfo>> {
        Logger.withTag("OlympusRepository").i { "Fetching manga info for: $url" }

        // Extract slug from URL - handle different URL formats
        val slug = when {
            url.contains("/series/") -> {
                url.substringAfter("/series/").substringBefore("/").substringBefore("?")
            }
            url.contains("slug=") -> {
                url.substringAfter("slug=").substringBefore("&")
            }
            else -> {
                // If URL is just the slug itself
                url.trim('/')
            }
        }

        Logger.withTag("OlympusRepository").i { "Extracted slug: $slug" }

        val infoUrl = "${apiUrl}series/$slug/"

        Logger.withTag("OlympusRepository").i { "Info URL: $infoUrl" }

        val infoFlow: Flow<State<MangaInfo?>> = fetchData(infoUrl) { html ->
            Logger.withTag("OlympusRepository").i { "Info response: ${html.take(200)}..." }
            extractMangaInfo(html)?.toMangaInfo(API, LANGUAGE, url)
        }

        val chaptersFlow: Flow<State<List<ChapterItem>>> = flow {
            emit(State.Loading)
            try {
                val allChapters = fetchAllChaptersAsync(slug)
                emit(State.Success(allChapters))
            } catch (e: Exception) {
                Logger.withTag("OlympusRepository").e(e) { "Error fetching all chapters: ${e.message}" }
                emit(State.Error(0, e.message ?: "Failed to fetch chapters"))
            }
        }

        return flow {
            emit(State.Loading)

            infoFlow
                .combine(chaptersFlow) { infoState, chapState ->
                    Pair(infoState, chapState)
                }
                .collect { (infoState, chapState) ->
                    if (infoState is State.Loading || chapState is State.Loading) {
                        emit(State.Loading)
                        return@collect
                    }

                    if (infoState is State.Error) {
                        emit(State.Error(0, infoState.message))
                        return@collect
                    }

                    val mangaInfo: MangaInfo? = (infoState as? State.Success)?.data
                    if (mangaInfo == null) {
                        emit(State.Error(0, "Failed to parse MangaInfo"))
                        return@collect
                    }

                    val chapterList: List<ChapterItem> = (chapState as? State.Success)?.data.orEmpty()

                    mangaInfo.chapters.clear()
                    mangaInfo.chapters.addAll(chapterList)
                    emit(State.Success(mangaInfo))
                }
        }
    }

    // New method to fetch all chapters asynchronously
    private suspend fun fetchAllChaptersAsync(slug: String): List<ChapterItem> {
        Logger.withTag("OlympusRepository").i { "Starting async fetch for all chapters of: $slug" }

        // First, fetch the first page to get total pages info
        val firstPageUrl = "${apiUrl}series/$slug/chapters?page=1&direction=desc&type=comic"
        val response = api.get(firstPageUrl)

        if (!response.status.isSuccess()) {
            Logger.withTag("OlympusRepository").e { "Failed to fetch first page: ${response.status.value}" }
            return emptyList()
        }

        val firstPageJson = response.bodyAsText()
        val firstPageResponse: OlympusbibliotecaChaptersResponse = try {
            jsonParser.decodeFromString(firstPageJson)
        } catch (e: Exception) {
            Logger.withTag("OlympusRepository").e(e) { "Error parsing first page: ${e.message}" }
            return emptyList()
        }

        val totalPages = firstPageResponse.meta?.last_page ?: 1
        val firstPageChapters = firstPageResponse.data?.filterNotNull()?.toChapterItems(slug) ?: emptyList()

        Logger.withTag("OlympusRepository").i { "Total pages to fetch: $totalPages" }

        if (totalPages <= 1) {
            Logger.withTag("OlympusRepository").i { "Only one page, returning ${firstPageChapters.size} chapters" }
            return firstPageChapters
        }

        // Fetch remaining pages asynchronously
        val remainingPages = (2..totalPages).toList()
        val batchSize = 5 // Process pages in batches to avoid overwhelming the server

        val allChaptersList = mutableListOf<ChapterItem>()
        allChaptersList.addAll(firstPageChapters)

        // Process pages in batches
        remainingPages.chunked(batchSize).forEach { batch ->
            coroutineScope {
                val batchResults = batch.map { page ->
                    async {
                        fetchChaptersForPage(slug, page)
                    }
                }.awaitAll()

                batchResults.forEach { pageChapters ->
                    allChaptersList.addAll(pageChapters)
                }
            }

            // Add a small delay between batches to be respectful to the server
            delay(100)
        }

        Logger.withTag("OlympusRepository").i { "Fetched total of ${allChaptersList.size} chapters across $totalPages pages" }
        return allChaptersList
    }

    // Helper method to fetch chapters for a specific page
    private suspend fun fetchChaptersForPage(slug: String, page: Int): List<ChapterItem> {
        return try {
            val pageUrl = "${apiUrl}series/$slug/chapters?page=$page&direction=desc&type=comic"
            Logger.withTag("OlympusRepository").d { "Fetching page $page: $pageUrl" }

            val response = api.get(pageUrl)

            if (!response.status.isSuccess()) {
                Logger.withTag("OlympusRepository").w { "Failed to fetch page $page: ${response.status.value}" }
                return emptyList()
            }

            val jsonContent = response.bodyAsText()
            val chaptersResponse: OlympusbibliotecaChaptersResponse = jsonParser.decodeFromString(jsonContent)
            val chapters = chaptersResponse.data?.filterNotNull()?.toChapterItems(slug) ?: emptyList()

            Logger.withTag("OlympusRepository").d { "Page $page: fetched ${chapters.size} chapters" }
            chapters

        } catch (e: Exception) {
            Logger.withTag("OlympusRepository").e(e) { "Error fetching page $page: ${e.message}" }
            emptyList()
        }
    }

    // Alternative method with configurable concurrency and retry logic
    private suspend fun fetchAllChaptersAsyncAdvanced(
        slug: String,
        maxConcurrentRequests: Int = 3,
        maxRetries: Int = 2
    ): List<ChapterItem> {
        Logger.withTag("OlympusRepository").i { "Starting advanced async fetch for all chapters of: $slug" }

        // Fetch first page to get metadata
        val firstPageResult = fetchChaptersForPageWithRetry(slug, 1, maxRetries)
        val firstPageUrl = "${apiUrl}series/$slug/chapters?page=1&direction=desc&type=comic"

        // We need to get the total pages from the API response
        val response = api.get(firstPageUrl)
        if (!response.status.isSuccess()) {
            return firstPageResult
        }

        val totalPages = try {
            val chaptersResponse: OlympusbibliotecaChaptersResponse = jsonParser.decodeFromString(response.bodyAsText())
            chaptersResponse.meta?.last_page ?: 1
        } catch (e: Exception) {
            Logger.withTag("OlympusRepository").e(e) { "Error getting total pages: ${e.message}" }
            return firstPageResult
        }

        if (totalPages <= 1) {
            return firstPageResult
        }

        val allChapters = mutableListOf<ChapterItem>()
        allChapters.addAll(firstPageResult)

        // Fetch remaining pages with controlled concurrency
        val remainingPages = (2..totalPages).toList()

        remainingPages.chunked(maxConcurrentRequests).forEach { batch ->
            coroutineScope {
                val batchResults = batch.map { page ->
                    async {
                        fetchChaptersForPageWithRetry(slug, page, maxRetries)
                    }
                }.awaitAll()

                batchResults.forEach { pageChapters ->
                    allChapters.addAll(pageChapters)
                }
            }

            // Small delay between batches
            delay(200)
        }

        Logger.withTag("OlympusRepository").i { "Advanced fetch completed: ${allChapters.size} chapters from $totalPages pages" }
        return allChapters
    }

    // Helper method with retry logic
    private suspend fun fetchChaptersForPageWithRetry(
        slug: String,
        page: Int,
        maxRetries: Int = 2
    ): List<ChapterItem> {
        repeat(maxRetries + 1) { attempt ->
            try {
                val result = fetchChaptersForPage(slug, page)
                if (result.isNotEmpty() || attempt == maxRetries) {
                    return result
                }
            } catch (e: Exception) {
                Logger.withTag("OlympusRepository").w { "Attempt ${attempt + 1} failed for page $page: ${e.message}" }
                if (attempt < maxRetries) {
                    delay(1000) // Exponential backoff
                } else {
                    Logger.withTag("OlympusRepository").e { "All retries exhausted for page $page" }
                }
            }
        }
        return emptyList()
    }


}

/*
 * Audit-trail postscript (Phase 9.x.cluster199.staleKdocSweep.cascade, Task #654, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster199 leaf 1/2 — :es/ Repository tier heavy-half batch OPENING leaf, sibling 350.
 *
 * Pairs with cluster199's closing leaf TaurusFansubEsRepository (sibling 351, ~686 lines).
 * Together they close the :es/ Repository tier after cluster198's light-half (siblings 345-349).
 *
 * The KDoc preamble at lines 3-20 is the longest in the :es/ tier — three migration-axis blocks:
 *  (a) baseline Phase 7.3 axes (Retrofit→Ktor, jsoup→ksoup, FormBody→Map, @Inject drop,
 *      Log→Kermit, java.time→kotlinx.datetime);
 *  (b) BaseMangaRepository (NOT BaseManga) inheritance + Coil3/Context image-builder drop;
 *  (c) JVM-only shim drops (Dispatchers.IO + DateTimeFormatter) → commonMain alternatives.
 * All three axes verified live at cluster199 boundary.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • FULFILLED-PORT — Three migration axes confirmed:
 *     (1) Imports at lines 22-55 show NO Retrofit/jsoup/okhttp3/@Inject/Dispatchers.IO/java.time.
 *     (2) `: BaseMangaRepository()` at line 62 confirms the dropped `BaseManga` parent (Coil3
 *         image-builder overrides absent — verified by grep: no `buildImageRequest`/
 *         `buildItemsImageRequest` overrides in this file).
 *     (3) `kotlinx.datetime.LocalDate.parse` + `LocalDateTime.parse` fallback at lines 372-380
 *         replace the Android source's DateTimeFormatter-pair pattern. Both ISO-8601 with-time
 *         (`...T...`) and bare-date (`yyyy-MM-dd`) forms handled by the if/else split.
 *
 *   • LIVE-NOT-STALE — `open class OlympusbibliotecaRepository(...)` at line 58 with `open val
 *     language: String = "es"` at line 90. `open` modifiers signal subclassing intent — at
 *     cluster199 boundary no :es/ subclass exists, but the open hooks are preserved per §253
 *     (parallels sibling 346/347 MangaParkRepositoryEs/Es419 which DO subclass an open EN parent).
 *
 *   • LIVE-NOT-STALE — `override suspend fun initSite(): Int { ... }` at lines 91-95 ACTIVE
 *     (not commented out). DataStore-backed _cachedHeaders @Volatile preload pattern, same
 *     idiom as sibling 349 ManhwawebEs (also active). Cluster199 leaves: 1/2 expose so far
 *     (need to verify sibling 351 below).
 *
 *   • LIVE-NOT-STALE — `override var baseUrl: String = ""` at line 77 mutable + `override
 *     suspend fun getBaseUrl()` at lines 107-111 mutates it (`baseUrl = url`). Pattern: read
 *     from SourcesDao with apiUrl fallback. Mirrors `defaultHeaders` `_cachedHeaders ?:
 *     emptyMap()` fallback chain.
 *
 *   • LIVE-NOT-STALE — `private val apiUrl = "https://dashboard.olympusbiblioteca.com/api/"`
 *     at line 86 hardcoded API endpoint. Contrasts with BASE_URL getter at lines 73-74 which
 *     reads from MangaSource enum (e.g. "https://olympusbiblioteca.com/" without /api/). The
 *     `apiUrl`-vs-`baseUrl`/`BASE_URL` distinction is significant: API requests go to
 *     `dashboard.*`, image/site URLs go to bare `olympusbiblioteca.com`. Preserved as-is.
 *
 *   • POTENTIAL-BUG-PRESERVED — `if (page > 51) return@flow` at line 193 in `fetchMoreManga`.
 *     The comment ("Based on the API response showing 51 total pages") freezes a value that
 *     may have shifted since the Android port. The hardcoded 51-page cap will silently stop
 *     pagination if the source ever publishes a 52nd page. Preserved per §253 — drift is
 *     unknown without runtime verification.
 *
 *   • POTENTIAL-BUG-PRESERVED — `fetchSearchDataF` SearchType.GENRES branch at lines 173-179.
 *     The comment at line 174 ("Olympus doesn't seem to have genre-based search in the
 *     provided endpoints") admits the genre endpoint URL `?genre=${searchType.genres}` is
 *     speculative. If the endpoint never existed, this branch returns empty results
 *     silently — observable as "no results" when user filters by genre. Preserved verbatim.
 *
 *   • POTENTIAL-BUG-PRESERVED — `sanitizeSlug(raw: String)` at lines 255-259 strips trailing
 *     timestamp suffixes (regex `(-\\d{8}(?:-\\d{6,})?)$`). Defined but NOT called anywhere
 *     in this file (verified by grep — only the definition is visible). DEAD-CODE-PRESERVED
 *     hybrid: declared but unreferenced. Preserved per §253 — likely a planned slug-cleanup
 *     escape hatch for future use, not yet wired into the URL-building paths.
 *
 *   • DEAD-CODE-PRESERVED — `fetchAllChaptersAsyncAdvanced` at lines 582-660 + its retry
 *     helper `fetchChaptersForPageWithRetry` at lines 638-660. Both private suspend funs are
 *     DEFINED but NOT CALLED — only `fetchAllChaptersAsync` (lines 496-553) is invoked from
 *     `fetchMangaChaptersF`. The Advanced variant is a parallel implementation with
 *     configurable concurrency (maxConcurrentRequests=3 vs the active path's batchSize=5) +
 *     retry-with-1s-backoff. Two parallel chapter-fetch architectures with different
 *     defaults — preserved per §253 as design alternatives; the active path stays Simple.
 *
 *   • DEAD-CODE-PRESERVED — `companion object { private const val TAG = "OlympusRepository" }`
 *     at lines 417-419. TAG is `private` so external use is impossible. Internal use is
 *     ZERO: every Logger.withTag(...) call in this file (~20 occurrences) hardcodes
 *     "OlympusRepository" as a string literal rather than referencing TAG. The constant is
 *     defined-but-unused — preserved per §253.
 *
 *   • LIVE-NOT-STALE — `BASE_URL/URL_VERSION/baseUrl/API/LANGUAGE/ICON/PRIORITY` 7-getter
 *     block at lines 73-84. ICON+PRIORITY are unique to this leaf within cluster198+199
 *     (siblings 345-349 expose only API/LANGUAGE/BASE_URL). The wider override surface
 *     suggests this leaf participates in a richer base-class contract — BaseMangaRepository
 *     vs the lighter Normal/SeparatedDetails Sites siblings expose.
 *
 *   • LIVE-NOT-STALE — `blackListGenres` at lines 113-121: 6 entries (Adult/Ecchi/Harem/Smut/
 *     Yaoi/Yuri). All UPPERCASE-friendly English keys — contrast with sibling 349's
 *     ManhwawebEs which used Spanish-key entries ("Girls love", "Boys love", "Milf"). The
 *     UI-key vs backend-key separation differs across :es/ sources — this source matches
 *     against ENGLISH genre names because the Olympus backend returns English genre labels
 *     in the JSON response (see allGenres at lines 131-149 mixing Spanish + English entries).
 *
 *   • LIVE-NOT-STALE — `sortTypes` at lines 123-129: 4 entries (latest/popular/rating/
 *     alphabetical) all lowercase English. Match the API's `?sort=` parameter expected
 *     vocabulary (lines 181 sortSearchUrl construction). Forward-only — no `getSortValue`
 *     helper since the keys equal the API param.
 *
 *   • LIVE-NOT-STALE — `allGenres` at lines 131-149: 16 entries, mixed Spanish + English
 *     ("Acción"/"Action" both present as separate entries? — no, just Spanish "Acción" with
 *     English "Sci-Fi"/"Slice of Life"/"Supernatural"/"Thriller"/"Isekai"/"Retornado"
 *     interspersed). Reflects the upstream Olympus backend's own mixed-language taxonomy.
 *
 *   • LIVE-NOT-STALE — `fetchAllChaptersAsync` at lines 496-553: production chapter-fetch
 *     path using batchSize=5 chunked-async pattern. 100ms delay between batches at line 548.
 *     Comment at lines 528-529 documents the batchSize choice ("Process pages in batches to
 *     avoid overwhelming the server").
 *
 *   • LIVE-NOT-STALE — `fetchData` private inline helper at lines 396-415 unifies the
 *     "emit Loading → fetch → emit Success/Error" pattern across `fetchChapterDataF`,
 *     `fetchMangaHomeF`, `fetchSearchDataF`, `fetchPopularManga`, etc. Single error path
 *     emits `State.Error(0, ...)` — code 0 is the source's documented "Unexpected error"
 *     sentinel (line 409 `errorMessage = "Unexpected error"`).
 *
 *   • LIVE-NOT-STALE — Chapter URL construction at line 370 uses hardcoded
 *     `"https://olympusbiblioteca.com/api/capitulo/$seriesSlug/${chapter.id}?type=comic"`.
 *     The `?type=comic` query param distinguishes this from `?type=novela` (Olympus also
 *     serves novels). The hardcoded `comic` value is the manga-only choice — preserved.
 *
 *   • LIVE-NOT-STALE — `extractPopularMangaItems` at lines 222-252 uses a NESTED-decode
 *     pattern: outer `OlympusbibliotecaPopularResponse.data.popular_comics` is a JSON STRING
 *     containing another JSON-encoded array (line 230 second `jsonParser.decodeFromString`
 *     call). This is the same shape as sibling 348 InMangaRepository's parseChapters
 *     double-decode — Olympus and InManga share a "JSON-as-string-field" wire format
 *     hand-off pattern.
 *
 *   • LIVE-NOT-STALE — `coroutineScope { ... async { ... }.awaitAll() }` structured-
 *     concurrency block at lines 535-545 + `delay(100)` polite-pacing at line 548. Same
 *     pattern repeats in `fetchAllChaptersAsyncAdvanced` (lines 617-631) with `delay(200)`.
 *     Two delay values (100ms vs 200ms) for the two implementations — preserved as
 *     documented design choice rather than harmonized to one constant.
 *
 *   • LIVE-NOT-STALE — Spanish UI strings: `"Capítulo"` does NOT appear in this file (Olympus
 *     stores chapter names as numeric strings like "Capítulo 1.5" → `chapter.name` server-
 *     side, mapped 1:1 to `ChapterItem.number` at line 368). No baked-in Spanish UI labels.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 34 imports at lines 22-55 cover the largest dependency
 *     graph in cluster198+199 so far:
 *       co.touchlab.kermit.Logger (1)
 *       kotlin.{concurrent.Volatile, time.ExperimentalTime} (2)
 *       kotlinx.coroutines.{async, awaitAll, coroutineScope, delay, flow.{Flow, catch,
 *         combine, flow}} (8)
 *       io.ktor.{client.statement.bodyAsText, http.isSuccess} (2)
 *       kotlinx.{datetime.{LocalDate, LocalDateTime}, serialization.json.Json} (3)
 *       core.{states.State, storage.DataStoreHelper} (2)
 *       data.{local.dao.SourcesDao, remote.api.ApiClient} (2)
 *       domain.model.{ChapterItem, MangaInfo, MangaItem, PopularManga} (4)
 *       presentation.features.home.data.SearchType (1)
 *       sources_repositry.{BaseMangaRepository, data.MangaSource} (2)
 *       sources_repositry.es.olympusbiblioteca.models.{
 *         chapter_images.OlympusbibliotecaChapterImagesResponse,
 *         chapters.OlympusbibliotecaChaptersResponse,
 *         details.OlympusbibliotecaDetailsResponse,
 *         home.OlympusbibliotecaHomeResponse,
 *         popular.{OlympusbibliotecaPopularResponse, PopularComic},
 *         search.OlympusbibliotecaSearchResponse
 *       } (7)
 *     All targets confirmed-live at cluster199 boundary. The model imports cover 6 distinct
 *     response shapes from sub-packages — sibling 350's model tree is the deepest in :es/.
 *
 *   • COSMETIC-NOT-STALE — Excessive blank lines between class-level blocks (lines 419-421
 *     between companion object and fetchMangaChaptersF declaration; lines 580-581 between
 *     fetchChaptersForPage and fetchAllChaptersAsyncAdvanced; lines 660-662 between retry
 *     helper and closing brace). Migration-era auto-formatting artifacts — preserved per §253.
 *
 *   • COSMETIC-NOT-STALE — "Replace your existing fetchMangaChaptersF method with this
 *     updated version" comment at line 422 — author note suggesting this was a retrofit
 *     edit, not a clean implementation. Preserved as historical artifact.
 */

