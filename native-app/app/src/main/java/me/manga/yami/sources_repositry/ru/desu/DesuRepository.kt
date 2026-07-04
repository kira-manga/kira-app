package me.manga.yamiapk.sources_repositry.ru.desu

import android.util.Log
import kotlinx.serialization.json.Json
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.emptyMangaInfo
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toPopularMangaList
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.NormalSitesv2
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.ru.desu.models.chaptes.DesuChapters
import me.manga.yamiapk.sources_repositry.ru.desu.models.home.DesuHome
import me.manga.yamiapk.sources_repositry.ru.desu.models.home.Response
import me.manga.yamiapk.sources_repositry.ru.desu.models.info.DesuMangaInfo
import me.manga.yamiapk.sources_repositry.ru.desu.models.info.Item0
import okhttp3.FormBody
import javax.inject.Inject

class DesuRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSitesv2(dataStore,api,sourcesRepository){

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        } }

    override val mangaSource: MangaSource
        get() = MangaSource.DESU

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    override var imgBaseUrl: String = "https://static.desu.city/"
    override var imgUrlVersion: Int = 0

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    private val apiUrl by lazy { "${baseUrl.ifBlank { BASE_URL }}manga/api/"}

    override val homeUrl: String by lazy {  "${apiUrl}?limit=50&order=updated&page=1"}
    override val popularUrl: String by lazy {  "${apiUrl}?limit=50&order=popular&page=1"}
    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override fun handelLoadMoreUrl(page: Int): String {
        return "${apiUrl}?limit=50&order=updated&page=$page"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${apiUrl}?limit=50&search=${searchType.toNormalQuery()}&page=1"
    }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf("Хентай","Hentai")

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
       return null
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? {
        return null
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? {
        return null
    }

    override fun sortFormBody(searchType: SearchType.SORT): FormBody? {
        return null
    }

    override fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem> {

       return mutableListOf()
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {

        return try {

            val apiResponse = jsonParser.decodeFromString<DesuHome>(html)
            apiResponse
                .toMangaItems(
                )
                .toMutableList()

        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }



    }

    override fun extractMangaList(html: String): List<PopularManga> {
        return try {

            val apiResponse = jsonParser.decodeFromString<DesuHome>(html)
            apiResponse
                .toMangaItems(
                )
                .toMutableList().toPopularMangaList()

        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }

    }

    override suspend fun extractMangaInfo(html: String, baseUrl: String): MangaInfo {
        return try {

            val apiResponse = jsonParser.decodeFromString<DesuMangaInfo>(html)
            apiResponse.toMangaInfo(baseUrl)

        } catch (e: Exception) {
            e.printStackTrace()
           emptyMangaInfo
        }

    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        return try {

            val apiResponse = jsonParser.decodeFromString<DesuHome>(html)
            apiResponse
                .toMangaItems(
                )
                .toMutableList()

        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

    }

    override fun getChapterImages(html: String): List<String> {
        return try {

            val chapters = jsonParser.decodeFromString<DesuChapters>(html)
            chapters
                .response             // Response?
                ?.pages               // Pages?
                ?.list                // List<Item9?>?
                .orEmpty()            // if null → empty list
                .mapNotNull { it     // for each Item9?
                    it?.img          // take its img (String?)
                }

        } catch (e: Exception) {
            e.printStackTrace()
           listOf()
        }

    }




    fun DesuHome.toMangaItems(): List<MangaItem> =
        response
            .orEmpty()               // safely handle null
            .filterNotNull()         // drop any null-elements
            .map { it.toMangaItem() }.filter {
                !it.genres.hasBlacklistedGenre()
            }

    /**
     * Map a single Response entry to your domain MangaItem.
     */
    fun Response.toMangaItem(): MangaItem =
        MangaItem(
            api       = API,
            language  = LANGUAGE,
            // prefer the localized title if available
            title     = russian.takeIf { !it.isNullOrBlank() } ?: name.orEmpty(),
            url       = "${apiUrl}${id}/",
            // assuming your Image model has an `original: String?`
            imageUrl  = image?.original.orEmpty(),
            rating    = score?.toInt(),
            // split comma-separated genres into a clean list
            genres    = genres
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            // map the Chapters container into your ChapterItem list
            chapters  = emptyList()
        )

    fun DesuMangaInfo.toMangaInfo(baseUrl: String): MangaInfo {
        val resp = response

        return if (resp == null) {
            // return an “empty” MangaInfo
            emptyMangaInfo
        } else {
            MangaInfo(
                api            = API,
                language       = LANGUAGE,
                url            = baseUrl,
                title          = resp.russian.takeIf { !it.isNullOrBlank() } ?: resp.name.orEmpty(),
                imageUrl       = resp.image?.original.orEmpty(),
                rating         = resp.score?.toString().orEmpty(),
                ratingCount    = resp.score_users?.toString().orEmpty(),
                description    = resp.description.orEmpty(),
                otherNames     = resp.synonyms.orEmpty(),
                author         = "",
                artist         = "",
                genres         = resp.genres
                    .orEmpty()
                    .mapNotNull { it?.text }
                    .filter { it.isNotBlank() },
                tags           = emptyList(),
                yearOfProduction = resp.released_on
                    ?.let { unixToYear(it) }
                    .orEmpty(),
                status         = resp.status.orEmpty(),
                favoritesCount = resp.views?.toString().orEmpty(),
                chapters       = resp.chapters
                    ?.list
                    .orEmpty()
                    .mapNotNull { it?.toChapterItem(baseUrl) }
                    .toMutableList()
            )
        }
    }


    /**
     * Helper: map a single JSON‐chapter entry to your ChapterItem.
     */
    private fun Item0.toChapterItem(mangaUrl:String): ChapterItem =
        ChapterItem(
            number = this.ch.toString().orEmpty(),
            url    ="${mangaUrl}chapter/${this.id}"
        )

    /**
     * Convert a Unix timestamp (seconds) to year string.
     * Adjust if your API uses milliseconds.
     */
    private fun unixToYear(timestamp: Int): String {
        val millis = timestamp.toLong() * 1000L
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = millis
        }
        return calendar.get(java.util.Calendar.YEAR).toString()
    }
}