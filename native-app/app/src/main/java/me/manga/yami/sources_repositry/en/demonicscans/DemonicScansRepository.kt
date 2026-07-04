package me.manga.yamiapk.sources_repositry.en.demonicscans


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
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject

class DemonicScansRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSitesv2(dataStore, api, sourcesRepository) {

    override val mangaSource: MangaSource
        get() = MangaSource.DEMONICSCANS

    override val BASE_URL: String
        get() = "https://demonicscans.org/"

    override val API: String
        get() = mangaSource.API

    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { baseUrl.ifBlank { BASE_URL } }
    override val popularUrl: String by lazy { baseUrl.ifBlank { BASE_URL } }

    override val sortTypes: Set<String>
        get() = setOf("Latest Updates", "Most Viewed", "New Titles")

    override val allGenres: Set<String>
        get() = setOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance", "Supernatural")

    override val blackListGenres: Set<String>
        get() = setOf()

    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0

    override suspend fun initSite(): Int {

        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override var customParseHome: Boolean = true
    override var useGetForHome: Boolean = true
    override var useGetForPopular: Boolean = true
    override var useGetForSearch: Boolean = false
    override var useGetForNormalSearch: Boolean = false

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override fun handelFormBodyHome(page: Int, popular: Boolean): FormBody? = null

    override fun handelFormBodyPopular(page: Int, popular: Boolean): FormBody? = null

    override fun handelLoadMoreUrl(page: Int): String {
        return if (page > 1) {
            "${baseUrl.ifBlank { BASE_URL }}lastupdates.php?list=${page}"
        } else {
            "${baseUrl.ifBlank { BASE_URL }}index.php"
        }
    }

    override fun handelSearchUrl(searchType: SearchType): String {

        return "${baseUrl.ifBlank { BASE_URL }}search.php?manga=${searchType.toNormalQuery()}"
    }

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? = null

    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? {
        return FormBody.Builder().apply {
            add("manga", searchType.query)
        }.build()
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? = null

    override fun sortFormBody(searchType: SearchType.SORT): FormBody? = null

    override fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem> {
        val doc = Jsoup.parse(string)
        val items = mutableListOf<MangaItem>()

        // Extract from "Latest Updates" section
        val updateElements = doc.select("#updates-container .updates-element")
        for (element in updateElements) {
            val thumb = element.selectFirst(".thumb a")
            val titleLink = element.selectFirst(".updates-element-info h2 a")
            val img = element.selectFirst(".thumb img")

            if (thumb != null && titleLink != null && img != null) {
                val url = titleLink.attr("href")
                val imageUrl = img.attr("src")
                val title = titleLink.text().trim()

                // Extract recent chapters
                val chapterElements = element.select(".chap-date")
                val chapters = chapterElements.mapNotNull { chapterEl ->
                    val chapterLink = chapterEl.selectFirst("a")
                    val dateEl = chapterEl.selectFirst("div:last-child a")

                    if (chapterLink != null) {
                        val chapterName = chapterLink.text().trim()
                        val chapterUrl = chapterLink.attr("href")
                        val date = dateEl?.text()?.trim()

                        Log.i("dslkfjslkdfjklsdfsdfsdfdfsd",chapterUrl)
                        ChapterItem(
                            name = chapterName,
                            number = extractChapterNumber(chapterName),
                            url = "${baseUrl.ifBlank { BASE_URL }}$chapterUrl",
                            date = parseDate(date)
                        )
                    } else null
                }

                items.add(MangaItem(
                    api = API,
                    language = LANGUAGE,
                    title = title,
                    url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url" ,
                    imageUrl = imageUrl,
                    rating = 0,
                    chapters = chapters,
                    genres = emptyList()
                ))
            }
        }

        return items
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {

        val items = extractCustomHomeMangaItems(html)
        Log.i("sfgjsfkdjgsfdgsdfgfdgdsfg",items.toString())
        return items
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<PopularManga>()

        // Extract from carousel for popular manga
        val carouselElements = doc.select("#carousel .owl-element")
        for (element in carouselElements) {
            val link = element.selectFirst("a")
            val img = element.selectFirst("img")
            val title = element.selectFirst("h1")

            if (link != null && img != null && title != null) {
                val url = link.attr("href")
                val imageUrl = img.attr("src")
                val mangaTitle = title.text().split("<br>").firstOrNull()?.trim() ?: title.text().trim()

                items.add(PopularManga(
                    api = API,
                    language = LANGUAGE,
                    title = mangaTitle,
                    url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url" ,
                    imageUrl = imageUrl
                ))
            }
        }

        return items
    }

    override suspend fun extractMangaInfo(html: String, baseUrli: String): MangaInfo {
        val document = Jsoup.parse(html)

        val title = document.selectFirst("#manga-info-rightColumn h1")?.text()?.trim() ?: ""

        val thumbnail = document.selectFirst("#manga-page img")?.attr("src") ?: ""

        val description = document.select("#manga-info-rightColumn .white-font")
            .text().trim().let { desc ->
                // Remove the generic intro text
                val startIndex = desc.indexOf("The Summary is")
                if (startIndex != -1) {
                    desc.substring(startIndex + "The Summary is".length).trim()
                } else desc
            }

        val genres = document.select(".genres-list li")
            .map { it.text().trim() }

        // Extract rating
        val ratingElements = document.select("#R-V-B .RVB")
        val rating = ratingElements.getOrNull(0)?.text()?.trim() ?: ""
        val views = ratingElements.getOrNull(1)?.text()?.trim() ?: ""
        val bookmarks = ratingElements.getOrNull(2)?.text()?.trim() ?: ""

        // Extract manga info stats
        val statsElements = document.select("#manga-info-stats .flex")
        val author = statsElements.find { it.text().contains("Author") }
            ?.select("li")?.getOrNull(1)?.text()?.trim() ?: "Updating"

        val status = statsElements.find { it.text().contains("Status") }
            ?.select("li")?.getOrNull(1)?.text()?.trim() ?: "Ongoing"

        val lastUpdate = statsElements.find { it.text().contains("Last Update") }
            ?.select("li")?.getOrNull(1)?.text()?.trim() ?: ""

        val alternativeNames = statsElements.find { it.text().contains("Alternatives") }
            ?.select("li")?.getOrNull(1)?.text()?.trim() ?: ""

        // Extract chapters
        val chapterElements = document.select("#chapters-list li")
        val chapters = chapterElements.mapNotNull { element ->
            val chapterLink = element.selectFirst("a")
            val dateSpan = element.selectFirst("span[style*='float:right']")

            if (chapterLink != null) {
                val chapterName = chapterLink.text().trim()
                val chapterUrl = chapterLink.attr("href")
                val date = dateSpan?.text()?.trim()

                ChapterItem(
                    name = chapterName,
                    number = extractChapterNumber(chapterName),
                    url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$chapterUrl" ,

                    date = parseDate(date)
                )
            } else null
        } // Reverse to get ascending order

        return MangaInfo(
            title = title,
            imageUrl = thumbnail,
            rating = rating,
            ratingCount = "",
            description = if (alternativeNames.isNotBlank()) "$description\n\nAlternative Names: $alternativeNames" else description,
            otherNames = alternativeNames,
            author = author,
            artist = "",
            genres = genres,
            tags = emptyList(),
            yearOfProduction = "",
            status = status,
            favoritesCount = bookmarks,
            chapters = chapters.toMutableList(),
            api = API,
            url = baseUrli,
            language = LANGUAGE
        )
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<MangaItem>()
        Log.i("dfljshdlfjsdfsdfsdfsdfsdfsgjhbdf",html)

        // Parse search results based on the actual HTML structure from the response
        // The search returns a list of <a> tags containing <li> elements with manga info
        val searchResults = doc.select("a")

        for (result in searchResults) {
            val liElement = result.selectFirst("li.flex.flex-row")
            if (liElement != null) {
                // Extract image
                val img = liElement.selectFirst("img.search-thumb")
                val imageUrl = img?.attr("src") ?: ""

                // Extract title from the div content
                val titleDiv = liElement.selectFirst("div.flex.flex-col div")
                val title = titleDiv?.text()?.trim() ?: ""

                // Get the URL from the href attribute of the <a> tag
                val url = result.attr("href")

                if (url.isNotEmpty() && title.isNotEmpty()) {
                    items.add(MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                        imageUrl = imageUrl,
                        rating = 0,
                        chapters = emptyList(),
                        genres = emptyList()
                    ))
                }
            }
        }

        return items
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun getChapterImages(html: String): List<String> {
        val document = Jsoup.parse(html)

        Log.i("saflhdsflksjdfsdfsdfsd",html)
        // Based on the actual HTML structure from the response file,
        // the images are stored in <img> tags with class "imgholder"
        val images = document.select("img.imgholder")
            .mapNotNull { img ->
                when {
                    img.hasAttr("src") -> {
                        val src = img.attr("src")
                        if (src.isNotEmpty() && !src.contains("btn_close.gif") && !src.contains("free_ads.jpg")) {
                            src
                        } else null
                    }
                    else -> null
                }
            }
            .filter { it.isNotEmpty() }

        return images
    }

    private fun extractChapterNumber(chapterName: String): String {
        val regex = """Chapter\s+(\d+(?:\.\d+)?)""".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(chapterName)?.groupValues?.get(1) ?: chapterName
    }

    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null

        return try {
            // DemonicScans uses format: "2025-09-26"
            LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        } catch (e: DateTimeParseException) {
            try {
                // Alternative format: "September 26, 2025"
                LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH))
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    companion object {
        private const val TAG = "DemonicScansRepository"
    }
}