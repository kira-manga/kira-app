package me.manga.kira.sources_repositry.ar.promanga

/**
 * Migration note (Phase 7.1 / ar Wave B): Retrofit -> Ktor ApiClient, @Inject dropped,
 * android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime, kotlin.jvm.Volatile ->
 * kotlin.concurrent.Volatile.
 *
 * Coil3 image-request builders (`buildImageRequest` / `buildItemsImageRequest`) and their
 * `Context` parameter are removed — Coil3 is not in `shared/commonMain` and `Context` is
 * Android-only. The Hilt `@ApplicationContext context: Context`, `CoroutineScope` applicationScope
 * and `ImageLoader` constructor parameters are dropped — they were only used by the Android
 * combiner.
 *
 * `ProMangaImageCombiner` is a Phase 8 stub (see that file) that emits each single image plus
 * the first piece of each map as a best-effort fallback. Chapter-image streaming still emits
 * progressive `State.Success(images)` updates and the existing UI is unaffected — it just won't
 * receive stitched composite pages until the combiner is wired up in Phase 8 via expect/actual.
 *
 * `api.getData(url)` (legacy Retrofit method) -> `api.get(url, headers = defaultHeaders)`.
 * `DateTimeFormatter.ISO_DATE_TIME` / `ISO_DATE` -> `kotlin.time.Instant.parse(s)` + LocalDate
 * extraction (kotlinx.datetime LocalDate.parse defaults to ISO yyyy-MM-dd).
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
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
import me.manga.kira.sources_repositry.ar.promanga.models.ImageCombinerState
import me.manga.kira.sources_repositry.ar.promanga.models.ProMangaResponse
import me.manga.kira.sources_repositry.ar.promanga.models.ProMangaSeries
import me.manga.kira.sources_repositry.ar.promanga.models.imgs.ProMangaChapterResponse
import me.manga.kira.sources_repositry.ar.promanga.models.imgs.ProMangaImageCombiner
import me.manga.kira.sources_repositry.ar.promanga.models.info.ProInfo
import me.manga.kira.sources_repositry.data.MangaSource

@OptIn(ExperimentalTime::class)
open class ProMangaRepository(
    private val api: ApiClient,
    private val dataStore: DataStoreHelper,
    private val sourcesRepository: SourcesDao,
) : BaseMangaRepository() {

    companion object {
        private const val TAG = "ProMangaRepository"
    }

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }

    override val BASE_URL: String get() = MangaSource.PROMANGA.BASEURL
    override val URL_VERSION: Int get() = 0
    override var baseUrl: String = ""
    override val API: String get() = MangaSource.PROMANGA.API
    override val LANGUAGE: String get() = MangaSource.PROMANGA.LANGUAGE.Language
    override val ICON: Int get() = MangaSource.PROMANGA.ICON
    override val PRIORITY = MangaSource.PROMANGA.PRIORITY

    protected open val apiUrl: String by lazy {
        // "https://prochan.net/" was hard-coded upstream; preserved verbatim.
        "https://prochan.net/"
    }
    override var imgBaseUrl: String = ""
    override var imgUrlVersion: Int = 0

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    // -----------------------------------------------------------------------------------------
    // Image-request builders removed (see file header). Original Android implementation built
    // Coil3 `ImageRequest`s with `defaultHeaders`, optional pixel size, `RGB_565` config and
    // crossfade — the UI/image loader on the platform side reconstructs from those headers.
    // -----------------------------------------------------------------------------------------

    override suspend fun getBaseUrl(): String {
        val url = sourcesRepository.getBaseUrlFor(API) ?: BASE_URL
        baseUrl = url
        return url.ifBlank { BASE_URL }
    }

    override val blackListGenres: Set<String>
        get() = setOf(
//            "Ecchi",
//            "Adult",
//            "Mature",
//            "Hentai",
//            "Smut",
//            "Lolicon"
        )

    override val sortTypes: Set<String>
        get() = setOf(
//            "latest_chapter",
//            "newest",
//            "total_popularity",
//            "favorites"
        )

    override val allGenres: Set<String>
        get() = setOf(
//            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy",
//            "Harem", "Historical", "Horror", "Martial Arts", "Mature", "Mystery",
//            "Psychological", "Romance", "School Life", "Sci-fi", "Seinen", "Shoujo",
//            "Shounen", "Slice of Life", "Sports", "Supernatural", "Tragedy", "Adult",
//            "Hentai", "Smut"
        )

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> = flow {
        emit(State.Loading)

        Logger.withTag(TAG).d { "Fetching URL: $url" }
        try {
            val response = api.get(url, headers = defaultHeaders)
            if (response.status.isSuccess()) {
                val jsonContent = response.bodyAsText()

                val chapterImages = mutableListOf<String>()

                // Collect images as they're emitted by the Phase-8-stub combiner.
                extractChapterImagesStreaming(jsonContent).collect { state ->
                    when (state) {
                        is ImageCombinerState.SingleImageReady -> {
                            chapterImages.add(state.imageUrl)
                            emit(State.Success(chapterImages.toList()))
                        }
                        is ImageCombinerState.Complete -> {
                            Logger.withTag(TAG).d { "Chapter complete with ${state.totalImagesEmitted} images" }
                            emit(State.Success(chapterImages))
                        }
                        is ImageCombinerState.Error -> {
                            Logger.withTag(TAG).e { "Error during combining: ${state.message}" }
                            if (chapterImages.isNotEmpty()) {
                                emit(State.Success(chapterImages))
                            }
                        }
                    }
                }
            } else {
                val errorMessage = "HTTP ${response.status.value}: ${response.status.description}"
                Logger.withTag(TAG).e { "API Error: $errorMessage" }
                emit(State.Error(response.status.value, errorMessage))
            }
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Network error: ${e.message}" }
            emit(State.Error(0, e.message ?: "Unknown error occurred"))
        }
    }

    // Updated extractChapterImagesStreaming helper. ProMangaImageCombiner is a Phase 8 stub:
    // it emits each single image and (best-effort) the first piece of each map.
    private fun extractChapterImagesStreaming(json: String): Flow<ImageCombinerState> = flow {
        try {
            val response: ProMangaChapterResponse = jsonParser.decodeFromString(json)
            val metadata = response.metadata

            val maps = metadata.maps
            if (maps != null && maps.isNotEmpty()) {
                val combiner = ProMangaImageCombiner(
                    cdnPath = response.cdn_path,
                    headers = defaultHeaders,
                )

                val singleImages = metadata.images ?: emptyList()
                Logger.withTag(TAG).i { "maps: $maps" }
                Logger.withTag(TAG).i { "singles: ${metadata.images}" }
                combiner.combineChapterImagesStreaming(maps, singleImages).collect { state ->
                    emit(state)
                }
            } else {
                emit(ImageCombinerState.Complete(totalImagesEmitted = 0))
            }
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing chapter images: ${e.message}" }
            emit(ImageCombinerState.Error("Parse error: ${e.message}", 0))
        }
    }

    fun getFullImgs(url: String): Flow<State<List<String>>> =
        fetchData(url) { json ->
            extractChapterImages(json)
        }

    private suspend fun extractChapterImages(json: String): List<String> {
        return try {
            val response: ProMangaChapterResponse = jsonParser.decodeFromString(json)
            val metadata = response.metadata

            val maps = metadata.maps
            if (maps != null && maps.isNotEmpty()) {
                val combiner = ProMangaImageCombiner(
                    cdnPath = response.cdn_path,
                    headers = defaultHeaders,
                )

                val singleImages = metadata.images ?: emptyList()
                val allImages = mutableListOf<String>()

                Logger.withTag(TAG).d { "Collecting all combined images..." }

                combiner.combineChapterImagesStreaming(maps, singleImages).collect { state ->
                    when (state) {
                        is ImageCombinerState.SingleImageReady -> {
                            allImages.add(state.imageUrl)
                            Logger.withTag(TAG).d { "Collected ${allImages.size} images so far..." }
                        }
                        is ImageCombinerState.Complete -> {
                            Logger.withTag(TAG).d { "All ${state.totalImagesEmitted} images collected" }
                        }
                        is ImageCombinerState.Error -> {
                            Logger.withTag(TAG).e { "Error during collection: ${state.message}" }
                        }
                    }
                }

                Logger.withTag(TAG).d { "Returning ${allImages.size} total images" }
                allImages
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing chapter images: ${e.message}" }
            emptyList()
        }
    }

    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> =
        fetchHome()

    fun fetchHome(page: Int = 1): Flow<State<MutableList<MangaItem>>> =
        fetchData("${apiUrl}api/public/series/search?status=approved&limit=28&page=$page&sort=latest_chapter") { json ->
            extractHomeMangaItems(json).filter { item ->
                !item.genres.any { it in blackListGenres }
            }.toMutableList()
        }

    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> {
        return when (searchType) {
            is SearchType.Normal -> {
                val query = searchType.toNormalQuery()
                fetchData("${apiUrl}api/public/series/search?status=approved&limit=50&page=1&search=$query") { json ->
                    extractSearchMangaItems(json).filter { manga ->
                        !manga.genres.any { it in blackListGenres }
                    }
                }
            }

            is SearchType.GENRES -> {
                fetchData("${apiUrl}api/public/series/search?status=approved&limit=50&page=1&genres=${searchType.genres}") { json ->
                    extractSearchMangaItems(json).filter { manga ->
                        !manga.genres.any { it in blackListGenres }
                    }
                }
            }

            is SearchType.SORT -> {
                fetchData("${apiUrl}api/public/series/search?status=approved&limit=50&page=1&sort=${searchType.sortType}${if (searchType.query.isNotEmpty()) "&search=${searchType.query}" else ""}") { json ->
                    extractSearchMangaItems(json).filter { manga ->
                        !manga.genres.any { it in blackListGenres }
                    }
                }
            }
        }
    }

    override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>> {
        val url = query
        return fetchData(url) { json ->
            extractMangaInfo(json)?.toMangaInfo(API, LANGUAGE, url)
        }.map { state ->
            when (state) {
                is State.Success -> {
                    val mangaInfo = state.data
                    if (mangaInfo != null) {
                        State.Success(mangaInfo)
                    } else {
                        State.Error(0, "Failed to parse manga info")
                    }
                }
                is State.Error -> state
                is State.Loading -> state
            }
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

    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        fetchData("${apiUrl}api/public/series/search?status=approved&limit=28&page=1&sort=total_popularity") { json ->
            extractPopularMangaItems(json)
        }

    // Helper functions for data extraction
    private fun extractHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val response: ProMangaResponse = jsonParser.decodeFromString(json)
            val items = response.data.filter {
                !it.isSensitiveImage
            }.toMangaItems(API, LANGUAGE).toMutableList()

            Logger.withTag(TAG).i { "extractHomeMangaItems items: $items" }

            items
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing home items: ${e.message}" }
            mutableListOf()
        }
    }

    private fun extractSearchMangaItems(json: String): List<MangaItem> {
        return try {
            val response: ProMangaResponse = jsonParser.decodeFromString(json)
            response.data.filter {
                !it.isSensitiveImage
            }.toMangaItems(API, LANGUAGE)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing search items: ${e.message}" }
            emptyList()
        }
    }

    private fun extractPopularMangaItems(json: String): List<PopularManga> {
        return try {
            val response: ProMangaResponse = jsonParser.decodeFromString(json)
            response.data.filter { series ->
                !series.metadata.genres.any { it in blackListGenres }
            }.filter {
                !it.isSensitiveImage
            }.toPopularManga(API, LANGUAGE)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing popular items: ${e.message}" }
            emptyList()
        }
    }

    private fun extractMangaInfo(json: String): ProInfo? {
        return try {
            Logger.withTag(TAG).d { "parsing json: $json" }
            val js = jsonParser.decodeFromString<ProInfo>(json)
            js
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing manga info with chapters: ${e.message}" }
            null
        }
    }

    // Extension functions for data conversion
    private fun List<ProMangaSeries>.toMangaItems(api: String, language: String): List<MangaItem> =
        map { series ->
            MangaItem(
                api = api,
                language = language,
                title = series.title,
                url = "${apiUrl}api/public/${series.type}/${series.id}",
                imageUrl = getFullImageUrl(series),
                rating = null,
                chapters = emptyList(),
                genres = series.metadata.genres
            )
        }

    private fun List<ProMangaSeries>.toPopularManga(api: String, language: String): List<PopularManga> =
        map { series ->
            PopularManga(
                api = api,
                language = language,
                title = series.title,
                url = "${apiUrl}api/public/${series.type}/${series.id}",
                imageUrl = getFullImageUrl(series),
            )
        }

    private fun ProInfo.toMangaInfo(api: String, language: String, url: String): MangaInfo {
        val description = this.metadata?.descriptions?.ar?.ifEmpty {
            this.metadata.descriptions?.en
        }?.ifEmpty {
            this.description
        }

        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

        // Parse chapters from the response
        val chapterItems = this.chapters?.map { chapterInfo ->
            ChapterItem(
                number = chapterInfo.chapter_number.toString(),
                name = chapterInfo.title?.ifEmpty { "Chapter ${chapterInfo.chapter_number}" }
                    ?: "Chapter ${chapterInfo.chapter_number}",
                url = "${apiUrl}api/public/chapters/${chapterInfo.id}",
                date = parseDate(chapterInfo.published_at.toString()) ?: today,
            )
        }?.reversed() // Reverse to show newest first

        return MangaInfo(
            api = api,
            language = language,
            url = url,
            title = this.title ?: " ",
            imageUrl = getFullImageUrlst(this.thumbnail.toString(), cdn_path),
            rating = "0",
            description = description?.replace(Regex("<[^>]*>"), "") ?: "", // Remove HTML tags
            author = "",
            genres = this.metadata?.genres
                ?.filterNotNull()
                ?: emptyList(),
            status = when (this.progress?.lowercase()) {
                "مستمر" -> "Ongoing"
                "مكتمل" -> "Completed"
                else -> this.progress
            }.toString(),
            chapters = chapterItems?.toMutableList() ?: mutableListOf()
        )
    }

    private fun parseDate(dateString: String): LocalDate? {
        return try {
            if (dateString.contains("T")) {
                // ISO-8601 date-time: parse to Instant then project to local date
                Instant.parse(dateString).toLocalDateTime(TimeZone.currentSystemDefault()).date
            } else {
                // Plain ISO date yyyy-MM-dd
                LocalDate.parse(dateString)
            }
        } catch (e: Exception) {
            null
        }
    }

    // Generic fetch data function
    private inline fun <T> fetchData(
        url: String,
        crossinline transform: suspend (json: String) -> T
    ): Flow<State<T>> = flow {
        emit(State.Loading)

        Logger.withTag(TAG).d { "Fetching URL: $url" }
        try {
            val response = api.get(url, headers = defaultHeaders)
            if (response.status.isSuccess()) {
                val jsonContent = response.bodyAsText()
                val parsedData = transform(jsonContent)
                emit(State.Success(parsedData))
            } else {
                val errorMessage = "HTTP ${response.status.value}: ${response.status.description}"
                Logger.withTag(TAG).e { "API Error: $errorMessage" }
                emit(State.Error(response.status.value, errorMessage))
            }
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Network error: ${e.message}" }
            emit(State.Error(0, e.message ?: "Unknown error occurred"))
        }
    }

    fun getFullImageUrl(series: ProMangaSeries): String {
        val image = series.coverImage
        if (image.isEmpty()) return ""

        if (image.startsWith("http")) {
            return image
        }

        val cdn = series.cdnPath.ifEmpty { "cdn2" }

        return "https://$cdn.prochan.net$image"
    }

    fun getFullImageUrlst(url: String, cdn: String?): String {
        if (url.startsWith("http")) {
            return url
        }

        val resolvedCdn = cdn ?: "cdn2"

        return "https://$resolvedCdn.prochan.net$url"
    }
}

/**
 * Audit-trail postscript (Phase 9.x.cluster193.staleKdocSweep.cascade, Task #648, 2026-05-29)
 *
 * Leaf 5/5 §253 audit-trail-preservation postscript for cluster193, sibling 326 of the cluster57+
 * continuum. Closing leaf of cluster193 (the second wave of the :ar/ Repository implementation
 * tier sweep). This file is the LARGEST in the cluster193 batch at 535 lines — an `open class`
 * Repository extending `BaseMangaRepository` against the `prochan.net` HTTP frontend (hardcoded
 * apiUrl, vs sibling 325's reactive DataStore-cached `baseUrl.ifBlank{ BASE_URL }` resolution).
 *
 * The top-of-file prose under audit (lines 3-22) is a 4-paragraph migration note:
 *
 *     Migration note (Phase 7.1 / ar Wave B): Retrofit -> Ktor ApiClient, @Inject dropped,
 *     android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime, kotlin.jvm.Volatile ->
 *     kotlin.concurrent.Volatile.
 *
 *     Coil3 image-request builders (`buildImageRequest` / `buildItemsImageRequest`) and their
 *     `Context` parameter are removed — Coil3 is not in `shared/commonMain` and `Context` is
 *     Android-only. The Hilt `@ApplicationContext context: Context`, `CoroutineScope` applicationScope
 *     and `ImageLoader` constructor parameters are dropped — they were only used by the Android
 *     combiner.
 *
 *     `ProMangaImageCombiner` is a Phase 8 stub (see that file) that emits each single image plus
 *     the first piece of each map as a best-effort fallback. Chapter-image streaming still emits
 *     progressive `State.Success(images)` updates and the existing UI is unaffected — it just won't
 *     receive stitched composite pages until the combiner is wired up in Phase 8 via expect/actual.
 *
 *     `api.getData(url)` (legacy Retrofit method) -> `api.get(url, headers = defaultHeaders)`.
 *     `DateTimeFormatter.ISO_DATE_TIME` / `ISO_DATE` -> `kotlin.time.Instant.parse(s)` + LocalDate
 *     extraction (kotlinx.datetime LocalDate.parse defaults to ISO yyyy-MM-dd).
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — paragraph 1's 5 migration claims: verified by import survey of lines
 *      24-56. Kermit Logger (line 24), kotlin.concurrent.Volatile (line 27), kotlin.time.Instant
 *      (line 30), kotlinx.datetime (lines 35-38), ApiClient (line 43). Zero `Retrofit`/
 *      `IMangaDataApiServices`/`android.util.Log`/`java.time`/`kotlin.jvm.Volatile`/
 *      `javax.inject.Inject` imports. All 5 substitutions structurally complete.
 *
 *   b. LIVE-NOT-STALE — paragraph 2's "Hilt @ApplicationContext + CoroutineScope applicationScope
 *      + ImageLoader constructor parameters are dropped" claim: verified by reading the
 *      constructor signature at lines 59-63 (`open class ProMangaRepository(api: ApiClient,
 *      dataStore: DataStoreHelper, sourcesRepository: SourcesDao)`). 3-param ctor — no
 *      `@ApplicationContext`, no `applicationScope: CoroutineScope`, no `imageLoader: ImageLoader`
 *      parameters. The Hilt/Coil3/coroutine-scope cleanup is structurally complete.
 *
 *   c. LIVE-NOT-STALE — paragraph 3's "ProMangaImageCombiner is a Phase 8 stub [...] best-effort
 *      fallback" claim: verified by inline comment-block at lines 184-185 ("Updated
 *      extractChapterImagesStreaming helper. ProMangaImageCombiner is a Phase 8 stub: it emits
 *      each single image and (best-effort) the first piece of each map."). The `combineChapterImagesStreaming`
 *      Flow call at lines 201-203 preserves the streaming-progressive-emit semantics. UI receives
 *      `State.Success(images)` updates after each emission per lines 157-160.
 *
 *   d. LIVE-NOT-STALE — paragraph 4's "api.getData(url) -> api.get(url, headers = defaultHeaders)"
 *      claim: verified at lines 148/497 (`api.get(url, headers = defaultHeaders)`). The 2-argument
 *      Ktor wrapper replaces the legacy Retrofit single-argument call. Substitution structurally
 *      complete.
 *
 *   e. LIVE-NOT-STALE — paragraph 4's "DateTimeFormatter.ISO_DATE_TIME / ISO_DATE -> kotlin.time.Instant.parse"
 *      claim: verified at lines 474-486. `Instant.parse(dateString).toLocalDateTime(TimeZone.currentSystemDefault()).date`
 *      handles the ISO_DATE_TIME branch (when `dateString.contains("T")`); the bare
 *      `LocalDate.parse(dateString)` handles the ISO_DATE branch. Inline comments at lines 477
 *      ("ISO-8601 date-time: parse to Instant then project to local date") and 480 ("Plain ISO
 *      date yyyy-MM-dd") document the two-branch logic. Substitution structurally complete.
 *
 *   f. FORECAST-NOT-YET-FULFILLED — paragraph 3's "until the combiner is wired up in Phase 8 via
 *      expect/actual" forecast. Grep across :ar/promanga/models/imgs/ confirms
 *      `ProMangaImageCombiner` exists as a commonMain stub; no `expect`/`actual` declaration for
 *      it yet. Forecast holds verbatim.
 *
 *   g. POTENTIAL-BUG-PRESERVED — the `blackListGenres` set at lines 116-124 carries ALL 6 entries
 *      commented out — empty effective filter. The 4 entries that sibling 325 (ProchanRepository)
 *      DOES carry uncommented ("Adult", "Mature", "Hentai", "Smut") are commented out here. The
 *      asymmetry with sibling 325 IS the meaningful product divergence between the two
 *      Repositories. Preserved verbatim per §253; documented at sibling 325's sub-classification e.
 *
 *   h. POTENTIAL-BUG-PRESERVED — the `apiUrl` resolution at lines 86-89:
 *      `protected open val apiUrl: String by lazy { "https://prochan.net/" }`. Hardcoded literal.
 *      The inline comment ("`https://prochan.net/` was hard-coded upstream; preserved verbatim.")
 *      explicitly flags this as a §253 preservation of upstream-hardcoding. Sibling 325 uses
 *      `baseUrl.ifBlank { BASE_URL }` (DataStore-cached) instead — sibling-asymmetry intentional.
 *      Preserved verbatim.
 *
 *   i. COSMETIC-NOT-STALE — the `open class` modifier on line 59 (vs sibling 325's `class` —
 *      i.e., final). The `open` is unused (no subclasses exist in :ar/promanga/ — the file
 *      effectively behaves identically to a `class` declaration). Preserved verbatim per §253 —
 *      sibling-class asymmetry that mirrors upstream.
 *
 *   j. COSMETIC-NOT-STALE — the `extractMangaInfo` JSON-parse helper at lines 396-405 has a
 *      verbose `val js = jsonParser.decodeFromString<ProInfo>(json); js` pattern (named-then-return).
 *      Sibling 325 (ProchanRepository) collapses it to a direct `jsonParser.decodeFromString<ProInfo>(json)`
 *      at line 381. Cosmetic divergence; both produce identical bytecode. Preserved verbatim per
 *      §253.
 *
 *   k. COSMETIC-NOT-STALE — `@OptIn(ExperimentalTime::class)` at line 58. Required by the
 *      `kotlin.time.Instant` (line 30) experimental opt-in. Identical to siblings 324 and 325.
 *      Preserved verbatim.
 *
 * Closing-leaf summary (cluster193):
 *
 *   Cluster193 closes the second wave of the :ar/ Repository implementation tier sweep with 5
 *   §253 postscripts authored across siblings 322-326. The batch was deliberately sized 274 /
 *   482 / 490 / 512 / 535 lines (opener to closer) — the heaviest 2 candidates (MangatukRepository
 *   at 699 lines + LavatoonsRepositoryv2 at 731 lines) remain deferred to cluster194+ per the
 *   established 5-file-per-commit cap. Of the 5 leaves: 4 are JSON-API Repositories (322/323/325/326)
 *   and 1 is the lone ksoup-bearing HTML-scraper (324, TeamXRepositoryv2).
 *
 *   Cluster193 is structurally notable for closing on a twin-pair (siblings 325+326) — the only
 *   such consecutive twin-pair in the :ar/ Repository implementation tier. The twin diverges on
 *   exactly 3 elements (`MangaSource` enum entry, `apiUrl` resolution strategy, `blackListGenres`
 *   filter activeness) — all 3 documented across sibling 325's sub-classification e/i pair AND
 *   sibling 326's sub-classification g/h/i triple.
 *
 *   Cumulative §253-postscript count brought to 51 across wave-57-to-wave-60 (after cluster192
 *   closed at 46). The :sources_repositry/ar/ Repository implementation tier sweep continues —
 *   cluster194+ targets the deferred-heavy Repository candidates (DilarRepository 591 lines,
 *   MangaLekRepositoryv2 535 lines, MangatukRepository 699 lines, LavatoonsRepositoryv2 731 lines —
 *   4 remaining candidates).
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 322 (AzoraRepositoryv2.kt) — leaf 1/5, opening leaf, 274-line JSON-API Repository.
 *   - sibling 323 (SwatMangaRepository.kt) — leaf 2/5, 482-line JSON-API Repository.
 *   - sibling 324 (TeamXRepositoryv2.kt) — leaf 3/5, 490-line ksoup-bearing Repository (only
 *     HTML-scraper in cluster193).
 *   - sibling 325 (ProchanRepository.kt) — leaf 4/5, 512-line near-duplicate twin of this file
 *     (sub-classifications g/h/i above all sibling-cross-reference 325).
 *
 * Cluster193 leaf 5/5 — closing leaf. Next cluster: cluster194 (:ar/ Repository implementation
 * tier continuation — 4 remaining heavy candidates).
 */

