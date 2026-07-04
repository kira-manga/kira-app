package me.manga.yamiapk.sources_repositry.`in`.komikcast

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
import org.jsoup.nodes.Element
import java.time.LocalDate
import javax.inject.Inject

class KomikCastRepository  @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSitesv2(dataStore,api,sourcesRepository){
    override val mangaSource: MangaSource
        get() = MangaSource.KOMIKCAST
    override val homeUrl: String by lazy {baseUrl.ifBlank { BASE_URL }}
    override val popularUrl: String by lazy {baseUrl.ifBlank { BASE_URL }}
    override var customParseHome: Boolean = true


    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0
    @Volatile
    private var _cachedHeaders: Map<String, String>? = null
    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()


    override fun handelLoadMoreUrl(page: Int): String {
       return "${baseUrl.ifBlank { BASE_URL }}daftar-komik/page/${page - 1}/?status&type&orderby=update"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { BASE_URL }}?s=${searchType.toNormalQuery()}"
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
        val doc = Jsoup.parse(html)
        return doc.select("div.list-update_item").map { item ->
            val anchor = item.selectFirst("a")
            val title = item.selectFirst("h3.title")?.text()?.trim().orEmpty()
            val imageUrl = item.selectFirst("img")?.attr("src").orEmpty()
            val url = anchor?.attr("href").orEmpty()

            MangaItem(
                title = title,
                imageUrl = imageUrl,
                url = url,
                api = API,
                language = LANGUAGE,
                rating = 0,
                chapters = listOf(),
                genres = listOf()
            )

        }.toMutableList()
    }


    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<MangaItem>()

        val targetBox = doc.select(".bixbox").firstOrNull { bix ->
            bix.selectFirst(".releases h3 span")?.text()?.contains("Rilisan Terbaru", ignoreCase = true) == true
        }

        val blocks = targetBox?.select("div.listupd > div.utao") ?: return mutableListOf()

        for (block in blocks) {


            val linkEl = block.selectFirst(".imgu a")
            val url = linkEl?.absUrl("href").orEmpty()

            val img = block.selectFirst(".imgu img")
            val imageUrl = img?.absUrl("data-src").orEmpty()

            val titleEl = block.selectFirst("h3")
            val title = titleEl?.text().orEmpty()

            val chapters = block.select("ul li a").map {
                ChapterItem(
                    name = it.text(),
                    number = it.text(),
                    url = it.absUrl("href")
                )
            }

            items += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = null,
                chapters = chapters,
                genres = emptyList()
            )



        }

        return items

    }

    override fun extractMangaList(html: String): List<PopularManga> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<PopularManga>()

        val blocks = doc.select(".hothome .swiper-slide")

        for (el in blocks) {
            // Fix: Look for the <a> tag that actually contains the href
            val anchor = el.select("a[href]").lastOrNull()
            val url = anchor?.absUrl("href").orEmpty()
            val title = anchor?.attr("title").orEmpty().ifBlank {
                el.selectFirst(".title")?.text().orEmpty()
            }

            val imageUrl = el.selectFirst("img")?.absUrl("src").orEmpty()

            items += PopularManga(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl
            )
        }

        return items
    }

    override suspend fun extractMangaInfo(
        html: String,
        baseUrl: String
    ): MangaInfo {
        val doc = Jsoup.parse(html)

        val seriesDetails = doc.selectFirst("div.komik_info:has(.komik_info-content)")



        val chapters = doc.select("div.komik_info-chapters li").map { el ->


                val link = el.select("a")
            val chapterName = el.select(".chapter-link-item").text()
            val chapterNumber = chapterName.replace(Regex("[^\\d.]"), "").trim()
            val chapterUrl = link.attr("href")

            val rawDate = el.select(".chapter-link-time").text()

            val parsed = try {
                val num = rawDate.filter { it.isDigit() }.toLongOrNull() ?: 0L
                when {
                    rawDate.contains("hour", true) -> LocalDate.now()
                    rawDate.contains("day", true) -> LocalDate.now().minusDays(num)
                    rawDate.contains("week", true) -> LocalDate.now().minusWeeks(num)
                    rawDate.contains("month", true) -> LocalDate.now().minusMonths(num)
                    rawDate.contains("year", true) -> LocalDate.now().minusYears(num)
                    else -> LocalDate.now()
                }
            } catch (e: Exception) {
                LocalDate.now()
            }

            ChapterItem(
                number = chapterNumber,
                name = chapterName,
                url = chapterUrl,
                date = parsed,
            )
        }.toMutableList()

        val title = seriesDetails?.selectFirst("h1.komik_info-content-body-title")?.text()
            ?.replace("bahasa indonesia", "", ignoreCase = true)?.trim().orEmpty()

        val description = seriesDetails?.select(".komik_info-description-sinopsis")
            ?.joinToString("\n") { it.text() }?.trim().orEmpty()

        val altName = seriesDetails?.selectFirst(".komik_info-content-native")?.ownText()?.takeIf { it.isNullOrBlank().not() }
        val fullDescription = if (!altName.isNullOrEmpty()) "$description\n\nAlternative Name: $altName" else description

        val genres = seriesDetails?.select(".komik_info-content-genre a")?.map { it.text() }?.toMutableList() ?: mutableListOf()
        val type = seriesDetails?.selectFirst(".komik_info-content-genre")?.ownText()?.takeIf { it.isNotBlank() }
        type?.let { genres.add(it) }

        val statusText = seriesDetails?.selectFirst(".komik_info-content-info:contains(Status)")?.text().orEmpty()
        val thumbnailUrl = seriesDetails?.selectFirst(".komik_info-content-thumbnail img")?.attr("src").orEmpty()

        return MangaInfo(
            title            = title,
            imageUrl         = thumbnailUrl,
            rating           = doc.selectFirst("span#averagerate")?.text().orEmpty(),
            ratingCount      = doc.selectFirst("span#countrate")?.text().orEmpty(),
            description      = fullDescription,
            otherNames       = altName.orEmpty(),
            author           = seriesDetails?.selectFirst(".komik_info-content-info:contains(Author)")?.ownText().orEmpty(),
            artist           = seriesDetails?.selectFirst(".komik_info-content-info:contains(Artist)")?.ownText().orEmpty(),
            genres           = genres,
            tags             = emptyList(),
            yearOfProduction = "",
            status           = statusText,
            favoritesCount   = "",
            chapters         = chapters,
            api              = API,
            url              = baseUrl,
            language         = LANGUAGE
        )
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)
        return doc.select("div.list-update_item").map { item ->
            val anchor = item.selectFirst("a")
            val title = item.selectFirst("h3.title")?.text()?.trim().orEmpty()
            val imageUrl = item.selectFirst("img")?.attr("src").orEmpty()
            val url = anchor?.attr("href").orEmpty()

            MangaItem(
                title = title,
                imageUrl = imageUrl,
                url = url,
                api = API,
                language = LANGUAGE,
                rating = 0,
                chapters = listOf(),
                genres = listOf()
            )
        }
    }


    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

    }
    override fun getChapterImages(html: String): List<String> {
        val doc = Jsoup.parse(html)
        val imgs =
            doc.select("div#chapter_body .main-reading-area img.size-full").distinctBy { img ->
                img.imgAttr()
            }.map { it.imgAttr() }

        return imgs
    }

         fun Element.imgAttr(): String = when {
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-cfsrc") -> attr("abs:data-cfsrc")
            else -> attr("abs:src")
        }

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language


}