package me.manga.kira.sources_repositry.ar.swatmanga

/**
 * Migration note (Phase 7.1 / ar Wave B): Retrofit -> Ktor ApiClient, @Inject dropped,
 * android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime, kotlin.jvm.Volatile ->
 * kotlin.concurrent.Volatile.
 *
 * Coil3 image-request builders (`buildImageRequest` / `buildItemsImageRequest`) and their
 * `Context` parameter are removed — Coil3 is not in `shared/commonMain` and `Context` is
 * Android-only. The UI/image layer reconstructs the request from `defaultHeaders` until Phase 8
 * supplies expect/actual platform image loaders.
 *
 * `LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE)` becomes `LocalDate.parse(s)` (kotlinx
 * defaults to ISO-8601 yyyy-MM-dd).
 */

import co.touchlab.kermit.Logger
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
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
import me.manga.kira.sources_repositry.ar.swatmanga.models.chapters.SwatChaptersResult
import me.manga.kira.sources_repositry.ar.swatmanga.models.chapters.SwatSeriesChaptersResponse
import me.manga.kira.sources_repositry.ar.swatmanga.models.chapters_images.SwatSeriesImagesResponse
import me.manga.kira.sources_repositry.ar.swatmanga.models.details.SwatSeriesDetailsResponse
import me.manga.kira.sources_repositry.ar.swatmanga.models.home.SwatResult
import me.manga.kira.sources_repositry.ar.swatmanga.models.home.SwatSeriesHomeResponse
import me.manga.kira.sources_repositry.ar.swatmanga.models.popular.SwatPopularResult
import me.manga.kira.sources_repositry.ar.swatmanga.models.popular.SwatSeriesPopularResponse
import me.manga.kira.sources_repositry.ar.swatmanga.models.search.SwatSearchResult
import me.manga.kira.sources_repositry.ar.swatmanga.models.search.SwatSeriesSearchResponse
import me.manga.kira.sources_repositry.data.MangaSource

