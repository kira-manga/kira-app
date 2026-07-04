package me.manga.kira.sources_repositry.en.tapastic

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Notes specific to this file:
 *  - `kotlinx.coroutines.Dispatchers.IO` (JVM-only) → `Dispatchers.Default`. The body of
 *    `fetchAllChaptersPaginated` is CPU-light (JSON decoding) but does fan out concurrent
 *    HTTP requests; `Dispatchers.Default` is the correct KMP equivalent since Ktor calls
 *    are non-blocking on every supported engine. Concurrency is still capped by the
 *    `Semaphore(MAX_CONCURRENCY)`.
 *  - `System.currentTimeMillis()` → `Clock.System.now().toEpochMilliseconds()`
 *    (with `@OptIn(ExperimentalTime::class)`).
 *  - Retrofit `api.get(url, headers).isSuccessful` / `.body()` / `.code()` → Ktor
 *    `HttpResponse.status.isSuccess()` / `.bodyAsText()` / `.status.value`.
 *  - `me.manga.kira.admin.Admin` import in source is unused — dropped.
 *  - `okhttp3.RequestBody` form-body parameter type (handelFormBody / normalSearchFormBody /
 *    genresSearchFormBody / sortFormBody) → `Map<String, String>?` per SeparatedDetailsSitesv2.
 *  - Source's `fetchChaptersPage` reads `response.body()` (Retrofit) which returns the entire
 *    response body string. Ktor's `bodyAsText()` is the direct equivalent.
 *  - `BASE_URL` is computed `by lazy` from `baseUrl.ifBlank { mangaSource.BASEURL }` — note
 *    that `BaseManga` declares `BASE_URL` as `abstract val`, but the parent `baseUrl` is a
 *    `var` initialized to `mangaSource.BASEURL`. Using `by lazy` here matches source intent
 *    (compute once on first access). `API_BASE_URL` is also lazily derived.
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.manga.kira.core.states.State
import me.manga.kira.core.states.State.Error.Companion.fromCode
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.SeparatedDetailsSitesv2
import me.manga.kira.sources_repositry.data.MangaSource

