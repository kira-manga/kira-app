package me.manga.yamiapk.sources_repositry.en.tapastic

import android.util.Log
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
import me.manga.yamiapk.admin.Admin
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.states.State.Error.Companion.fromCode
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.SeparatedDetailsSitesv2
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.RequestBody
import org.jsoup.Jsoup
import javax.inject.Inject

class TapasticRepository @Inject constructor(
    dataStore: DataStoreHelper,
    api: IMangaDataApiServices,
    sourcesDao: SourcesDao,
) : SeparatedDetailsSitesv2(dataStore, api, sourcesDao) {

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
            by lazy { "https://story-api.${BASE_URL.removePrefix("https://")}"}

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
        return "$BASE_URL/series/$seriesId/episodes?page=$page&sort=NEWEST&since=${System.currentTimeMillis()}&large=true&last_access=0"
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

    override fun handelFormBody(page: Int, popular: Boolean): RequestBody? = null
    override fun normalSearchFormBody(searchType: SearchType.Normal): RequestBody? = null
    override fun genresSearchFormBody(searchType: SearchType.GENRES): RequestBody? = null
    override fun sortFormBody(searchType: SearchType.SORT): RequestBody? = null

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

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        return try {
            parseSearchHtml(html)
        } catch (e: Exception) {
            Log.e(TAG, "getSearchResults: failed to parse: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseSearchHtml(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)

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
                    url = "$BASE_URL/series/$seriesId",   // ✅ always numeric id at the end
                    title = title,
                    imageUrl = thumbnailUrl,
                    rating = 0,
                    chapters = mutableListOf(),
                    genres = emptyList(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "parseSearchHtml: failed: ${e.message}", e)
                null
            }
        }
    }


    override fun extractHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val response: TapasDataWrapper<TapasWrapperContent> = jsonParser.decodeFromString(json)
            val items = response.toMangaItems(API, LANGUAGE, BASE_URL).toMutableList()

            Log.i("adfglsfgsfgfdgfdsgdfgsdgfd1",items.toString())
            items
        } catch (e: Exception) {
            Log.e(TAG, "extractHomeMangaItems: failed to parse: ${e.message}", e)
            mutableListOf()
        }
    }

    override fun extractMangaList(json: String): List<PopularManga> {
        return try {
            val response: TapasDataWrapper<TapasWrapperContent> = jsonParser.decodeFromString(json)
            val items =  response.toPopularMangaList(API, LANGUAGE, BASE_URL)
            Log.i("adfglsfgsfgfdgfdsgdfgsdgfd2",items.toString())
            items

        } catch (e: Exception) {
            Log.e(TAG, "extractMangaList: failed to parse: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun extractMangaInfo(html: String, url: String): MangaInfo {
        return try {
            parseMangaInfoHtml(html, url)
        } catch (e: Exception) {
            Log.e(TAG, "extractMangaInfo: failed to parse: ${e.message}", e)
            createEmptyMangaInfo(url)
        }
    }

    private fun parseMangaInfoHtml(html: String, url: String): MangaInfo {
        val document = Jsoup.parse(html)

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
            ratingCount = "0",
            description = description,
            otherNames = "",
            author = author,
            artist = author,
            genres = genres,
            tags = emptyList(),
            yearOfProduction = "",
            status = status,
            favoritesCount = "0",
            chapters = mutableListOf()
        )
    }

    // ==================== Chapters Fetching (Multi-Page) ====================

    override suspend fun fetchMangaChaptersF(mangaId: String): Flow<State<MangaInfo>> {
        val infoUrl = createInfoUrl(mangaId)
        val seriesId = extractSeriesId(mangaId)

        // Flow for manga info
        val infoFlow: Flow<State<MangaInfo?>> = fetchDataWithHeaders(
            { api.get(infoUrl, defaultHeaders) }
        ) { html -> extractMangaInfo(html, infoUrl) }

        // Flow for chapters (with pagination)
        val chaptersFlow: Flow<State<List<ChapterItem>>> = flow {
            emit(State.Loading)
            try {
                val allChapters = fetchAllChaptersPaginated(seriesId)
                emit(State.Success(allChapters))
            } catch (e: Exception) {
                Log.e(TAG, "chaptersFlow error: ${e.message}", e)
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
        Dispatchers.IO) {
        coroutineScope {
            val all = mutableListOf<ChapterItem>()

            // 1) هات أول صفحة عشان نعرف هل فيه صفحات تانية
            val first = fetchChaptersPage(seriesId, page = 1)

            all += first.data.episodes
                .filter { it.free }
                .filter { !it.scheduled }
                .map { it.toChapterItem(BASE_URL) }

            // لو مفيش صفحات تانية خلاص
            if (!first.data.pagination.hasNext) {
                return@coroutineScope all
                    .distinctBy { it.url }
                    .sortedByDescending { it.number.replace("Episode ", "").toIntOrNull() ?: 0 }
            }

            // 2) احسب عدد الصفحات "تقريبًا" بالـ total والـ limit (لو متاحين)
            val total = first.data.pagination.total
            val limit = first.data.pagination.limit
            val totalPages = if (total > 0 && limit > 0) {
                ((total + limit - 1) / limit)
            } else {
                // fallback لو total مش موجود: امشي sequential لكن هنا هنستخدم حد أمان
                MAX_PAGES_SAFETY
            }

            val pagesToFetch = (2..minOf(totalPages, MAX_PAGES_SAFETY)).toList()

            // 3) semaphore لتحديد عدد الـ requests المتوازية
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

            // 4) نظّف و sort
            all.distinctBy { it.url }
                .sortedByDescending { it.number.replace("Episode ", "").toIntOrNull() ?: 0 }
        }
    }
    private suspend fun fetchChaptersPage(seriesId: String, page: Int): TapasChaptersResponse {
        val url = buildChaptersUrl(seriesId, page)
        Log.e(TAG, "parseChapters:  ${url}")

        val response = api.get(url, defaultHeaders)

        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code()} for page=$page")
        }

        // مهم: استخدم string() مش toString()
        val body = response.body()
            ?: throw IllegalStateException("Empty body for page=$page")

        return jsonParser.decodeFromString(body)
    }
    /**
     * Parse chapters from JSON (for compatibility with base class)
     */
    override fun parseChapters(json: String): List<ChapterItem> {
        return try {
            val response: TapasChaptersResponse = jsonParser.decodeFromString(json)
            response.data.episodes
                .filter { !it.scheduled }
                .map { it.toChapterItem(BASE_URL) }
        } catch (e: Exception) {
            Log.e(TAG, "parseChapters: failed to parse: ${e.message}", e)
            emptyList()
        }
    }

    override fun getChapterImages(html: String): List<String> {
        return try {
            val document = Jsoup.parse(html)
            val images = document.select("img.content__img").mapNotNull { img ->
                when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> null
                }
            }

            if (images.isEmpty()) {
                Log.w(TAG, "getChapterImages: No images found, chapter might be locked")
            }

            images
        } catch (e: Exception) {
            Log.e(TAG, "getChapterImages: failed to parse: ${e.message}", e)
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
            Log.e(TAG, "extractEpisodeIdFromUrl: failed: ${e.message}", e)
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
            ratingCount = "0",
            description = "",
            otherNames = "",
            author = "",
            artist = "",
            genres = emptyList(),
            tags = emptyList(),
            yearOfProduction = "",
            status = "Unknown",
            favoritesCount = "0",
            chapters = mutableListOf()
        )
    }
}