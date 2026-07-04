package me.manga.kira.sources_repositry.pt.sussytoons

/**
 * Migration note (Phase 7.7): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * Gson -> kotlinx.serialization, @Inject dropped, android.util.Log -> Kermit Logger,
 * java.time -> kotlinx.datetime.
 *
 * Date parsing: the Android source first tried `LocalDate.parse(dateStr)` (ISO date), and on
 * failure fell back to `SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)`. Since both formats are
 * `yyyy-MM-dd`, the second branch was effectively unreachable — kotlinx.datetime's
 * `LocalDate.parse` covers the ISO case directly. The Calendar reconstitution dance is dropped.
 *
 * `emptyMangaInfo` / `toPopularMangaList` helpers from `core.util.data_classes.HandelDataClasses`
 * are inlined locally (same approach as the `fr/` wave).
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
import me.manga.kira.sources_repositry.pt.sussytoons.models.ChapterDto
import me.manga.kira.sources_repositry.pt.sussytoons.models.ChapterPageDto
import me.manga.kira.sources_repositry.pt.sussytoons.models.MangaDto
import me.manga.kira.sources_repositry.pt.sussytoons.models.MangaReferenceDto
import me.manga.kira.sources_repositry.pt.sussytoons.models.MangaStatus
import me.manga.kira.sources_repositry.pt.sussytoons.models.PageDto
import me.manga.kira.sources_repositry.pt.sussytoons.models.ResultDto

@OptIn(ExperimentalTime::class)
class SussytoonsRepository(
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
    val useWidthInThumbnail = true
    override val mangaSource: MangaSource
        get() = MangaSource.SUSSYTOONS

    // Updated API URLs to match GreenShit structure
    private val newApiUrl = "https://api2.sussytoons.wtf"
    private val newCdnUrl = "https://cdn.sussytoons.wtf/"

    override var imgBaseUrl: String = newCdnUrl
    override var imgUrlVersion: Int = 0

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    private val scanId: Long = 1
    private val defaultScanId: Int = 1

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: defaultHeaders
        _cachedHeaders = headers
        return super.initSite()
    }

    private val apiUrl by lazy { baseUrl.ifBlank { BASE_URL } }

    // Updated endpoints to match GreenShit structure
    override val homeUrl: String by lazy {
        "${baseUrl.ifBlank { BASE_URL }}obras/atualizacoes?pagina=1&limite=24&gen_id=1"
    }

    override val popularUrl: String by lazy {
        "${baseUrl.ifBlank { BASE_URL }}obras/ranking?pagina=1&limite=15&gen_id=1&tipo=visualizacoes_geral"
    }

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: mapOf(
            "scan-id" to scanId.toString()
        )

    override fun handelLoadMoreUrl(page: Int): String {
        return "${apiUrl}obras/atualizacoes?pagina=$page&limite=24&gen_id=1"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return when (searchType) {
            is SearchType.Normal -> {
                "${apiUrl}obras/search?pagina=1&limite=35&obr_nome=${searchType.toNormalQuery()}&todos_generos=1&orderBy=ultima_atualizacao&orderDirection=DESC"
            }
            else -> homeUrl
        }
    }

    override val sortTypes: Set<String>
        get() = setOf(
//            "updated", "popular"
        )

    override val allGenres: Set<String>
        get() = setOf()

    override val blackListGenres: Set<String>
        get() = setOf(
            "Adulto",
            "Hentai"
        )

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? = null
    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? = null
    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? = null
    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? = null

    override fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem> {
        return mutableListOf()
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        return try {
            logJsonRaw("SUSSY_HOME_JSON", html)

            val unwrappedResponse = unwrapJsonResponse(html)
            val response = jsonParser.decodeFromString<ResultDto<List<MangaDto>>>(unwrappedResponse)

            Logger.withTag("SUSSY_HOME_PARSED").i { "Found ${response.results.size} items" }
            response.toMangaItems().toMutableList()
        } catch (e: Exception) {
            Logger.withTag("SUSSY_HOME_ERROR").e(e) { "Failed to parse home manga items" }
            mutableListOf()
        }
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        return try {
            logJsonRaw("SUSSY_POPULAR_JSON", html)

            val unwrappedResponse = unwrapJsonResponse(html)
            val response = jsonParser.decodeFromString<ResultDto<List<MangaDto>>>(unwrappedResponse)
            val mangs = response.toMangaItems().toPopularMangaList()

            Logger.withTag("SUSSY_POPULAR_PARSED").d { "Found ${mangs.size} popular items" }
            mangs
        } catch (e: Exception) {
            Logger.withTag("SUSSY_POPULAR_ERROR").e(e) { "Failed to parse popular manga list" }
            mutableListOf()
        }
    }

    override suspend fun extractMangaInfo(html: String, baseUrl: String): MangaInfo {
        return try {
            logJsonRaw("SUSSY_DETAILS_JSON", html)

            // For single manga details, unwrap "resultado" wrapper
            val unwrappedResponse = unwrapJsonResponse(html)
            val mangaDto = jsonParser.decodeFromString<MangaDto>(unwrappedResponse)

            Logger.withTag("SUSSY_DETAILS_PARSED").i { "Parsed manga: ${mangaDto.name}" }
            mangaDto.toMangaInfo(baseUrl)
        } catch (e: Exception) {
            Logger.withTag("SUSSY_DETAILS_ERROR").e(e) { "Failed to parse manga info" }
            emptyMangaInfo
        }
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        return try {
            logJsonRaw("SUSSY_SEARCH_JSON", html)

            val unwrappedResponse = unwrapJsonResponse(html)
            val response = jsonParser.decodeFromString<ResultDto<List<MangaDto>>>(unwrappedResponse)

            Logger.withTag("SUSSY_SEARCH_PARSED").i { "Found ${response.results.size} search results" }
            response.toMangaItems()
        } catch (e: Exception) {
            Logger.withTag("SUSSY_SEARCH_ERROR").e(e) { "Failed to parse search results" }
            mutableListOf()
        }
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun getChapterImages(html: String): List<String> {
        return try {
            logJsonRaw("SUSSY_PAGES_JSON", html)

            // Unwrap "resultado" wrapper for chapter pages
            val unwrappedResponse = unwrapJsonResponse(html)
            val chapterPageDto = jsonParser.decodeFromString<ChapterPageDto>(unwrappedResponse)

            val pages = chapterPageDto.toPageList()
            Logger.withTag("SUSSY_PAGES_PARSED").i { "Found ${pages.size} pages" }
            pages
        } catch (e: Exception) {
            Logger.withTag("SUSSY_PAGES_ERROR").e(e) { "Failed to parse chapter images" }
            listOf()
        }
    }

    // Helper to unwrap "resultado" or "resultados" wrapper if present
    private fun unwrapJsonResponse(jsonString: String): String {
        return try {
            val jsonElement = jsonParser.decodeFromString<JsonElement>(jsonString)
            if (jsonElement is JsonObject) {
                // Check for "resultado" or "resultados" wrapper
                val unwrapped = jsonElement["resultado"] ?: jsonElement["resultados"]
                if (unwrapped != null) {
                    return jsonParser.encodeToString(JsonElement.serializer(), unwrapped)
                }
            }
            jsonString
        } catch (e: Exception) {
            jsonString
        }
    }

    // Data transformation methods
    private fun ResultDto<List<MangaDto>>.toMangaItems(): List<MangaItem> =
        results.filter { it.type != "TEXTO" }.map { it.toMangaItem() }

    private fun MangaDto.toMangaItem(): MangaItem {
        return MangaItem(
            api = API,
            language = LANGUAGE,
            title = name,
            url = "${apiUrl}obras/${id}",
            imageUrl = getThumbnailUrl(),
            rating = null,
            genres = genres.map { it.value },
            chapters = emptyList()
        )
    }

    private fun MangaDto.toMangaInfo(baseUrl: String): MangaInfo {
        val cleanDescription = description?.let {
            Ksoup.parseBodyFragment(it).text()
        }.orEmpty()

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = baseUrl,
            title = name,
            imageUrl = getThumbnailUrl(),
            rating = "",
            description = cleanDescription,
            author = "",
            genres = genres.map { it.value },
            status = status.toStatusString(),
            chapters = chapters?.map { it.toChapterItem() }?.reversed()?.toMutableList() ?: mutableListOf()
        )
    }

    private fun ChapterDto.toChapterItem(): ChapterItem {
        val chapterNumber = number?.toString() ?: "0"
        // Updated to use new API structure matching GreenShit
        return ChapterItem(
            number = chapterNumber,
            name = name,
            url = "${apiUrl}capitulos/${id}",
            date = parseDate(updateAt),
            isDownloaded = false,
            isBookmarked = false,
        )
    }

    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return Clock.System.todayIn(TimeZone.currentSystemDefault())

        return try {
            LocalDate.parse(dateStr)
        } catch (e: Exception) {
            // The Android fallback used `SimpleDateFormat("yyyy-MM-dd")` which matches kotlinx's
            // ISO LocalDate parse — so a second attempt would always fail too. Preserve the
            // original "return today on parse failure" behaviour.
            Clock.System.todayIn(TimeZone.currentSystemDefault())
        }
    }

    private fun MangaDto.getThumbnailUrl(): String {
        return thumbnail?.let {
            when {
                it.startsWith("http") -> it
                it.contains("/") -> {
                    val cleaned = if (it.startsWith("/")) it else "/$it"
                    "${newCdnUrl.dropTrailingSlash()}$cleaned"
                }                // Updated to use new CDN structure with optional width
                else -> {
                    val width = if (useWidthInThumbnail) "?width=300" else ""
                    "${newCdnUrl}scans/${scanId}/obras/${id}/$it$width"
                }
            }
        }.orEmpty()
    }

    private fun MangaStatus.toStatusString(): String {
        return when (value?.lowercase()) {
            "em andamento" -> "Em Andamento"
            "completo" -> "Completo"
            "hiato" -> "Hiato"
            else -> "Unknown"
        }
    }

    private fun ChapterPageDto.toPageList(): List<String> {
        // Handle novel type chapters
        if (type == "TEXTO") {
            Logger.withTag("SussytoonsRepository").w { "Novel chapters are not supported" }
            return emptyList()
        }

        // Get chapter number and format it properly
        val chapterNumStr = chapterNumber?.let { number ->
            Logger.withTag("SUSSY_CHAPTER_NUM").i { "Processing chapter number: $number" }

            // Check if the number has a fractional part
            val numStr = number.toString()
            if (numStr.contains(".") && numStr.substringAfter(".") != "0") {
                // Has decimal part: format as "770_5"
                numStr.replace(".", "_")
            } else {
                // Whole number: format as "747"
                number.trim().substringBefore(".")
            }
        } ?: "0"

        // Get manga reference data
        val mangaRef = manga ?: MangaReferenceDto(mangaId ?: 0)

        return pages.mapNotNull { page ->
            try {
                page.toPageUrl(mangaRef, chapterNumStr)
            } catch (e: Exception) {
                Logger.withTag("SussytoonsRepository").e(e) { "Error processing page: ${page.src}" }
                null
            }
        }
    }

    private fun PageDto.toPageUrl(mangaRef: MangaReferenceDto, chapterNumber: String): String? {
        val cleanSrc = src.trim()
        if (cleanSrc.isBlank()) {
            Logger.withTag("SUSSY_PAGE").w { "Empty src for page" }
            return null
        }

        // If absolute URL, return as-is
        if (cleanSrc.startsWith("http://") || cleanSrc.startsWith("https://")) {
            return cleanSrc
        }

        val normalizedSrc = cleanSrc.removePrefix("/")

        return when {
            // WordPress content (mime type indicates legacy content)
            mime != null -> {
                val url = "${newCdnUrl}wp-content/uploads/WP-manga/data/${normalizedSrc.toPathSegment()}"
                Logger.withTag("SUSSY_PAGE_WP").d { "WordPress URL: $url" }
                url
            }
            // Path-based structure
            path != null -> {
                val cleanPath = path!!.trim()
                val absolutePath = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
                val url = if (absolutePath.endsWith(normalizedSrc, ignoreCase = true)) {
                    "${newCdnUrl.dropTrailingSlash()}${absolutePath}"
                } else {
                    val pathWithoutTrailing = absolutePath.removeSuffix("/")
                    "${newCdnUrl.dropTrailingSlash()}${pathWithoutTrailing}/${normalizedSrc}"
                }
                Logger.withTag("SUSSY_PAGE_PATH").d { "Path-based URL: $url" }
                url
            }
            // Standard new structure
            else -> {
                val url = "${newCdnUrl}scans/${scanId}/obras/${mangaRef.id}/capitulos/${chapterNumber}/${normalizedSrc}"
                Logger.withTag("SUSSY_PAGE_STD").d { "Standard URL: $url" }
                url
            }
        }
    }

    private fun String.toPathSegment(): String {
        return this.trim().split("/")
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    private fun logJsonRaw(tag: String, json: String) {
        try {
            // Parse and pretty print JSON without using Any serializer
            val jsonElement = jsonParser.decodeFromString<JsonElement>(json)
            val formatted = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), jsonElement)
            Logger.withTag(tag).i { formatted }
        } catch (e: Exception) {
            // If parsing fails, log raw JSON
            Logger.withTag(tag).i { "RAW_JSON:\n$json" }
            Logger.withTag(tag).e(e) { "Failed to pretty print JSON" }
        }
    }

    // --- Inlined helpers (HandelDataClasses not yet ported to commonMain) ---
    private fun List<MangaItem>.toPopularMangaList(): List<PopularManga> = this.map {
        PopularManga(
            api = it.api,
            language = it.language,
            title = it.title,
            url = it.url,
            imageUrl = it.imageUrl,
        )
    }

    private val emptyMangaInfo: MangaInfo
        get() = MangaInfo(
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
}
