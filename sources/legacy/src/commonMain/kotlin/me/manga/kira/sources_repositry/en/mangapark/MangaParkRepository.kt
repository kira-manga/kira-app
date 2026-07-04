package me.manga.kira.sources_repositry.en.mangapark

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Notes specific to this file:
 *  - Coil3 image-request builders (`buildImageRequest`, `buildItemsImageRequest`) removed
 *    in Phase 7.0 from BaseMangaRepository — the Android-side overrides are dropped here
 *    too. `defaultHeaders` is still exposed so the UI layer can reconstruct the request.
 *    See BaseMangaRepository's migration note for details.
 *  - Source's `LocalDateTime.ofEpochSecond(timestamp / 1000, 0, ZoneOffset.UTC).toLocalDate()`
 *    → `Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.UTC).date`. The
 *    source divides the timestamp by 1000 (treating it as ms) and then re-applies seconds
 *    semantics — that's a bug-shaped construction but preserved verbatim: we feed the raw
 *    millis straight to `fromEpochMilliseconds`, which is the semantically-equivalent KMP
 *    expression.
 *  - Retrofit `Response<String>` → Ktor `HttpResponse`. `response.isSuccessful` →
 *    `response.status.isSuccess()`, `body().orEmpty()` → `bodyAsText()`, `code()` →
 *    `status.value`, `errorBody()?.string()` → `bodyAsText()` (Ktor doesn't differentiate
 *    error body; the body text is read after status check).
 *  - `class MangaParkRepository` is **open** because subclasses in other language packs
 *    (`ar/`, `es/`, `fr/`, `in/`, `it/`, `pt/`, `ru/`, `tr/` mangapark variants) extend it
 *    and override only `language`. The class is the parent for Phase 7.3/7.5 ports.
 */

import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import co.touchlab.kermit.Logger
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
import me.manga.kira.sources_repositry.en.mangapark.models.ChapterListResponse
import me.manga.kira.sources_repositry.en.mangapark.models.DetailsResponse
import me.manga.kira.sources_repositry.en.mangapark.models.GraphQL
import me.manga.kira.sources_repositry.en.mangapark.models.IdVariables
import me.manga.kira.sources_repositry.en.mangapark.models.MangaParkChapter
import me.manga.kira.sources_repositry.en.mangapark.models.MangaParkManga
import me.manga.kira.sources_repositry.en.mangapark.models.PageListResponse
import me.manga.kira.sources_repositry.en.mangapark.models.SearchPayload
import me.manga.kira.sources_repositry.en.mangapark.models.SearchResponse
import me.manga.kira.sources_repositry.en.mangapark.models.SearchVariables

