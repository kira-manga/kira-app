package me.manga.kira.sources_repositry.ar.promanga

/**
 * Migration note (Phase 7.1 / ar Wave B): Retrofit -> Ktor ApiClient, @Inject dropped,
 * android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime, kotlin.jvm.Volatile ->
 * kotlin.concurrent.Volatile.
 *
 * Near-duplicate of [ProMangaRepository] in upstream (only `MangaSource.PROCHAN`, `apiUrl`
 * resolution, and `blackListGenres` differ). Kept as a sibling class — not a subclass — because
 * upstream did so. The same Phase-8 considerations apply (Coil3/Context/applicationScope/ImageLoader
 * dropped, `ProMangaImageCombiner` is a stub).
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
class ProchanRepository(
    private val api: ApiClient,
    private val dataStore: DataStoreHelper,
    private val sourcesRepository: SourcesDao,
) : BaseMangaRepository() {

    companion object {
        private const val TAG = "ProchanRepository"
    }

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }

    override val BASE_URL: String get() = MangaSource.PROCHAN.BASEURL
    override val URL_VERSION: Int get() = 0
    override var baseUrl: String = ""
    override val API: String get() = MangaSource.PROCHAN.API
    override val LANGUAGE: String get() = MangaSource.PROCHAN.LANGUAGE.Language
    override val ICON: Int get() = MangaSource.PROCHAN.ICON
    override val PRIORITY = MangaSource.PROCHAN.PRIORITY

    private val apiUrl: String by lazy {
        baseUrl.ifBlank { BASE_URL }
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
    // Image-request builders removed (Coil3 not in commonMain; see file header).
    // -----------------------------------------------------------------------------------------

    override suspend fun getBaseUrl(): String {
        val url = sourcesRepository.getBaseUrlFor(API) ?: BASE_URL
        baseUrl = url
        return url.ifBlank { BASE_URL }
    }

    override val blackListGenres: Set<String>
        get() = setOf(
//            "Ecchi",
            "Adult",
            "Mature",
            "Hentai",
            "Smut",
//            "Lolicon"
        )

    override val sortTypes: Set<String>
        get() = setOf(
//            "latest_chapter", "newest", "total_popularity", "favorites"
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

    // ProMangaImageCombiner is a Phase 8 stub — see that file.
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
            jsonParser.decodeFromString<ProInfo>(json)
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

        val chapterItems = this.chapters?.map { chapterInfo ->
            ChapterItem(
                number = chapterInfo.chapter_number.toString(),
                name = chapterInfo.title?.ifEmpty { "Chapter ${chapterInfo.chapter_number}" }
                    ?: "Chapter ${chapterInfo.chapter_number}",
                url = "${apiUrl}api/public/chapters/${chapterInfo.id}",
                date = parseDate(chapterInfo.published_at.toString()) ?: today,
            )
        }?.reversed()

        return MangaInfo(
            api = api,
            language = language,
            url = url,
            title = this.title ?: " ",
            imageUrl = getFullImageUrlst(this.thumbnail.toString(), cdn_path),
            rating = "0",
            description = description?.replace(Regex("<[^>]*>"), "") ?: "",
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
                Instant.parse(dateString).toLocalDateTime(TimeZone.currentSystemDefault()).date
            } else {
                LocalDate.parse(dateString)
            }
        } catch (e: Exception) {
            null
        }
    }

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
 * Leaf 4/5 §253 audit-trail-preservation postscript for cluster193, sibling 325 of the cluster57+
 * continuum. Penultimate leaf of cluster193. This file is a 512-line near-duplicate twin of
 * sibling 326 (ProMangaRepository.kt) — the upstream maintained two parallel Repositories rather
 * than one parent + one subclass, and the KMP port preserves that structural decision verbatim.
 *
 * The top-of-file prose under audit (lines 3-12) is a 2-paragraph migration note:
 *
 *     Migration note (Phase 7.1 / ar Wave B): Retrofit -> Ktor ApiClient, @Inject dropped,
 *     android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime, kotlin.jvm.Volatile ->
 *     kotlin.concurrent.Volatile.
 *
 *     Near-duplicate of [ProMangaRepository] in upstream (only `MangaSource.PROCHAN`, `apiUrl`
 *     resolution, and `blackListGenres` differ). Kept as a sibling class — not a subclass — because
 *     upstream did so. The same Phase-8 considerations apply (Coil3/Context/applicationScope/ImageLoader
 *     dropped, `ProMangaImageCombiner` is a stub).
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — paragraph 1's 5 migration claims: verified by import survey of lines
 *      14-46. Kermit Logger (line 14), kotlin.concurrent.Volatile (line 17), kotlin.time.Instant
 *      (line 20), kotlinx.datetime (lines 25-28), ApiClient (line 33). Zero `Retrofit`/
 *      `IMangaDataApiServices`/`android.util.Log`/`java.time`/`kotlin.jvm.Volatile`/
 *      `javax.inject.Inject` imports. All 5 substitutions are structurally complete.
 *
 *   b. LIVE-NOT-STALE — paragraph 2's "kept as a sibling class — not a subclass" claim:
 *      verified by reading line 49 (`class ProchanRepository(...) : BaseMangaRepository()`).
 *      Inherits directly from `BaseMangaRepository`, NOT from `ProMangaRepository`. Sibling
 *      relationship preserved verbatim per upstream. The 3 differentiating elements
 *      (`MangaSource.PROCHAN` line 68, `apiUrl` resolution lines 76-78, `blackListGenres` lines
 *      103-111) are all clearly identifiable as the only structural divergence from sibling 326.
 *
 *   c. LIVE-NOT-STALE — paragraph 2's "ProMangaImageCombiner is a stub" cross-reference: verified
 *      at lines 175-178 (`val combiner = ProMangaImageCombiner(cdnPath = response.cdn_path,
 *      headers = defaultHeaders)`) — referenced verbatim. The "see that file" cross-reference
 *      points to `:ar/promanga/models/imgs/ProMangaImageCombiner.kt` which is genuinely a Phase 8
 *      stub per the inline `// ProMangaImageCombiner is a Phase 8 stub — see that file.` comment
 *      at line 167.
 *
 *   d. LIVE-NOT-STALE — the "Coil3/Context/applicationScope/ImageLoader dropped" cross-reference
 *      to sibling 326's identical posture: verified by reading lines 93-95 inline comment
 *      ("Image-request builders removed (Coil3 not in commonMain; see file header)."). Zero
 *      `coil` / `android.content.Context` / `kotlinx.coroutines.CoroutineScope` /
 *      `androidx.compose.coil` imports. Posture preserved verbatim from sibling 326.
 *
 *   e. POTENTIAL-BUG-PRESERVED — the `blackListGenres` set at lines 103-111 carries 4 non-commented
 *      entries ("Adult", "Mature", "Hentai", "Smut") + 2 commented-out entries ("Ecchi",
 *      "Lolicon"). The asymmetry vs sibling 326 (which has ALL 6 entries commented out and an
 *      empty effective filter) is the ONE meaningful product divergence between the two
 *      Repositories. Likely intentional per the per-source content-policy split (Prochan has
 *      tighter filtering than ProManga). Preserved verbatim per §253.
 *
 *   f. POTENTIAL-BUG-PRESERVED — the `sortTypes` set at lines 113-116 is FULLY commented out
 *      (zero active entries — the empty `setOf()` yields an empty set). The UI sort-picker for
 *      this source receives an empty sort-options menu. Identical asymmetry with sibling 326's
 *      `sortTypes` (also empty). Preserved verbatim — the dev-comment scaffold awaits Phase 8
 *      activation.
 *
 *   g. POTENTIAL-BUG-PRESERVED — the `allGenres` set at lines 118-125 is FULLY commented out
 *      (zero active entries — empty set). The genre filter dropdown is empty. Identical asymmetry
 *      with sibling 326's `allGenres`. Preserved verbatim.
 *
 *   h. COSMETIC-NOT-STALE — `@OptIn(ExperimentalTime::class)` at line 48 + the dual `kotlin.time.Instant`
 *      import (line 20) + `Clock.System` access pattern (line 421). Required by the experimental
 *      kotlin.time API; identical to sibling 326. Preserved verbatim per §253.
 *
 *   i. LIVE-NOT-STALE — the `apiUrl` resolution at lines 76-78 (`baseUrl.ifBlank { BASE_URL }`).
 *      Sibling 326 uses the hard-coded `"https://prochan.net/"` literal instead. This IS one of
 *      the 3 listed differences in paragraph 2 — sibling 325 reads from `sourcesRepository`-cached
 *      `baseUrl` while sibling 326 hardcodes. The divergence is intentional per upstream design
 *      and preserved verbatim.
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 322 (AzoraRepositoryv2.kt) — leaf 1/5, opening leaf, 274-line JSON-API Repository.
 *   - sibling 323 (SwatMangaRepository.kt) — leaf 2/5, 482-line JSON-API Repository.
 *   - sibling 324 (TeamXRepositoryv2.kt) — leaf 3/5, 490-line ksoup-bearing Repository.
 *   - sibling 326 (ProMangaRepository.kt) — leaf 5/5, closing leaf, 535-line `open class` twin of
 *     this file. Sibling-twin cross-reference for the 3 differentiating-element audit
 *     (sub-classifications b/e/i above).
 *
 * Cluster193 leaf 4/5 — penultimate leaf. Next leaf: ProMangaRepository.kt (sibling 326, closing
 * leaf).
 */

