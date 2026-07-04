package me.manga.kira.sources_repositry.fr.raijinscan

/**
 * Migration note (Phase 7.4): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime,
 * android.util.Base64 -> kotlin.io.encoding.Base64.
 */

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
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

@OptIn(ExperimentalTime::class)
class RaijinScanRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
): NormalSitesv2(api, sourcesRepository) {
    override val mangaSource: MangaSource
        get() = MangaSource.RAIJINSCAN
    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy {  "${baseUrl.ifBlank { BASE_URL }}page/1/?post_type=wp-manga&s=&sort=recently_added"}

    override val popularUrl: String
        get() = "${baseUrl.ifBlank { BASE_URL }}page/1/?post_type=wp-manga&s&sort=most_viewed"

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}page/$page/?post_type=wp-manga&s&sort=most_viewed"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
       return "${baseUrl.ifBlank { BASE_URL }}?post_type=wp-manga&s=${searchType.toNormalQuery()}"
    }
    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() =setOf()
    override val blackListGenres: Set<String>
        get() = setOf()

    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0
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


    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> {
        return homeMangaParse(html)
    }

    fun homeMangaParse(response: String): MutableList<MangaItem> {
        val doc: Document = Ksoup.parse(response)
        return doc.select("div.original.card-lg div.unit").mapNotNull { it ->
            val linkElement = it.selectFirst("div.info > a") ?: return@mapNotNull null
            val link = linkElement.absUrl("href")
            val title = linkElement.text().trim()

            val imgElement = it.selectFirst("div.poster-image-wrapper > img")
            val thumbnailUrl = imgElement?.absUrl("data-src")?.ifEmpty { imgElement.absUrl("src") } ?: ""
            MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = link,
                imageUrl = thumbnailUrl,
                rating = null,
                chapters = mutableListOf(),
                genres = listOf()
            )
        }.toMutableList()
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {

        return homeMangaParse(html)
    }



    override fun extractMangaList(html: String): List<PopularManga> {
        return homeMangaParse(html).toPopularMangaList()

    }
    private val descriptionScriptRegex = """content\.innerHTML = `([\s\S]+?)`;""".toRegex()

    override suspend fun extractMangaInfo(
        html: String,
        baseUrl: String
    ): MangaInfo {
        val doc: Document = Ksoup.parse(html)

        val chapters = doc.select("ul.scroll-sm li.item").map { el ->

            val link = el.selectFirst("a")!!
            val chapterName = link.attr("title").trim()
            val chapterNumber = chapterName
                .replace(Regex("[^\\d.]"), "")             // remove everything except digits and '.'
                .trim()
            val chapterUrl    = link.attr("abs:href")

            val rawDate = link.selectFirst("> span:nth-of-type(2)")?.text()
                // marked as NEW: try img@alt

            val parsed = parseChapterDate(rawDate.toString()) ?: Clock.System.todayIn(TimeZone.currentSystemDefault())

            ChapterItem(
                number = chapterNumber,
                name = chapterName,
                url = chapterUrl,
                date = parsed,
            )
        }.toMutableList()
        val scriptDescription = doc.select("script:containsData(content.innerHTML)")
            .firstNotNullOfOrNull { descriptionScriptRegex.find(it.data())?.groupValues?.get(1)?.trim() }
        val description = scriptDescription ?: doc.selectFirst("div.description-content")?.text()

        return MangaInfo(
            title            =  doc.selectFirst("h1.serie-title")?.text().orEmpty(),
            imageUrl         = doc.selectFirst("img.cover")?.attr("abs:src").orEmpty(),
            rating           = doc.selectFirst("span#averagerate")?.text().orEmpty(),
            description      =  description.orEmpty(),
            author           = doc.selectFirst("div.author-content")?.text().orEmpty(),
            genres           = doc.select("div.genre-list div.genre-link").eachText(),
            status           = doc.selectFirst("div.stat-item:has(span:contains(État du titre)) span.manga")?.text().orEmpty(),
            chapters         = chapters,
            api              = API,
            url              = baseUrl,
            language         = LANGUAGE
        )
    }
    override suspend fun getSearchResults(string: String): List<MangaItem> {
        return homeMangaParse(string)
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // persist in background
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

        // update the cache immediately
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun getChapterImages(html: String): List<String> {
        val doc: Document = Ksoup.parse(html)

        val imgs = doc.select("div.protected-image-data").map{

            val encodedUrl = it.attr("data-src")
            val decoded = Base64.decode(encodedUrl).decodeToString()

            decoded.replace("http://", "https://")

        }
           return  imgs
    }


    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    fun List<MangaItem>.toPopularMangaList(): List<PopularManga> {
        return this.map {
            PopularManga(
                api = it.api,
                language = it.language,
                title = it.title,
                url = it.url,
                imageUrl = it.imageUrl
            )
        }
    }

    private val slashDateFormatter = LocalDate.Format {
        day()
        char('/')
        monthNumber()
        char('/')
        year()
    }

    fun parseChapterDate(raw: String): LocalDate {
        val trimmed = raw.trim().lowercase()
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

        // 1) Keywords
        if (trimmed == "new") return today

        // 2) French relative expressions with attached units (e.g. "2m", "3j", "1an")
        val regex = Regex("il y a (\\d+(?:\\.\\d+)?|un|une)\\s*([a-zéèê]+)", RegexOption.IGNORE_CASE)
        val match = regex.find(trimmed)

        if (match != null) {
            val (amountRaw, unitRaw) = match.destructured
            val amount = when (amountRaw) {
                "un", "une" -> 1L
                else -> amountRaw.toDoubleOrNull()?.toLong() ?: return today
            }

            return when (unitRaw) {
                "j", "jour", "jours" -> today.minus(amount, DateTimeUnit.DAY)
                "h", "heure", "heures" -> today // still same-day, approximate
                "min", "minute", "minutes" -> today // same-day
                "m", "mois" -> today.minus(amount.toInt(), DateTimeUnit.MONTH)
                "sem", "semaine", "semaines" -> today.minus(amount.toInt(), DateTimeUnit.WEEK)
                "an", "ans", "année", "annee", "années", "annees" -> today.minus(amount.toInt(), DateTimeUnit.YEAR)
                else -> today
            }
        }

        // 3) Fallback absolute date (format dd/MM/yyyy)
        return try {
            LocalDate.parse(trimmed, slashDateFormatter)
        } catch (_: Exception) {
            today
        }
    }


}
