package me.manga.yamiapk.sources_repositry.ar.azora

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.states.State.Error.Companion.fromCode
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType

import me.manga.yamiapk.sources_repositry.common.NormalSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import javax.inject.Inject

class AzoraRepositoryv2 @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    private val sourcesDao: SourcesDao,
) : NormalSites(dataStore, api, sourcesDao) {

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

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? = null
    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? = null
    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? = null
    override fun sortFormBody(searchType: SearchType.SORT): FormBody? = null

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
    override suspend fun getSearchResults(json: String): List<MangaItem> {
        return try {
            val response: AzoraQueryResponse = jsonParser.decodeFromString(json)
            response.toMangaItems(API, LANGUAGE)
        } catch (e: Exception) {
            Log.e(TAG, "getSearchResults: failed to parse search results: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Extract home manga items from JSON API response
     * Endpoint: /api/query?page=x&perPage=xx&orderBy=lastChapterAddedAt&orderDirection=desc
     */
    override fun extractHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val response: AzoraQueryResponse = jsonParser.decodeFromString(json)
            response.toMangaItems(API, LANGUAGE).toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "extractHomeMangaItems: failed to parse home items: ${e.message}", e)
            mutableListOf()
        }
    }

    /**
     * Extract popular manga list from JSON API response
     * Endpoint: /api/query?page=x&perPage=xx&orderBy=totalViews&orderDirection=desc
     */
    override fun extractMangaList(json: String): List<PopularManga> {
        return try {
            val response: AzoraQueryResponse = jsonParser.decodeFromString(json)
            response.toPopularMangaList(API, LANGUAGE)
        } catch (e: Exception) {
            Log.e(TAG, "extractMangaList: failed to parse popular list: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Extract manga info from JSON API response
     * Endpoint: /api/post/?postId=xxx
     *
     * @param json The JSON response from the API
     * @param url The original URL used for the request (for reference)
     */
    override suspend fun extractMangaInfo(json: String, url: String): MangaInfo {
        return try {
            val response: AzoraPostDetailResponse = jsonParser.decodeFromString(json)
            response.toMangaInfo(API, LANGUAGE, url)
        } catch (e: Exception) {
            Log.e(TAG, "extractMangaInfo: failed to parse manga info: ${e.message}", e)
            createEmptyMangaInfo(url)
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
            Log.e(TAG, "parseChapters: failed to parse chapters: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get chapter images from JSON API response
     * Endpoint: /api/chapter?chapterId=xxx
     */
    override fun getChapterImages(json: String): List<String> {
        return try {
            val response: AzoraChapterImagesResponse = jsonParser.decodeFromString(json)
            response.toImageUrls()
        } catch (e: Exception) {
            Log.e(TAG, "getChapterImages: failed to parse chapter images: ${e.message}", e)
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
            Log.e(TAG, "extractPostIdFromUrl: failed to extract post ID: ${e.message}", e)
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
            Log.e(TAG, "extractChapterIdFromUrl: failed to extract chapter ID: ${e.message}", e)
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
            ratingCount = "0",
            description = "",
            otherNames = "",
            author = "",
            artist = "",
            genres = emptyList(),
            tags = emptyList(),
            yearOfProduction = "",
            status = "Unknown",
            favoritesCount = "0",
            chapters = mutableListOf()
        )
    }
}