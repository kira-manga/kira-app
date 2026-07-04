package me.manga.yamiapk.sources_repositry.en.mangapark

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
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
import me.manga.yamiapk.sources_repositry.en.mangapark.models.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject


 open class MangaParkRepository @Inject constructor(
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
    open val language : String = "en"

    override val BASE_URL: String
        get() = MangaSource.MANGAPARK.BASEURL

    override val URL_VERSION: Int
        get() = 0

    override var baseUrl: String = ""

    override val API: String
        get() = MangaSource.MANGAPARK.API

    override val LANGUAGE: String
        get() = MangaSource.MANGAPARK.LANGUAGE.Language

    override val ICON: Int
        get() = MangaSource.MANGAPARK.ICON

    override val PRIORITY = MangaSource.MANGAPARK.PRIORITY

    private val defaultDomain = "mangapark.io"
    private val apiUrl = "https://$defaultDomain/apo/"
    override var imgBaseUrl: String = "https://$defaultDomain"
    override var imgUrlVersion: Int = 0

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

     override suspend fun initSite(): Int {
         val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
         _cachedHeaders = headers
         return super.initSite()
     }
    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override suspend fun getBaseUrl(): String {
//        val url = sourcesRepository.getBaseUrlFor(API) ?: apiUrl
//        baseUrl = url
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
            .data(processImageUrl(url))
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
            url.startsWith("/data") -> url
            url.startsWith("http") -> url
            url.startsWith("/") -> "$imgBaseUrl$url"
            else -> url
        }
    }

    override val blackListGenres: Set<String>
        get() = setOf(
            "hentai",
//            "adult",
//            "mature",
            "smut",
            "ecchi",
            "yaoi",
            "yuri",
            "shounen ai",
            "shoujo ai"
        )

    override val sortTypes: Set<String>
        get() = setOf(
            "field_score",      // Rating Score
            "field_follow",     // Most Follows
            "field_review",     // Most Reviews
            "field_comment",    // Most Comments
            "field_chapter",    // Most Chapters
            "field_update",     // New Chapters
            "field_create",     // Recently Created
            "field_name",       // Name A-Z
            "views_d030",       // Most Views 30 days
            "views_d007",       // Most Views 7 days
            "views_h024"        // Most Views 24 hours
        )

    override val allGenres: Set<String>
        get() = setOf(
            "action", "adventure", "comedy", "drama", "fantasy", "horror",
            "mystery", "romance", "sci-fi", "slice of life", "sports",
            "supernatural", "thriller", "historical", "psychological",
            "seinen", "shounen", "shoujo", "josei"
        )

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchPages(url)

    private fun fetchPages(url :String):  Flow<State<List<String>>>{

        val chapterUrl = BASE_URL
        val payload = GraphQL(
            IdVariables(url.substringAfterLast("#")),
            PAGES_QUERY,
        ).toJsonString(jsonParser)

        return fetchData(chapterUrl, payload) { response ->
            extractChapterPages(response)

        }
    }

    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> =
        fetchHome()

    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> {
        val payload = searchMangaRequest(
            page = 1,
            sortBy = "field_score",
//            language = LANGUAGE
        )

        return fetchData(apiUrl, payload) { response ->
            extractSearchManga(response)
                .filter { !hasBlacklistedGenres(it.genres) }
                .toPopularManga(API, LANGUAGE)
                .shuffled()
        }
    }

    private fun fetchHome(page: Int = 1): Flow<State<MutableList<MangaItem>>> {
        val payload = searchMangaRequest(
            page = page,
            sortBy = "field_update",
        )

        return fetchData(apiUrl, payload) { response ->
            extractSearchManga(response)
                .filter { !hasBlacklistedGenres(it.genres) }
                .toMangaItems(API, LANGUAGE)
                .toMutableList()
        }
    }

    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> {
        return when (searchType) {
            is SearchType.Normal -> {
                val payload = searchMangaRequest(
                    query = searchType.query,
                    page = 1,
                )
                fetchData(apiUrl, payload) { response ->
                    extractSearchManga(response)
                        .filter { !hasBlacklistedGenres(it.genres) }
                        .toMangaItems(API, LANGUAGE)
                }
            }

            is SearchType.GENRES -> {
                val payload = searchMangaRequest(
                    query = searchType.query,
                    includedGenres = listOf(searchType.genres),
                    page = 1,
                )
                fetchData(apiUrl, payload) { response ->
                    extractSearchManga(response)
                        .filter { !hasBlacklistedGenres(it.genres) }
                        .toMangaItems(API, LANGUAGE)
                }
            }

            is SearchType.SORT -> {
                val payload = searchMangaRequest(
                    query = searchType.query,
                    sortBy = searchType.sortType,
                    includedGenres = if (searchType.genres.isNotEmpty()) listOf(searchType.genres) else null,
                    page = 1,
                )
                fetchData(apiUrl, payload) { response ->
                    extractSearchManga(response)
                        .filter { !hasBlacklistedGenres(it.genres) }
                        .toMangaItems(API, LANGUAGE)
                }
            }
        }
    }

    override suspend fun fetchMangaChaptersF(url: String): Flow<State<MangaInfo>> {
        // Extract manga ID from URL
        val mangaId = url.substringAfterLast("#")

        val infoPayload = createIdPayload(mangaId, DETAILS_QUERY)
        val chaptersPayload = createIdPayload(mangaId, CHAPTERS_QUERY)

        val infoFlow: Flow<State<MangaInfo?>> = fetchData(apiUrl, infoPayload) { response ->
            extractMangaDetails(response)?.toMangaInfo(API, LANGUAGE, url)
        }

        val chaptersFlow: Flow<State<List<ChapterItem>>> =
            fetchData(apiUrl, chaptersPayload) { response ->
                extractChapterList(response).toChapterItems()
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

                val mangaInfo: MangaInfo? = (infoState as? State.Success)?.data
                if (mangaInfo == null) {
                    emit(State.Error(0, "Failed to parse MangaInfo"))
                    return@collect
                }

                val chapters: List<ChapterItem> = (chaptersState as? State.Success)?.data.orEmpty()
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



    private fun createIdPayload(id: String, query: String): String {
        val idVariables = IdVariables(id)
        val graphQL = GraphQL(idVariables, query)
        // Use the specific serializer for IdVariables
        return jsonParser.encodeToString(
            GraphQL.serializer(IdVariables.serializer()),
            graphQL
        )
    }


    // Data extraction functions
    private fun extractSearchManga(json: String): List<MangaParkManga> {
        return try {
            Log.e("MangaParkRepository", " extractSearchManga =  $json")

            val response = jsonParser.decodeFromString<SearchResponse>(json)
            response.data.searchComics.items.map { it.data }
        } catch (e: Exception) {
            Log.e("MangaParkRepository", "Error parsing search manga", e)
            emptyList()
        }
    }

    private fun extractMangaDetails(json: String): MangaParkManga? {
        return try {
            Log.e("MangaParkRepository", " extractMangaDetails =  $json")

            val response = jsonParser.decodeFromString<DetailsResponse>(json)
            response.data.comic.data
        } catch (e: Exception) {
            Log.e("MangaParkRepository", "Error parsing manga details", e)
            null
        }
    }

    private fun extractChapterList(json: String): List<MangaParkChapter> {
        return try {

            Log.e("MangaParkRepository", " extractChapterList =  $json")

            val response = jsonParser.decodeFromString<ChapterListResponse>(json)
            response.data.chapterList.map { it.data }
        } catch (e: Exception) {
            Log.e("MangaParkRepository", "Error parsing chapter list", e)
            emptyList()
        }
    }


    private fun extractChapterPages(json: String): List<String> {
        return try {
            Log.e("MangaParkRepositoraadady", "jsooon  === $json")

            val response = jsonParser.decodeFromString<PageListResponse>(json)
            response.data.chapterPages.data.imageFile.urlList
        } catch (e: Exception) {
            Log.e("MangaParkRepositoraadady", "Error parsing chapter pages", e)
            emptyList()
        }
    }



    fun searchMangaRequest(
        page: Int,
        query: String? = null,
        sortBy: String? = null,
        includedGenres: List<String>? = null,
        excludedGenres: List<String>? = null,

        incTLangs: List<String>? = listOf(
        language
        ),
    ): String {
        val payload = GraphQL(
            SearchVariables(
                SearchPayload(
                    page = page,
                    size = 30,
                    query = query,
                    sortby = sortBy,
                    incGenres = includedGenres,
                    excGenres = excludedGenres,
                    incTLangs =incTLangs
                    ),
            ),
            SEARCH_QUERY,
        ).toJsonString(jsonParser)

        return payload
    }

    inline fun <reified T> T.toJsonString(json: Json = Json): String =
        json.encodeToString(this)


    // Utility functions
    private fun hasBlacklistedGenres(genres: List<String>?): Boolean {
        return genres?.any { genre ->
            blackListGenres.any { blacklisted ->
                genre.lowercase().contains(blacklisted.lowercase())
            }
        } ?: false
    }

    // Extension functions for data transformation
    private fun List<MangaParkManga>.toMangaItems(api: String, language: String): List<MangaItem> {
        return map { it.toMangaItem(api, language) }
    }

    private fun MangaParkManga.toMangaItem(api: String, language: String): MangaItem {
        return MangaItem(
            api = api,
            language = language,
            title = name ?: "",
            url = "$imgBaseUrl$urlPath#$id",
            imageUrl = processImageUrl(cover ?: ""),
            rating = 0, // MangaPark doesn't provide rating in search results
            chapters = emptyList(),
            genres = genres ?: emptyList()
        )
    }

    private fun List<MangaParkManga>.toPopularManga(
        api: String,
        language: String
    ): List<PopularManga> {
        return map { it.toPopularManga(api, language) }
    }

    private fun MangaParkManga.toPopularManga(api: String, language: String): PopularManga {
        return PopularManga(
            api = api,
            language = language,
            title = name ?: "",
            url = "$imgBaseUrl$urlPath#$id",
            imageUrl = processImageUrl(cover ?: "")
        )
    }

    private fun MangaParkManga.toMangaInfo(api: String, language: String, url: String): MangaInfo {
        return MangaInfo(
            api = api,
            language = language,
            url = url,
            title = name ?: "",
            imageUrl = processImageUrl(cover ?: ""),
            rating = "",
            ratingCount = "",
            description = summary ?: "",
            otherNames = altNames?.joinToString(", ") ?: "",
            author = authors?.joinToString(", ") ?: "",
            artist = artists?.joinToString(", ") ?: "",
            genres = genres ?: emptyList(),
            tags = emptyList(),
            yearOfProduction = "",
            status = when (originalStatus ?: uploadStatus) {
                "ongoing" -> "Ongoing"
                "completed" -> "Completed"
                "hiatus" -> "Hiatus"
                "cancelled" -> "Cancelled"
                else -> "Unknown"
            },
            favoritesCount = "",
            chapters = mutableListOf()
        )
    }

    private fun List<MangaParkChapter>.toChapterItems(): List<ChapterItem> {
        return map { it.toChapterItem() }.reversed()
    }

    private fun MangaParkChapter.toChapterItem(): ChapterItem {
        return ChapterItem(
            number = displayName ?: "",
            name = buildString {
                append(displayName ?: "")
                title?.let { append(": $it") }
            },
            url = "$imgBaseUrl$urlPath#$id",
            date = (dateModify ?: dateCreate)?.let { timestamp ->
                try {
                    LocalDateTime.ofEpochSecond(timestamp / 1000, 0, ZoneOffset.UTC).toLocalDate()
                } catch (e: Exception) {
                    null
                }
            }
        )
    }

    // Generic fetch function
    private inline fun <T> fetchData(
        url: String,
        payload: String? = null,
        crossinline transform: suspend (String) -> T
    ): Flow<State<T>> = flow {
        emit(State.Loading)

        try {

            val response = if (payload != null) {

                Log.i("MangaParkRepositoryccassadas", "url: $url")
                api.postJson(url, payload, defaultHeaders)
            } else {
                api.get(url, defaultHeaders)
            }

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
            emit(State.Error(0, e.localizedMessage ?: "Unknown error occurred"))
        }
    }

    companion object {
        // GraphQL Query constants (from the original extension)
        const val SEARCH_QUERY = """
            query (
                ${"$"}select: SearchComic_Select
            ) {
                get_searchComic(
                    select: ${"$"}select
                ) {
                    items {
                        data {
                            id
                            name
                            altNames
                            artists
                            authors
                            genres
                            originalStatus
                            uploadStatus
                            summary
                            extraInfo
                            urlCoverOri
                            urlPath
                            max_chapterNode {
                                data {
                                    imageFile {
                                        urlList
                                    }
                                }
                            }
                            first_chapterNode {
                                data {
                                    imageFile {
                                        urlList
                                    }
                                }
                            }
                        }
                    }
                }
            }
        """

        const val DETAILS_QUERY = """
            query(
                ${"$"}id: ID!
            ) {
                get_comicNode(
                    id: ${"$"}id
                ) {
                    data {
                        id
                        name
                        altNames
                        artists
                        authors
                        genres
                        originalStatus
                        uploadStatus
                        summary
                        extraInfo
                        urlCoverOri
                        urlPath
                        max_chapterNode {
                            data {
                                imageFile {
                                    urlList
                                }
                            }
                        }
                        first_chapterNode {
                            data {
                                imageFile {
                                    urlList
                                }
                            }
                        }
                    }
                }
            }
        """

        const val CHAPTERS_QUERY = """
            query(
                ${"$"}id: ID!
            ) {
                get_comicChapterList(
                    comicId: ${"$"}id
                ) {
                    data {
                        id
                        dname
                        title
                        dateModify
                        dateCreate
                        urlPath
                        srcTitle
                        userNode {
                            data {
                                name
                            }
                        }
                        dupChapters {
                            data {
                                id
                                dname
                                title
                                dateModify
                                dateCreate
                                urlPath
                                srcTitle
                                userNode {
                                    data {
                                        name
                                    }
                                }
                            }
                        }
                    }
                }
            }
        """

        const val PAGES_QUERY = """
            query(
                ${"$"}id: ID!
            ) {
                get_chapterNode(
                    id: ${"$"}id
                ) {
                    data {
                        imageFile {
                            urlList
                        }
                    }
                }
            }
        """

    }
}