open class SwatMangaRepository(
    private val api: ApiClient,
    private val dataStore: DataStoreHelper,
    private val sourcesRepository: SourcesDao,
) : BaseMangaRepository() {

    companion object {
        private const val TAG = "SwatMangaRepository"
    }

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }

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

    // -----------------------------------------------------------------------------------------
    // Image-request builders removed (see file header). The original Android implementation
    // built Coil3 `ImageRequest`s with `defaultHeaders`, optional pixel size, `RGB_565` config
    // and crossfade — the UI/image loader on the platform side reconstructs from the headers.
    // -----------------------------------------------------------------------------------------

    private fun processImageUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "$imgBaseUrl$url"
            else -> url
        }
    }

    override val blackListGenres: Set<String>
        get() = setOf()

    override val sortTypes: Set<String>
        get() = setOf(
            "العنوان",
            "الأعلى تقييماً",
            "الأكثر متابعة",
            "الأكثر مشاهدة",
            "أكثر الفصول",
            "الأحدث",
        )

    val sortMap: LinkedHashMap<String, String> = linkedMapOf(
        "العنوان" to "title",
        "الأعلى تقييماً" to "-rating",
        "الأكثر متابعة" to "-followers_count",
        "الأكثر مشاهدة" to "-views_count",
        "أكثر الفصول" to "-chapters_count",
        "الأحدث" to "-created_at"
    )
    override val allGenres: Set<String>
        get() = setOf()

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
            Logger.withTag(TAG).i { "popular response: $response" }
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
                val url = "${apiUrl}series/?search=${searchType.query}&order_by=${sortMap[searchType.sortType]}&page=1&page_size=20"
                fetchData(url) { response ->
                    extractSearchResults(response)
                        .toMangaItemsFromSearch(API, LANGUAGE)
                }
            }
        }
    }

    override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>> {
        val url = query
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
                Logger.withTag(TAG).e(e) { "chapters flow failure: ${e.message}" }
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
        emit(State.Error(0, e.message ?: "Unknown error occurred"))
    }

    // Updated data extraction functions
    private fun extractSeriesHomeList(json: String): List<SwatResult> {
        return try {
            Logger.withTag(TAG).d { "extractSeriesHomeList response: $json" }
            val response = jsonParser.decodeFromString<SwatSeriesHomeResponse>(json)
            response.swatResults?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing series home list" }
            emptyList()
        }
    }

    private fun extractPopularList(json: String): List<SwatPopularResult> {
        return try {
            Logger.withTag(TAG).d { "extractPopularList response: $json" }
            val response = jsonParser.decodeFromString<SwatSeriesPopularResponse>(json)
            response.swatPopularResults?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing popular list" }
            emptyList()
        }
    }

    private fun extractSearchResults(json: String): List<SwatSearchResult> {
        return try {
            Logger.withTag(TAG).d { "extractSearchResults response: $json" }
            val response = jsonParser.decodeFromString<SwatSeriesSearchResponse>(json)
            response.swatSearchResults?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing search results" }
            emptyList()
        }
    }

    private fun extractMangaDetails(json: String): SwatSeriesDetailsResponse? {
        return try {
            Logger.withTag(TAG).d { "extractMangaDetails response: $json" }
            jsonParser.decodeFromString<SwatSeriesDetailsResponse>(json)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing manga details" }
            null
        }
    }

    private fun extractChaptersList(json: String): List<SwatChaptersResult> {
        return try {
            Logger.withTag(TAG).d { "extractChaptersList response: $json" }
            val response = jsonParser.decodeFromString<SwatSeriesChaptersResponse>(json)
            response.swatChaptersResults?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing chapters list" }
            emptyList()
        }
    }

    private fun extractChapterImages(json: String): List<String> {
        return try {
            Logger.withTag(TAG).d { "extractChapterImages response: $json" }
            val response = jsonParser.decodeFromString<SwatSeriesImagesResponse>(json)
            response.images?.mapNotNull { it?.image } ?: emptyList()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing chapter images" }
            emptyList()
        }
    }

    // Utility functions
    private fun extractSeriesIdFromUrl(url: String): String {
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
            description = story ?: "",
            author = "", // No author field in new structure
            genres = genres?.mapNotNull { it?.name } ?: emptyList(),
            status = when (status?.name) {
                "ongoing" -> "Ongoing"
                "completed" -> "Completed"
                "hiatus" -> "Hiatus"
                "cancelled" -> "Cancelled"
                else -> "Unknown"
            },
            chapters = mutableListOf()
        )
    }

    private fun List<SwatChaptersResult>.toChapterItems(): List<ChapterItem> {
        return map { it.toChapterItem() }
    }

    private fun SwatChaptersResult.toChapterItem(): ChapterItem {
        return ChapterItem(
            number = this.chapter ?: "",
            name = this.title ?: "",
            url = "${apiUrl}chapters/${this.id?.toInt()}/",
            date = this.created_at?.let { dateString ->
                runCatching {
                    // kotlinx.datetime LocalDate.parse defaults to ISO-8601 yyyy-MM-dd
                    LocalDate.parse(dateString.substring(0, 10))
                }.getOrNull()
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
            Logger.withTag(TAG).i { "Fetching URL: $url" }
            val response = api.get(url, headers = defaultHeaders)

            if (response.status.isSuccess()) {
                val responseBody = response.bodyAsText()
                val parsedData = transform(responseBody)
                emit(State.Success(parsedData))
            } else {
                val errorMessage = "HTTP ${response.status.value}: ${response.status.description}"
                emit(State.Error(response.status.value, errorMessage))
            }
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error fetching data" }
            emit(State.Error(0, e.message ?: "Unknown error occurred"))
        }
    }
}

