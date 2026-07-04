package me.manga.yamiapk.sources_repositry.en.comick_io

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
import kotlinx.serialization.ExperimentalSerializationApi
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
import me.manga.yamiapk.sources_repositry.en.comick_io.models.chapter_images.ChapterImgs
import me.manga.yamiapk.sources_repositry.en.comick_io.models.chapters.Chapter
import me.manga.yamiapk.sources_repositry.en.comick_io.models.chapters.infochapters
import me.manga.yamiapk.sources_repositry.en.comick_io.models.home.ComickItem
import me.manga.yamiapk.sources_repositry.en.comick_io.models.homev2.homeV2
import me.manga.yamiapk.sources_repositry.en.comick_io.models.homev2.homeV2Item
import me.manga.yamiapk.sources_repositry.en.comick_io.models.info.Info
import me.manga.yamiapk.sources_repositry.en.comick_io.models.info.MdComicMdGenre
import me.manga.yamiapk.sources_repositry.en.comick_io.models.info.MuComicCategory
import me.manga.yamiapk.sources_repositry.en.comick_io.models.search.SearchItem
import org.jsoup.SerializationException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject


open class ComickRepository @Inject constructor(
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
        get() = MangaSource.COMICKIO.BASEURL
    override val URL_VERSION: Int
        get() = 0
    override var baseUrl: String =""
    override val API: String
        get() = MangaSource.COMICKIO.API
    override val LANGUAGE: String
        get() = MangaSource.COMICKIO.LANGUAGE.Language
    override val ICON: Int
        get() = MangaSource.COMICKIO.ICON

    override val PRIORITY = MangaSource.COMICKIO.PRIORITY

        private val apiUrl = "https://api.comick.fun/"
    override var imgBaseUrl: String = "https://meo.comick.pictures/"
    override var imgUrlVersion: Int = 0

    open val language:String = "en"



    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()




    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // persist in background
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

        // update the cache immediately
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

    override suspend fun getBaseUrl(): String {
        val url = sourcesRepository.getBaseUrlFor(API) ?: apiUrl
        baseUrl = url
        return url
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
//    299: Genderswap
//    2: Adult
//    296: Crossdressing
//    251: Ecchi
//    1: Gender Bender
//    320: Incest
//    302: Loli
//    3: Mature
//    287: Sexual Violence
//    267: Shoujo Ai
//    268: Shounen Ai
//    270: Smut
//    275: Yaoi
//    276: Yuri
            "Genderswap",
            "Adult",
            "Crossdressing",
//            "Ecchi",
            "Gender Bender",
            "Incest",
            "Loli",
//            "Mature",
            "Sexual Violence",
            "Shoujo Ai",
            "Shounen Ai",
            "Smut",
            "Yaoi",
            "Yuri"

        )
    val blackListInt = setOf(
        299,
        2,
        296,
//        251,
        1,
        320,
        302,
        3,
//        28,
        267,
        268,
        270,
        276,
        275
    )

    private fun Set<Int>.toStringSet(): Set<String> = this
        .map { it.toString() }
        .toSet()


    fun List<Int?>?.hasBlacklistedGenreInt(): Boolean =
        this?.any { it in blackListInt } ?: false

    fun List<String>.hasBlacklistedGenre(): Boolean =
        this.any { it in blackListInt.toStringSet() }

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchData(url) { html ->
            extractChapterImgs(
                html
            )
        }


    override fun fetchMangaHomeF(baseUrl: String): Flow<State<MutableList<MangaItem>>> = fetchHome()




    fun fetchHome(page: Int = 1): Flow<State<MutableList<MangaItem>>> =
        fetchData("${apiUrl}chapter/?page=$page&order=new&tachiyomi=true&lang=$language") { html ->
            extractHomeMangaItems(
                html
            )
        }


    override val sortTypes: Set<String>
        get() = setOf(
            "view",
            "uploaded",
            "rating",
            "follow",
            "user_follow_count",
        )
    override val allGenres: Set<String>
        get() = setOf(
            "action",
            "adult",
            "adventure",
            "comedy",
            "crime",
            "drama",
            "fantasy",
            "gender Bender",
            "historical",
            "horror",
            "isekai",
            "mecha",
            "medical",
            "mystery",
            "psychological",
            "romance",
            "sci-Fi",
            "slice of Life",
            "sports",
            "superhero",
            "thriller",
            "tragedy",
            "wuxia",
            )

    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> {

        return when (searchType) {

            is SearchType.Normal -> {
         fetchData("${apiUrl}v1.0/search?limit=50&page=1&tachiyomi=true&q=${searchType.toNormalQuery()}")
        { html ->
            extractSearchMangaItems(
                html
            ).filter {
                !it.genres.hasBlacklistedGenreInt()
            }.StoMangaItems(API, LANGUAGE)
        }
      }



            is SearchType.GENRES -> {
                fetchData("${apiUrl}v1.0/search?genres=${searchType.genres}&limit=50&page=1&tachiyomi=true")
                { html ->
                    extractSearchMangaItems(
                        html
                    ).filter {
                        !it.genres.hasBlacklistedGenreInt()
                    }.StoMangaItems(API, LANGUAGE)
                }
            }

            is SearchType.SORT -> {
                fetchData("${apiUrl}v1.0/search?genres=${searchType.genres}&limit=50&page=1&excludes=${searchType.query}&tachiyomi=true&sort=${searchType.sortType}")
                { html ->
                    extractSearchMangaItems(
                        html
                    ).filter {
                        !it.genres.hasBlacklistedGenreInt()
                    }.StoMangaItems(API, LANGUAGE)
                }
            }


        }
        }

    fun extractSearchMangaItems(json: String): List<SearchItem> {
        val comickItems: List<SearchItem> = jsonParser.decodeFromString(json)
        return comickItems
    }



    fun extractHomeMangaItems(json: String): MutableList<MangaItem> {
        val TAG = "ComickParser"
        try {
            Log.i(TAG, "Parsing homeV2 JSON =${json})")
            val comickItems:  List<homeV2Item>  = jsonParser.decodeFromString(json)
            val result = comickItems
                .toMangaItemsV2(API, LANGUAGE, apiUrl)
                .filter { manga -> !manga.genres.hasBlacklistedGenre() }
                .toMutableList()

            Log.i(TAG, "Parsed successfully — items count = ${result.size}")
            return result
        } catch (e: SerializationException) {
            Log.e(TAG, "SerializationException while decoding homeV2 JSON: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception while extracting manga items: ${e.message}", e)
        }

        // return empty list on failure
        Log.w(TAG, "extractHomeMangaItems returning empty list due to previous errors")
        return mutableListOf()
    }

    private val publishedDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    override suspend fun fetchMangaChaptersF(url: String): Flow<State<MangaInfo>> {

     Log.i("asfsldhfsdgsfdgdfgdsfgdsfgds",url.toString())
        val infoUrl     = "$url?tachiyomi=true&lang=en"
        val chaptersUrl = "$url/chapters?tachiyomi=true&limit=99999&lang=$language"

        // 1) Create a Flow<State<MangaInfo>> for the “info” endpoint:
        val infoFlow: Flow<State<MangaInfo?>> = fetchData(infoUrl) { html ->
            extractMangaInfo(html)?.toMangaInfo(API, LANGUAGE, url)
        }

        // 2) Create a Flow<State<List<ChapterItem>>> for the “chapters” endpoint,
        //    and if it errors out, convert that error into a Success(emptyList()).
        val chaptersFlow: Flow<State<List<ChapterItem>>> =
            fetchData(chaptersUrl) { html ->
                extractMangaInfoChapters(html)
                    .chapters
                    .orEmpty()

                    .toChapterItem("${apiUrl}chapter")
            }
                // If fetchData(...) for chaptersUrl ever throws an exception internally,
                // catch it here and emit Success(emptyList()) instead.
                .catch { e ->
                    emit(State.Success(emptyList()))
                }
                // If the HTTP call itself succeeded but returned a State.Error, map that Error→Success(emptyList()):
                .map { state ->
                    when (state) {
                        is State.Success -> state
                        is State.Error -> {
                            State.Success(emptyList())
                        }
                        is State.Loading -> State.Loading
                    }
                }


        // 3) Combine both flows so that we can react whenever either one emits Loading/Success/Error.
        //    We immediately emit State.Loading, and then wait until both have emitted at least once.
        return flow {
            emit(State.Loading)

            infoFlow
                .combine(chaptersFlow) { infoState, chapState ->
                    Pair(infoState, chapState)
                }
                .collect { (infoState, chapState) ->
                    // 3a) If the “info” call is still Loading, we stay in Loading.
                    if (infoState is State.Loading || chapState is State.Loading) {
                        emit(State.Loading)
                        return@collect
                    }

                    // 3b) If “info” failed completely (i.e. State.Error), forward that error:
                    if (infoState is State.Error) {
                        emit(State.Error(0,infoState.message))
                        return@collect
                    }

                    // 3c) Otherwise, infoState is State.Success<MangaInfo?>. If the MangaInfo inside is null, treat as error:
                    val mangaInfo: MangaInfo? = (infoState as? State.Success)?.data
                    if (mangaInfo == null) {
                        emit(State.Error(0,"Failed to parse MangaInfo"))
                        return@collect
                    }

                    // 3d) chapState at this point is either Loading (handled above), or State.Success(empty or non‐empty list).
                    val chapterList: List<ChapterItem> = (chapState as? State.Success)?.data.orEmpty()

                    // 3e) Fill the MangaInfo’s .chapters field and emit Success:
                    mangaInfo.chapters.clear()
                    mangaInfo.chapters.addAll(chapterList)
                    emit(State.Success(mangaInfo))
                }
        }
    }


        fun extractMangaInfo(json: String): Info? {
            return try {
                val comickItems: Info = jsonParser.decodeFromString(json)
                comickItems
            } catch (e: Exception) {
                null
            }
        }

        fun extractMangaInfoChapters(json: String): infochapters {
            return try {
                val currentTimestamp = System.currentTimeMillis()

                val comickItems: infochapters = jsonParser.decodeFromString(json)
                // Filter chapters by publish date
                val filteredChapters = comickItems.chapters?.filter {
                    val publishTime = it?.publish_at
                        // if it's either null or blank, skip parsing and treat as "not yet published"
                        ?.takeIf { s -> s.isNotBlank() }
                        ?.let { nonNullPublishAt ->
                            try {
                                publishedDateFormat.parse(nonNullPublishAt)?.time
                            } catch (e: ParseException) {
                                0L
                            }
                        }
                    // if any of the above was null, fall back to 0L
                        ?: 0L


                    val publishedChapter = publishTime <= currentTimestamp

                    publishedChapter


                } ?: emptyList()

                // Return a copy of the original object with only the filtered chapters
                comickItems.copy(chapters = filteredChapters)
            } catch (e: Exception) {

                infochapters(
                    chapters = listOf(),
                    total = 10,
                    checkVol2Chap1 = true,
                    limit = 1000,
                )
            }
        }


    override fun fetchMoreManga(
        page: Int,
        currentItems: List<MangaItem>?
    ): Flow<State<List<MangaItem>>> =
        flow {
            if (page > 50 ) return@flow
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
            emit(State.Error(0,e.localizedMessage ?: "Unknown error occurred"))
        }


    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        fetchData("${apiUrl}v1.0/search?sort=follow&tachiyomi=true&q=") { html ->
            extractSearchMangaItems(
                html
            )
//                .filter {
//                !it.genres.hasBlacklistedGenreInt()
//            }
                .StoPopularManga(API, LANGUAGE).shuffled()
        }


    fun extractChapterImgs(json: String): List<String> {

        val comickItems: ChapterImgs = jsonParser.decodeFromString(json)
// If you want to keep only non‐null names:
            val names: List<String> = comickItems.chapter.md_images
            .mapNotNull { "${imgBaseUrl}${it.b2key}" } ?:  comickItems.chapter.md_images
                .mapNotNull { "${imgBaseUrl}${it.b2key}" }


        return names
    }

    // Modify the fetchData function to accept a suspend lambda for transform.
    private inline fun <T> fetchData(
        baseUrl: String,
        crossinline transform: suspend (htmlContent: String) -> T
    ): Flow<State<T>> = flow {
        emit(State.Loading)


        try {
            val response = api.getData(baseUrl)
            if (response.isSuccessful) {
                val htmlContent = response.body().orEmpty()

                // Now transform can be invoked as a suspend function
                val parsedData = transform(htmlContent)
                emit(State.Success(parsedData))
            } else {
                val errorMessage =
                    response.errorBody()?.string().orEmpty().ifEmpty { "Unexpected error" }
                emit(State.Error(0,errorMessage))
            }
        } catch (e: Exception) {
            emit(State.Error(0,e.localizedMessage ?: "Unknown error occurred"))
        }
    }

    fun List<homeV2Item>.toMangaItemsV2(api: String, language: String, baseUrl: String): List<MangaItem> =
        map { item ->
            val md = item.md_comics
            MangaItem(
                api = api,
                language = language,
                // prefer md_comics.title; fallback to empty string
                title = md?.title ?: "",
                // build reader URL (ensure baseUrl has trailing slash or include it here)
                url = "${baseUrl}comic/${md?.hid.orEmpty()}",
                imageUrl = md?.cover_url ?: "",
                rating = 0,
                // chapters endpoint not present -> leave empty list
                chapters = listOf(),
                // convert genre ids to strings, drop nulls
                genres = md?.genres?.mapNotNull { it?.toString() } ?: listOf()
            )
        }
    private fun List<ComickItem>.toMangaItems(api: String, language: String, baseUrl: String): List<MangaItem> = map { item ->
        MangaItem(
            api = api,
            language = language,
            title = item.title.toString(),
            // build the reader URL however your app expects it:
            url = "${baseUrl}comic/${item.hid}",
            imageUrl = item.cover_url.toString(),
            // use the numeric rating_count, or parse the `rating` string if you prefer
            rating = 0,
            // chapters aren’t in this endpoint, so start null (or emptyList())
            chapters = listOf(),
            genres = item.genres.map { genreId ->
                // either use a lookup:
                // GENRE_MAP[genreId] ?: genreId.toString()
                genreId.toString()
            }
        )
    }

    fun List<Chapter?>.toChapterItem(baseUrl: String):  List<ChapterItem> = map { item ->


        ChapterItem(
            number = item?.chap ?:"0",
            name = item?.title ?: "",
            url = "${baseUrl}/${item?.hid}",
            date = item?.created_at?.let { raw ->
            try {
                if (raw.contains("T")) {
                    LocalDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
                } else {
                    LocalDate.parse(raw, DateTimeFormatter.ISO_DATE)
                }
            } catch (e: Exception) {
                null
            }
        },

        )

    }

    fun Info.toMangaInfo(api: String, language: String, Url: String): MangaInfo {
        // 1) API identifier (you can change this to whatever you want)



        // 3) Use englishLink as the canonical URL to this manga’s page.

        // 4) Extract the nested Comic object once for convenience
        val c = this.comic

        // 5) Title: fallback to empty string if null
        val mangaTitle: String = c?.title.orEmpty()

        // 6) Cover image URL
        val imageUrl: String = c?.cover_url.orEmpty()

        // 7) Bayesian rating (string)
        val bayesianRating: String = c?.bayesian_rating.orEmpty()

        // 8) Number of ratings → convert to String, default "0"
        val ratingCount: String = c?.rating_count
            ?.toString()
            ?: "0"

        // 9) Description (desc)
        val description: String = c?.desc.orEmpty()

        // 10) Other names: join all MdTitle.title values by comma
        val otherNames: String = c
            ?.md_titles
            ?.mapNotNull { it?.title }     // drop any null titles
            ?.joinToString(", ")
            .orEmpty()

        // 11) Authors → join names by comma
        val authorNames: String = this.authors
            ?.mapNotNull { it?.name }
            ?.joinToString(", ")
            .orEmpty()

        // 12) Artists → join names by comma
        val artistNames: String = this.artists
            ?.mapNotNull { it?.name }
            ?.joinToString(", ") ?: ""

        // 13) Genres → from MdComicMdGenre.md_genres?.name
        val genreList: List<String> = c
            ?.md_comic_md_genres
            ?.mapNotNull { mdGenreWrapper: MdComicMdGenre? ->
                mdGenreWrapper
                    ?.md_genres
                    ?.name
            }
            ?: emptyList()

        // 14) Tags → from MuComicCategory.mu_categories?.title
        //      (assumes that c.mu_comics?.mu_comic_categories is a list)
        val tagList: List<String> = c
            ?.mu_comics
            ?.mu_comic_categories
            ?.mapNotNull { catWrapper: MuComicCategory? ->
                catWrapper
                    ?.mu_categories
                    ?.title
            }
            ?: emptyList()

        // 15) Year of production → convert Int? to String
        val yearOfProduction: String = c
            ?.year
            ?.toString()
            .orEmpty()

        // 16) Status → keep as integer converted to String (you can swap this for a human-readable mapping)
        val statusStr: String = when (c?.status) {
            1    -> "Ongoing"
            2    -> "Completed"
            3    -> "Cancelled"
            4    -> "Hiatus"
            null -> "Unknown"   // in case `c` is null or `status` is null
            else  -> "Unknown"  // in case `status` has some other value
        }


        // 17) Favorites count → user_follow_count → convert to String (default "0")
        val favoritesCountStr: String = c
            ?.user_follow_count
            ?.toString()
            ?: "0"

        // 18) Prepare empty chapters list (since ChapterItem isn’t included in Info.kt).
        //     Replace this with your real chapter-mapping logic if/when you have it.

        return MangaInfo(
            api = api,
            language = language,
            url = Url,
            title = mangaTitle,
            imageUrl = imageUrl,
            rating = bayesianRating,
            ratingCount = ratingCount,
            description = description,
            otherNames = otherNames,
            author = authorNames,
            artist = artistNames,
            genres = genreList,
            tags = tagList,
            yearOfProduction = yearOfProduction,
            status = statusStr,
            favoritesCount = favoritesCountStr,
            chapters = mutableListOf()
        )
    }


    fun List<SearchItem>.StoPopularManga(api: String, language: String): List<PopularManga> {
        return this.map { it.toPopularManga(api,language) }
    }
    fun SearchItem.toPopularManga(api: String, language: String): PopularManga {
        // 1) API identifier (you can also make this a constant somewhere)
        val apiName = api

        // 2) Language: if the API tells us this is an English‐titled manga, use "en", otherwise assume "jp"
        val languageCode = language

        // 3) Title: use whatever the SearchItem.title field contains
        val domainTitle = title

        // 4) URL: build a full URL from the slug (adjust the base path if Comick IO’s format changes)
        val domainUrl = "${apiUrl}comic/$hid"

        // 5) Image URL: just forward the cover_url
        val domainImageUrl = cover_url


        return PopularManga(
            api = apiName,
            language = languageCode,
            title = domainTitle ?: "",
            url = domainUrl,
            imageUrl = domainImageUrl ?:"",
        )
    }

    fun List<SearchItem>.StoMangaItems(api: String, language: String): List<MangaItem> {
        return this.map { it.toMangaItem(api,language) }
    }

    fun SearchItem.toMangaItem(api: String, language: String): MangaItem {
        // 1) API identifier (you can also make this a constant somewhere)
        val apiName = api

        // 2) Language: if the API tells us this is an English‐titled manga, use "en", otherwise assume "jp"
        val languageCode = language

        // 3) Title: use whatever the SearchItem.title field contains
        val domainTitle = title

        // 4) URL: build a full URL from the slug (adjust the base path if Comick IO’s format changes)
        val domainUrl = "${apiUrl}comic/$hid"

        // 5) Image URL: just forward the cover_url
        val domainImageUrl = cover_url

        // 6) Rating: SearchItem.rating comes in as a String. We attempt to parse it to Int;
        //    if parsing fails, we store `null`. If you prefer Float or Double, change the domain field.
        val domainRating: Int? = rating?.toFloatOrNull()?.toInt()

        // 7) Chapters: SearchItem doesn’t include chapter details, so for now we set it to null.
        //    Once you fetch chapters from a separate endpoint, you can fill this in.
        val domainChapters = null

        // 8) Genres: SearchItem.genres is a List<Int> of genre‐IDs. If you have a map of ID→Name,
        //    replace the .map { it.toString() } below with a lookup. For now, we just stringify the IDs.
        val domainGenres: List<String>? = genres?.map { it.toString() }

        return MangaItem(
            api = apiName,
            language = languageCode,
            title = domainTitle ?: "",
            url = domainUrl,
            imageUrl = domainImageUrl ?:"",
            rating = domainRating,
            chapters = domainChapters,
            genres = domainGenres?: listOf()
        )
    }


    companion object {
        const val SLUG_SEARCH_PREFIX = "id:"
        private val SPACE_AND_SLASH_REGEX = Regex("[ /]")
        private const val IGNORED_GROUPS_PREF = "IgnoredGroups"
        private const val IGNORED_TAGS_PREF = "IgnoredTags"
        private const val SHOW_ALTERNATIVE_TITLES_PREF = "ShowAlternativeTitles"
        const val SHOW_ALTERNATIVE_TITLES_DEFAULT = false
        private const val INCLUDE_MU_TAGS_PREF = "IncludeMangaUpdatesTags"
        const val INCLUDE_MU_TAGS_DEFAULT = false
        private const val GROUP_TAGS_PREF = "GroupTags"
        const val GROUP_TAGS_DEFAULT = false
        private const val MIGRATED_IGNORED_GROUPS = "MigratedIgnoredGroups"
        private const val FIRST_COVER_PREF = "DefaultCover"
        private const val FIRST_COVER_DEFAULT = true
        private const val SCORE_POSITION_PREF = "ScorePosition"
        const val SCORE_POSITION_DEFAULT = "top"
        private const val LOCAL_TITLE_PREF = "LocalTitle"
        private const val LOCAL_TITLE_DEFAULT = false
        private const val CHAPTER_SCORE_FILTERING_PREF = "ScoreAutoFiltering"
        private const val CHAPTER_SCORE_FILTERING_DEFAULT = false
        private const val LIMIT = 20
        private const val CHAPTERS_LIMIT = 99999
    }
    }