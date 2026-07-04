package me.manga.yamiapk.sources_repositry.ar.dilar.v2


import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
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
import me.manga.yamiapk.sources_repositry.common.SeparatedDetailsSitesv2
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

class DilarV2Repository @Inject constructor(
    dataStore: DataStoreHelper,
    api: IMangaDataApiServices,
    sourcesRepository: SourcesDao
) : SeparatedDetailsSitesv2(dataStore, api, sourcesRepository) {

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

    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL  }}
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { mangaSource.BASEURL  }}api/series/?page=1" }
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
            return base + ("Referer" to baseUrl.ifBlank { mangaSource.BASEURL  })
        }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + ("Referer" to baseUrl.ifBlank { mangaSource.BASEURL  })
        _cachedHeaders = merged
        dataStore.saveHeadersForApi(API, merged)
    }

    // ==================== URL Building ====================

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { mangaSource.BASEURL  }}api/series/?page=$page"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { mangaSource.BASEURL  }}api/search/quick_search"
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

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? = null

    override fun normalSearchFormBody(searchType: SearchType.Normal): RequestBody {
        val searchRequest = DilarSearchRequest(
            query = searchType.query,
            includes = listOf("Manga")
        )
        val jsonBody = jsonParser.encodeToString(searchRequest)

        Log.e("SEARCHsadas_BODY", jsonBody) // ✅ هنا الصح

        return jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? = null
    override fun sortFormBody(searchType: SearchType.SORT): FormBody? = null

    // ==================== Parsing Methods ====================

    /**
     * Parse home/series list from JSON API response
     * Endpoint: /api/series/?page=x
     */
    override fun extractHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val response: DilarSeriesListResponse = jsonParser.decodeFromString(json)

            response.toMangaItems(API, LANGUAGE, baseUrl.ifBlank { mangaSource.BASEURL  }).toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "extractHomeMangaItems: failed to parse: ${e.message}", e)
            mutableListOf()
        }
    }

    /**
     * Parse popular manga list (same as home for this API)
     */
    override fun extractMangaList(json: String): List<PopularManga> {
        return try {
            val response: DilarSeriesListResponse = jsonParser.decodeFromString(json)
            response.toPopularMangaList(API, LANGUAGE, baseUrl.ifBlank { mangaSource.BASEURL  })
        } catch (e: Exception) {
            Log.e(TAG, "extractMangaList: failed to parse: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Parse search results from JSON API response
     * Endpoint: /api/search/quick_search (POST)
     */
    override suspend fun getSearchResults(json: String): List<MangaItem> {
        return try {
            val response: List<DilarSearchResponse> = jsonParser.decodeFromString(json)
            response.toMangaItems(API, LANGUAGE, baseUrl.ifBlank { mangaSource.BASEURL  })
        } catch (e: Exception) {
            Log.e(TAG, "getSearchResults: failed to parse: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Parse manga info from JSON API response
     * Endpoint: /api/series/{id}
     */
    override suspend fun extractMangaInfo(json: String, url: String): MangaInfo {
        return try {
            val response: DilarSeriesDetailResponse = jsonParser.decodeFromString(json)
            response.toMangaInfo(API, LANGUAGE, url)
        } catch (e: Exception) {
            Log.e(TAG, "extractMangaInfo: failed to parse: ${e.message}", e)
            createEmptyMangaInfo(url)
        }
    }

    /**
     * Parse chapters from JSON API response
     * Endpoint: /api/series/{id}/chapters
     */
    override fun parseChapters(json: String): List<ChapterItem> {
        return try {
            val response: DilarChaptersResponse = jsonParser.decodeFromString(json)
            Log.e(TAG, "parseChapters: : ${response}")

            val chapters = response.toChapterItems(baseUrl.ifBlank { mangaSource.BASEURL  })
                .sortedByDescending { it.number.replace("Chapter ", "").toDoubleOrNull() ?: 0.0 }
            Log.e(TAG, "parseedChapters: : ${chapters}")

            chapters
        } catch (e: Exception) {
            Log.e(TAG, "parseChapters: failed to parse: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Parse chapter images from JSON API response
     * Endpoint: /api/chapters/{releaseId}
     *
     * Image URL format: https://dilar.tube/uploads/releases/{storageKey}/hq/{page.url}
     */
    override fun getChapterImages(json: String): List<String> {
        return try {
            val response: DilarChapterImagesResponse = jsonParser.decodeFromString(json)
            response.toImageUrls()
        } catch (e: Exception) {
            Log.e(TAG, "getChapterImages: failed to parse: ${e.message}", e)
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
            Log.e(TAG, "extractSeriesIdFromUrl: failed: ${e.message}", e)
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
            Log.e(TAG, "extractReleaseIdFromUrl: failed: ${e.message}", e)
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