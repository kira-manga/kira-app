package me.manga.yamiapk.sources_repositry.en.mangabuddy

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
import me.manga.yamiapk.sources_repositry.common.SeparatedDetailsSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

class MangaBuddyRepositoryV2 @Inject constructor(
    private val api: IMangaDataApiServices,
    private val dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
    ): SeparatedDetailsSites(dataStore,api,sourcesRepository)   {



    override val mangaSource: MangaSource
        get() = MangaSource.MANGABUDDY

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language
//    private val parser: MangaBuddyParser by lazy { MangaBuddyParser() }

    override var imgBaseUrl: String = "https://res.mbbcdn.com/"
    override var imgUrlVersion: Int = 0
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}/latest" }
    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}/home" }

    override fun handelLoadMoreUrl(page: Int): String = "${baseUrl.ifBlank { BASE_URL }}/latest?page=$page/"



    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  ->  "${baseUrl.ifBlank { BASE_URL }}/search?q=${searchType.query}"
            is SearchType.GENRES  -> ""
            is SearchType.SORT    -> ""
        }




    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
       return null
    }

    override fun createInfoUrl(mangaId: String): String  {
        return if (mangaId.startsWith("http", ignoreCase = true)) {
            mangaId
        } else {
            // BASE_URL already ends with no slash, so we add it
            "${baseUrl.ifBlank { BASE_URL }}$mangaId"
        }
    }
    override fun createChaptersUrl(mangaId: String): String  {
        return if (mangaId.startsWith("http", ignoreCase = true)) {
            mangaId
        } else {
            // BASE_URL already ends with no slash, so we add it
            "${baseUrl.ifBlank { BASE_URL }}/api/manga/${mangaId}/chapters?source=detail"
        }
    }
    override fun handelSearchFormBody(
        page: Int,
        searchType: SearchType.Normal
    ): FormBody? {
        return null
    }

    override fun parseChapters(html: String): List<ChapterItem> {
        val document = Jsoup.parse(html)
        val chapterElements = document.select("ul.chapter-list > li")
        val DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH)

        return chapterElements.map { element ->
            // Link and URL
            val anchor = element.selectFirst("a")!!
            val href = anchor.attr("href").trim()
            val url =  href

            // Chapter number
            val titleEl = anchor.selectFirst("strong.chapter-title")!!
            val rawNumber = titleEl.text().trim()
            val number = rawNumber.removePrefix("Chapter").trim()
            val chpNumOnly = number.replace(Regex("[^\\d.]"), "")  // removes all non‐digits, yields "245"

            // Update date
            val timeEl = anchor.selectFirst("time.chapter-update")
            val dateText = timeEl?.text()?.trim()

            val date = dateText?.let { parseDateString(it) ?: LocalDate.now() }

            ChapterItem(
                number = chpNumOnly,
                name = titleEl.text().toString(),
                url = url,
                date = date
            )
        }
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val mangaList = mutableListOf<MangaItem>()
        val doc: Document = Jsoup.parse(html)
        // each manga entry is wrapped in a div.book-item
        val bookItems = doc.select("div.book-item")  // :contentReference[oaicite:0]{index=0}

        for (item in bookItems) {
            // Thumbnail link & image
            val thumbLink = item.selectFirst(".thumb a")
            val imgElement = item.selectFirst(".thumb img")
            // Latest chapter label
            // Title & URL
            val titleAnchor = item.selectFirst(".meta .title h3 a")
            // Genres list
            val genreSpans = item.select(".genres span")
            // Summary paragraph

            if (thumbLink != null && imgElement != null && titleAnchor != null) {
                val title = titleAnchor.attr("title").trim()
                val url = titleAnchor.attr("href")
                // image is lazy‑loaded in data-src
                val imageUrl = imgElement.attr("data-src")  // :contentReference[oaicite:1]{index=1}
                // parse rating if needed:
                val ratingText = item.selectFirst(".rating .score")?.text()?.trim()
                val rating = ratingText?.substringAfter(" ")?.toFloatOrNull() ?: 0f



                // collect genres as plain strings
                val genres = genreSpans.map { it.text().trim() }
                if (genres.hasBlacklistedGenre()) continue

                mangaList.add(
                    MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = url,
                        imageUrl = imageUrl,
                        rating = rating.toInt(),
                        chapters = listOf(),
                        genres = genres,
                    )
                )
            }
        }

        return mangaList
    }

    override fun extractMangaList(html: String): List<PopularManga>   {
        val updates = mutableListOf<PopularManga>()
        val doc: Document = Jsoup.parse(html)

        // Find the section whose header title is exactly "HOT UPDATES"
        val hotSection = doc.selectFirst(
            ".section.box:has(.section-header .title span:matchesOwn(HOT UPDATES))"
        )
        if (hotSection != null) {
        }
        // Each item is wrapped in a .trending-item.carousel-cell
        val items = hotSection?.select(".trending-item.carousel-cell") ?: return updates

        for (item in items) {
            val linkEl = item.selectFirst("a")
            val imgEl = item.selectFirst(".icon img")
            val chapEl = item.selectFirst(".latest-chapter")

            if (linkEl != null && imgEl != null) {
                // Title comes from the link's title attribute; fallback to the <h4.name> text
                val baseTitle = linkEl.attr("title").ifBlank {
                    item.selectFirst(".name")?.text() ?: "Untitled"
                }
                // Append chapter info if present
                val chapterText = chapEl?.text()?.let { " – $it" } ?: ""
                val title = baseTitle + chapterText

                val url = linkEl.attr("href")
                // Images are lazy‑loaded via data-src
                val imageUrl = imgEl.attr("data-src")

                updates.add(
                    PopularManga(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = url,
                        imageUrl = imageUrl
                    )
                )
            }
        }
        return updates
    }

    override fun extractMangaInfo(html: String, url: String, combinUrl: String): MangaInfo {
        val doc = Jsoup.parse(html)

        // Basic info
        val title           = doc.selectFirst("div.detail .name h1")?.text() ?: ""
        val otherNames      = doc.selectFirst("div.detail .name h2")?.text() ?: ""
        val imageUrl        = doc.selectFirst("div.img-cover img.lazy")?.attr("data-src") ?: ""

        // Ratings
        val rating          = doc.selectFirst("div.rate-info span.score")?.text() ?: "0"
        val ratingCount     = doc.selectFirst("div.rate-info span.votes")?.text()?.removeSurrounding("(", ")") ?: "0"

        // Description
        val description     = doc.selectFirst("div.summary .content")?.text()?.trim() ?: ""

        // Author / Artist
        val author          = doc.select("div.detail .info span:contains(Author) + a").eachText().joinToString(", ")
        val artist          = doc.select("div.detail .info span:contains(Artist) + a").eachText().joinToString(", ")

        // Genres & Tags
        val status = doc
            .selectFirst("p:has(strong:contains(Status)) span")
            ?.text()
            ?: "Unknown"

// Genres (all <a> links in the <p> whose <strong> contains “Genres”)
        val genres = doc
            .select("p:has(strong:contains(Genres)) a")
            .map { it.text().trim().trimEnd(',').trim() }
            .filter { it.isNotEmpty()}

        val tags            = doc.select("div.detail .tags a").eachText()

        // Year of Production & Status
        val yearOfProduction= doc.select("div.detail .info span:containsf(Year) + span").text()

        // Favorites / Bookmarks count
        val favoritesCount  = doc.selectFirst("button.bookmark-btn span.count")?.text() ?: "0"


        return MangaInfo(
            api             = API,
            language        = LANGUAGE,
            url             = url,
            title           = title,
            imageUrl        = imageUrl,
            rating          = rating,
            ratingCount     = ratingCount,
            description     = description,
            otherNames      = otherNames,
            author          = author,
            artist          = artist,
            genres          = genres,
            tags            = tags,
            yearOfProduction= yearOfProduction,
            status          = status,
            favoritesCount  = favoritesCount,
            chapters        = mutableListOf()
        )
    }

    override fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)

        return doc.select("div.list.manga-list div.book-item").mapNotNull { item ->
            // 1. Cover image (lazy-loaded via data-src)
            val imgEl = item.selectFirst("div.thumb a img") ?: return@mapNotNull null
            val imageUrl = imgEl.absUrl("data-src")                        // :contentReference[oaicite:0]{index=0}

            // 2. Title and page URL
            val titleA = item.selectFirst("div.meta .title h3 a")
                ?: return@mapNotNull null
            val title   = titleA.text().trim()
            val pageUrl = titleA.attr("href")    // e.g. "/the-eternal-supreme"

            // 3. Latest-chapter text (if you need it)
            val latestChapter = item.selectFirst("div.thumb span.latest-chapter")
                ?.text()
                ?: ""

            // 4. Views (optional)
            val viewsText = item.selectFirst("div.meta .views span")?.text()?.trim() ?: "0"
            val views = viewsText.replace("[^\\d.]".toRegex(), "").toIntOrNull() ?: 0

            // 5. Rating (optional)
            val rating = item.selectFirst("div.meta .rating .score")
                ?.text()
                ?.toDoubleOrNull()
                ?: 0.0

            // 6. Genres (optional)
            val genres = item.select("div.meta .genres span")
                .map { it.text().trim().trimEnd(',') }
                .filter { it.isNotEmpty() }



            MangaItem(
                api      = API,
                language = LANGUAGE,
                title    = title,
                url      = pageUrl,
                imageUrl = imageUrl,
                rating   = rating.toInt(),           // or keep as Double if you change MangaItem
                chapters = listOf(),                 // search results won’t include chapter list
                genres   = genres
            )
        }
    }


    override suspend fun initSite(): Int {
        fixedImgUrl =false

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
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures your value wins:
            return base + refererHeader
        }

    private val refererHeader = "Referer" to "https://mangabuddy.com/"

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // Option A: merge here so that cache always has Referer, and if you persist headers you want to include it
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged

        dataStore.saveHeadersForApi(API, merged)

    }
    override fun getChapterImages(html: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl.ifBlank { BASE_URL })  // where BASE_URL == "https://s2.mbcdnsab.org/"

        val doc2 = doc.html()
        val realDocument = Jsoup.parse(doc2, doc.location())

        if (!html.contains("var mainServer = \"")) {
            val chapterImagesFromHtml = realDocument.select("#chapter-images img, .chapter-image[data-src]")

            // 17/03/2023: Certain hosts only embed two pages in their "#chapter-images" and leave
            // the rest to be lazily(?) loaded by javascript. Let's extract `chapImages` and compare
            // the count against our select query. If both counts are the same, extract the original
            // images directly from the <img> tags otherwise pick the higher count. (heuristic)
            // First things first, let's verify `chapImages` actually exists.
            if (html.contains("var chapImages = '")) {
                val chapterImagesFromJs = html
                    .substringAfter("var chapImages = '")
                    .substringBefore("'")
                    .split(',')


                if (chapterImagesFromJs.all { e ->
                        e.startsWith("http://") || e.startsWith("https://")
                    }
                ) {
                    // Great, we can use these.
                    if (chapterImagesFromHtml.count() < chapterImagesFromJs.count()) {
                        // Seems like we've hit such a host, let's use the images we've obtained
                        // from the javascript string.
//
                        return chapterImagesFromJs.mapIndexed { index, path ->
                            path
                        }
                    }
                }
            }
            return chapterImagesFromHtml.mapIndexed { index, element ->
                element.attr("abs:data-src")
            }
        }


        // While the site may support multiple CDN hosts, we have opted to ignore those
        val mainServer = html
            .substringAfter("var mainServer = \"")
            .substringBefore("\"")
        val schemePrefix = if (mainServer.startsWith("//")) "https:" else ""

        val chapImages = html
            .substringAfter("var chapImages = '")
            .substringBefore("'")
            .split(',')



        return chapImages.mapIndexed { index, path ->
            "$schemePrefix$mainServer$path"
        }

    }



    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()

    override val blackListGenres: Set<String>
        get() =setOf(
        "Adult",
        "Smut",
//        "Mature",
//        "Ecchi",
        "Hentai",
        "Yuri",
        "Fetish",
        "SM/BDSM/SUB-DOM",
        "Incest",
        "Omegaverse",
        "Netorare/NTR",
        "Bara(ML)",
//        "Shoujo(G)",
        "Yaoi",
        "Shounen ai",
        "Gender Bender",
        "Shouja ai",

        )

