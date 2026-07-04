package me.manga.kira.sources_repositry.en.comick_io

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime,
 * SimpleDateFormat -> kotlin.time.Instant.parse / LocalDate.parse fallbacks.
 *
 * Image-request methods (`buildImageRequest`, `buildItemsImageRequest`) were removed in the
 * Phase 7 batch 7.0 base-class trim — see BaseMangaRepository.kt header.
 *
 * Kept `open class` + `open val language: String = "en"` so the ar/, es/, fr/, in/, it/, pt/,
 * ru/, tr/ Comick subclasses can extend it and override the language code.
 */
import co.touchlab.kermit.Logger
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerializationException
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
import me.manga.kira.sources_repositry.en.comick_io.models.chapter_images.ChapterImgs
import me.manga.kira.sources_repositry.en.comick_io.models.chapters.Chapter
import me.manga.kira.sources_repositry.en.comick_io.models.chapters.infochapters
import me.manga.kira.sources_repositry.en.comick_io.models.home.ComickItem
import me.manga.kira.sources_repositry.en.comick_io.models.homev2.homeV2Item
import me.manga.kira.sources_repositry.en.comick_io.models.info.Info
import me.manga.kira.sources_repositry.en.comick_io.models.info.MdComicMdGenre
import me.manga.kira.sources_repositry.en.comick_io.models.info.MuComicCategory
import me.manga.kira.sources_repositry.en.comick_io.models.search.SearchItem

