package me.manga.kira.sources_repositry.ru.desu

/**
 * Migration note (Phase 7.8): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * Gson -> kotlinx.serialization, @Inject dropped, android.util.Log -> Kermit Logger,
 * java.time -> kotlinx.datetime.
 *
 * `unixToYear` reimplemented using kotlinx.datetime (`Instant.fromEpochSeconds(...).toLocalDateTime(...)`)
 * instead of `java.util.Calendar` — preserves the exact semantics (system-default timezone year
 * from a Unix-seconds timestamp).
 */

import kotlin.concurrent.Volatile
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.NormalSitesv2
import me.manga.kira.sources_repositry.data.MangaSource
import me.manga.kira.sources_repositry.ru.desu.models.chaptes.DesuChapters
import me.manga.kira.sources_repositry.ru.desu.models.home.DesuHome
import me.manga.kira.sources_repositry.ru.desu.models.home.Response
import me.manga.kira.sources_repositry.ru.desu.models.info.DesuMangaInfo
import me.manga.kira.sources_repositry.ru.desu.models.info.Item0

@OptIn(ExperimentalTime::class)
class DesuRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSitesv2(api, sourcesRepository) {

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }

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

    private val apiUrl by lazy { "${baseUrl.ifBlank { BASE_URL }}manga/api/" }

    override val homeUrl: String by lazy { "${apiUrl}?limit=50&order=updated&page=1" }
    override val popularUrl: String by lazy { "${apiUrl}?limit=50&order=popular&page=1" }

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
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
        get() = setOf("Хентай", "Hentai")

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        return null
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? {
        return null
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? {
        return null
    }

    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? {
        return null
    }

    override fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem> {
        return mutableListOf()
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        return try {
            val apiResponse = jsonParser.decodeFromString<DesuHome>(html)
            apiResponse
                .toMangaItems()
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
                .toMangaItems()
                .toMutableList()
                .toPopularMangaList()
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
            emptyMangaInfo()
        }
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        return try {
            val apiResponse = jsonParser.decodeFromString<DesuHome>(html)
            apiResponse
                .toMangaItems()
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
            api = API,
            language = LANGUAGE,
            // prefer the localized title if available
            title = russian.takeIf { !it.isNullOrBlank() } ?: name.orEmpty(),
            url = "${apiUrl}${id}/",
            // assuming your Image model has an `original: String?`
            imageUrl = image?.original.orEmpty(),
            rating = score?.toInt(),
            // split comma-separated genres into a clean list
            genres = genres
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            // map the Chapters container into your ChapterItem list
            chapters = emptyList(),
        )

    fun DesuMangaInfo.toMangaInfo(baseUrl: String): MangaInfo {
        val resp = response

        return if (resp == null) {
            // return an "empty" MangaInfo
            emptyMangaInfo()
        } else {
            MangaInfo(
                api = API,
                language = LANGUAGE,
                url = baseUrl,
                title = resp.russian.takeIf { !it.isNullOrBlank() } ?: resp.name.orEmpty(),
                imageUrl = resp.image?.original.orEmpty(),
                rating = resp.score?.toString().orEmpty(),
                description = resp.description.orEmpty(),
                author = "",
                genres = resp.genres
                    .orEmpty()
                    .mapNotNull { it?.text }
                    .filter { it.isNotBlank() },
                status = resp.status.orEmpty(),
                chapters = resp.chapters
                    ?.list
                    .orEmpty()
                    .mapNotNull { it?.toChapterItem(baseUrl) }
                    .toMutableList(),
            )
        }
    }

    /**
     * Helper: map a single JSON-chapter entry to your ChapterItem.
     */
    private fun Item0.toChapterItem(mangaUrl: String): ChapterItem =
        ChapterItem(
            number = this.ch.toString(),
            url = "${mangaUrl}chapter/${this.id}",
        )

    /**
     * Convert a Unix timestamp (seconds) to year string.
     * Adjust if your API uses milliseconds.
     *
     * Source used `java.util.Calendar.getInstance()` with system timezone. Ported to
     * `Instant.fromEpochSeconds(...).toLocalDateTime(currentSystemDefault())` — same observable
     * behaviour for the year component.
     */
    private fun unixToYear(timestamp: Int): String {
        val instant = Instant.fromEpochSeconds(timestamp.toLong())
        val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return dt.year.toString()
    }

    private fun emptyMangaInfo(): MangaInfo = MangaInfo(
        api = API,
        language = LANGUAGE,
        url = "",
        title = "",
        imageUrl = "",
        rating = "",
        description = "",
        author = "",
        genres = emptyList(),
        status = "",
        chapters = mutableListOf(),
    )

    private fun List<MangaItem>.toPopularMangaList(): List<PopularManga> = this.map {
        PopularManga(
            api = it.api,
            language = it.language,
            title = it.title,
            url = it.url,
            imageUrl = it.imageUrl,
        )
    }
}