//            _cachedHeaders
//            ?: runBlocking {
//                val stored = dataStore.getHeadersForApi(API) ?: emptyMap()
//                // merge in your fixed referer header
//                stored + ("Referer" to "https://mangabuddy.com/")
//            }.also { _cachedHeaders = it }


    fun parseDateString(text: String): LocalDate? {
        val now = ZonedDateTime.now(ZoneId.of("Africa/Cairo"))
        val trimmed = text.trim().lowercase(Locale.ENGLISH)

        // Handle “just now”
        if (trimmed == "just now") {
            return now.toLocalDate()
        }

        // Handle “yesterday”
        if (trimmed.startsWith("yesterday")) {
            // Could be “yesterday” or “yesterday at HH:mm”; we only care about the date
            return now.minusDays(1).toLocalDate()
        }

        // Regex for “X unit(s) ago”
        val regex = Regex("(\\d+)\\s+(minute|hour|day|week|month|year)s?\\s+ago")
        val match = regex.find(trimmed)
        if (match != null) {
            val (valueStr, unit) = match.destructured
            val value = valueStr.toLongOrNull() ?: return null
            val adjusted = when (unit) {
                "minute" -> now.minusMinutes(value)
                "hour"   -> now.minusHours(value)
                "day"    -> now.minusDays(value)
                "week"   -> now.minusWeeks(value)
                "month"  -> now.minusMonths(value)
                "year"   -> now.minusYears(value)
                else     -> now
            }
            return adjusted.toLocalDate()
        }

        // Fallback: parse absolute date, e.g. “May 27, 2025”
        return try {
            // You may need multiple patterns; here’s one example:
            val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
            LocalDate.parse(text.trim(), formatter)
        } catch (e: Exception) {
            // If parsing fails, return null or log warning
            null
        }
    }


}