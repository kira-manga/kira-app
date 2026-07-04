package me.manga.yamiapk.sources_repositry.ru.senkuro


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
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toPopularMangaList
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.ru.senkuro.models.*
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject

open class SenkuroRepository @Inject constructor(
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
        }
    }

    open val language: String = "ru"

    override val BASE_URL: String
        get() = MangaSource.SENKURO.BASEURL

    override val URL_VERSION: Int
        get() = 0

    override var baseUrl: String = ""

    override val API: String
        get() = MangaSource.SENKURO.API

    override val LANGUAGE: String
        get() = MangaSource.SENKURO.LANGUAGE.Language

    override val ICON: Int
        get() = MangaSource.SENKURO.ICON

    override val PRIORITY = MangaSource.SENKURO.PRIORITY

    private val apiUrl = "https://api.senkuro.com/graphql"
    override var imgBaseUrl: String = "https://shiro.senkuro.net"
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
    @Volatile
    private var currentCursor: String? = null


    override val blackListGenres: Set<String>
        get() = setOf(
//            "hentai", "yaoi", "yuri", "shoujo_ai", "shounen_ai"
        )

    override val sortTypes: Set<String>
        get() = setOf("popular", "updated", "rating")

    override val allGenres: Set<String>
        get() = setOf(
            "action", "adventure", "comedy", "drama", "fantasy", "horror",
            "mystery", "romance", "sci-fi", "slice of life", "sports",
            "supernatural", "thriller", "historical", "psychological"
        )

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchPages(url)

    private fun fetchPages(url: String): Flow<State<List<String>>> {
        val parts = url.split(",,")
        val mangaId = parts[0]
        val chapterId = parts[2]

        val payload = GraphQL(
            FetchChapterPagesVariables(mangaId = mangaId, chapterId = chapterId),
            CHAPTERS_PAGES_QUERY,
        ).toJsonString(jsonParser)

        return fetchData(apiUrl, payload) { response ->
            extractChapterPages(response)
        }
    }

    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> {
        resetPagination()
        val payload = createPopularMangaRequest()
        return fetchData(apiUrl, payload) { response ->
            extractHomeManga(response)
                .toMangaItemList(API, LANGUAGE).toMutableList()
        }

    }

    private fun extractHomeMangaWithPagination(json: String): MangaPageResult {
        return try {
            Log.e("SenkuroRepository", "extractHomeManga = $json")
            val response = jsonParser.decodeFromString<PageWrapperDto<MangasConnectionDto>>(json)

            val pageInfo = response.data?.mangas?.pageInfo
            val items = response.data?.mangas?.edges?.map { it.node } ?: emptyList()

            MangaPageResult(
                items = items,
                paginationState = PaginationState(
                    endCursor = pageInfo?.endCursor,
                    hasNextPage = pageInfo?.hasNextPage ?: false
                )
            )
        } catch (e: Exception) {
            Log.e("SenkuroRepository", "Error parsing popular manga", e)
            MangaPageResult(emptyList(), PaginationState(null, false))
        }
    }
    private fun extractHomeManga(json: String): List<MangaNode> {
        return try {
            Log.e("SenkuroRepository", "extractHomeManga = $json")
            val response = jsonParser.decodeFromString<PageWrapperDto<MangasConnectionDto>>(json)

           currentCursor =  response.data?.mangas?.pageInfo?.endCursor
            response.data?.mangas?.edges?.map { it.node } ?: emptyList()
        } catch (e: Exception) {
            Log.e("SenkuroRepository", "Error parsing popular manga", e)
            emptyList()
        }
    }

    fun List<MangaNode>.toMangaItemList(
        api: String,
        language: String
    ): List<MangaItem> {
        return map { node ->
            val title = node.titles.find { it.lang == "RU" }?.content
                ?: node.titles.find { it.lang == "EN" }?.content
                ?: node.originalName?.content
                ?: node.titles.firstOrNull()?.content
                ?: ""

            MangaItem(
                api = api,
                language = language,
                title = title,
                url = "${node.id},,${node.slug}",
                imageUrl = processImageUrl(node.cover?.original?.url ?: ""),
                rating = node.rating?.toIntOrNull(),
                chapters = emptyList(),
                genres = emptyList(),
            )
        }
    }
    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> {
        val payload = createPopularMangaRequest(orderField = "VIEWS")

        return fetchData(apiUrl, payload) { response ->
            extractPopularManga(response)
                .toPopularMangaList(API, LANGUAGE)
        }
    }

    /** Parse GraphQL response that contains `data.mangas.edges[].node` */
    private fun extractPopularManga(json: String): List<MangaNode> {
        return try {
            Log.e("SenkuroRepository", "extractPopularManga = $json")
            val response = jsonParser.decodeFromString<PageWrapperDto<MangasConnectionDto>>(json)
            response.data?.mangas?.edges?.map { it.node } ?: emptyList()
        } catch (e: Exception) {
            Log.e("SenkuroRepository", "Error parsing popular manga", e)
            emptyList()
        }
    }

    /** Map MangaNode -> PopularManga */
    private fun List<MangaNode>.toPopularMangaList(api: String, language: String): List<PopularManga> {
        return map { node ->
            val title = node.titles.find { it.lang == "RU" }?.content
                ?: node.titles.find { it.lang == "EN" }?.content
                ?: node.originalName?.content
                ?: node.titles.firstOrNull()?.content
                ?: ""

            PopularManga(
                api = api,
                language = language,
                title = title,
                url = "${node.id},,${node.slug}",
                imageUrl = processImageUrl(node.cover?.original?.url ?: "")
            )
        }
    }

    // Extension functions for MangaNode transformation






    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> {
        return when (searchType) {
            is SearchType.Normal -> {
                val payload = searchMangaRequest(
                    query = searchType.query,
                    page = 1,
                )
                fetchData(apiUrl, payload) { response ->
                    extractSearchManga(response)
//                        .filter { !hasBlacklistedGenres(it.genres) }
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
//                        .filter { !hasBlacklistedGenres(it.genres) }
                        .toMangaItems(API, LANGUAGE)
                }
            }

            is SearchType.SORT -> {
                val payload = searchMangaRequest(
                    query = searchType.query,
                    includedGenres = if (searchType.genres.isNotEmpty()) listOf(searchType.genres) else null,
                    page = 1,
                )
                fetchData(apiUrl, payload) { response ->
                    extractSearchManga(response)
//                        .filter { !hasBlacklistedGenres(it.genres) }
                        .toMangaItems(API, LANGUAGE)
                }
            }
        }
    }

    override suspend fun fetchMangaChaptersF(url: String): Flow<State<MangaInfo>> {
        val parts = url.split(",,")
        val mangaId = parts[0]

        val infoPayload = createIdPayload(mangaId, DETAILS_QUERY)
        val chaptersPayload = createIdPayload(mangaId, CHAPTERS_QUERY)

        val infoFlow: Flow<State<MangaInfo?>> = fetchData(apiUrl, infoPayload) { response ->
            extractMangaDetails(response)?.toMangaInfo(API, LANGUAGE, url)
        }

        val chaptersFlow: Flow<State<List<ChapterItem>>> =
            fetchData(apiUrl, chaptersPayload) { response ->
                extractChapterList(response, mangaId)
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
        emit(State.Loading as State<List<MangaItem>>)

        // Create payload with current cursor
        val payload = createPopularMangaRequest(cursor = currentCursor)

        fetchData(apiUrl, payload) { response ->
            extractHomeMangaWithPagination(response)
        }.collect { state ->
            when (state) {
                is State.Success -> {
                    val result = state.data

                    // Update cursor for next request
                    currentCursor = result.paginationState.endCursor

                    // Convert to MangaItem list
                    val newItems = result.items.toMangaItemList(API, LANGUAGE)

                    // Merge with existing items
                    val mergedList = (currentItems?.toMutableList() ?: mutableListOf()).apply {
                        addAll(newItems)
                    }

                    // Check if we should stop pagination
                    if (!result.paginationState.hasNextPage || newItems.isEmpty()) {
                        emit(State.Success(currentItems ?: emptyList()))
                    } else {
                        emit(State.Success(mergedList))
                    }
                }
                is State.Error -> emit(state)
                is State.Loading -> emit(State.Loading)
            }
        }
    }.catch { e ->
        emit(State.Error(0, e.localizedMessage ?: "Unknown error occurred"))
    }


    private fun createIdPayload(id: String, query: String): String {
        val variables = FetchDetailsVariables(mangaId = id)
        val graphQL = GraphQL(variables, query)
        return jsonParser.encodeToString(graphQL)
    }

    // Data extraction functions
    private fun extractSearchManga(json: String): List<MangaTachiyomiInfoDto> {
        return try {
            Log.e("SenkuroRepository", "extractSearchManga = $json")
            val response = jsonParser.decodeFromString<PageWrapperDto<MangaTachiyomiSearchDto<MangaTachiyomiInfoDto>>>(json)
            response.data?.mangaTachiyomiSearch?.mangas ?: emptyList()
        } catch (e: Exception) {
            Log.e("SenkuroRepository", "Error parsing search manga", e)
            emptyList()
        }
    }

    private fun extractMangaDetails(json: String): MangaTachiyomiInfoDto? {
        return try {
            Log.e("SenkuroRepository", "extractMangaDetails = $json")
            val response = jsonParser.decodeFromString<PageWrapperDto<SubInfoDto>>(json)
            response.data?.mangaTachiyomiInfo
        } catch (e: Exception) {
            Log.e("SenkuroRepository", "Error parsing manga details", e)
            null
        }
    }

    private fun extractChapterList(json: String, mangaId: String): List<ChapterItem> {
        return try {
            Log.e("SenkuroRepository", "extractChapterList = $json")
            val response = jsonParser.decodeFromString<PageWrapperDto<MangaTachiyomiChaptersDto>>(json)
            val teamsList = response.data?.mangaTachiyomiChapters?.teams
            response.data?.mangaTachiyomiChapters?.chapters?.map { chapter ->
                chapter.toChapterItem(mangaId, teamsList ?: emptyList())
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("SenkuroRepository", "Error parsing chapter list", e)
            emptyList()
        }
    }

    private fun extractChapterPages(json: String): List<String> {
        return try {
            Log.e("SenkuroRepository", "extractChapterPages = $json")
            val response = jsonParser.decodeFromString<PageWrapperDto<MangaTachiyomiChapterPages>>(json)
            response.data?.mangaTachiyomiChapterPages?.pages?.map { it.url } ?: emptyList()
        } catch (e: Exception) {
            Log.e("SenkuroRepository", "Error parsing chapter pages", e)
            emptyList()
        }
    }


    private fun createPopularMangaRequest(
        cursor: String? = null,
        search: String? = null,
        orderField: String = "LAST_CHAPTER_AT",    // change default here if you prefer POPULARITY_SCORE
        orderDirection: String = "DESC",
        first: Int = 20,
        operationName: String = "fetchMangas"      // send operationName like the server logs showed
    ): String {
        val variables = PopularMangaVariables(
            first = first,
            after = cursor,
            search = search,
            orderField = orderField,
            orderDirection = orderDirection,
            offset = 20
        )

        // GraphQL wrapper already supports operationName
        val graphQL = GraphQL(
            variables = variables,
            query = POPULAR_MANGA_QUERY,
            operationName = operationName
        )

        return jsonParser.encodeToString(graphQL)
    }

    private fun searchMangaRequest(
        page: Int,
        query: String? = null,
        includedGenres: List<String>? = null,
        excludedGenres: List<String>? = null,
    ): String {
        val offset = 20 * (page - 1)
        val payload = GraphQL(
            SearchVariables(
                query = query,
                offset = offset,
                genre = SearchVariables.FiltersDto(
                    include = includedGenres,
                    exclude = (excludedGenres?.toMutableList() ?: mutableListOf()).apply {
                        addAll(blackListGenres)
                    }
                ),
            ),
            SEARCH_QUERY,
        )
        return jsonParser.encodeToString(payload)
    }
    fun resetPagination() {
        currentCursor = null
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

    private val simpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S", Locale.ROOT)
    }

    private fun parseDate(date: String?): LocalDateTime? {
        date ?: return null
        return try {
            val millis = simpleDateFormat.parse(date)!!.time
            LocalDateTime.ofEpochSecond(millis / 1000, 0, ZoneOffset.UTC)
        } catch (_: Exception) {
            null
        }
    }

    // Extension functions for data transformation
    private fun List<MangaTachiyomiInfoDto>.toMangaItems(api: String, language: String): List<MangaItem> {
        return map { it.toMangaItem(api, language) }
    }

    private fun MangaTachiyomiInfoDto.toMangaItem(api: String, language: String): MangaItem {
        val title = titles.find { it.lang == "RU" }?.content
            ?: titles.find { it.lang == "EN" }?.content
            ?: titles.firstOrNull()?.content
            ?: ""

        return MangaItem(
            api = api,
            language = language,
            title = title,
            url = "$id,,$slug",
            imageUrl = processImageUrl(cover?.original?.url ?: ""),
            rating = 0,
            chapters = emptyList(),
            genres = genres?.map { it.titles.find { t -> t.lang == "RU" }?.content ?: it.slug } ?: emptyList()
        )
    }

    private fun List<MangaTachiyomiInfoDto>.toPopularManga(api: String, language: String): List<PopularManga> {
        return map { it.toPopularManga(api, language) }
    }

    private fun MangaTachiyomiInfoDto.toPopularManga(api: String, language: String): PopularManga {
        val title = titles.find { it.lang == "RU" }?.content
            ?: titles.find { it.lang == "EN" }?.content
            ?: titles.firstOrNull()?.content
            ?: ""

        return PopularManga(
            api = api,
            language = language,
            title = title,
            url = "$id,,$slug",
            imageUrl = processImageUrl(cover?.original?.url ?: "")
        )
    }

    private fun MangaTachiyomiInfoDto.toMangaInfo(api: String, language: String, url: String): MangaInfo {
        val title = titles.find { it.lang == "RU" }?.content
            ?: titles.find { it.lang == "EN" }?.content
            ?: titles.firstOrNull()?.content
            ?: ""

        val altNames = alternativeNames?.joinToString(" / ") { it.content } ?: ""
        val description = localizations?.find { it.lang == "RU" }?.description.orEmpty()

        return MangaInfo(
            api = api,
            language = language,
            url = url,
            title = title,
            imageUrl = processImageUrl(cover?.original?.url ?: ""),
            rating = "",
            ratingCount = "",
            description = if (altNames.isNotEmpty()) "Альтернативные названия:\n$altNames\n\n$description" else description,
            otherNames = altNames,
            author = mainStaff?.filter { it.roles.contains("STORY") }?.joinToString(", ") { it.person.name } ?: "",
            artist = mainStaff?.filter { it.roles.contains("ART") }?.joinToString(", ") { it.person.name } ?: "",
            genres = genres?.map { it.titles.find { t -> t.lang == "RU" }?.content ?: it.slug } ?: emptyList(),
            tags = tags?.map { it.titles.find { t -> t.lang == "RU" }?.content ?: it.slug } ?: emptyList(),
            yearOfProduction = "",
            status = when (status) {
                "FINISHED" -> "Completed"
                "ONGOING" -> "Ongoing"
                "HIATUS" -> "Hiatus"
                "ANNOUNCE" -> "Announced"
                "CANCELLED" -> "Cancelled"
                else -> "Unknown"
            },
            favoritesCount = "",
            chapters = mutableListOf()
        )
    }

    private fun MangaTachiyomiChaptersDto.ChaptersMessage.BookDto.toChapterItem(
        mangaId: String,
        teams: List<MangaTachiyomiChaptersDto.ChaptersMessage.TeamsDto>
    ): ChapterItem {
        return ChapterItem(
            number = number,
            name = "$volume. Глава $number ${name ?: ""}",
            url = "$mangaId,,$slug,,$id,,$slug",
            date = parseDate(createdAt)?.toLocalDate(),
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
                Log.i("SenkuroRepository", "url: $url")
                Log.i("SenkuroRepository", "payload: $payload")

                api.postJson(url, payload, defaultHeaders)
            } else {
                api.get(url, defaultHeaders)
            }

            if (response.isSuccessful) {
                val responseBody = response.body().orEmpty()
                Log.i("SenkuroRepositoryuhgh", "responseBody: $responseBody")
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
        private const val POPULAR_MANGA_QUERY = """
query fetchMangas(
    ${"$"}first: Int = 20,
    ${"$"}after: String,
    ${"$"}search: String,
    ${"$"}orderField: String,
    ${"$"}orderDirection: String
) {
  mangas(first: ${"$"}first, after: ${"$"}after, search: ${"$"}search,
         orderBy: { direction: ${"$"}orderDirection, field: ${"$"}orderField }) {
    edges {
      node {
        id
        slug
        originalName { lang content }
        titles { lang content }
        status
        type
        rating
        score
        containExplicitThemes
        cover {
          original { height width url }
        }
      }
    }
    pageInfo {
      hasNextPage
      endCursor
    }
  }
}
"""




        private const val SEARCH_QUERY = """
    query searchTachiyomiManga(
        ${"$"}query: String,
        ${"$"}type: MangaTachiyomiSearchTypeFilter,
        ${"$"}status: MangaTachiyomiSearchStatusFilter,
        ${"$"}translationStatus: MangaTachiyomiSearchTranslationStatusFilter,
        ${"$"}genre: MangaTachiyomiSearchGenreFilter,
        ${"$"}tag: MangaTachiyomiSearchTagFilter,
        ${"$"}format: MangaTachiyomiSearchGenreFilter,
        ${"$"}rating: MangaTachiyomiSearchTagFilter,
        ${"$"}offset: Int,
    ) {
        mangaTachiyomiSearch(
            query: ${"$"}query,
            type: ${"$"}type,
            status: ${"$"}status,
            translationStatus: ${"$"}translationStatus,
            genre: ${"$"}genre,
            tag: ${"$"}tag,
            format: ${"$"}format,
            rating: ${"$"}rating,
            offset: ${"$"}offset,
        ) {
            mangas {
                id
                slug
                titles {
                    lang
                    content
                }
                alternativeNames {
                    lang
                    content
                }
                cover {
                    original {
                        url
                    }
                }
            }
        }
    }
"""

        private const val DETAILS_QUERY = """
            query fetchTachiyomiManga(${"$"}mangaId: ID!) {
                mangaTachiyomiInfo(mangaId: ${"$"}mangaId) {
                    id
                    slug
                    titles {
                        lang
                        content
                    }
                    alternativeNames {
                        lang
                        content
                    }
                    localizations {
                        lang
                        description
                    }
                    type
                    rating
                    status
                    formats
                    genres {
                        slug
                        titles {
                            lang
                            content
                        }
                    }
                    tags {
                        slug
                        titles {
                            lang
                            content
                        }
                    }
                    cover {
                        original {
                            url
                        }
                    }
                    mainStaff {
                        roles
                        person {
                            name
                        }
                    }
                }
            }
        """

        private const val CHAPTERS_QUERY = """
            query fetchTachiyomiChapters(${"$"}mangaId: ID!) {
                mangaTachiyomiChapters(mangaId: ${"$"}mangaId) {
                    chapters {
                        id
                        slug
                        branchId
                        name
                        teamIds
                        number
                        volume
                        createdAt
                    }
                    teams {
                        id
                        slug
                        name
                    }
                }
            }
        """

        private const val CHAPTERS_PAGES_QUERY = """
            query fetchTachiyomiChapterPages(
                ${"$"}mangaId: ID!,
                ${"$"}chapterId: ID!
            ) {
                mangaTachiyomiChapterPages(
                    mangaId: ${"$"}mangaId,
                    chapterId: ${"$"}chapterId
                ) {
                    pages {
                        url
                    }
                }
            }
        """
    }
}