@OptIn(ExperimentalTime::class)
open class ComickRepository(
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
        get() = MangaSource.COMICKIO.BASEURL
    override val URL_VERSION: Int
        get() = 0
    override var baseUrl: String = ""
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

    open val language: String = "en"


    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
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

    // -----------------------------------------------------------------------------------------
    // Image-request builders removed in the KMP port (see BaseMangaRepository.kt header).
    // The original Android implementation built a Coil3 `ImageRequest` with `defaultHeaders` and
    // an optional pixel size, plus `bitmapConfig(RGB_565)` and `allowHardware(false)`. The
    // headers map is still exposed via `defaultHeaders` so the platform-side image loader can
    // reconstruct the request.
    // -----------------------------------------------------------------------------------------

    override suspend fun getBaseUrl(): String {
        val url = sourcesRepository.getBaseUrlFor(API) ?: apiUrl
        baseUrl = url
        return url
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


    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = fetchHome()


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
        val tag = "ComickParser"
        try {
            Logger.withTag(tag).i { "Parsing homeV2 JSON =$json)" }
            val comickItems: List<homeV2Item> = jsonParser.decodeFromString(json)
            val result = comickItems
                .toMangaItemsV2(API, LANGUAGE, apiUrl)
                .filter { manga -> !manga.genres.hasBlacklistedGenre() }
                .toMutableList()

            Logger.withTag(tag).i { "Parsed successfully — items count = ${result.size}" }
            return result
        } catch (e: SerializationException) {
            Logger.withTag(tag).e(e) { "SerializationException while decoding homeV2 JSON: ${e.message}" }
        } catch (e: Exception) {
            Logger.withTag(tag).e(e) { "Unexpected exception while extracting manga items: ${e.message}" }
        }

        // return empty list on failure
        Logger.withTag(tag).w { "extractHomeMangaItems returning empty list due to previous errors" }
        return mutableListOf()
    }

    /**
     * Original source parsed `publish_at` via
     * `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)` with UTC zone, then
     * compared `.time` (epoch millis) to `System.currentTimeMillis()`.
     *
     * `kotlin.time.Instant.parse(...)` accepts ISO-8601 timestamps with a `Z` suffix (UTC), so
     * we use it directly here. Any parse failure falls back to `0L` (treated as "already
     * published") — matching the source's `ParseException -> 0L` behaviour.
     */
    private fun parsePublishMillis(raw: String): Long {
        return try {
            Instant.parse(raw).toEpochMilliseconds()
        } catch (_: Exception) {
            0L
        }
    }

    override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>> {

        Logger.withTag("asfsldhfsdgsfdgdfgdsfgdsfgds").i { query }
        val infoUrl = "$query?tachiyomi=true&lang=en"
        val chaptersUrl = "$query/chapters?tachiyomi=true&limit=99999&lang=$language"

        // 1) Create a Flow<State<MangaInfo>> for the “info” endpoint:
        val infoFlow: Flow<State<MangaInfo?>> = fetchData(infoUrl) { html ->
            extractMangaInfo(html)?.toMangaInfo(API, LANGUAGE, query)
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
                        emit(State.Error(0, infoState.message))
                        return@collect
                    }

                    // 3c) Otherwise, infoState is State.Success<MangaInfo?>. If the MangaInfo inside is null, treat as error:
                    val mangaInfo: MangaInfo? = (infoState as? State.Success)?.data
                    if (mangaInfo == null) {
                        emit(State.Error(0, "Failed to parse MangaInfo"))
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
            val currentTimestamp = Clock.System.now().toEpochMilliseconds()

            val comickItems: infochapters = jsonParser.decodeFromString(json)
            // Filter chapters by publish date
            val filteredChapters = comickItems.chapters?.filter {
                val publishTime = it?.publish_at
                    // if it's either null or blank, skip parsing and treat as "not yet published"
                    ?.takeIf { s -> s.isNotBlank() }
                    ?.let { nonNullPublishAt ->
                        parsePublishMillis(nonNullPublishAt)
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
            if (page > 50) return@flow
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
            .mapNotNull { "${imgBaseUrl}${it.b2key}" } ?: comickItems.chapter.md_images
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
            val response = api.get(baseUrl, defaultHeaders)
            if (response.status.isSuccess()) {
                val htmlContent = response.bodyAsText()

                // Now transform can be invoked as a suspend function
                val parsedData = transform(htmlContent)
                emit(State.Success(parsedData))
            } else {
                val errorMessage =
                    runCatching { response.bodyAsText() }.getOrNull().orEmpty().ifEmpty { "Unexpected error" }
                emit(State.Error(0, errorMessage))
            }
        } catch (e: Exception) {
            emit(State.Error(0, e.message ?: "Unknown error occurred"))
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

    fun List<Chapter?>.toChapterItem(baseUrl: String): List<ChapterItem> = map { item ->


        ChapterItem(
            number = item?.chap ?: "0",
            name = item?.title ?: "",
            url = "${baseUrl}/${item?.hid}",
            date = item?.created_at?.let { raw ->
                try {
                    if (raw.contains("T")) {
                        // ISO-8601 datetime with offset/Z — Instant.parse handles it.
                        Instant.parse(raw)
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                            .date
                    } else {
                        // Plain date (yyyy-MM-dd) — kotlinx.datetime.LocalDate.parse handles ISO_DATE.
                        LocalDate.parse(raw)
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
            description = description,
            author = authorNames,
            genres = genreList,
            status = statusStr,
            chapters = mutableListOf()
        )
    }


    fun List<SearchItem>.StoPopularManga(api: String, language: String): List<PopularManga> {
        return this.map { it.toPopularManga(api, language) }
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
            imageUrl = domainImageUrl ?: "",
        )
    }

    fun List<SearchItem>.StoMangaItems(api: String, language: String): List<MangaItem> {
        return this.map { it.toMangaItem(api, language) }
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
            imageUrl = domainImageUrl ?: "",
            rating = domainRating,
            chapters = domainChapters,
            genres = domainGenres ?: listOf()
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

/*
 * Audit-trail postscript (Phase 9.x.cluster196.staleKdocSweep.cascade, Task #651, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster196 leaf 5/5 — closing leaf, sibling 340. Closes the :en/ Repository tier heavy-half
 * 5-leaf §253 sweep: BatotoEnRepositoryv2 (571) + MangaParkRepository (708) + ZazamangaRepository
 * (747) + BatcaveRepository (796) + ComickRepository (801) = 3623 lines total. Cumulative
 * §253-postscript count after cluster196 lands: 65.
 *
 * Preamble (lines 3-13) classified LIVE-NOT-STALE — Phase 7.2 migration receipts (Retrofit→Ktor,
 * jsoup→ksoup, FormBody→Map, @Inject drop, android.util.Log→Kermit, java.time→kotlinx.datetime,
 * SimpleDateFormat→Instant.parse/LocalDate.parse). Identical preamble fingerprint to all four
 * sibling cluster196 leaves (336-339) — Phase 7.2 was a uniform sweep across the :en/ tier.
 *
 * File-specific classifications (4-bullet tail of the preamble):
 *
 *   1. FULFILLED-PORT — Image-request builder removal (preamble lines 8-9 + retained
 *      explanation block at lines 108-114). `buildImageRequest`/`buildItemsImageRequest` were
 *      trimmed in Phase 7 batch 7.0 base-class trim; `defaultHeaders` Map is the surviving seam
 *      that platform-side image loaders reconstruct from. Confirmed pattern across all cluster196
 *      leaves — same FULFILLED-PORT receipt fingerprint.
 *
 *   2. PARTIALLY-FULFILLED-FORECAST — `open class ComickRepository` + `open val language: String
 *      = "en"` (line 85) for the 8 language-pack subclass forecast (preamble lines 11-12: ar, es,
 *      fr, in, it, pt, ru, tr). Cluster191 leaf 5/5 (sibling 316) confirmed `ComickRepositoryAr`
 *      exists; remaining 7 subclasses unverified at cluster196 boundary. Same partial-fulfillment
 *      shape as MangaParkRepository sibling 337 (also 8-subclass language-pack forecast).
 *
 *   3. LIVE-NOT-STALE — `@Volatile private var _cachedHeaders` (lines 88-89) + `defaultHeaders`
 *      getter Elvis-fallback to `emptyMap()` (lines 95-96) + `refreshHeaders` write-through
 *      (lines 99-106). PLAIN VARIANT — no initSite preload (unlike MangaParkRepository sibling
 *      337) and no Referer merge (unlike ZazamangaRepository sibling 338 and BatcaveRepository
 *      sibling 339). ComickRepository is the simplest cache shape in cluster196 — `DataStore`
 *      write-through only, no Cloudflare gating, no Referer pinning. Reflects ComickIO's REST
 *      API origin (api.comick.fun is a JSON API, not a scraped HTML site).
 *
 *   4. LIVE-NOT-STALE — `parsePublishMillis` migration narrative (lines 307-322). Explicit
 *      SimpleDateFormat→Instant.parse port with ParseException→0L fallback preserved verbatim.
 *      Same migration-receipt idiom as cluster196 sibling 339 (BatcaveRepository's 14
 *      DateTimeFormatter ports), here narrowed to a single ISO-8601 timestamp parser.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • COSMETIC-NOT-STALE — `blackListGenres` set commented-out ID prologue at lines 124-137
 *     (the upstream Comick genre-ID→name mapping retained as prose: 299=Genderswap, 2=Adult,
 *     296=Crossdressing, 251=Ecchi, 1=Gender Bender, 320=Incest, 302=Loli, 3=Mature, 287=Sexual
 *     Violence, 267=Shoujo Ai, 268=Shounen Ai, 270=Smut, 275=Yaoi, 276=Yuri). String-version of
 *     the set at lines 138-152 omits "Ecchi" (line 141 commented) and "Mature" (line 145
 *     commented). Companion `blackListInt` set at lines 154-169 omits 251 (Ecchi, line 158) and
 *     28 (unknown, line 163). The two filter shapes are NOT in sync — the string set excludes
 *     "Ecchi"/"Mature" while the int set excludes 251 ("Ecchi") and an enigmatic 28 (no comment
 *     on what genre 28 maps to in the prose). Classification straddles COSMETIC-NOT-STALE (the
 *     decision to disable Ecchi/Mature filtering at the user-content level) and
 *     POTENTIAL-BUG-PRESERVED (the two-filter disagreement on whether 251/28 are filtered at
 *     the int level: code paths using `hasBlacklistedGenreInt` skip 251/28 while `hasBlacklistedGenre`
 *     does not — though the int set is the authority since `toStringSet()` derives from it).
 *
 *   • POTENTIAL-BUG-PRESERVED — `extractChapterImgs` (lines 492-502): Elvis fallback at line 497
 *     `?: comickItems.chapter.md_images.mapNotNull { "${imgBaseUrl}${it.b2key}" }` is
 *     UNREACHABLE. The LHS is itself a `mapNotNull` over `md_images` whose lambda always
 *     returns a non-null string `"${imgBaseUrl}${it.b2key}"` (string interpolation can never
 *     produce null). The Elvis can never trigger — both branches compute identical lists. Likely
 *     a copy-paste artifact from an earlier draft where `b2key` was nullable and `mapNotNull`
 *     filtered nulls. Preserved per §253 (POTENTIAL-BUG-PRESERVED — observable behaviour
 *     unchanged; LHS always wins).
 *
 *   • FORECAST-NOT-YET-FULFILLED — `private fun List<ComickItem>.toMangaItems` at lines 549-567.
 *     This converter consumes the legacy `ComickItem` schema (singular, non-V2), but it is
 *     UNCALLED anywhere in the file body. The active conversion path is `homeV2Item.toMangaItemsV2`
 *     (line 530) which `extractHomeMangaItems` (line 290) invokes against the homev2 JSON tree.
 *     The `ComickItem` schema in `:en/comick_io/models/home` remains imported (line 46) only so
 *     this orphan converter compiles. Either FORECAST (a future v3/v4 schema migration was
 *     anticipated and this is the rollback path) or unscrubbed legacy. Preserved.
 *
 *   • FORECAST-NOT-YET-FULFILLED — companion-object constants block (lines 778-800): 14
 *     Tachiyomi-upstream preference-key constants (`SLUG_SEARCH_PREFIX`, `IGNORED_GROUPS_PREF`,
 *     `IGNORED_TAGS_PREF`, `SHOW_ALTERNATIVE_TITLES_PREF`, `INCLUDE_MU_TAGS_PREF`,
 *     `GROUP_TAGS_PREF`, `MIGRATED_IGNORED_GROUPS`, `FIRST_COVER_PREF`, `SCORE_POSITION_PREF`,
 *     `LOCAL_TITLE_PREF`, `CHAPTER_SCORE_FILTERING_PREF`, `LIMIT=20`, `CHAPTERS_LIMIT=99999`)
 *     plus 5 default-value pairs. NONE of these are referenced in the body. Direct fingerprint
 *     of the tachiyomi-extensions Comick.kt source — preserved as a forecast surface for the
 *     eventual preferences UI port (covered by deferred Phase 7.x.details.chapterfilters and
 *     a future Phase 10.x preferences UI scope). DEBT-NOT-STALE adjacent: the LIMIT=20 and
 *     CHAPTERS_LIMIT=99999 constants ARE potentially live (the body uses `&limit=50` and
 *     `&limit=99999` as inline literals at lines 241/253/264/328 — they ignore the constants).
 *     The inline-literal drift is a SECOND POTENTIAL-BUG-PRESERVED: changing the constants
 *     would NOT change the request payload because the body bypasses them.
 *
 *   • DEBT-NOT-STALE — `toMangaInfo` (lines 596-701): the orphan-extracted "// 1)" through
 *     "// 18)" comment cadence + 6 unused intermediate locals (`bayesianRating` at line 612
 *     IS used at line 694 as `rating`; `otherNames` line 623, `artistNames` line 636, `tagList`
 *     line 652, `yearOfProduction` line 663, `favoritesCountStr` line 680 are all computed and
 *     DISCARDED — never set on the returned `MangaInfo` constructor at lines 688-700). The
 *     `MangaInfo` data class evidently does not carry fields for `otherNames`, `artist`,
 *     `tags`, `year`, `favoritesCount`. The intermediate computation is dead-write debt
 *     preserved for the eventual `MangaInfo` schema widening. Also notable: the function
 *     signature declares `Url: String` parameter (capital U, Kotlin convention violation) at
 *     line 596 — preserved per §253 (cosmetic).
 *
 *   • LIVE-NOT-STALE — `infoFlow.combine(chaptersFlow)` parallel-IO at lines 364-399. Same
 *     pattern fingerprint as MangaParkRepository sibling 337 (lines 292-294 of that file):
 *     `emit(State.Loading)` → `combine` → collect `(infoState, chapState)` → bail on Loading,
 *     forward info Error, treat null parse as Error, allow chapters Error → empty list via
 *     `.catch { emit(Success(emptyList())) }` chain at lines 347-359. Chapters Error explicitly
 *     does NOT fail the manga details fetch — info populates the screen even when the
 *     chapters endpoint 5xx's. Behaviourally identical to MangaPark — the parallel-IO+
 *     graceful-chapters-degradation idiom is a cross-cluster convention.
 *
 *   • LIVE-NOT-STALE — `fetchMoreManga` 50-page hard cap at line 455 (`if (page > 50) return@flow`).
 *     Matches MangaParkRepository sibling 337 pattern (line 323 of that file). Cross-cluster
 *     convention for pagination upper-bound.
 *
 *   • LIVE-NOT-STALE — ISO-8601 chapter-date parsing chain in `List<Chapter?>.toChapterItem`
 *     at lines 576-589: `T`-detection branches between `Instant.parse(...).toLocalDateTime(...)
 *     .date` (datetime with offset/Z) and `LocalDate.parse(raw)` (ISO_DATE). Catches `Exception`
 *     → null. Same migration-receipt idiom as BatcaveRepository sibling 339's 14
 *     DateTimeFormatter ports, here narrowed to a single 2-branch chain.
 *
 *   • LIVE-NOT-STALE — status-int mapping at lines 669-676 (1→Ongoing, 2→Completed, 3→Cancelled,
 *     4→Hiatus, null/else→Unknown). Direct upstream-Comick status enum carried verbatim.
 *
 *   • COSMETIC-NOT-STALE — `fetchPopularManga` commented-out blacklist filter at lines 485-487
 *     (`//.filter { !it.genres.hasBlacklistedGenreInt() }`). Disabled-by-default for the
 *     "popular" surface — popular manga show all genres regardless of user blacklist.
 *     Same pattern as ZazamangaRepository's commented-out filter at lines 367-371 (sibling 338).
 *
 *   • DEBT-NOT-STALE — `extractMangaInfoChapters` fallback constructor at lines 440-446
 *     (`infochapters(chapters=listOf(), total=10, checkVol2Chap1=true, limit=1000)`). The
 *     fallback values are arbitrary placeholders — `total=10`, `checkVol2Chap1=true`,
 *     `limit=1000` carry no behavioural meaning when chapters is empty. Preserved because
 *     `infochapters` constructor likely requires those fields non-null.
 *
 *   • DEBUG-TAG NOISE — scrambled `Logger.withTag("asfsldhfsdgsfdgdfgdsfgdsfgds")` at line 326.
 *     Single occurrence (lighter than the 4-tag ComickRepository neighbour pattern). Cross-cluster
 *     fingerprint of the scrambled keyboard-mash tag idiom (cluster196 leaves 2/5: 2 tags,
 *     3/5: 5 tags HEAVIEST, 4/5: 4 tags, 5/5: 1 tag — the cluster196 distribution).
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — six imports from `:en/comick_io/models` subtree at lines
 *     43-51: chapter_images.ChapterImgs, chapters.Chapter, chapters.infochapters, home.ComickItem,
 *     homev2.homeV2Item, info.Info + Info.MdComicMdGenre + Info.MuComicCategory + search.SearchItem.
 *     ComickRepository is the canonical consumer of the `:en/comick_io/models` JSON schema
 *     tree. ComickRepositoryAr (sibling 316, cluster191) inherits this consumption via `open
 *     class` extension. The models subtree's lifecycle is bound to this file — retiring
 *     ComickRepository would orphan ~8 model packages.
 *
 * Cross-cluster pattern register (cluster196 5-leaf arc, sibling indices 336-340):
 *
 *   • Volatile _cachedHeaders pattern variants observed in cluster196:
 *       - PLAIN write-through only:           ComickRepository sibling 340 (this file)
 *       - + initSite preload:                 MangaParkRepository sibling 337 (lines 111-115)
 *       - + Referer-merge:                    ZazamangaRepository sibling 338 + BatcaveRepository sibling 339
 *       - + initSite + 6-step Cloudflare:     BatcaveRepository sibling 339 (lines 86-146)
 *     Three orthogonal axes (write-through / initSite / Referer / Cloudflare) — the cluster196
 *     5-leaf set covers all observed combinations.
 *
 *   • Flow.combine parallel-IO with graceful-chapters-degradation: observed in MangaParkRepository
 *     sibling 337 and ComickRepository sibling 340 (this file). Both REST-API repositories with
 *     separate info+chapters endpoints. NormalSites/SeparatedDetailsSites HTML-scraping
 *     repositories do NOT use this idiom (they bundle info+chapters in one HTML fetch).
 *
 *   • Debug-tag noise distribution across cluster196: leaf 1/5 (BatotoEn): 0 tags. Leaf 2/5
 *     (MangaPark): 2 tags. Leaf 3/5 (Zazamanga): 5 tags HEAVIEST. Leaf 4/5 (Batcave): 4 tags.
 *     Leaf 5/5 (Comick): 1 tag. Total cluster196: 12 scrambled tags. Heaviest concentration
 *     in the middle of the cluster — possibly a single dev's debug session left across 3
 *     adjacent files (Zazamanga + Batcave + neighbouring DLE-CMS-shaped sources).
 *
 *   • POTENTIAL-BUG-PRESERVED count across cluster196: leaf 1/5 (duplicate Yaoi(BL)), leaf 2/5
 *     (none observed of bug-shape, only debt), leaf 3/5 (duplicate yaoi/Yaoi + empty sortTypes),
 *     leaf 4/5 (companion-object TAG copy-paste "DemonicScansRepository"), leaf 5/5 (unreachable
 *     Elvis in extractChapterImgs + inline-literal drift from LIMIT/CHAPTERS_LIMIT constants).
 *     Five distinct preserved-bug fingerprints across the 5-leaf arc.
 *
 * Cluster196 5-leaf arc closes here. Next cluster (197) target: TBD — pending Task #651
 * completion, build-gate validation, and Task #652 creation. Likely candidates for cluster197
 * surface: remaining :en/ Repository tier heavy-half spillover (if any > 500-line files remain),
 * or :ar//:es//:fr/ language-pack Repository tier opening, or :sources_repositry root-tier
 * orchestration (BaseMangaRepository, NormalSites/v2, SeparatedDetailsSites/v2 abstract
 * superclasses).
 */
