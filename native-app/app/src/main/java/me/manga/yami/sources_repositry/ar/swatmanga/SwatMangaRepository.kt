package me.manga.yamiapk.sources_repositry.ar.swatmanga

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
// Updated imports for new data classes
import me.manga.yamiapk.sources_repositry.ar.swatmanga.models.home.*
import me.manga.yamiapk.sources_repositry.ar.swatmanga.models.popular.*
import me.manga.yamiapk.sources_repositry.ar.swatmanga.models.details.*
import me.manga.yamiapk.sources_repositry.ar.swatmanga.models.chapters.*
import me.manga.yamiapk.sources_repositry.ar.swatmanga.models.chapters_images.*
import me.manga.yamiapk.sources_repositry.ar.swatmanga.models.search.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

open class SwatMangaRepository @Inject constructor(
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

    open val language: String = "ar"

    override val BASE_URL: String
        get() = MangaSource.SWATMANGA.BASEURL

    override val URL_VERSION: Int
        get() = 0

    override var baseUrl: String = ""

    override val API: String
        get() = MangaSource.SWATMANGA.API

    override val LANGUAGE: String
        get() = MangaSource.SWATMANGA.LANGUAGE.Language

    override val ICON: Int
        get() = MangaSource.SWATMANGA.ICON

    override val PRIORITY = MangaSource.SWATMANGA.PRIORITY

    private val defaultDomain = "appswat.com"
    private val apiUrl = "https://$defaultDomain/v2/api/v1/"
    override var imgBaseUrl: String = "https://$defaultDomain"
    override var imgUrlVersion: Int = 0

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override suspend fun getBaseUrl(): String {
        return apiUrl
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
            .data(processImageUrl(url))
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

    private fun processImageUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "$imgBaseUrl$url"
            else -> url
        }
    }

    override val blackListGenres: Set<String>
        get() = setOf(

        )

    override val sortTypes: Set<String>
        get() = setOf(
            "العنوان",
            "الأعلى تقييماً",
            "الأكثر متابعة",
            "الأكثر مشاهدة",
            "أكثر الفصول" ,
            "الأحدث",

        )

    val sortMap: LinkedHashMap<String, String> = linkedMapOf(
        "العنوان"           to "title",
        "الأعلى تقييماً"    to "-rating",
        "الأكثر متابعة"     to "-followers_count",
        "الأكثر مشاهدة"     to "-views_count",
        "أكثر الفصول"       to "-chapters_count",
        "الأحدث"            to "-created_at"
    )
    override val allGenres: Set<String>
        get() = setOf(

        )

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchChapterPages(url)

    private fun fetchChapterPages(chapterUrl: String): Flow<State<List<String>>> {
        return fetchData(chapterUrl) { response ->
            extractChapterImages(response)
        }
    }

    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> =
        fetchHome()

    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> {
        val url = "${apiUrl}chapters/?limit=20&offset=1&created_last=week&order_by=-views_count"
        return fetchData(url) { response ->
            Log.i("dsfkljsdfklsdjfksdfsdfds",response)
            extractPopularList(response)
                .toPopularManga(API, LANGUAGE)
                .shuffled()
        }
    }

    private fun fetchHome(page: Int = 1): Flow<State<MutableList<MangaItem>>> {
        val url = "${apiUrl}series/releases/?page=$page&page_size=20"
        return fetchData(url) { response ->
            extractSeriesHomeList(response)
                .toMangaItems(API, LANGUAGE)
                .toMutableList()
        }
    }

    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> {
        return when (searchType) {
            is SearchType.Normal -> {
                val url = "${apiUrl}series/?search=${searchType.query}&page=1&page_size=20"
                fetchData(url) { response ->
                    extractSearchResults(response)
                        .toMangaItemsFromSearch(API, LANGUAGE)
                }
            }

            is SearchType.GENRES -> {
                val url = "${apiUrl}series/?search=${searchType.query}&page=1&page_size=20"
                fetchData(url) { response ->
                    extractSearchResults(response)
                        .toMangaItemsFromSearch(API, LANGUAGE)
                }
            }

            is SearchType.SORT -> {
                val url = "${apiUrl}series/?search=${searchType.query}&order_by=${sortMap.get(searchType.sortType)}&page=1&page_size=20"
                fetchData(url) { response ->
                    extractSearchResults(response)
                        .toMangaItemsFromSearch(API, LANGUAGE)
                }
            }
        }
    }

    override suspend fun fetchMangaChaptersF(url: String): Flow<State<MangaInfo>> {
        val seriesId = extractSeriesIdFromUrl(url)
        val infoUrl = "${apiUrl}series/$seriesId"
        val chaptersUrl = "${apiUrl}series/$seriesId/chapters/?page=1&page_size=3000"

        val infoFlow: Flow<State<SwatSeriesDetailsResponse?>> = fetchData(infoUrl) { response ->
            extractMangaDetails(response)
        }

        val chaptersFlow: Flow<State<List<ChapterItem>>> =
            fetchData(chaptersUrl) { response ->
                extractChaptersList(response).toChapterItems()
            }.catch { e ->
                emit(State.Success(emptyList()))
            }.map { state ->
                when (state) {
                    is State.Success -> state
                    is State.Error -> State.Success(emptyList())
                    is State.Loading -> State.Loading
                }
            }

        return flow {
            emit(State.Loading)

            infoFlow.combine(chaptersFlow) { infoState, chaptersState ->
                Pair(infoState, chaptersState)
            }.collect { (infoState, chaptersState) ->
                if (infoState is State.Loading || chaptersState is State.Loading) {
                    emit(State.Loading)
                    return@collect
                }

                if (infoState is State.Error) {
                    emit(State.Error(0, infoState.message))
                    return@collect
                }

                val mangaDetails: SwatSeriesDetailsResponse? = (infoState as? State.Success)?.data
                if (mangaDetails == null) {
                    emit(State.Error(0, "Failed to parse MangaInfo"))
                    return@collect
                }

                val chapters: List<ChapterItem> = (chaptersState as? State.Success)?.data.orEmpty()
                val mangaInfo = mangaDetails.toMangaInfo(API, LANGUAGE, url)
                mangaInfo.chapters.clear()
                mangaInfo.chapters.addAll(chapters)
                emit(State.Success(mangaInfo))
            }
        }
    }

    override fun fetchMoreManga(
        page: Int,
        currentItems: List<MangaItem>?
    ): Flow<State<List<MangaItem>>> = flow {
        if (page > 50) return@flow
        emit(State.Loading as State<List<MangaItem>>)

        fetchHome(page).collect { state ->
            when (state) {
                is State.Success -> {
                    val newItems = state.data ?: emptyList()
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

    // Updated data extraction functions
    private fun extractSeriesHomeList(json: String): List<SwatResult> {
        return try {
            Log.d("SwatMangaRepository", "extractSeriesHomeList response: $json")
            val response = jsonParser.decodeFromString<SwatSeriesHomeResponse>(json)
            response.swatResults?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Log.e("SwatMangaRepository", "Error parsing series home list", e)
            emptyList()
        }
    }

    private fun extractPopularList(json: String): List<SwatPopularResult> {
        return try {
            Log.d("SwatMangaRepository", "extractPopularList response: $json")
            val response = jsonParser.decodeFromString<SwatSeriesPopularResponse>(json)
            response.swatPopularResults?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Log.e("SwatMangaRepository", "Error parsing popular list", e)
            emptyList()
        }
    }

    private fun extractSearchResults(json: String): List<SwatSearchResult> {
        return try {
            Log.d("SwatMangaRepository", "extractSearchResults response: $json")
            val response = jsonParser.decodeFromString<SwatSeriesSearchResponse>(json)
            response.swatSearchResults?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Log.e("SwatMangaRepository", "Error parsing search results", e)
            emptyList()
        }
    }

    private fun extractMangaDetails(json: String): SwatSeriesDetailsResponse? {
        return try {
            Log.d("SwatMangaRepository", "extractMangaDetails response: $json")
            jsonParser.decodeFromString<SwatSeriesDetailsResponse>(json)
        } catch (e: Exception) {
            Log.e("SwatMangaRepository", "Error parsing manga details", e)
            null
        }
    }

    private fun extractChaptersList(json: String): List<SwatChaptersResult> {
        return try {
            Log.d("SwatMangaRepository", "extractChaptersList response: $json")
            val response = jsonParser.decodeFromString<SwatSeriesChaptersResponse>(json)

            Log.i("sadjaskdjaskdasdasadasmldassdas",response.swatChaptersResults?.toString() ?: "null")
            response.swatChaptersResults?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Log.e("SwatMangaRepository", "Error parsing chapters list", e)
            emptyList()
        }
    }

    private fun extractChapterImages(json: String): List<String> {
        return try {
            Log.d("SwatMangaRepository", "extractChapterImages response: $json")
            val response = jsonParser.decodeFromString<SwatSeriesImagesResponse>(json)
            response.images?.mapNotNull { it?.image } ?: emptyList()
        } catch (e: Exception) {
            Log.e("SwatMangaRepository", "Error parsing chapter images", e)
            emptyList()
        }
    }

    // Utility functions
    private fun extractSeriesIdFromUrl(url: String): String {
        return url.substringAfterLast("/").substringBefore("?").substringBefore("#")
    }

    private fun extractChapterIdFromUrl(url: String): String {
        return url.substringAfterLast("/").substringBefore("?").substringBefore("#")
    }

    private fun hasBlacklistedGenres(genres: List<String>?): Boolean {
        return genres?.any { genre ->
            blackListGenres.any { blacklisted ->
                genre.lowercase().contains(blacklisted.lowercase())
            }
        } ?: false
    }

    // Updated extension functions for new data classes
    private fun List<SwatResult>.toMangaItems(api: String, language: String): List<MangaItem> {
        return map { it.toMangaItem(api, language) }
    }

    private fun SwatResult.toMangaItem(api: String, language: String): MangaItem {
        return MangaItem(
            api = api,
            language = language,
            title = title ?: "",
            url = "${apiUrl.removeSuffix("/")}/${serieId}",
            imageUrl = processImageUrl(poster?.medium ?: ""),
            rating = rating?.toIntOrNull() ?: 0,
            chapters = emptyList(),
            genres = genres?.mapNotNull { it?.name } ?: emptyList()
        )
    }

    private fun List<SwatSearchResult>.toMangaItemsFromSearch(api: String, language: String): List<MangaItem> {
        return map { it.toMangaItem(api, language) }
    }

    private fun SwatSearchResult.toMangaItem(api: String, language: String): MangaItem {
        return MangaItem(
            api = api,
            language = language,
            title = title ?: "",
            url = "${apiUrl.removeSuffix("/")}/${id}",
            imageUrl = processImageUrl(poster?.medium ?: ""),
            rating = rating?.toIntOrNull() ?: 0,
            chapters = emptyList(),
            genres = genres?.mapNotNull { it?.name } ?: emptyList()
        )
    }

    private fun List<SwatPopularResult>.toPopularManga(api: String, language: String): List<PopularManga> {
        return map { it.toPopularManga(api, language) }
    }

    private fun SwatPopularResult.toPopularManga(api: String, language: String): PopularManga {
        return PopularManga(
            api = api,
            language = language,
            title = serie?.title ?: title ?: "",
            url = "${apiUrl.removeSuffix("/")}/${serie?.id}",
            imageUrl = processImageUrl(serie?.poster?.medium ?: "")
        )
    }

    private fun SwatSeriesDetailsResponse.toMangaInfo(api: String, language: String, url: String): MangaInfo {
        return MangaInfo(
            api = api,
            language = language,
            url = url,
            title = title ?: "",
            imageUrl = processImageUrl(poster?.medium ?: ""),
            rating = rating ?: "",
            ratingCount = ratings_count?.toString() ?: "0",
            description = story ?: "",
            otherNames = "", // No alternative field in new structure
            author = "", // No author field in new structure
            artist = "", // No artist field in new structure
            genres = genres?.mapNotNull { it?.name } ?: emptyList(),
            tags = emptyList(),
            yearOfProduction = published ?: "",
            status = when (status?.name) {
                "ongoing" -> "Ongoing"
                "completed" -> "Completed"
                "hiatus" -> "Hiatus"
                "cancelled" -> "Cancelled"
                else -> "Unknown"
            },
            favoritesCount = favorites_count?.toString() ?: "0",
            chapters = mutableListOf()
        )
    }

    private fun List<SwatChaptersResult>.toChapterItems(): List<ChapterItem> {
        return map {
            it.toChapterItem() }
    }

    private fun SwatChaptersResult.toChapterItem(): ChapterItem {

        return ChapterItem(
            number = this.chapter ?: "",
            name = this.title ?: "",
            url = "${apiUrl}chapters/${this.id?.toInt()}/",
            date = this.created_at?.let { dateString ->
                try {
                    LocalDate.parse(dateString.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (e: Exception) {
                    null
                }
            }
        )
    }

    // Generic fetch function
    private inline fun <T> fetchData(
        url: String,
        crossinline transform: suspend (String) -> T
    ): Flow<State<T>> = flow {
        emit(State.Loading)

        try {
            Log.i("SwatMangaRepository", "Fetching URL: $url")
            val response = api.get(url, defaultHeaders)

            if (response.isSuccessful) {
                val responseBody = response.body().orEmpty()
                val parsedData = transform(responseBody)
                emit(State.Success(parsedData))
            } else {
                val errorMessage = response.errorBody()?.string().orEmpty()
                    .ifEmpty { "HTTP ${response.code()}: ${response.message()}" }
                emit(State.Error(response.code(), errorMessage))
            }
        } catch (e: Exception) {
            Log.e("SwatMangaRepository", "Error fetching data", e)
            emit(State.Error(0, e.localizedMessage ?: "Unknown error occurred"))
        }
    }
}