@OptIn(ExperimentalTime::class)
open class MangaParkRepository(
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
    open val language: String = "en"

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

    private fun fetchPages(url: String): Flow<State<List<String>>> {

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
        emit(State.Error(0, e.message ?: "Unknown error occurred"))
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
            Logger.withTag("MangaParkRepository").i { " extractSearchManga =  $json" }

            val response = jsonParser.decodeFromString<SearchResponse>(json)
            response.data.searchComics.items.map { it.data }
        } catch (e: Exception) {
            Logger.withTag("MangaParkRepository").e(e) { "Error parsing search manga" }
            emptyList()
        }
    }

    private fun extractMangaDetails(json: String): MangaParkManga? {
        return try {
            Logger.withTag("MangaParkRepository").i { " extractMangaDetails =  $json" }

            val response = jsonParser.decodeFromString<DetailsResponse>(json)
            response.data.comic.data
        } catch (e: Exception) {
            Logger.withTag("MangaParkRepository").e(e) { "Error parsing manga details" }
            null
        }
    }

    private fun extractChapterList(json: String): List<MangaParkChapter> {
        return try {

            Logger.withTag("MangaParkRepository").i { " extractChapterList =  $json" }

            val response = jsonParser.decodeFromString<ChapterListResponse>(json)
            response.data.chapterList.map { it.data }
        } catch (e: Exception) {
            Logger.withTag("MangaParkRepository").e(e) { "Error parsing chapter list" }
            emptyList()
        }
    }


    private fun extractChapterPages(json: String): List<String> {
        return try {
            Logger.withTag("MangaParkRepositoraadady").i { "jsooon  === $json" }

            val response = jsonParser.decodeFromString<PageListResponse>(json)
            response.data.chapterPages.data.imageFile.urlList
        } catch (e: Exception) {
            Logger.withTag("MangaParkRepositoraadady").e(e) { "Error parsing chapter pages" }
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
                    incTLangs = incTLangs
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
            description = summary ?: "",
            author = authors?.joinToString(", ") ?: "",
            genres = genres ?: emptyList(),
            status = when (originalStatus ?: uploadStatus) {
                "ongoing" -> "Ongoing"
                "completed" -> "Completed"
                "hiatus" -> "Hiatus"
                "cancelled" -> "Cancelled"
                else -> "Unknown"
            },
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
                    // Source did `LocalDateTime.ofEpochSecond(timestamp / 1000, 0, ZoneOffset.UTC)`
                    // which is `Instant.ofEpochMilli(timestamp).atZone(UTC).toLocalDate()` —
                    // KMP equivalent below.
                    Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.UTC).date
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

                Logger.withTag("MangaParkRepositoryccassadas").i { "url: $url" }
                api.postJson(url, payload, defaultHeaders)
            } else {
                api.get(url, defaultHeaders)
            }

            if (response.status.isSuccess()) {
                val responseBody = response.bodyAsText()
                val parsedData = transform(responseBody)
                emit(State.Success(parsedData))
            } else {
                val errorMessage = response.bodyAsText()
                    .ifEmpty { "HTTP ${response.status.value}: ${response.status.description}" }
                emit(State.Error(response.status.value, errorMessage))
            }
        } catch (e: Exception) {
            emit(State.Error(0, e.message ?: "Unknown error occurred"))
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

/**
 * Audit-trail postscript (Phase 9.x.cluster196.staleKdocSweep.cascade, Task #651, 2026-05-29)
 *
 * Leaf 2/5 §253 audit-trail-preservation postscript for cluster196, sibling 337. 708-line
 * BaseMangaRepository direct subclass — distinguished from cluster195 + cluster196 leaf 1/5
 * (BatotoEnRepositoryv2 / NormalSites) and earlier cluster194/etc leaves (SeparatedDetailsSites)
 * by NOT extending any intermediate base. The class implements all abstract fetch* methods
 * directly. Parent class for the multi-language-pack mangapark family — declared `open` with
 * `open val language: String = "en"` (cluster192 leaf 1/5 MangaParkRepositoryAr at sibling 317
 * is one such subclass).
 *
 * The top-of-file prose under audit (lines 3-25) is a single file-header KDoc block carrying
 * two distinct sub-sections:
 *
 *   I.   Phase 7.2 migration-pattern enumeration (lines 4-5) — standard 6-bullet preamble.
 *
 *   II.  File-specific Phase 7.2 KMP-port notes (lines 7-24) — 4 bullets covering:
 *        (a) Coil3 image-request builders (buildImageRequest, buildItemsImageRequest) removed
 *            in Phase 7.0 from BaseMangaRepository; Android-side overrides dropped; but
 *            defaultHeaders preserved so the UI layer can reconstruct the request.
 *        (b) The timestamp-millis bug-shaped construction preserved: source's
 *            `LocalDateTime.ofEpochSecond(timestamp / 1000, 0, ZoneOffset.UTC)` treated millis
 *            as seconds-after-dividing, which is mathematically the same as feeding millis
 *            straight to Instant.fromEpochMilliseconds — the KMP port chose the cleaner
 *            expression with an inline comment at lines 525-527 documenting the equivalence.
 *        (c) Retrofit Response semantics mapping (4 method equivalences:
 *            isSuccessful → status.isSuccess, body().orEmpty() → bodyAsText, code() →
 *            status.value, errorBody().string() → bodyAsText).
 *        (d) Class openness rationale — subclasses in 8 other language packs
 *            (ar, es, fr, in, it, pt, ru, tr mangapark variants) extend MangaParkRepository
 *            overriding only `language`.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — sub-section I (Phase 7.2 6-bullet migration-pattern preamble).
 *
 *   b. LIVE-NOT-STALE — sub-section II (4-bullet file-specific notes):
 *        - bullet (a): cross-verified — no buildImageRequest / buildItemsImageRequest overrides
 *          in the file body; `defaultHeaders` getter at lines 108-109 returns _cachedHeaders or
 *          emptyMap(). Phase 7.0 removal landed.
 *        - bullet (b): cross-verified — toChapterItem at lines 515-534 uses
 *          Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.UTC).date wrapped
 *          in a try/catch with null fallback (lines 528-530). Inline comment at lines 525-527
 *          duplicates the preamble note — redundant-but-LIVE.
 *        - bullet (c): cross-verified — fetchData at lines 537-566 uses
 *          response.status.isSuccess(), response.bodyAsText(), and response.status.value per
 *          Ktor 3 conventions.
 *        - bullet (d): cross-verified — class declared `open` at line 65, `language` declared
 *          `open val language: String = "en"` at line 79.
 *
 *   c. PARTIALLY-FULFILLED-FORECAST — Phase 7.3/7.5 sub-section II bullet (d) forecast
 *      partially fulfilled: cluster192 leaf 1/5 MangaParkRepositoryAr (sibling 317) extends
 *      this class. The other 7 language-pack variants (es, fr, in, it, pt, ru, tr mangapark)
 *      remain forecast targets — not yet surveyed in audit-trail sweeps. Forecast holds.
 *
 *   d. DEBUG-TAG NOISE — 2 unconventional Kermit tags appear in the source:
 *      `MangaParkRepositoraadady` (extractChapterPages at lines 401 + 406) and
 *      `MangaParkRepositoryccassadas` (fetchData at line 548). Same cross-cluster category as
 *      cluster195 leaf 2/5 DemonicScansRepository's debug-tag-noise companion object.
 *      Preserved per §253 — fixing would change the observable log-line prefix.
 *
 *   e. COSMETIC-NOT-STALE — 2 commented-out blackListGenres entries (`//"adult"` at line 140,
 *      `//"mature"` at line 141). Toggled out during upstream content-policy iterations.
 *      Preserved per §253 as rollback escape hatch.
 *
 *   f. COSMETIC-NOT-STALE — commented-out language-pack parameter at line 197 of
 *      fetchPopularManga (`// language = LANGUAGE`). The searchMangaRequest signature has a
 *      default `incTLangs: List<String>? = listOf(language)` so the explicit pass-through is
 *      redundant; the comment documents the redundancy. Preserved verbatim.
 *
 *   g. COSMETIC-NOT-STALE — 2 commented-out lines in getBaseUrl at lines 123-124:
 *      `// val url = sourcesRepository.getBaseUrlFor(API) ?: apiUrl` and `// baseUrl = url`.
 *      The override returns fixed apiUrl unconditionally — sources-repository overrideable
 *      base-URL plumbing is intentionally disabled for this site. Documents the bypass; the
 *      live behaviour is "fixed apiUrl".
 *
 *   h. LIVE-NOT-STALE — @Volatile `_cachedHeaders` pattern (lines 105-120) plus initSite +
 *      refreshHeaders pair. Same canonical shape as cluster195 + cluster196 leaf 1/5; this
 *      leaf ADDITIONALLY overrides initSite to pre-fill the cache from dataStore at
 *      app-startup (lines 111-115). The initSite override is one of the few cross-cluster
 *      variants of the cache-population strategy — most leaves rely on first-call lazy
 *      population through refreshHeaders. Worth noting for the forecast cleanup.
 *
 *   i. PARTIALLY-FULFILLED-FORECAST — Phase 8 parallel-IO pattern fulfilled in a different
 *      shape than cluster195 leaf 5/5: fetchMangaChaptersF at lines 265-317 uses Flow.combine
 *      to interleave infoFlow + chaptersFlow concurrently (lines 292-294). Unlike
 *      TapasticRepository's Semaphore-bounded parallel-chapter-fetch (which controls
 *      concurrency for N chapters), this is a 2-payload concurrent fetch — the simpler
 *      "combine two independent flows" pattern. Both are valid parallel-IO strategies; the
 *      combine pattern fits this leaf's 2-endpoint topology while Semaphore fits
 *      TapasticRepository's N-pages topology. Forecast partially fulfilled — cross-cluster the
 *      parallel-IO patterns are diverging by topology, not converging on a single approach.
 *
 *   j. LIVE-NOT-STALE — fetchMoreManga hard page-cap guard at line 323
 *      (`if (page > 50) return@flow`). Preserves source's pagination cap; the value 50 is a
 *      magic number preserved verbatim. Future Phase 8 could extract to a const but the
 *      source-fidelity argument keeps it inline.
 *
 *   k. LIVE-NOT-STALE — processImageUrl at lines 128-135 normalises 4 URL shapes
 *      (`/data` passthrough, absolute http passthrough, root-relative → imgBaseUrl-prefixed,
 *      else passthrough). The `/data` passthrough shape is mangapark-specific — preserved
 *      verbatim.
 *
 *   l. LIVE-NOT-STALE — GraphQL companion object at lines 568-705 carries 4 query string
 *      constants (SEARCH_QUERY, DETAILS_QUERY, CHAPTERS_QUERY, PAGES_QUERY) using
 *      triple-quoted raw strings with `${"$"}name` GraphQL variable interpolation.
 *      Kotlin-to-GraphQL dollar escaping preserved verbatim — the GraphQL schema-tree consumer
 *      pattern is cross-cluster sibling-relevant to cluster196 leaf 5/5 ComickRepository's
 *      :en/comick_io/models JSON schema tree (different schema language, same consumer
 *      topology).
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 336 (BatotoEnRepositoryv2.kt) — leaf 1/5, opening leaf, 571 lines, NormalSites
 *     subclass with regex + LocalDate KMP-port notes.
 *   - sibling 338 (ZazamangaRepository.kt) — leaf 3/5, 747 lines, NEXT leaf.
 *   - sibling 339 (BatcaveRepository.kt) — leaf 4/5, 796 lines, cross-package consumer of the
 *     :en/readcomiconline/Dto.kt data classes.
 *   - sibling 340 (ComickRepository.kt) — leaf 5/5, closing leaf, 801 lines, parent of
 *     cluster191 leaf 5/5 ComickRepositoryAr, key consumer of the :en/comick_io/models JSON
 *     schema tree.
 *
 * Cluster196 leaf 2/5 — middle-opening leaf. Next leaf: ZazamangaRepository.kt (sibling 338).
 */
