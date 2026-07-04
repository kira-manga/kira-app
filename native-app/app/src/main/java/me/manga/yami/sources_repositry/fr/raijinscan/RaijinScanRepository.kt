package me.manga.yamiapk.sources_repositry.fr.raijinscan

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.NormalSitesv2
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class RaijinScanRepository@Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSitesv2(dataStore,api,sourcesRepository){
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


    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> {
        return homeMangaParse(html)
    }

    fun homeMangaParse(response: String): MutableList<MangaItem> {
        val doc: Document = Jsoup.parse(response)
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
        val doc: Document = Jsoup.parse(html)

        val chapters = doc.select("ul.scroll-sm li.item").map { el ->

            val link = el.selectFirst("a")!!
            val chapterName = link.attr("title").trim()
            val chapterNumber = chapterName
                .replace(Regex("[^\\d.]"), "")             // remove everything except digits and '.'
                .trim()
            val chapterUrl    = link.attr("abs:href")

            val rawDate = link.selectFirst("> span:nth-of-type(2)")?.text()
                // marked as NEW: try img@alt

            val parsed = parseChapterDate(rawDate.toString()) ?: LocalDate.now()

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
            ratingCount      = doc.selectFirst("span#countrate")?.text().orEmpty(),
            description      =  description.orEmpty(),
            otherNames       = "",
            author           = doc.selectFirst("div.author-content")?.text().orEmpty(),
            artist           = doc.selectFirst("div.artist-content")?.text().orEmpty(),
            genres           = doc.select("div.genre-list div.genre-link").eachText(),
            tags             = doc.select("div.tags-content a").eachText(),
            yearOfProduction = doc.select("div.summary-content")
                .find { it.select("h5:contains(سنة الانتاج)").isNotEmpty() }
                ?.text().orEmpty(),
            status           = doc.selectFirst("div.stat-item:has(span:contains(État du titre)) span.manga")?.text().orEmpty(),
            favoritesCount   = doc.selectFirst("div.add-bookmark .action_detail span")?.text().orEmpty(),
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

    override fun getChapterImages(html: String): List<String> {
        val doc: Document = Jsoup.parse(html)

        val imgs = doc.select("div.protected-image-data").map{

            val encodedUrl = it.attr("data-src")
            val decoded = String(Base64.decode(encodedUrl, Base64.DEFAULT))

            decoded.replace("http://", "https://")

        }
           return  imgs
    }


    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
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
    fun parseChapterDate(raw: String): LocalDate {
        val trimmed = raw.trim().lowercase()

        // 1) Keywords
        if (trimmed == "new") return LocalDate.now()

        // 2) French relative expressions with attached units (e.g. "2m", "3j", "1an")
        val regex = Regex("il y a (\\d+(?:\\.\\d+)?|un|une)\\s*([a-zéèê]+)", RegexOption.IGNORE_CASE)
        val match = regex.find(trimmed)

        if (match != null) {
            val (amountRaw, unitRaw) = match.destructured
            val amount = when (amountRaw) {
                "un", "une" -> 1L
                else -> amountRaw.toDoubleOrNull()?.toLong() ?: return LocalDate.now()
            }

            return when (unitRaw) {
                "j", "jour", "jours" -> LocalDate.now().minusDays(amount)
                "h", "heure", "heures" -> LocalDate.now() // still same-day, approximate
                "min", "minute", "minutes" -> LocalDate.now() // same-day
                "m", "mois" -> LocalDate.now().minusMonths(amount)
                "sem", "semaine", "semaines" -> LocalDate.now().minusWeeks(amount)
                "an", "ans", "année", "annee", "années", "annees" -> LocalDate.now().minusYears(amount)
                else -> LocalDate.now()
            }
        }

        // 3) Fallback absolute date (format dd/MM/yyyy)
        return try {
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            LocalDate.parse(trimmed, formatter)
        } catch (_: Exception) {
            LocalDate.now()
        }
    }


}