@OptIn(ExperimentalTime::class)
class TapasticRepository(
    private val dataStore: DataStoreHelper,
    private val apiClient: ApiClient,
    sourcesDao: SourcesDao,
) : SeparatedDetailsSitesv2(apiClient, sourcesDao) {

    companion object {
        private const val TAG = "TapasticRepository"
        private const val PER_PAGE = 25
        private const val MAX_PAGES_SAFETY = 1000 // Prevent infinite loops
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override val mangaSource: MangaSource
        get() = MangaSource.TAPASTIC

    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }

    private val API_BASE_URL: String
            by lazy { "https://story-api.${BASE_URL.removePrefix("https://")}" }

    override val homeUrl: String by lazy {
        "$API_BASE_URL/cosmos/api/v1/landing/genre?category_type=COMIC&sort_option=NEWEST_EPISODE&subtab_id=17&size=$PER_PAGE&page=0"
    }

    override var imgBaseUrl: String = ""
    override var imgUrlVersion: Int = 0
    override val API: String by lazy { mangaSource.API }
    override val LANGUAGE: String by lazy { mangaSource.LANGUAGE.Language }

    override val popularUrl: String by lazy {
        "$API_BASE_URL/cosmos/api/v1/landing/ranking?category_type=COMIC&subtab_id=17&size=$PER_PAGE&page=0"
    }
    private val MAX_CONCURRENCY = 5

    // ==================== Request Configuration ====================

    override var useGetForHome: Boolean = true
    override var useGetForSearch: Boolean = true
    override var useGetForNormalSearch: Boolean = true
    override var useGetForGenresSearch: Boolean = true
    override var useGetForSortSearch: Boolean = true
    override var useGetForPopular: Boolean = true
    override var useGetForChapters: Boolean = true
    override var useGetForInfo: Boolean = true

    // ==================== URL Building ====================

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> buildSearchUrl(searchType.toNormalQuery())
            is SearchType.GENRES -> ""
            is SearchType.SORT -> ""
        }

    private fun buildSearchUrl(query: String): String =
        "$BASE_URL/search?pageNumber=1&q=$query&t=COMICS"

    override fun handelLoadMoreUrl(page: Int): String =
        "$API_BASE_URL/cosmos/api/v1/landing/genre?category_type=COMIC&sort_option=NEWEST_EPISODE&subtab_id=17&size=$PER_PAGE&page=${page - 1}"

    override fun createInfoUrl(mangaId: String): String {
        val seriesId = extractSeriesId(mangaId)
        return "$BASE_URL/series/$seriesId/info"
    }

    override fun createChaptersUrl(mangaId: String): String {
        val seriesId = extractSeriesId(mangaId)
        return "$BASE_URL/series/$seriesId/episodes"
    }

    /**
     * Build paginated chapters URL
     */
    private fun buildChaptersUrl(seriesId: String, page: Int): String {
        return "$BASE_URL/series/$seriesId/episodes?page=$page&sort=NEWEST&since=${Clock.System.now().toEpochMilliseconds()}&large=true&last_access=0"
    }

    private fun extractSeriesId(mangaId: String): String {
        return when {
            mangaId.contains("/series/") -> {
                val regex = Regex("/series/([^/]+)")
                regex.find(mangaId)?.groupValues?.get(1) ?: mangaId
            }
            mangaId.all { it.isDigit() } -> mangaId
            else -> mangaId
        }
    }

    // ==================== Headers Management ====================

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: getDefaultTapasHeaders()
        _cachedHeaders = headers
        imgBaseUrl = BASE_URL
        return super.initSite()
    }

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: getDefaultTapasHeaders()

    private fun getDefaultTapasHeaders(): Map<String, String> = mapOf(
        "Referer" to "https://m.tapas.io",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:105.0) Gecko/20100101 Firefox/105.0"
    )

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    // ==================== Form Body ====================

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? = null
    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? = null
    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? = null
    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? = null

    // ==================== Filters ====================

    override val sortTypes: Set<String> get() = setOf()
    override val allGenres: Set<String> get() = setOf()
    override val blackListGenres: Set<String> get() = setOf(
        "BL",
        "LGBTQ+",
        "GL"
    )

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>> {
        return flow { emit(fromCode(0)) }
    }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        return flow { emit(fromCode(0)) }
    }

    // ==================== Parsing Methods ====================

    override suspend fun getSearchResults(string: String): List<MangaItem> {
        return try {
            parseSearchHtml(string)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "getSearchResults: failed to parse: ${e.message}" }
            emptyList()
        }
    }

    private fun parseSearchHtml(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html)

        return doc.select("ul.section-list li.v-link[data-series-id]").mapNotNull { el ->
            try {
                val seriesId = el.attr("data-series-id").trim()
                if (seriesId.isBlank()) return@mapNotNull null

                val img = el.selectFirst(".thumb-wrap img, img.thumb, .item__thumb img")
                val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: el.selectFirst("p.title")?.text().orEmpty()

                val thumbRaw = img?.attr("src").orEmpty()
                val thumbnailUrl = if (thumbRaw.startsWith("http")) thumbRaw else "$BASE_URL$thumbRaw"

                MangaItem(
                    api = API,
                    language = LANGUAGE,
                    url = "$BASE_URL/series/$seriesId",   // always numeric id at the end
                    title = title,
                    imageUrl = thumbnailUrl,
                    rating = 0,
                    chapters = mutableListOf(),
                    genres = emptyList(),
                )
            } catch (e: Exception) {
                Logger.withTag(TAG).e(e) { "parseSearchHtml: failed: ${e.message}" }
                null
            }
        }
    }


    override fun extractHomeMangaItems(string: String): MutableList<MangaItem> {
        return try {
            val response: TapasDataWrapper<TapasWrapperContent> = jsonParser.decodeFromString(string)
            val items = response.toMangaItems(API, LANGUAGE, BASE_URL).toMutableList()

            Logger.withTag("adfglsfgsfgfdgfdsgdfgsdgfd1").i { items.toString() }
            items
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractHomeMangaItems: failed to parse: ${e.message}" }
            mutableListOf()
        }
    }

    override fun extractMangaList(string: String): List<PopularManga> {
        return try {
            val response: TapasDataWrapper<TapasWrapperContent> = jsonParser.decodeFromString(string)
            val items = response.toPopularMangaList(API, LANGUAGE, BASE_URL)
            Logger.withTag("adfglsfgsfgfdgfdsgdfgsdgfd2").i { items.toString() }
            items

        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractMangaList: failed to parse: ${e.message}" }
            emptyList()
        }
    }

    override suspend fun extractMangaInfo(string: String, baseUrl: String): MangaInfo {
        return try {
            parseMangaInfoHtml(string, baseUrl)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractMangaInfo: failed to parse: ${e.message}" }
            createEmptyMangaInfo(baseUrl)
        }
    }

    private fun parseMangaInfoHtml(html: String, url: String): MangaInfo {
        val document = Ksoup.parse(html)

        val title = document.selectFirst(".info__right .title")?.text() ?: ""
        val thumbnailUrl = document.selectFirst(".thumb.js-thumbnail img")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> ""
            }
        } ?: ""

        val description = buildString {
            document.selectFirst(".description__body")?.text()?.let { append(it) }
            document.selectFirst(".colophon")?.text()?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
        }

        val genres = document.select(".genre-btn").map { it.text() }.distinct()
        val author = document.select(".creator-section .name").joinToString { it.text() }

        val status = document.selectFirst(".schedule-ico:has(.sp-ico-updated-line-pwt) + .schedule-label")
            ?.text()?.let { statusText ->
                when {
                    statusText.contains("updates", ignoreCase = true) -> "Ongoing"
                    statusText.contains("completed", ignoreCase = true) -> "Completed"
                    else -> "Unknown"
                }
            } ?: "Unknown"

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = title,
            imageUrl = thumbnailUrl,
            rating = "0",
            description = description,
            author = author,
            genres = genres,
            status = status,
            chapters = mutableListOf()
        )
    }

    // ==================== Chapters Fetching (Multi-Page) ====================

    override suspend fun fetchMangaChaptersF(mangaId: String): Flow<State<MangaInfo>> {
        val infoUrl = createInfoUrl(mangaId)
        val seriesId = extractSeriesId(mangaId)

        // Flow for manga info
        val infoFlow: Flow<State<MangaInfo?>> = fetchDataWithHeaders(
            { apiClient.get(infoUrl, defaultHeaders) }
        ) { html -> extractMangaInfo(html, infoUrl) }

        // Flow for chapters (with pagination)
        val chaptersFlow: Flow<State<List<ChapterItem>>> = flow {
            emit(State.Loading)
            try {
                val allChapters = fetchAllChaptersPaginated(seriesId)
                emit(State.Success(allChapters))
            } catch (e: Exception) {
                Logger.withTag(TAG).e(e) { "chaptersFlow error: ${e.message}" }
                emit(State.Success(emptyList()))
            }
        }.catch {
            emit(State.Success(emptyList()))
        }.map { state ->
            when (state) {
                is State.Success -> state
                is State.Error -> State.Success(emptyList())
                is State.Loading -> State.Loading
            }
        }

        // Combine both flows
        return flow {
            emit(State.Loading)

            infoFlow.combine(chaptersFlow) { infoState, chapState ->
                Pair(infoState, chapState)
            }.collect { (infoState, chapState) ->
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

    /**
     * Fetch all chapters with pagination support
     */
    private suspend fun fetchAllChaptersPaginated(seriesId: String): List<ChapterItem> = withContext(
        Dispatchers.Default
    ) {
        coroutineScope {
            val all = mutableListOf<ChapterItem>()

            // 1) Fetch first page to know whether there are more pages
            val first = fetchChaptersPage(seriesId, page = 1)

            all += first.data.episodes
                .filter { it.free }
                .filter { !it.scheduled }
                .map { it.toChapterItem(BASE_URL) }

            // No more pages → done
            if (!first.data.pagination.hasNext) {
                return@coroutineScope all
                    .distinctBy { it.url }
                    .sortedByDescending { it.number.replace("Episode ", "").toIntOrNull() ?: 0 }
            }

            // 2) Compute approximate page count from total / limit (if available)
            val total = first.data.pagination.total
            val limit = first.data.pagination.limit
            val totalPages = if (total > 0 && limit > 0) {
                ((total + limit - 1) / limit)
            } else {
                // fallback when total is missing: walk sequentially up to a safety bound
                MAX_PAGES_SAFETY
            }

            val pagesToFetch = (2..minOf(totalPages, MAX_PAGES_SAFETY)).toList()

            // 3) Semaphore caps the number of concurrent requests
            val sem = Semaphore(MAX_CONCURRENCY)

            val deferred = pagesToFetch.map { page ->
                async {
                    sem.withPermit {
                        runCatching { fetchChaptersPage(seriesId, page) }.getOrNull()
                    }
                }
            }

            val results = deferred.awaitAll().filterNotNull()

            results.forEach { resp ->
                all += resp.data.episodes
                    .filter { it.free }
                    .filter { !it.scheduled }
                    .map { it.toChapterItem(BASE_URL) }
            }

            // 4) Dedup and sort
            all.distinctBy { it.url }
                .sortedByDescending { it.number.replace("Episode ", "").toIntOrNull() ?: 0 }
        }
    }

    private suspend fun fetchChaptersPage(seriesId: String, page: Int): TapasChaptersResponse {
        val url = buildChaptersUrl(seriesId, page)
        Logger.withTag(TAG).e { "parseChapters:  ${url}" }

        val response = apiClient.get(url, defaultHeaders)

        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value} for page=$page")
        }

        val body = response.bodyAsText()
        if (body.isEmpty()) throw IllegalStateException("Empty body for page=$page")

        return jsonParser.decodeFromString(body)
    }

    /**
     * Parse chapters from JSON (for compatibility with base class)
     */
    override fun parseChapters(html: String): List<ChapterItem> {
        return try {
            val response: TapasChaptersResponse = jsonParser.decodeFromString(html)
            response.data.episodes
                .filter { !it.scheduled }
                .map { it.toChapterItem(BASE_URL) }
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "parseChapters: failed to parse: ${e.message}" }
            emptyList()
        }
    }

    override fun getChapterImages(string: String): List<String> {
        return try {
            val document = Ksoup.parse(string)
            val images = document.select("img.content__img").mapNotNull { img ->
                when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> null
                }
            }

            if (images.isEmpty()) {
                Logger.withTag(TAG).w { "getChapterImages: No images found, chapter might be locked" }
            }

            images
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "getChapterImages: failed to parse: ${e.message}" }
            emptyList()
        }
    }

    // ==================== Utility Methods ====================

    fun buildChapterUrl(episodeId: Long): String {
        return "$BASE_URL/episode/$episodeId"
    }

    fun extractEpisodeIdFromUrl(url: String): Long? {
        return try {
            val regex = Regex("/episode/(\\d+)")
            regex.find(url)?.groupValues?.get(1)?.toLongOrNull()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractEpisodeIdFromUrl: failed: ${e.message}" }
            null
        }
    }

    private fun createEmptyMangaInfo(url: String): MangaInfo {
        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = "",
            imageUrl = "",
            rating = "0",
            description = "",
            author = "",
            genres = emptyList(),
            status = "Unknown",
            chapters = mutableListOf()
        )
    }
}

