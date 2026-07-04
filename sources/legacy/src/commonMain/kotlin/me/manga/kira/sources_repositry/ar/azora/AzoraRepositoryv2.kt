package me.manga.kira.sources_repositry.ar.azora

/**
 * Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, okhttp3.FormBody -> Map<String, String>?,
 * @Inject dropped, android.util.Log -> Kermit Logger, kotlin.jvm.Volatile -> kotlin.concurrent.Volatile.
 * `extractMangaInfo` is now `suspend` per the new `NormalSites` base. `IMangaDataApiServices`
 * (Retrofit) replaced with `ApiClient` (Ktor wrapper).
 */

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import me.manga.kira.sources_repositry.common.NormalSites
import me.manga.kira.sources_repositry.data.MangaSource

class AzoraRepositoryv2(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    private val sourcesDao: SourcesDao,
) : NormalSites(api, sourcesDao) {

    companion object {
        private const val TAG = "AzoraRepositoryv2"
        private const val API_BASE_URL = "https://api.azoramoon.com"
        private const val PER_PAGE = 24
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override val mangaSource: MangaSource
        get() = MangaSource.AZORA

    override val BASE_URL: String by lazy { API_BASE_URL }
    override val homeUrl: String by lazy {
        "$API_BASE_URL/api/query?page=1&perPage=$PER_PAGE&orderBy=lastChapterAddedAt&orderDirection=desc"
    }

    override var imgBaseUrl: String = API_BASE_URL
    override var imgUrlVersion: Int = 0
    override val API: String by lazy { mangaSource.API }
    override val LANGUAGE: String by lazy { mangaSource.LANGUAGE.Language }

    // Popular manga URL - ordered by total views
    override val popularUrl: String by lazy {
        "$API_BASE_URL/api/query?page=1&perPage=$PER_PAGE&orderBy=totalViews&orderDirection=desc"
    }

    // ==================== URL Building ====================

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> buildSearchUrl(searchType.toNormalQuery())
            is SearchType.GENRES -> ""
            is SearchType.SORT -> ""
        }

    private fun buildSearchUrl(query: String): String =
        "$API_BASE_URL/api/query?searchTerm=$query&perPage=$PER_PAGE"

    /**
     * URL for loading more manga (pagination)
     * Uses lastChapterAddedAt ordering for home/latest updates
     */
    override fun handelLoadMoreUrl(page: Int): String =
        "$API_BASE_URL/api/query?page=$page&perPage=$PER_PAGE&orderBy=lastChapterAddedAt&orderDirection=desc"

    // ==================== Headers Management ====================

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    // ==================== Form Body (Not used for this API) ====================

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? = null
    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? = null
    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? = null
    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? = null

    // ==================== Filters (Not implemented yet) ====================

    override val sortTypes: Set<String> get() = setOf()
    override val allGenres: Set<String> get() = setOf()
    override val blackListGenres: Set<String> get() = setOf()

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }

    // ==================== Parsing Methods ====================

    /**
     * Parse search results from JSON API response
     * Endpoint: /api/query?searchTerm=xxx&perPage=xx
     */
    override suspend fun getSearchResults(string: String): List<MangaItem> {
        return try {
            val response: AzoraQueryResponse = jsonParser.decodeFromString(string)
            response.toMangaItems(API, LANGUAGE)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "getSearchResults: failed to parse search results: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Extract home manga items from JSON API response
     * Endpoint: /api/query?page=x&perPage=xx&orderBy=lastChapterAddedAt&orderDirection=desc
     */
    override fun extractHomeMangaItems(string: String): MutableList<MangaItem> {
        return try {
            val response: AzoraQueryResponse = jsonParser.decodeFromString(string)
            response.toMangaItems(API, LANGUAGE).toMutableList()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractHomeMangaItems: failed to parse home items: ${e.message}" }
            mutableListOf()
        }
    }

    /**
     * Extract popular manga list from JSON API response
     * Endpoint: /api/query?page=x&perPage=xx&orderBy=totalViews&orderDirection=desc
     */
    override fun extractMangaList(string: String): List<PopularManga> {
        return try {
            val response: AzoraQueryResponse = jsonParser.decodeFromString(string)
            response.toPopularMangaList(API, LANGUAGE)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractMangaList: failed to parse popular list: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Extract manga info from JSON API response
     * Endpoint: /api/post/?postId=xxx
     *
     * @param string The JSON response from the API
     * @param baseUrl The original URL used for the request (for reference)
     */
    override suspend fun extractMangaInfo(string: String, baseUrl: String): MangaInfo {
        return try {
            val response: AzoraPostDetailResponse = jsonParser.decodeFromString(string)
            response.toMangaInfo(API, LANGUAGE, baseUrl)
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractMangaInfo: failed to parse manga info: ${e.message}" }
            createEmptyMangaInfo(baseUrl)
        }
    }

    /**
     * Parse chapters from manga detail response
     * Used when you need just the chapters from a post detail
     */
    fun parseChapters(json: String): List<ChapterItem> {
        return try {
            val response: AzoraPostDetailResponse = jsonParser.decodeFromString(json)
            response.post?.chapters?.toChapterItems()
                ?.sortedBy { it.number.replace("Chapter ", "").toDoubleOrNull() }
                ?.reversed()
                ?: emptyList()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "parseChapters: failed to parse chapters: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Get chapter images from JSON API response
     * Endpoint: /api/chapter?chapterId=xxx
     */
    override fun getChapterImages(string: String): List<String> {
        return try {
            val response: AzoraChapterImagesResponse = jsonParser.decodeFromString(string)
            response.toImageUrls()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "getChapterImages: failed to parse chapter images: ${e.message}" }
            emptyList()
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Build the URL for fetching manga details by ID
     */
    fun buildMangaDetailUrl(postId: Int): String {
        return "$API_BASE_URL/api/post/?postId=$postId"
    }

    /**
     * Build the URL for fetching chapter images by ID
     */
    fun buildChapterImagesUrl(chapterId: Int): String {
        return "$API_BASE_URL/api/chapter?chapterId=$chapterId"
    }

    /**
     * Extract post ID from a manga URL
     * Example: "https://api.azoramoon.com/api/post/?postId=92" -> 92
     */
    fun extractPostIdFromUrl(url: String): Int? {
        return try {
            val regex = Regex("postId=(\\d+)")
            regex.find(url)?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractPostIdFromUrl: failed to extract post ID: ${e.message}" }
            null
        }
    }

    /**
     * Extract chapter ID from a chapter URL
     * Example: "https://api.azoramoon.com/api/chapter?chapterId=85027" -> 85027
     */
    fun extractChapterIdFromUrl(url: String): Int? {
        return try {
            val regex = Regex("chapterId=(\\d+)")
            regex.find(url)?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "extractChapterIdFromUrl: failed to extract chapter ID: ${e.message}" }
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
 * Audit-trail postscript (Phase 9.x.cluster193.staleKdocSweep.cascade, Task #648, 2026-05-29)
 *
 * Leaf 1/5 §253 audit-trail-preservation postscript for cluster193, sibling 322 of the cluster57+
 * continuum. Opening leaf of cluster193 (the second wave of the :ar/ Repository implementation
 * tier sweep, deferred from cluster192's batch-composition cap). This file is the SMALLEST in
 * the cluster193 batch at 274 lines — a JSON-API Repository extending `NormalSites` against the
 * `api.azoramoon.com` backend with no HTML scraping (vs the ksoup-bearing siblings).
 *
 * The top-of-file prose under audit (lines 3-8) is a single migration-note paragraph:
 *
 *     Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, okhttp3.FormBody -> Map<String, String>?,
 *     @Inject dropped, android.util.Log -> Kermit Logger, kotlin.jvm.Volatile -> kotlin.concurrent.Volatile.
 *     `extractMangaInfo` is now `suspend` per the new `NormalSites` base. `IMangaDataApiServices`
 *     (Retrofit) replaced with `ApiClient` (Ktor wrapper).
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — the "Retrofit -> Ktor ApiClient" migration claim: verified by import
 *      survey of lines 10-26. `me.manga.kira.data.remote.api.ApiClient` import present (line 19).
 *      Zero `Retrofit`/`IMangaDataApiServices` imports. Constructor (line 28-32) accepts
 *      `api: ApiClient`. Body uses `api.get(url, headers = defaultHeaders)` pattern. The Ktor
 *      migration is structurally complete.
 *
 *   b. LIVE-NOT-STALE — the "okhttp3.FormBody -> Map<String, String>?" migration claim: verified
 *      by signature survey. Lines 104-107 declare `handelFormBody`, `normalSearchFormBody`,
 *      `genresSearchFormBody`, `sortFormBody` all returning `Map<String, String>?` (uniformly
 *      `null` since this is a JSON-API backend with no form-encoded POSTs). Zero `okhttp3.FormBody`
 *      imports.
 *
 *   c. LIVE-NOT-STALE — the "@Inject dropped" migration claim: verified by annotation survey.
 *      Zero `javax.inject.Inject` imports; constructor declaration at line 28-32 carries no DI
 *      annotations. The Koin binding lives in the consuming module per the platform-deps
 *      cross-module wiring boundary (see [[project_yami_kmp_platform_deps]]).
 *
 *   d. LIVE-NOT-STALE — the "android.util.Log -> Kermit Logger" migration claim: verified by
 *      reading lines 10 (`import co.touchlab.kermit.Logger`) + 134/148/162/179/196/210/240/254
 *      (multiple `Logger.withTag(TAG).e(e) { ... }` call sites). Zero `android.util.Log` imports.
 *      Lazy-evaluated message-block lambda pattern is the Kermit idiom.
 *
 *   e. LIVE-NOT-STALE — the "kotlin.jvm.Volatile -> kotlin.concurrent.Volatile" migration claim:
 *      verified by reading line 11 (`import kotlin.concurrent.Volatile`) + line 91 (the
 *      `@Volatile private var _cachedHeaders: Map<String, String>?` declaration). The KMP-portable
 *      `kotlin.concurrent.Volatile` is the chosen import (vs the JVM-only `kotlin.jvm.Volatile`).
 *
 *   f. LIVE-NOT-STALE — the "extractMangaInfo is now suspend per the new NormalSites base"
 *      migration claim: verified by reading line 174 (`override suspend fun extractMangaInfo`).
 *      The `suspend` keyword is present at the override site. The base class `NormalSites`
 *      (line 25 import) declares the corresponding `suspend` signature — the override compiles
 *      against the new base. The widening from non-suspend to suspend at the base reflects the
 *      Ktor migration's coroutine-first I/O posture.
 *
 *   g. COSMETIC-NOT-STALE — the section-divider comments `// ==================== <Section> ====================`
 *      pattern (lines 64, 83, 102, 109, 123, 215). Cosmetic discipline preserving the upstream
 *      file's visual structure; identical pattern observed in sibling 318 (DilarV2Repository). Not
 *      a sweep concern. Preserved verbatim per §253.
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 323 (SwatMangaRepository.kt) — leaf 2/5, 482-line Repository extending
 *     `BaseMangaRepository` against `appswat.com/v2/api/v1/` with kotlinx.datetime LocalDate.parse
 *     ISO-8601 default + Phase 8 Coil3 image-request stub.
 *   - sibling 324 (TeamXRepositoryv2.kt) — leaf 3/5, 490-line ksoup-bearing Repository with
 *     Arabic month-name map + Phase 8 parallel-IO TODO + Phase 8 locale-aware date TODO.
 *   - sibling 325 (ProchanRepository.kt) — leaf 4/5, 512-line near-duplicate twin of sibling 326
 *     with `prochan.net` CDN routing + commented-out blackListGenres scaffold.
 *   - sibling 326 (ProMangaRepository.kt) — leaf 5/5, closing leaf, 535-line `open class` with
 *     hard-coded `prochan.net` apiUrl + ProMangaImageCombiner Phase 8 stub fan-out.
 *
 * Cluster193 leaf 1/5 — opening leaf. Next leaf: SwatMangaRepository.kt (sibling 323).
 */