/**
 * Audit-trail postscript (Phase 9.x.cluster193.staleKdocSweep.cascade, Task #648, 2026-05-29)
 *
 * Leaf 2/5 §253 audit-trail-preservation postscript for cluster193, sibling 323 of the cluster57+
 * continuum. Middle leaf of cluster193 (the second wave of the :ar/ Repository implementation
 * tier sweep). This file is a 482-line Repository extending `BaseMangaRepository` against the
 * `appswat.com/v2/api/v1/` backend — a pure-JSON API with 6 model families (chapters / images /
 * details / home / popular / search) and combine-flow chapter-info hydration.
 *
 * The top-of-file prose under audit (lines 3-15) is a 3-paragraph migration note:
 *
 *     Migration note (Phase 7.1 / ar Wave B): Retrofit -> Ktor ApiClient, @Inject dropped,
 *     android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime, kotlin.jvm.Volatile ->
 *     kotlin.concurrent.Volatile.
 *
 *     Coil3 image-request builders (`buildImageRequest` / `buildItemsImageRequest`) and their
 *     `Context` parameter are removed — Coil3 is not in `shared/commonMain` and `Context` is
 *     Android-only. The UI/image layer reconstructs the request from `defaultHeaders` until Phase 8
 *     supplies expect/actual platform image loaders.
 *
 *     `LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE)` becomes `LocalDate.parse(s)` (kotlinx
 *     defaults to ISO-8601 yyyy-MM-dd).
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — paragraph 1's 5 migration claims verified in concert: lines 17 (Kermit
 *      Logger import), 26 (kotlinx.datetime LocalDate import), 31 (ApiClient import), 20
 *      (kotlin.concurrent.Volatile import). Zero `Retrofit`/`IMangaDataApiServices`/`android.util.Log`/
 *      `java.time`/`kotlin.jvm.Volatile`/`javax.inject.Inject` imports. The constructor at line
 *      50-54 carries no DI annotations and accepts `api: ApiClient`. All 5 substitutions are
 *      structurally complete.
 *
 *   b. LIVE-NOT-STALE — paragraph 2's "Coil3 image-request builders [...] are removed" claim:
 *      verified by inline comment-block dropping at lines 110-114 ("Image-request builders removed
 *      (see file header). The original Android implementation built Coil3 `ImageRequest`s [...] —
 *      the UI/image loader on the platform side reconstructs from the headers."). Zero
 *      `androidx.compose` / `coil` / `android.content.Context` imports. The `defaultHeaders`
 *      property (line 98-99) remains as the bridge for downstream image-layer reconstruction.
 *
 *   c. LIVE-NOT-STALE — paragraph 3's "kotlinx defaults to ISO-8601 yyyy-MM-dd" claim: verified
 *      at line 452 (`LocalDate.parse(dateString.substring(0, 10))`). The `.substring(0, 10)` slice
 *      preserves the upstream's ISO_LOCAL_DATE format by trimming any trailing time-component
 *      tail before the parse. kotlinx.datetime's default `LocalDate.parse(String)` is ISO-8601
 *      compliant — substitution is structurally accurate.
 *
 *   d. LIVE-NOT-STALE — the `open class` modifier on line 50: deliberate. Allows subclassing for
 *      Arabic-language-variant siblings. The KMP toolchain handles `open class` identically to
 *      JVM; no migration concern.
 *
 *   e. POTENTIAL-BUG-PRESERVED — the `hasBlacklistedGenres` helper at lines 361-367 is declared
 *      but never called (zero in-file call sites; grep across :ar/swatmanga/ confirms it is not
 *      reachable from any public method). The corresponding `blackListGenres` set at lines
 *      124-125 is empty (`setOf()`). The dead-code pair is preserved verbatim per §253 — likely a
 *      future-Phase enrichment hook awaiting populated `blackListGenres` content.
 *
 *   f. POTENTIAL-BUG-PRESERVED — the `rating` field on `SwatResult.toMangaItem` (line 381):
 *      `rating?.toIntOrNull() ?: 0`. Conflates "rating present but unparseable" with "rating
 *      explicitly zero" — both produce `0` in the resulting `MangaItem.rating`. Upstream behaved
 *      identically per migration policy. Preserved verbatim per §253.
 *
 *   g. COSMETIC-NOT-STALE — the Arabic-literal sortTypes set at lines 128-135 ("العنوان",
 *      "الأعلى تقييماً", "الأكثر متابعة", "الأكثر مشاهدة", "أكثر الفصول", "الأحدث") +
 *      the corresponding sortMap on lines 137-144 (Arabic-display-name → URL-query-key
 *      LinkedHashMap). Insertion-ordered LinkedHashMap preserves upstream display ordering.
 *      Locale-routed strings preserved verbatim per §253.
 *
 *   h. COSMETIC-NOT-STALE — the chapter-flow error-swallowing branch at lines 220-229: catches
 *      Flow-level failures + collapses State.Error → State.Success(emptyList()) to allow the
 *      combine-flow to emit Info-only on chapter-fetch failure. Aggressive partial-success policy
 *      preserved verbatim — would warrant documentation if changed, not in a §253 sweep.
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 322 (AzoraRepositoryv2.kt) — leaf 1/5, opening leaf, 274-line JSON-API Repository.
 *   - sibling 324 (TeamXRepositoryv2.kt) — leaf 3/5, 490-line ksoup-bearing Repository with
 *     Arabic month-name map.
 *   - sibling 325 (ProchanRepository.kt) — leaf 4/5, near-duplicate twin of sibling 326.
 *   - sibling 326 (ProMangaRepository.kt) — leaf 5/5, closing leaf.
 *
 * Cluster193 leaf 2/5 — middle leaf. Next leaf: TeamXRepositoryv2.kt (sibling 324).
 */

