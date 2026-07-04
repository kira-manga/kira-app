package me.manga.kira.sources_repositry.pt.mediocretoons

/**
 * Migration note (Phase 7.7): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * Gson -> kotlinx.serialization, @Inject dropped, android.util.Log -> Kermit Logger,
 * java.time -> kotlinx.datetime.
 *
 * Date parsing: upstream API returns ISO-8601 datetimes (e.g. `2024-07-13T12:34:56`). The
 * Android source used `DateTimeFormatter.ISO_DATE_TIME` + `LocalDateTime.parse`; we port that
 * to `kotlinx.datetime.LocalDateTime.parse(dateString).date`. `DateTimeParseException` is
 * replaced by `IllegalArgumentException` (kotlinx.datetime's thrown type on parse failure).
 *
 * `emptyMangaInfo` / `toPopularMangaList` helpers from `core.util.data_classes.HandelDataClasses`
 * have not been ported to commonMain — we inline equivalents in this file, mirroring the
 * approach taken in the `fr/` wave.
 */

import co.touchlab.kermit.Logger
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
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
import me.manga.kira.sources_repositry.pt.mediocretoons.models.Capitulo
import me.manga.kira.sources_repositry.pt.mediocretoons.models.MangaData
import me.manga.kira.sources_repositry.pt.mediocretoons.models.MediocretoonsChapter
import me.manga.kira.sources_repositry.pt.mediocretoons.models.MediocretoonsHome
import me.manga.kira.sources_repositry.pt.mediocretoons.models.MediocretoonsMangaInfo

@OptIn(ExperimentalTime::class)
class MediocretoonsRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
): NormalSitesv2(api, sourcesRepository) {

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        } }

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override val mangaSource: MangaSource
        get() = MangaSource.MEDIOCRETOONS


    val chapterimgUrl = "https://storage.mediocretoons.com/"
    override var imgBaseUrl: String = "https://cdn.mediocretoons.site/"
    override var imgUrlVersion: Int = 0

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    private val apiUrl by lazy { "${baseUrl.ifBlank { BASE_URL }}obras"}

    override val homeUrl: String by lazy { "$apiUrl?limite=40&pagina=1&formato=5" }
    override val popularUrl: String by lazy { "$apiUrl?limite=40&pagina=1&formato=" }


    @kotlin.concurrent.Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures your value wins:
            return base + customHeaders
        }

    private val customHeaders = mapOf(
        "Referer" to "https://mediocretoons.com",
        "Origin" to "https://mediocretoons.com",
        "Accept" to "application/json"
    )

    override fun handelLoadMoreUrl(page: Int): String {
        return "$apiUrl?limite=50&pagina=$page&formato=5"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "$apiUrl?limite=10&pagina=1&temCapitulo=true&string=${searchType.toNormalQuery()}"
    }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()

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
            Logger.withTag("fghdsflgsdfgdfgdfsgsdfgdfg1").i { html }
            val apiResponse = jsonParser.decodeFromString<MediocretoonsHome>(html)
            apiResponse.toMangaItems().toMutableList()
        } catch (e: Exception) {
            Logger.withTag("fghdsflgsdfgdfgdfsgsdfgdfg2").i { e.toString() }

            mutableListOf()
        }
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        return try {
            val apiResponse = jsonParser.decodeFromString<MediocretoonsHome>(html)
            apiResponse.toMangaItems().toMutableList().toPopularMangaList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    override suspend fun extractMangaInfo(html: String, baseUrl: String): MangaInfo {
        return try {
            val apiResponse = jsonParser.decodeFromString<MediocretoonsMangaInfo>(html)
            apiResponse.toMangaInfo()
        } catch (e: Exception) {
            emptyMangaInfo
        }
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        return try {
            val apiResponse = jsonParser.decodeFromString<MediocretoonsHome>(html)
            apiResponse.toMangaItems().toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + customHeaders

        _cachedHeaders = merged
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun getChapterImages(html: String): List<String> {
        return try {
            val chapter = jsonParser.decodeFromString<MediocretoonsChapter>(html)
            chapter.paginas?.map { page ->
                buildImageUrl(chapter.obra?.id ?: 0, chapter.numero ?: "0", page.src ?: "")
            } ?: emptyList()
        } catch (e: Exception) {
            listOf()
        }
    }

    private fun buildImageUrl(mangaId: Int, chapterNum: String, imageName: String): String {
        return "${imgBaseUrl}obras/$mangaId/capitulos/$chapterNum/$imageName"
    }

    private fun MediocretoonsHome.toMangaItems(): List<MangaItem> =
        data?.filterNotNull()?.map { it.toMangaItem() } ?: emptyList()

    private fun MangaData.toMangaItem(): MangaItem =
        MangaItem(
            api = API,
            language = LANGUAGE,
            title = nome ?: "",
            url = "${baseUrl.ifBlank { BASE_URL }}obras/$id",
            imageUrl = if (!imagem.isNullOrBlank()) "https://cdn.mediocretoons.site/obras/${id}/${imagem}" else "https://mediocretoons.site/logo.png",
            rating = null,
            genres = tags?.mapNotNull { it?.nome }?.filter { it.isNotBlank() } ?: emptyList(),
            chapters = emptyList()
        )

    private fun MediocretoonsMangaInfo.toMangaInfo(): MangaInfo =
        MangaInfo(
            api = API,
            language = LANGUAGE,
            url = "${baseUrl.ifBlank { BASE_URL }}obras/$id",
            title = nome ?: "",
            imageUrl = if (!imagem.isNullOrBlank()) "https://cdn.mediocretoons.site/obras/${id}/${imagem}" else "https://mediocretoons.site/logo.png",
            rating = "",
            description = descricao ?: "",
            author = agente?.nome ?: "",
            genres = tags?.mapNotNull { it?.nome }?.filter { it.isNotBlank() } ?: emptyList(),
            status = status?.nome ?: "",
            chapters = capitulos?.mapNotNull { chapter ->
                chapter?.toChapterItem()
            }?.toMutableList() ?: mutableListOf()
        )

    private fun Capitulo.toChapterItem(): ChapterItem {
        // Prefer lancado_em (release date) over criado_em (created date)
        val chapterDate = parseDate(lancado_em) ?: parseDate(criado_em)

        return ChapterItem(
            number = numero ?: "0",
            name = nome ?: "Chapter ${numero ?: "0"}",
            url = "${baseUrl.ifBlank { BASE_URL }}capitulos/${id ?: 0}",
            date = chapterDate,
            isDownloaded = false,
            isBookmarked = false,
        )
    }

    private fun parseDate(dateString: String?): LocalDate? {
        return try {
            if (dateString.isNullOrBlank()) return null
            // kotlinx.datetime's `LocalDateTime.parse` accepts ISO-8601 datetimes (offset stripped)
            // — matches the Android `DateTimeFormatter.ISO_DATE_TIME` behaviour for the shapes
            // this API actually returns. If the upstream ever sends an offset (e.g. `...Z` or
            // `+03:00`), strip it first so the LocalDateTime parser doesn't reject it.
            val normalized = dateString
                .substringBefore('Z')
                .let { s -> Regex("([+-]\\d{2}:?\\d{2})$").replace(s, "") }
            val dateTime = LocalDateTime.parse(normalized)
            dateTime.date
        } catch (e: IllegalArgumentException) {
            Logger.withTag("MediocretoonsRepo").w(e) { "Failed to parse date: $dateString" }
            null
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