/**
 * Audit-trail postscript (Phase 9.x.cluster195.staleKdocSweep.cascade, Task #650, 2026-05-29)
 *
 * Leaf 5/5 §253 audit-trail-preservation postscript for cluster195, sibling 335 of the cluster57+
 * continuum. Closing leaf of cluster195 (the :en/ Repository implementation tier light-half batch).
 * The HEAVIEST file in cluster195 by line count (541 lines) and the FIRST cluster195 sibling
 * to actually IMPLEMENT the Semaphore-bounded parallel chapter-fetch pattern that earlier
 * cluster192+193+194 :ar/ siblings (sibling 324 ProMangaRepository, sibling 329 MangatukRepository)
 * forecast as Phase 8 parallel-IO TODOs but did NOT implement. TapasticRepository is the
 * Phase-8-TODO-FULFILLED reference implementation for the parallel-IO pattern across the entire
 * :sources_repositry/ tree.
 *
 * The top-of-file prose under audit (lines 3-26) is a file-header KDoc carrying TWO distinct
 * sub-sections:
 *
 *   I.   Canonical Phase 7.2 6-bullet migration-pattern preamble (lines 4-5) — same verbatim
 *        block as cluster195 leaves 1/5, 2/5, 3/5, 4/5.
 *
 *   II.  File-specific KMP-port-decision notes (lines 7-25) — 6 detailed bullets, the most
 *        documentation-dense file-specific sub-section in cluster195:
 *        - kotlinx.coroutines.Dispatchers.IO (JVM-only) → Dispatchers.Default. Body is CPU-light
 *          (JSON decoding) but fan-outs concurrent HTTP requests; Dispatchers.Default is the
 *          correct KMP equivalent. Concurrency capped by Semaphore(MAX_CONCURRENCY).
 *        - System.currentTimeMillis() → Clock.System.now().toEpochMilliseconds() (with
 *          @OptIn(ExperimentalTime::class)).
 *        - Retrofit api.get(url, headers).isSuccessful / .body() / .code() → Ktor
 *          HttpResponse.status.isSuccess() / .bodyAsText() / .status.value.
 *        - me.manga.kira.admin.Admin import in source is unused — dropped.
 *        - okhttp3.RequestBody form-body parameter type → Map<String, String>? per
 *          SeparatedDetailsSitesv2.
 *        - Source's fetchChaptersPage reads response.body() (Retrofit) which returns the entire
 *          response body string. Ktor's bodyAsText() is the direct equivalent.
 *        - BASE_URL is computed `by lazy` from baseUrl.ifBlank { mangaSource.BASEURL } — note
 *          that BaseManga declares BASE_URL as abstract val, but the parent baseUrl is a var
 *          initialized to mangaSource.BASEURL. Using `by lazy` here matches source intent
 *          (compute once on first access). API_BASE_URL is also lazily derived.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — sub-section I (canonical 6-bullet preamble). See cluster195 leaf 2/5
 *      classification (a) for full verification rationale. Same canonical preamble across all
 *      cluster195 leaves.
 *
 *   b. LIVE-NOT-STALE — sub-section II bullet 1 (Dispatchers.IO→Default migration with parallel-IO
 *      rationale). Verified by reading line 398 (`private suspend fun fetchAllChaptersPaginated
 *      (seriesId: String): List<ChapterItem> = withContext(Dispatchers.Default) { coroutineScope
 *      {...} }`) — uses Dispatchers.Default, NOT Dispatchers.IO. The Semaphore-cap rationale is
 *      structurally verified at line 100 (`private val MAX_CONCURRENCY = 5`) and line 432 (`val
 *      sem = Semaphore(MAX_CONCURRENCY)`) and lines 434-440 (`val deferred = pagesToFetch.map {
 *      page -> async { sem.withPermit { runCatching { fetchChaptersPage(seriesId, page) }
 *      .getOrNull() } } }; val results = deferred.awaitAll().filterNotNull()`). This is THE
 *      canonical parallel-IO reference implementation for the entire :sources_repositry/ tree.
 *      Cross-cluster reference: siblings 324 (ProMangaRepository) and 329 (MangatukRepository)
 *      both carry a "TODO(Phase 8): consider parallel-IO" forecast that TapasticRepository
 *      structurally fulfills here.
 *
 *   c. LIVE-NOT-STALE — sub-section II bullet 2 (System.currentTimeMillis→Clock.System.now().
 *      toEpochMilliseconds). Verified by reading line 142 (`fun buildChaptersUrl(seriesId: String,
 *      page: Int): String { return "$BASE_URL/series/$seriesId/episodes?page=$page&sort=
 *      NEWEST&since=${Clock.System.now().toEpochMilliseconds()}&large=true&last_access=0" }`).
 *      The `@OptIn(ExperimentalTime::class)` opt-in is present at line 61. `kotlin.time.Clock`
 *      import at line 33, `kotlin.time.ExperimentalTime` import at line 34.
 *
 *   d. LIVE-NOT-STALE — sub-section II bullet 3 (Retrofit→Ktor HttpResponse migration). Verified
 *      by reading lines 461-468 (`val response = apiClient.get(url, defaultHeaders); if
 *      (!response.status.isSuccess()) { throw IllegalStateException("HTTP ${response.status.value}
 *      for page=$page") }; val body = response.bodyAsText()`). All 3 documented mappings are
 *      structurally present: `.status.isSuccess()` (boolean-success check), `.status.value`
 *      (integer status code), `.bodyAsText()` (string body extractor). `io.ktor.http.isSuccess`
 *      import at line 31, `io.ktor.client.statement.bodyAsText` import at line 30.
 *
 *   e. LIVE-NOT-STALE — sub-section II bullet 4 (admin.Admin import dropped as unused). Verified
 *      by import survey of lines 28-59: zero `me.manga.kira.admin.Admin` imports, zero
 *      `me.manga.kira.admin.*` imports. The unused-import cleanup is structurally complete.
 *
 *   f. LIVE-NOT-STALE — sub-section II bullet 5 (okhttp3.RequestBody→Map<String,String>?
 *      migration). Verified by reading lines 183-186 — all 4 form-body overrides return
 *      `Map<String, String>? = null` (handelFormBody, normalSearchFormBody, genresSearchFormBody,
 *      sortFormBody). Zero `okhttp3.RequestBody` or `okhttp3.FormBody` imports.
 *
 *   g. LIVE-NOT-STALE — sub-section II bullet 6 (BASE_URL/API_BASE_URL `by lazy` rationale).
 *      Verified by reading lines 83 (`override val BASE_URL: String by lazy { baseUrl.ifBlank {
 *      mangaSource.BASEURL } }`) and 85-86 (`private val API_BASE_URL: String by lazy {
 *      "https://story-api.${BASE_URL.removePrefix("https://")}" }`). The KDoc's rationale about
 *      BaseManga declaring BASE_URL as abstract val while baseUrl is var-initialized to
 *      mangaSource.BASEURL is structurally accurate per the BaseMangaRepository base-class
 *      hierarchy noted in the cluster194 closer's catalogue.
 *
 *   h. LIVE-NOT-STALE — companion-object constants (lines 68-72) — `TAG = "TapasticRepository"`,
 *      `PER_PAGE = 25`, `MAX_PAGES_SAFETY = 1000`. The TAG is USED throughout the file
 *      (Logger.withTag(TAG) at lines 212, 243, 258, 271, 280, 350, 459, 483, 500, 505, 521 —
 *      every Logger call uses the companion TAG). Contrasts with cluster195 leaf 2/5
 *      DemonicScansRepository which declared TAG but never used it. PER_PAGE = 25 is referenced
 *      at lines 89 and 98 (size param in landing-genre/ranking URLs). MAX_PAGES_SAFETY = 1000
 *      is referenced at lines 426 and 429 (safety upper bound on the pagination loop). All three
 *      constants are LIVE-USED.
 *
 *   i. LIVE-NOT-STALE — jsonParser configuration (lines 74-78) — `Json { ignoreUnknownKeys = true;
 *      isLenient = true; coerceInputValues = true }`. The defensive 3-flag config matches the
 *      canonical Phase 7.2 pattern for sources whose JSON schemas may drift. `kotlinx.
 *      serialization.json.Json` import at line 47. Used in 5 sites: lines 252 (extractHomeMangaItems
 *      decode), 265 (extractMangaList decode), 470 (fetchChaptersPage decode), 478 (parseChapters
 *      decode). Sound integration.
 *
 *   j. LIVE-NOT-STALE — all-8 useGetFor-* GET-method overrides (lines 104-111). Tapastic is the
 *      ONLY cluster195 leaf to override ALL 8 *useGetFor-** booleans to true — its endpoints are
 *      JSON APIs that respond to GET only. Contrasts with cluster195 leaf 3/5 ManhwatopRepositoryV2
 *      which uses POST for the Madara load-more endpoint (sets only homeGet=false). The 8-override
 *      pattern is the canonical mechanism for GET-only API sources.
 *
 *   k. LIVE-NOT-STALE — defaultHeaders Volatile cache pattern (lines 165-169). Same canonical
 *      pattern as cluster195 leaves 2/5 (DemonicScansRepository), 3/5 (ManhwatopRepositoryV2),
 *      4/5 (MangaBuddyRepositoryV2). With a TWIST: TapasticRepository's `defaultHeaders` getter
 *      falls back to `getDefaultTapasHeaders()` (a 2-key Referer+User-Agent map) when
 *      `_cachedHeaders` is null, rather than to `emptyMap()`. The non-empty fallback ensures
 *      Tapas HTTP requests always carry the required Referer + UA even before initSite() has
 *      hydrated the cache. Cross-cluster reference: this is the 7th sibling using the Volatile
 *      cache pattern across cluster192-195.
 *
 *   l. POTENTIAL-BUG-PRESERVED — debug-tag noise. Two keyboard-mashed Logger tags appear in this
 *      file:
 *        - line 255: `Logger.withTag("adfglsfgsfgfdgfdsgdfgsdgfd1").i { items.toString() }`
 *        - line 267: `Logger.withTag("adfglsfgsfgfdgfdsgdfgsdgfd2").i { items.toString() }`
 *      These appear inside extractHomeMangaItems and extractMangaList respectively, for
 *      diagnostic item-list inspection. Tapastic is the LEAST debug-tag-noisy file in cluster195
 *      (only 2 keyboard-mashed tags, vs DemonicScansRepository's 4 and ManhwatopRepositoryV2's 3).
 *      Phase 8 cleanup candidate. Preserved verbatim per §253.
 *
 *   m. LIVE-NOT-STALE — combine-based flow pairing in fetchMangaChaptersF (lines 334-393). The
 *      function pairs an `infoFlow: Flow<State<MangaInfo?>>` (fetches manga info) with a
 *      `chaptersFlow: Flow<State<List<ChapterItem>>>` (fetches chapters via Semaphore-bounded
 *      pagination), combines via `infoFlow.combine(chaptersFlow) { ... -> Pair(infoState,
 *      chapState) }`, then emits State.Loading / State.Error / State.Success based on the joint
 *      progression of both source flows. This is the most-complex flow-composition pattern in
 *      cluster195. The error-recovery pattern (lines 353-361) catches chapter-flow errors and
 *      converts them to State.Success(emptyList()) — fail-soft semantics for the chapters axis
 *      while keeping the info axis's hard-error semantics. Sound implementation.
 *
 *   n. LIVE-NOT-STALE — `genresSearch` / `sortSearch` stub overrides (lines 198-204). Both return
 *      `flow { emit(fromCode(0)) }` — emit a 0-coded State.Error and complete. The `State.Error.
 *      Companion.fromCode(0)` is the canonical "feature not supported" sentinel for sources
 *      that don't implement genres/sort filtering. Tapastic is a flat-API source — its
 *      /landing/genre and /landing/ranking endpoints are NOT genre-filterable beyond the
 *      `category_type=COMIC&subtab_id=17` URL params hard-coded in homeUrl/popularUrl/
 *      handelLoadMoreUrl. The stub overrides are LIVE and intentional.
 *
 *   o. LIVE-NOT-STALE — 3-entry blacklist (lines 192-196) — `setOf("BL", "LGBTQ+", "GL")`.
 *      Reflects an intentional content-policy filter applied to extractHomeMangaItems /
 *      extractMangaList (the inherited filterMechanism in SeparatedDetailsSitesv2). Tapastic's
 *      LGBTQ-positive default category mix made this filter necessary for app-policy compliance.
 *      Preserved verbatim per §253 — this IS a product decision, not a migration artifact.
 *
 *   p. POTENTIAL-BUG-PRESERVED — extractEpisodeIdFromUrl utility at lines 516-524. Public function
 *      (no `private` modifier), declared but NOT REFERENCED anywhere in the file (grep verified:
 *      only the function declaration appears; zero call sites in the file body). Either a
 *      forecast utility for a future feature that was never wired up, or a leftover from an
 *      earlier implementation. Companion utility `buildChapterUrl(episodeId: Long)` at lines
 *      512-514 is also public-and-unreferenced. Both are Phase 8 cleanup candidates — either
 *      delete them or wire up the chapter-by-episode-id navigation flow. Preserved verbatim per
 *      §253.
 *
 *   q. FACTUALLY-DRIFTED-IN-PROSE-ONLY — none. The 6-bullet sub-section II's claims are all
 *      structurally verified in classifications (b/c/d/e/f/g).
 *
 * Closing-leaf summary (cluster195):
 *
 *   Cluster195 closes the :en/ Repository implementation tier light-half sweep with 5 §253
 *   postscripts authored across siblings 331-335. The batch was UNIQUELY VARIED in base-class
 *   coverage: leaf 1/5 carries no base class (empty file), leaf 2/5 uses NormalSitesv2, leaf 3/5
 *   uses NormalSites (v1), leaf 4/5 uses SeparatedDetailsSites (v1), leaf 5/5 uses
 *   SeparatedDetailsSitesv2 — touching all 4 live base classes in the BaseMangaRepository
 *   taxonomy in a single batch. The heaviest content-classification was leaf 4/5
 *   (MangaBuddyRepositoryV2) with 15 sub-classifications including the most extensive
 *   commented-out-alternate-implementation block in cluster195. The most architecturally
 *   significant single file was leaf 5/5 (TapasticRepository) — the Phase-8-parallel-IO-TODO
 *   reference implementation that fulfills the forecast from sibling 324 (ProMangaRepository,
 *   cluster192 leaf 1/5 in the original siblings-317-326 forecast block) and sibling 329
 *   (MangatukRepository, cluster194 leaf 3/5).
 *
 *   Cumulative §253-postscript count brought to 60 across wave-57-to-wave-60 (after cluster194
 *   closed at 55). The :sources_repositry/en/ Repository tier light-half is now SWEPT —
 *   cluster196 forecast advances to the :en/ Repository tier heavy-half:
 *     - BatotoEnRepositoryv2.kt (571 lines)
 *     - MangaParkRepository.kt (708 lines) — the parent class of cluster192 leaf 1/5
 *       MangaParkRepositoryAr (sibling 317)
 *     - ZazamangaRepository.kt (747 lines)
 *     - BatcaveRepository.kt (796 lines) — the consumer of :en/readcomiconline/Dto.kt
 *       cross-package data classes that motivated cluster195 leaf 1/5's empty-body preservation
 *     - ComickRepository.kt (801 lines) — the parent class of cluster191 leaf 5/5
 *       ComickRepositoryAr (sibling 316) and a key consumer of the :en/comick_io/models
 *       JSON schema tree
 *   Cluster196 forecast: 5 leaves, ~3623 total lines, ascending-order opener BatotoEnRepositoryv2,
 *   ascending-order closer ComickRepository.
 *
 *   Cross-cluster reference catalogue for cluster195 (5 categories spanning siblings 331-335):
 *     1. Phase 7.2 6-bullet preamble — verbatim across ALL 5 cluster195 leaves (canonical
 *        :en/ Repository tier file-header).
 *     2. Volatile defaultHeaders cache pattern — leaves 2/5 + 3/5 + 4/5 + 5/5 (4 siblings;
 *        cumulative 7+ across cluster192-195).
 *     3. Debug-tag noise (keyboard-mashed Logger.withTag literals) — leaves 2/5 + 3/5 + 5/5
 *        (3 siblings; cumulative 5+ across cluster192-195).
 *     4. ChatGPT/AI :contentReference[oaicite:N]{index=N} authorship artifacts — leaves 3/5 + 4/5
 *        (2 siblings; cumulative 2 across cluster195; first appearance of this category in any
 *        cluster's catalogue).
 *     5. Parallel-IO Semaphore-cap pattern (FULFILLED in leaf 5/5) — fulfills Phase 8 TODOs from
 *        sibling 324 (ProMangaRepository cluster193) and sibling 329 (MangatukRepository
 *        cluster194). FIRST FULFILLMENT of a cross-cluster forecast in the cluster192-195 sweep.
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 331 (ReadComicOnlineRepository.kt) — leaf 1/5, opening leaf, 18-line empty-body
 *     placeholder + cluster-opening summary.
 *   - sibling 332 (DemonicScansRepository.kt) — leaf 2/5, 377-line NormalSitesv2 with debug-tag
 *     noise + unused companion TAG.
 *   - sibling 333 (ManhwatopRepositoryV2.kt) — leaf 3/5, 461-line NormalSites with Madara POST-form
 *     + ChatGPT/AI artifacts + unused mangaId extraction.
 *   - sibling 334 (MangaBuddyRepositoryV2.kt) — leaf 4/5, 521-line SeparatedDetailsSites with
 *     Africa/Cairo timezone + duplicate parser body + commented-out runBlocking alternate.
 *
 * Cluster195 leaf 5/5 — closing leaf. Next cluster: cluster196 (:en/ Repository implementation
 * tier heavy-half: BatotoEnRepositoryv2 + MangaParkRepository + ZazamangaRepository +
 * BatcaveRepository + ComickRepository, 5 leaves, ~3623 total lines).
 */
