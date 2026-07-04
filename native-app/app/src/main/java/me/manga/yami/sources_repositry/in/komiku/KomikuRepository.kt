package me.manga.yamiapk.sources_repositry.`in`.komiku

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

class KomikuRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
    ): NormalSitesv2(dataStore,api,sourcesRepository){
    override val mangaSource: MangaSource
        get() = MangaSource.KOMIKU
    override val BASE_URL: String = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    private val baseUrlApi  by lazy { "https://api.komiku.id"}

    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language
    override val homeUrl: String by lazy {  "$baseUrlApi/other/hot/?orderby=modified&category_name="}
    override val popularUrl: String by lazy { "$baseUrlApi/other/hot/?orderby=meta_value_num"}


    override var imgBaseUrl: String = "https://thumbnail.komiku.org/"
    override var imgUrlVersion: Int = 0
    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()


    override fun handelLoadMoreUrl(page: Int): String {
        return "$baseUrlApi/other/hot/page/$page/?orderby=meta_value_num"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "$baseUrlApi/page/1/?post_type=manga".toHttpUrl().newBuilder().addQueryParameter("s", searchType.toNormalQuery()).toString()
    }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf(
            "Mature",
            "Yuri",
            "Shoujo Ai"
        )

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
        return extractKomikuMangaItems(html).toMutableList()
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        return extractKomikuMangaItems(html).toMutableList()
    }
    private val coverUploadRegex = Regex("""/uploads/\d\d\d\d/\d\d/""")
    private val coverRegex = Regex("""(/Manga-|/Manhua-|/Manhwa-)""")

    override fun extractMangaList(html: String): List<PopularManga> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<PopularManga>()

        val blocks = doc.select("div.bge")
        for (el in blocks) {
            val url = el.select("a:has(h3)").attr("href").orEmpty()
            val title = el?.select("h3")?.text()?.trim().toString()

           val img =  if (el.select("img").attr("abs:src").contains(coverUploadRegex)) {
                el.select("img").attr("abs:src")
            } else {
              el.select("img").attr("abs:src").substringBeforeLast("?").replace(coverRegex, "/Komik-")
            }




            items += PopularManga(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = img,
            )
        }
        return items

    }
    fun extractKomikuMangaItems(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html, BASE_URL)   // IMPORTANT FIX
        val items = mutableListOf<MangaItem>()

        val elements = doc.select(".bge")

        for (el in elements) {

            val infoAnchor = el.selectFirst(".bgei a")
            val titleAnchor = el.selectFirst(".kan a")

            val chapterStartEl = el.select(".kan .new1").firstOrNull()?.selectFirst("a")
            val chapterLatestEl = el.select(".kan .new1").lastOrNull()?.selectFirst("a")

            val title = titleAnchor?.selectFirst("h3")?.text()?.trim().orEmpty()

            val url = titleAnchor?.absUrl("href").orEmpty()
            val imageUrl = infoAnchor?.selectFirst("img")?.absUrl("src").orEmpty()

            fun fixUrl(href: String): String {
                return if (href.startsWith("http")) href
                else BASE_URL.removeSuffix("/") + href
            }

            val chapterStart = chapterStartEl?.let {
                val chapterName = it.selectFirst("span:nth-of-type(2)")?.text()?.trim().orEmpty()
                val chapterUrl = fixUrl(it.attr("href"))

                ChapterItem(
                    name = chapterName,
                    number = chapterName,
                    url = chapterUrl
                )
            }

            val chapterLatest = chapterLatestEl?.let { linkElement ->
                val chapterName = linkElement.selectFirst("span:nth-of-type(2)")?.text()?.trim().orEmpty()
                val chapterUrl = fixUrl(linkElement.attr("href"))

                ChapterItem(
                    name = chapterName,
                    number = chapterName,
                    url = chapterUrl
                )
            }

            items += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = null,
                chapters = listOfNotNull(chapterStart, chapterLatest),
                genres = emptyList()
            )
        }

        return items
    }
    fun fixUrl(href: String): String {
        return if (href.startsWith("http")) href
        else baseUrlApi.removeSuffix("/") + href
    }
    override suspend fun extractMangaInfo(
        html: String,
        baseUrl: String
    ): MangaInfo {
        Log.i("kjhjkhkjhjhjlkhjkhkjhl",baseUrl)
        val document = Jsoup.parse(html)
        val cleanedTitle = document.select("#Judul h1 span[itemprop=name]").text().trim()

        val title = cleanedTitle.replaceFirst(
            Regex("(?i)^(?:baca\\s+)?komik\\b[:\\-–—]?\\s*"),
            ""
        ).trim()
        val otherNames = document.select("#Judul p.j2").text().trim()
        val description = document.select("#Judul .desc").text().trim()
        val thumbnailUrl = document.select("#Informasi .ims img").attr("abs:src")
        val author = document.select("table.inftable tr:has(td:contains(Pengarang)) td:nth-child(2)").text().trim()
        val status = document.select("table.inftable tr:has(td:contains(Status)) td:nth-child(2)").text().trim()
        val genreElements = document.select("ul.genre li span[itemprop=genre]")
        val genres = genreElements.map { it.text().trim() }

        val chapters = mutableListOf<ChapterItem>()
        val javaTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

        val chapterRows = document.select("#Daftar_Chapter tbody tr")
        for (i in 1 until chapterRows.size) {

            val row = chapterRows[i]

            val chapterLink = row.select("td.judulseries a")
            val chapterTitle = chapterLink.text().trim()
            val chapterHref = chapterLink.attr("href")
            val chapterUrl = if (chapterHref.startsWith("http")) chapterHref else BASE_URL.removeSuffix("/") + chapterHref
            val dateText = row.select("td.tanggalseries").text().trim()
            val localDate = runCatching {
                LocalDate.parse(dateText, javaTimeFormatter)
            }.getOrElse { LocalDate.now() }

            chapters += ChapterItem(
                name = chapterTitle,
                date = localDate,

                number = chapterTitle.replace("Chapter", "").trim(),
                url = chapterUrl,

            )
        }

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = baseUrl,
            title = title,
            imageUrl = thumbnailUrl,
            rating = "",
            ratingCount = "",
            description = description,
            otherNames = otherNames,
            author = author,
            artist = "",
            genres = genres,
            tags = emptyList(),
            yearOfProduction = "",
            status = status,
            favoritesCount = "",
            chapters = chapters
        )
    }


    override suspend fun getSearchResults(html: String): List<MangaItem> {
        return extractKomikuMangaItems(html).toMutableList()

    }


    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // persist in background
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

        // update the cache immediately

    }

    override fun getChapterImages(html: String): List<String> {
        val document = Jsoup.parse(html)

       return document.select("#Baca_Komik img").map {  element ->
           element.attr("abs:src")
        }
    }

}