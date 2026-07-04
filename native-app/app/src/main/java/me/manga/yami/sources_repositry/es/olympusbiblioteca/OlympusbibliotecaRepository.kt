package me.manga.yamiapk.sources_repositry.es.olympusbiblioteca


import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.chapter_images.OlympusbibliotecaChapterImagesResponse
import me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.chapters.OlympusbibliotecaChaptersResponse
import me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.details.OlympusbibliotecaDetailsResponse
import me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.home.OlympusbibliotecaHomeResponse
import me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.popular.OlympusbibliotecaPopularResponse
import me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.popular.PopularComic
import me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.search.OlympusbibliotecaSearchResponse
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

open class OlympusbibliotecaRepository @Inject constructor(
    private val api: IMangaDataApiServices,
    private val dataStore: DataStoreHelper,
    private val sourcesRepository: SourcesDao,
) : BaseMangaRepository() {

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        } }

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

    override fun buildItemsImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int
    ): ImageRequest {
        val coilHeaders = NetworkHeaders.Builder()
            .apply { defaultHeaders.forEach(::add) }
            .build()

        return ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(coilHeaders)
            .crossfade(true)
            .build()
    }

    override fun buildImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int
    ): ImageRequest {
        val coilHeaders = NetworkHeaders.Builder()
            .apply { defaultHeaders.forEach(::add) }
            .build()

        return ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(coilHeaders)
            .apply {
                if (screenWidthPx != 0) {
                    size(Dimension.Pixels(screenWidthPx), Dimension.Undefined)
                }
            }
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(true)
            .build()
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
            emit(State.Error(0, e.localizedMessage ?: "Unknown error occurred"))
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
                Log.w("OlympusRepository", "popular_comics field is null or empty")
                return mutableListOf()
            }
            val popularComics: List<PopularComic> = jsonParser.decodeFromString(popularComicsJson)
            Log.i("OlympusRepository", "Parsed ${popularComics.size} popular comics")

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
            Log.e("OlympusRepository", "Error parsing popular comics: ${e.message}", e)
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
            Log.e("OlympusRepository", "Error parsing home items: ${e.message}", e)
            mutableListOf()
        }
    }

    fun extractSearchMangaItems(json: String): List<me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.search.Data> {
        return try {
            val response: OlympusbibliotecaSearchResponse = jsonParser.decodeFromString(json)
            response.data?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Log.e("OlympusRepository", "Error parsing search items: ${e.message}", e)
            emptyList()
        }
    }

    fun extractMangaInfo(json: String): me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.details.Data? {
        return try {
            val response: OlympusbibliotecaDetailsResponse = jsonParser.decodeFromString(json)
            response.data
        } catch (e: Exception) {
            Log.e("OlympusRepository", "Error parsing manga info: ${e.message}", e)
            null
        }
    }

    fun extractMangaChapters(json: String): List<me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.chapters.Data> {
        return try {
            val response: OlympusbibliotecaChaptersResponse = jsonParser.decodeFromString(json)
            response.data?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Log.e("OlympusRepository", "Error parsing chapters: ${e.message}", e)
            emptyList()
        }
    }

    fun extractChapterImages(json: String): List<String> {
        return try {
            val response: OlympusbibliotecaChapterImagesResponse = jsonParser.decodeFromString(json)
            response.chapter?.pages?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Log.e("OlympusRepository", "Error parsing chapter images: ${e.message}", e)
            emptyList()
        }
    }

    // Extension methods

    private fun List<me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.search.Data>.toMangaItems(
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

    private fun me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.details.Data.toMangaInfo(
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
            ratingCount = like_count?.toString() ?: "0",
            description = summary.orEmpty(),
            otherNames = "",
            author = "",
            artist = "",
            genres = genres?.mapNotNull { it?.name } ?: emptyList(),
            tags = emptyList(),
            yearOfProduction = "",
            status = status?.name.orEmpty(),
            favoritesCount = bookmark_count?.toString() ?: "0",
            chapters = mutableListOf()
        )
    }

    private fun List<me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.models.chapters.Data>.toChapterItems(
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
                            LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
                        } else {
                            LocalDate.parse(dateStr, DateTimeFormatter.ISO_DATE)
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
            val response = api.getData(url)
            if (response.isSuccessful) {
                val htmlContent = response.body().orEmpty()
                val parsedData = transform(htmlContent)
                emit(State.Success(parsedData))
            } else {
                val errorMessage = response.errorBody()?.string().orEmpty()
                    .ifEmpty { "Unexpected error" }
                emit(State.Error(0, errorMessage))
            }
        } catch (e: Exception) {
            emit(State.Error(0, e.localizedMessage ?: "Unknown error occurred"))
        }
    }

    companion object {
        private const val TAG = "OlympusRepository"
    }










    // Replace your existing fetchMangaChaptersF method with this updated version
    override suspend fun fetchMangaChaptersF(url: String): Flow<State<MangaInfo>> {
        Log.i("OlympusRepository", "Fetching manga info for: $url")

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

        Log.i("OlympusRepository", "Extracted slug: $slug")

        val infoUrl = "${apiUrl}series/$slug/"

        Log.i("OlympusRepository", "Info URL: $infoUrl")

        val infoFlow: Flow<State<MangaInfo?>> = fetchData(infoUrl) { html ->
            Log.i("OlympusRepository", "Info response: ${html.take(200)}...")
            extractMangaInfo(html)?.toMangaInfo(API, LANGUAGE, url)
        }

        val chaptersFlow: Flow<State<List<ChapterItem>>> = flow {
            emit(State.Loading)
            try {
                val allChapters = fetchAllChaptersAsync(slug)
                emit(State.Success(allChapters))
            } catch (e: Exception) {
                Log.e("OlympusRepository", "Error fetching all chapters: ${e.message}", e)
                emit(State.Error(0, e.localizedMessage ?: "Failed to fetch chapters"))
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
    private suspend fun fetchAllChaptersAsync(slug: String): List<ChapterItem> = withContext(Dispatchers.IO) {
        Log.i("OlympusRepository", "Starting async fetch for all chapters of: $slug")

        // First, fetch the first page to get total pages info
        val firstPageUrl = "${apiUrl}series/$slug/chapters?page=1&direction=desc&type=comic"
        val response = api.getData(firstPageUrl)

        if (!response.isSuccessful) {
            Log.e("OlympusRepository", "Failed to fetch first page: ${response.code()}")
            return@withContext emptyList()
        }

        val firstPageJson = response.body().orEmpty()
        val firstPageResponse: OlympusbibliotecaChaptersResponse = try {
            jsonParser.decodeFromString(firstPageJson)
        } catch (e: Exception) {
            Log.e("OlympusRepository", "Error parsing first page: ${e.message}", e)
            return@withContext emptyList()
        }

        val totalPages = firstPageResponse.meta?.last_page ?: 1
        val firstPageChapters = firstPageResponse.data?.filterNotNull()?.toChapterItems(slug) ?: emptyList()

        Log.i("OlympusRepository", "Total pages to fetch: $totalPages")

        if (totalPages <= 1) {
            Log.i("OlympusRepository", "Only one page, returning ${firstPageChapters.size} chapters")
            return@withContext firstPageChapters
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
            kotlinx.coroutines.delay(100)
        }

        Log.i("OlympusRepository", "Fetched total of ${allChaptersList.size} chapters across $totalPages pages")
        return@withContext allChaptersList
    }

    // Helper method to fetch chapters for a specific page
    private suspend fun fetchChaptersForPage(slug: String, page: Int): List<ChapterItem> = withContext(Dispatchers.IO) {
        try {
            val pageUrl = "${apiUrl}series/$slug/chapters?page=$page&direction=desc&type=comic"
            Log.d("OlympusRepository", "Fetching page $page: $pageUrl")

            val response = api.getData(pageUrl)

            if (!response.isSuccessful) {
                Log.w("OlympusRepository", "Failed to fetch page $page: ${response.code()}")
                return@withContext emptyList()
            }

            val jsonContent = response.body().orEmpty()
            val chaptersResponse: OlympusbibliotecaChaptersResponse = jsonParser.decodeFromString(jsonContent)
            val chapters = chaptersResponse.data?.filterNotNull()?.toChapterItems(slug) ?: emptyList()

            Log.d("OlympusRepository", "Page $page: fetched ${chapters.size} chapters")
            return@withContext chapters

        } catch (e: Exception) {
            Log.e("OlympusRepository", "Error fetching page $page: ${e.message}", e)
            return@withContext emptyList()
        }
    }

    // Alternative method with configurable concurrency and retry logic
    private suspend fun fetchAllChaptersAsyncAdvanced(
        slug: String,
        maxConcurrentRequests: Int = 3,
        maxRetries: Int = 2
    ): List<ChapterItem> = withContext(Dispatchers.IO) {
        Log.i("OlympusRepository", "Starting advanced async fetch for all chapters of: $slug")

        // Fetch first page to get metadata
        val firstPageResult = fetchChaptersForPageWithRetry(slug, 1, maxRetries)
        val firstPageUrl = "${apiUrl}series/$slug/chapters?page=1&direction=desc&type=comic"

        // We need to get the total pages from the API response
        val response = api.getData(firstPageUrl)
        if (!response.isSuccessful) {
            return@withContext firstPageResult
        }

        val totalPages = try {
            val chaptersResponse: OlympusbibliotecaChaptersResponse = jsonParser.decodeFromString(response.body().orEmpty())
            chaptersResponse.meta?.last_page ?: 1
        } catch (e: Exception) {
            Log.e("OlympusRepository", "Error getting total pages: ${e.message}", e)
            return@withContext firstPageResult
        }

        if (totalPages <= 1) {
            return@withContext firstPageResult
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
            kotlinx.coroutines.delay(200)
        }

        Log.i("OlympusRepository", "Advanced fetch completed: ${allChapters.size} chapters from $totalPages pages")
        return@withContext allChapters
    }

    // Helper method with retry logic
    private suspend fun fetchChaptersForPageWithRetry(
        slug: String,
        page: Int,
        maxRetries: Int = 2
    ): List<ChapterItem> = withContext(Dispatchers.IO) {
        repeat(maxRetries + 1) { attempt ->
            try {
                val result = fetchChaptersForPage(slug, page)
                if (result.isNotEmpty() || attempt == maxRetries) {
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w("OlympusRepository", "Attempt ${attempt + 1} failed for page $page: ${e.message}")
                if (attempt < maxRetries) {
                    delay(1000) // Exponential backoff
                } else {
                    Log.e("OlympusRepository", "All retries exhausted for page $page")
                }
            }
        }
        return@withContext emptyList()
    }







}