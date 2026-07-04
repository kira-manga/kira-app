package me.manga.yamiapk.sources_repositry.pt.sussytoons

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
import me.manga.yamiapk.sources_repositry.pt.sussytoons.models.ChapterDto
import me.manga.yamiapk.sources_repositry.pt.sussytoons.models.ChapterPageDto
import me.manga.yamiapk.sources_repositry.pt.sussytoons.models.MangaDto
import me.manga.yamiapk.sources_repositry.pt.sussytoons.models.MangaStatus
import me.manga.yamiapk.sources_repositry.pt.sussytoons.models.PageDto
import me.manga.yamiapk.sources_repositry.pt.sussytoons.models.ResultDto
import okhttp3.FormBody
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import me.manga.yamiapk.sources_repositry.pt.sussytoons.models.MangaReferenceDto

class SussytoonsRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
) : NormalSitesv2(dataStore, api, sourcesRepository) {

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

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

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

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? = null
    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? = null
    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? = null
    override fun sortFormBody(searchType: SearchType.SORT): FormBody? = null

    override fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem> {
        return mutableListOf()
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        return try {
            logJsonRaw("SUSSY_HOME_JSON", html)

            val unwrappedResponse = unwrapJsonResponse(html)
            val response = jsonParser.decodeFromString<ResultDto<List<MangaDto>>>(unwrappedResponse)

            Log.i("SUSSY_HOME_PARSED", "Found ${response.results.size} items")
            response.toMangaItems().toMutableList()
        } catch (e: Exception) {
            Log.e("SUSSY_HOME_ERROR", "Failed to parse home manga items", e)
            mutableListOf()
        }
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        return try {
            logJsonRaw("SUSSY_POPULAR_JSON", html)

            val unwrappedResponse = unwrapJsonResponse(html)
            val response = jsonParser.decodeFromString<ResultDto<List<MangaDto>>>(unwrappedResponse)
            val mangs = response.toMangaItems().toPopularMangaList()

            Log.d("SUSSY_POPULAR_PARSED", "Found ${mangs.size} popular items")
            mangs
        } catch (e: Exception) {
            Log.e("SUSSY_POPULAR_ERROR", "Failed to parse popular manga list", e)
            mutableListOf()
        }
    }

    override suspend fun extractMangaInfo(html: String, baseUrl: String): MangaInfo {
        return try {
            logJsonRaw("SUSSY_DETAILS_JSON", html)

            // For single manga details, unwrap "resultado" wrapper
            val unwrappedResponse = unwrapJsonResponse(html)
            val mangaDto = jsonParser.decodeFromString<MangaDto>(unwrappedResponse)

            Log.i("SUSSY_DETAILS_PARSED", "Parsed manga: ${mangaDto.name}")
            mangaDto.toMangaInfo(baseUrl)
        } catch (e: Exception) {
            Log.e("SUSSY_DETAILS_ERROR", "Failed to parse manga info", e)
            emptyMangaInfo
        }
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        return try {
            logJsonRaw("SUSSY_SEARCH_JSON", html)

            val unwrappedResponse = unwrapJsonResponse(html)
            val response = jsonParser.decodeFromString<ResultDto<List<MangaDto>>>(unwrappedResponse)

            Log.i("SUSSY_SEARCH_PARSED", "Found ${response.results.size} search results")
            response.toMangaItems()
        } catch (e: Exception) {
            Log.e("SUSSY_SEARCH_ERROR", "Failed to parse search results", e)
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
            Log.i("SUSSY_PAGES_PARSED", "Found ${pages.size} pages")
            pages
        } catch (e: Exception) {
            Log.e("SUSSY_PAGES_ERROR", "Failed to parse chapter images", e)
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
            Jsoup.parseBodyFragment(it).text()
        }.orEmpty()

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = baseUrl,
            title = name,
            imageUrl = getThumbnailUrl(),
            rating = "",
            ratingCount = "",
            description = cleanDescription,
            otherNames = "",
            author = "",
            artist = "",
            genres = genres.map { it.value },
            tags = emptyList(),
            yearOfProduction = "",
            status = status.toStatusString(),
            favoritesCount = "",
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
            chaptersImages = emptyList()
        )
    }

    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return LocalDate.now()

        return try {
            LocalDate.parse(dateStr)
        } catch (e: Exception) {
            try {
                val date = dateFormat.parse(dateStr)
                date?.let {
                    val calendar = java.util.Calendar.getInstance()
                    calendar.time = it
                    LocalDate.of(
                        calendar.get(java.util.Calendar.YEAR),
                        calendar.get(java.util.Calendar.MONTH) + 1,
                        calendar.get(java.util.Calendar.DAY_OF_MONTH)
                    )
                }
            } catch (e: Exception) {
                LocalDate.now()
            }
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
            Log.w("SussytoonsRepository", "Novel chapters are not supported")
            return emptyList()
        }

        // Get chapter number and format it properly
        val chapterNumStr = chapterNumber?.let { number ->
            Log.i("SUSSY_CHAPTER_NUM", "Processing chapter number: $number")

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
                Log.e("SussytoonsRepository", "Error processing page: ${page.src}", e)
                null
            }
        }
    }

    private fun PageDto.toPageUrl(mangaRef: MangaReferenceDto, chapterNumber: String): String? {
        val cleanSrc = src.trim()
        if (cleanSrc.isBlank()) {
            Log.w("SUSSY_PAGE", "Empty src for page")
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
                Log.d("SUSSY_PAGE_WP", "WordPress URL: $url")
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
                Log.d("SUSSY_PAGE_PATH", "Path-based URL: $url")
                url
            }
            // Standard new structure
            else -> {
                val url = "${newCdnUrl}scans/${scanId}/obras/${mangaRef.id}/capitulos/${chapterNumber}/${normalizedSrc}"
                Log.d("SUSSY_PAGE_STD", "Standard URL: $url")
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
            Log.i(tag, formatted)
        } catch (e: Exception) {
            // If parsing fails, log raw JSON
            Log.i(tag, "RAW_JSON:\n$json")
            Log.e(tag, "Failed to pretty print JSON", e)
        }
    }
}