package me.manga.kira.sources_repositry.ar.dilar.v2

/**
 * Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, okhttp3.FormBody / RequestBody ->
 * Map<String, String>? (for form bodies) or raw JSON String via api.postJson (for JSON bodies),
 * @Inject dropped, android.util.Log -> Kermit Logger, kotlin.jvm.Volatile ->
 * kotlin.concurrent.Volatile.
 *
 * Upstream's `normalSearchFormBody` returned an okhttp3 RequestBody (raw JSON body, not form-data).
 * The KMP base class `normalSearchFormBody` returns `Map<String, String>?` for form-encoded
 * bodies. To preserve the upstream's POST-with-JSON-body behaviour, this class overrides
 * `normalSearch` directly and calls `api.postJson(url, jsonBody, defaultHeaders)` instead.
 * `normalSearchFormBody` itself is left returning `null` to satisfy the abstract contract.
 */

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.manga.kira.core.states.State
import me.manga.kira.core.states.State.Error.Companion.fromCode
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.SeparatedDetailsSitesv2
import me.manga.kira.sources_repositry.data.MangaSource

class DilarV2Repository(
    private val dataStore: DataStoreHelper,
    api: ApiClient,
    sourcesRepository: SourcesDao,
) : SeparatedDetailsSitesv2(api, sourcesRepository) {

    companion object {
        private const val TAG = "DilarV2Repository"
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    override val mangaSource: MangaSource
        get() = MangaSource.DILARV2

    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { mangaSource.BASEURL }}api/series/?page=1" }
    override val popularUrl: String = ""

    override var imgBaseUrl: String = "https://dilar.tube/uploads"
    override var imgUrlVersion: Int = 0
    override val API: String by lazy { mangaSource.API }
    override val LANGUAGE: String by lazy { mangaSource.LANGUAGE.Language }

    // ==================== Headers Management ====================

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            return base + ("Referer" to baseUrl.ifBlank { mangaSource.BASEURL })
        }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + ("Referer" to baseUrl.ifBlank { mangaSource.BASEURL })
        _cachedHeaders = merged
        dataStore.saveHeadersForApi(API, merged)
    }

    // ==================== URL Building ====================

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { mangaSource.BASEURL }}api/series/?page=$page"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { mangaSource.BASEURL }}api/search/quick_search"
    }

    override fun createInfoUrl(mangaId: String): String {
        return mangaId // Already full URL
    }

    override fun createChaptersUrl(mangaId: String): String {
        return "$mangaId/chapters"
    }

    // ==================== Search Settings ====================

    override var useGetForNormalSearch: Boolean = false
    override var useGetForGenresSearch: Boolean = false
    override var useGetForSortSearch: Boolean = false

    // ==================== Filters (Not implemented) ====================

    override val sortTypes: Set<String> get() = setOf()
    override val allGenres: Set<String> get() = setOf()
    override val blackListGenres: Set<String> get() = setOf()

    // ==================== Form Body ====================

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? = null

    /**
     * Upstream returned an okhttp3 `RequestBody` carrying raw JSON for the search endpoint.
     * The base class's `Map<String, String>?` return type is form-data only, so we leave this
     * `null` and submit the raw JSON via the custom `normalSearch` override below.
     */
    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? = null

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? = null
    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? = null

    /**
     * Build the raw JSON body the dilar v2 `/api/search/quick_search` endpoint expects.
     */
    private fun buildSearchJsonBody(searchType: SearchType.Normal): String {
        val searchRequest = DilarSearchRequest(
            query = searchType.query,
            includes = listOf("Manga")
        )
        val jsonBody = jsonParser.encodeToString(searchRequest)
        Logger.withTag("SEARCHsadas_BODY").e { jsonBody }
        return jsonBody
    }

    /**
     * Custom normalSearch override: POST with `application/json` body (not form-data) — preserves
     * upstream's `api.post(url, body = requestBody, headers = defaultHeaders)` behaviour where
     * `requestBody` was a JSON-typed `okhttp3.RequestBody`.
     */
    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        val jsonBody = buildSearchJsonBody(searchType)
        return fetchDataWithHeaders({
            api.postJson(url, body = jsonBody, headers = defaultHeaders)
        }) { html -> getSearchResults(html) }
    }

    // ==================== Parsing Methods ====================

    /**
     * Parse home/series list from JSON API response
     * Endpoint: /api/series/?page=x
     */
    override fun extractHomeMangaItems(string: String): MutableList<MangaItem> {
        return try {
            val response: DilarSeriesListResponse = jsonParser.decodeFromString(string)
            response.toMangaItems(API, LANGUAGE, baseUrl.ifBlank { mangaSource.BASEURL }).toMutableList()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractHomeMangaItems: failed to parse: ${e.message}" }
            mutableListOf()
        }
    }

    /**
     * Parse popular manga list (same as home for this API)
     */
    override fun extractMangaList(string: String): List<PopularManga> {
        return try {
            val response: DilarSeriesListResponse = jsonParser.decodeFromString(string)
            response.toPopularMangaList(API, LANGUAGE, baseUrl.ifBlank { mangaSource.BASEURL })
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractMangaList: failed to parse: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Parse search results from JSON API response
     * Endpoint: /api/search/quick_search (POST)
     */
    override suspend fun getSearchResults(string: String): List<MangaItem> {
        return try {
            val response: List<DilarSearchResponse> = jsonParser.decodeFromString(string)
            response.toMangaItems(API, LANGUAGE, baseUrl.ifBlank { mangaSource.BASEURL })
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "getSearchResults: failed to parse: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Parse manga info from JSON API response
     * Endpoint: /api/series/{id}
     */
    override suspend fun extractMangaInfo(string: String, baseUrl: String): MangaInfo {
        return try {
            val response: DilarSeriesDetailResponse = jsonParser.decodeFromString(string)
            response.toMangaInfo(API, LANGUAGE, baseUrl)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractMangaInfo: failed to parse: ${e.message}" }
            createEmptyMangaInfo(baseUrl)
        }
    }

    /**
     * Parse chapters from JSON API response
     * Endpoint: /api/series/{id}/chapters
     */
    override fun parseChapters(html: String): List<ChapterItem> {
        return try {
            val response: DilarChaptersResponse = jsonParser.decodeFromString(html)
            Logger.withTag(TAG).e { "parseChapters: : ${response}" }

            val chapters = response.toChapterItems(baseUrl.ifBlank { mangaSource.BASEURL })
                .sortedByDescending { it.number.replace("Chapter ", "").toDoubleOrNull() ?: 0.0 }
            Logger.withTag(TAG).e { "parseedChapters: : ${chapters}" }

            chapters
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "parseChapters: failed to parse: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Parse chapter images from JSON API response
     * Endpoint: /api/chapters/{releaseId}
     *
     * Image URL format: https://dilar.tube/uploads/releases/{storageKey}/hq/{page.url}
     */
    override fun getChapterImages(string: String): List<String> {
        return try {
            val response: DilarChapterImagesResponse = jsonParser.decodeFromString(string)
            response.toImageUrls()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "getChapterImages: failed to parse: ${e.message}" }
            emptyList()
        }
    }

    // ==================== Genre/Sort Search (Not implemented) ====================

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }

    // ==================== Utility Methods ====================

    /**
     * Extract series ID from URL
     * Example: "https://v2.dilar.tube/api/series/4559" -> "4559"
     */
    fun extractSeriesIdFromUrl(url: String): String? {
        return try {
            val regex = Regex("/series/(\\d+)")
            regex.find(url)?.groupValues?.get(1)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractSeriesIdFromUrl: failed: ${e.message}" }
            null
        }
    }

    /**
     * Extract release ID from chapter URL
     * Example: "https://v2.dilar.tube/api/chapters/128895" -> "128895"
     */
    fun extractReleaseIdFromUrl(url: String): String? {
        return try {
            val regex = Regex("/chapters/(\\d+)")
            regex.find(url)?.groupValues?.get(1)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractReleaseIdFromUrl: failed: ${e.message}" }
            null
        }
    }

    private fun createEmptyMangaInfo(url: String): MangaInfo {
        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = "",
            imageUrl = "",
            rating = "0",
            description = "",
            author = "",
            genres = emptyList(),
            status = "Unknown",
            chapters = mutableListOf()
        )
    }
}

/**
 * Audit-trail postscript (Phase 9.x.cluster192.staleKdocSweep.cascade, Task #647, 2026-05-29)
 *
 * Leaf 2/5 §253 audit-trail-preservation postscript for cluster192, sibling 318 of the cluster57+
 * continuum. Medium 306-line Repository extending `SeparatedDetailsSitesv2` with JSON-body POST
 * search (preserves upstream's non-standard `okhttp3.RequestBody` JSON-body endpoint behaviour via
 * the KMP `api.postJson(...)` direct path) plus 2 utility methods for ID extraction from URLs.
 *
 * The top-of-file prose under audit (lines 3-14):
 *
 *     Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, okhttp3.FormBody / RequestBody ->
 *     Map<String, String>? (for form bodies) or raw JSON String via api.postJson (for JSON bodies),
 *     @Inject dropped, android.util.Log -> Kermit Logger, kotlin.jvm.Volatile ->
 *     kotlin.concurrent.Volatile.
 *
 *     Upstream's `normalSearchFormBody` returned an okhttp3 RequestBody (raw JSON body, not
 *     form-data). The KMP base class `normalSearchFormBody` returns `Map<String, String>?` for
 *     form-encoded bodies. To preserve the upstream's POST-with-JSON-body behaviour, this class
 *     overrides `normalSearch` directly and calls `api.postJson(url, jsonBody, defaultHeaders)`
 *     instead. `normalSearchFormBody` itself is left returning `null` to satisfy the abstract
 *     contract.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — the "Retrofit -> Ktor ApiClient" migration claim: verified by import
 *      survey of lines 16-33. Imports include `me.manga.kira.data.remote.api.ApiClient` (line
 *      26) and zero Retrofit/`IMangaDataApiServices` references. The Ktor migration is complete.
 *
 *   b. LIVE-NOT-STALE — the "FormBody / RequestBody -> Map / api.postJson" claim: verified by
 *      reading lines 120-156. `handelFormBody` and `normalSearchFormBody` both return `null` (the
 *      class doesn't use form-data search bodies); `normalSearch` (lines 150-156) overrides the
 *      base class and dispatches via `api.postJson(url, body = jsonBody, headers = defaultHeaders)`
 *      with the JSON body constructed by `buildSearchJsonBody`. The dual-path claim
 *      ("Map for form, postJson for JSON") is structurally accurate.
 *
 *   c. LIVE-NOT-STALE — the "@Inject dropped" claim: verified by import survey. Zero
 *      `javax.inject.Inject` imports. Constructor (lines 35-39) carries no DI annotations.
 *
 *   d. LIVE-NOT-STALE — the "android.util.Log -> Kermit Logger" claim: verified by import survey.
 *      `co.touchlab.kermit.Logger` import present (line 16). Zero `android.util.Log` imports.
 *      Logger usage at lines 141, 169, 182, 196, 210, 222, 226, 230, 246, 272, 287 — all use the
 *      KMP-portable Kermit tag form `Logger.withTag(TAG).e/i { ... }`.
 *
 *   e. LIVE-NOT-STALE — the "kotlin.jvm.Volatile -> kotlin.concurrent.Volatile" claim: verified
 *      by import survey. Line 17 imports `kotlin.concurrent.Volatile`. The `_cachedHeaders`
 *      property at line 73-74 carries the `@Volatile` annotation. The migration is correctly
 *      applied — `kotlin.concurrent.Volatile` is the KMP-portable annotation; the JVM-only
 *      `kotlin.jvm.Volatile` (or `java.util.concurrent.atomic` variant) would have failed iOS
 *      compilation.
 *
 *   f. POTENTIAL-BUG-PRESERVED — the `Logger.withTag("SEARCHsadas_BODY").e { jsonBody }` call at
 *      line 141. Hardcoded telemetry tag "SEARCHsadas_BODY" appears to be a typo/keyboard-mash
 *      that was committed verbatim — likely upstream debug noise. The `.e` (error) level is
 *      incorrect for a search-body trace (should be `.d` or `.i`). Preserved verbatim per §253;
 *      a future cleanup slice could normalize this to `Logger.withTag(TAG).d { "search body: $jsonBody" }`.
 *
 *   g. COSMETIC-NOT-STALE — the section dividers (`// ==================== <Section> ====================`)
 *      at lines 65, 88, 106, 112, 118, 158, 251, 261. Section dividers are an upstream stylistic
 *      convention preserved verbatim. Not a sweep concern.
 *
 *   h. FACTUALLY-DRIFTED-IN-PROSE-ONLY — the section divider at line 251 reads
 *      "// ==================== Genre/Sort Search (Not implemented) ====================" but the
 *      same "(Not implemented)" qualifier also applies to the filter declarations at lines 112-116
 *      (`sortTypes`, `allGenres`, `blackListGenres` all return `setOf()`). The divider drift is
 *      cosmetic — the "Not implemented" note correctly describes the genre/sort branches that
 *      return `flow { fromCode(0) }`. Not a sweep concern beyond noting the documentation
 *      asymmetry.
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 317 (MangaParkRepositoryAr.kt) — leaf 1/5, opening leaf, 4-override minimal subclass.
 *   - sibling 319 (MangamelloRepository.kt) — leaf 3/5, medium Repository with emptyMangaInfo inline.
 *   - sibling 320 (MangamelloPlusRepository.kt) — leaf 4/5, twin of 319 with Bug 4 fix.
 *   - sibling 321 (AasqRepositoryv2.kt) — leaf 5/5, closing leaf with locale-aware date parser.
 *
 * Cluster192 leaf 2/5. Next leaf: MangamelloRepository.kt (sibling 319).
 */
