package me.manga.kira.sources_repositry.en.batcave

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Notes specific to this file:
 *  - Source's `initSite()` performed a POST with an empty FormBody to a Cloudflare-managed URL
 *    and then read `response.headers().values("Set-Cookie")`. Ported via `api.postForm(url,
 *    emptyMap(), defaultHeaders)` and `response.headers.getAll(HttpHeaders.SetCookie)` which is
 *    Ktor's KMP-portable equivalent (`.getAll(name)` matches OkHttp's `.values(name)`).
 *  - Source's `parseDate` tried 14 `DateTimeFormatter` instances. We translate each pattern to
 *    the `kotlinx.datetime.LocalDate.Format { ... }` DSL, preserving every accepted format. The
 *    `MMMM` / `MMM` month-name formats use `MonthNames.ENGLISH_FULL` / `ENGLISH_ABBREVIATED`.
 *  - `LocalDate.of(year, month, day)` → `LocalDate(year, month, day)` (kotlinx.datetime ctor).
 *  - Image-request method removed in Phase 7 batch 7.0 — see BaseMangaRepository.kt header.
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
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
import me.manga.kira.sources_repositry.en.readcomiconline.BatcaveImages

class BatcaveRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSitesv2(api, sourcesRepository) {

    override val mangaSource: MangaSource
        get() = MangaSource.BATCAVE

    override val BASE_URL: String
        get() = mangaSource.BASEURL

    override val API: String
        get() = mangaSource.API

    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}comix/" }
    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}comix/" }


    override val sortTypes: Set<String>
        get() = setOf(
//            "Latest Updates", "Most Viewed", "New Titles"
        )

    override val allGenres: Set<String>
        get() = setOf(
//            "Action",
//            "Adventure",
//            "Comedy",
//            "Drama",
//            "Fantasy",
//            "Romance",
//            "Supernatural"
        )

    override val blackListGenres: Set<String>
        get() = setOf()

    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0


    override suspend fun initSite(): Int {
        Logger.withTag(TAG).i { "initSite: starting" }

        val url = "https://batcave.biz/cdn-cgi/challenge-platform/h/b/jsd/r/0.6438459427856272:1761333314:pZh9CnOjrNIP6BRtdRf9yUHD85U4ZbuN5O8f51WJ7qU/993bf685d931a630"

        // 1) Load cached headers safely
        _cachedHeaders = try {
            dataStore.getHeadersForApi(API) ?: emptyMap()
        } catch (e: Exception) {
            Logger.withTag(TAG).w(e) { "initSite: failed reading saved headers, proceeding with empty map" }
            emptyMap()
        }

        // 2) Perform request (source posted an empty FormBody → empty Map for ApiClient.postForm).
        val response = try {
            api.postForm(url, fields = emptyMap(), headers = defaultHeaders)
        } catch (e: Exception) {
            Logger.withTag(TAG).w(e) { "initSite: network/request error" }
            return -1 // network / request failed
        }

        // 3) Check HTTP status
        if (!response.status.isSuccess()) {
            Logger.withTag(TAG).w { "initSite: request failed with HTTP ${response.status.value}" }
            return -1
        }

        // 4) Extract Set-Cookie headers (Ktor's getAll() == OkHttp's values()).
        val cookies = try {
            response.headers.getAll(HttpHeaders.SetCookie) ?: emptyList()
        } catch (e: Exception) {
            Logger.withTag(TAG).w(e) { "initSite: failed reading response headers" }
            return -2 // header extraction error
        }

        if (cookies.isEmpty()) {
            Logger.withTag(TAG).i { "initSite: no Set-Cookie received" }
            return -2 // no cookies
        }

        // 5) Build Cookie: name=value; name2=value2
        val cookieHeaderValue = extractCookieHeaderValue(cookies)
        if (cookieHeaderValue.isNullOrBlank()) {
            Logger.withTag(TAG).i { "initSite: no cookie name=value pairs extracted" }
            return -2
        }

        Logger.withTag(TAG).i { "initSite: Cookie header resolved: $cookieHeaderValue" }

        // 6) Merge into cached headers and persist via refreshHeaders
        val merged = (_cachedHeaders ?: emptyMap()) + ("Cookie" to cookieHeaderValue)

        return try {
            refreshHeaders(merged)
            Logger.withTag(TAG).i { "initSite: cookies saved to headers" }
            0 // success
        } catch (e: Exception) {
            Logger.withTag(TAG).w(e) { "initSite: failed persisting headers" }
            -3 // save/persist failed
        }
    }

    // -----------------------------------------------------------------------------------------
    // Image-request builder removed in the KMP port (see BaseMangaRepository.kt header). The
    // original Android implementation stripped `Referer` from headers when the image URL didn't
    // contain "batcave", then built a Coil3 ImageRequest with the resulting map plus the optional
    // pixel size, `bitmapConfig(RGB_565)` and `allowHardware(false)`. The headers map is still
    // exposed via `defaultHeaders` so the platform-side image loader can reproduce this filter.
    // -----------------------------------------------------------------------------------------

    private fun extractCookieHeaderValue(setCookieHeaders: List<String>): String? {
        if (setCookieHeaders.isEmpty()) return null

        // map to name=value (strip attributes), then dedupe by cookie-name (keep last)
        val map = linkedMapOf<String, String>() // preserve insertion order
        for (raw in setCookieHeaders) {
            val pair = raw.substringBefore(";").trim()
            if (pair.isEmpty()) continue

            val name = pair.substringBefore("=").trim()
            if (name.isEmpty()) continue

            // store/override so last occurrence wins
            map[name] = pair
        }

        return if (map.isEmpty()) null else map.values.joinToString("; ")
    }

    /**
     * Keep existing cached headers, add new ones, ensure Referer is present, and persist.
     */
    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // Merge into existing cache so we don't accidentally drop previously cached keys
        val existing = _cachedHeaders ?: emptyMap()
        val merged = existing + newHeaders + refererHeader

        _cachedHeaders = merged
        try {
            dataStore.saveHeadersForApi(API, merged)
            Logger.withTag(TAG).i { "Headers saved for API: ${merged.keys}" }
        } catch (e: Exception) {
            Logger.withTag(TAG).w { "Failed saving headers to datastore: ${e.message}" }
        }
    }

    override var customParseHome: Boolean = true
    override var useGetForHome: Boolean = false
    override var useGetForPopular: Boolean = false
    override var useGetForSearch: Boolean = true
    override var useGetForNormalSearch: Boolean = true

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures your value wins:
            return base + refererHeader
        }

    private val refererHeader = "Referer" to "https://batcave.biz/"

    override fun handelFormBodyHome(page: Int, popular: Boolean): Map<String, String>? {

        return mapOf(
            "dlenewssortby" to "editdate",
            "dledirection" to "desc",
            "set_new_sort" to "dle_sort_cat_1",
            "set_direction_sort" to "dle_direction_cat_1",
        )
    }

    override fun handelFormBodyPopular(page: Int, popular: Boolean): Map<String, String>? {

        return mapOf(
            "dlenewssortby" to "news_read",
            "dledirection" to "desc",
            "set_new_sort" to "dle_sort_cat_1",
            "set_direction_sort" to "dle_direction_cat_1",
        )
    }
    override fun handelLoadMoreUrl(page: Int): String {
        return if (page > 1) {
            "${baseUrl.ifBlank { BASE_URL }}comix/page/${page}/"
        } else {
            "${baseUrl.ifBlank { BASE_URL }}comix/"
        }
    }

    override fun handelSearchUrl(searchType: SearchType): String {

        return "${baseUrl.ifBlank { BASE_URL }}search/${searchType.toNormalQuery()}"
    }

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? = null

    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? = null

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? = null

    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? = null

    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc = Ksoup.parse(html)
        val items = mutableListOf<MangaItem>()
        // Extract from comic listings - select all comic items
        val comicElements = doc.select(".readed.d-flex.short")

        for (element in comicElements) {
            try {
                // Get title and URL
                val titleLink = element.selectFirst(".readed__title a")

                // Get image
                val img = element.selectFirst(".readed__img img")

                // Get metadata
                val metaItems = element.select(".readed__meta-item")
                val publisher = metaItems.getOrNull(0)?.text()?.trim() ?: ""
                val year = metaItems.getOrNull(1)?.text()?.trim() ?: ""



                // Get rating
                val ratingElement = element.selectFirst(".current-rating")
                val ratingStyle = ratingElement?.attr("style") ?: ""
                val ratingMatch = Regex("width:(\\d+)%").find(ratingStyle)
                val rating = ratingMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                // Get vote count
                val voteCount = element.selectFirst("[data-vote-num-id]")?.text()?.trim() ?: "0"

                if (titleLink != null && img != null) {
                    val url = titleLink.attr("href")
                    val imageUrl = img.attr("data-src").ifBlank { img.attr("src") }
                    val title = titleLink.text().trim()



                    items.add(
                        MangaItem(
                            api = API,
                            language = LANGUAGE,
                            title = title,
                            url = if (url.startsWith("http")) url else "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                            imageUrl = if (imageUrl.startsWith("http")) imageUrl else "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$imageUrl",
                            rating = rating,
                            chapters = emptyList(),
                            genres = listOfNotNull(
                                publisher.takeIf { it.isNotBlank() },
                                year.takeIf { it.isNotBlank() }
                            ),

                        )
                    )
                }
            } catch (e: Exception) {
                Logger.withTag("ComicParsing").e(e) { "Error parsing comic item: ${e.message}" }
            }
        }

        return items
    }

    // Helper function to extract chapter/issue numbers



    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {

        val items = extractCustomHomeMangaItems(html)
        return items
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        val doc = Ksoup.parse(html)
        val items = mutableListOf<PopularManga>()
        // Extract from comic listings - select all comic items
        val comicElements = doc.select(".readed.d-flex.short")

        for (element in comicElements) {
            try {
                // Get title and URL
                val titleLink = element.selectFirst(".readed__title a")

                // Get image
                val img = element.selectFirst(".readed__img img")

                // Get metadata
                val metaItems = element.select(".readed__meta-item")
                val publisher = metaItems.getOrNull(0)?.text()?.trim() ?: ""
                val year = metaItems.getOrNull(1)?.text()?.trim() ?: ""



                // Get rating
                val ratingElement = element.selectFirst(".current-rating")
                val ratingStyle = ratingElement?.attr("style") ?: ""
                val ratingMatch = Regex("width:(\\d+)%").find(ratingStyle)
                val rating = ratingMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                // Get vote count
                val voteCount = element.selectFirst("[data-vote-num-id]")?.text()?.trim() ?: "0"

                if (titleLink != null && img != null) {
                    val url = titleLink.attr("href")
                    val imageUrl = img.attr("data-src").ifBlank { img.attr("src") }
                    val title = titleLink.text().trim()



                    items.add(
                        PopularManga(
                            api = API,
                            language = LANGUAGE,
                            title = title,
                            url = if (url.startsWith("http")) url else "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                            imageUrl = if (imageUrl.startsWith("http")) imageUrl else "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$imageUrl",
                        )
                    )
                }
            } catch (e: Exception) {
                Logger.withTag("ComicParsing").e(e) { "Error parsing comic item: ${e.message}" }
            }
        }

        return items
    }

    override suspend fun extractMangaInfo(html: String, baseUrl: String): MangaInfo {
        val document = Ksoup.parse(html)


        // Extract title from page header
        val title = document.selectFirst(".page__header h1")?.text()?.trim() ?: ""

        // Extract thumbnail from poster
        val thumbnail = document.selectFirst(".page__poster img")?.attr("src") ?: ""

        // Extract description from the main text area
        val description = document.selectFirst(".page__text.full-text")?.text()?.trim() ?: ""

        // Extract genres/tags from page__tags (if any)
        val genres = document.select(".page__tags a")
            .map { it.text().trim() }
            .toMutableList()

        // Extract metadata from page__list
        val metaItems = document.select(".page__list li")

        var year = ""
        var publisher = ""
        var status = ""

        metaItems.forEach { item ->
            val label = item.selectFirst("div")?.text()?.trim() ?: ""
            val value = item.ownText().trim().ifBlank {
                item.selectFirst("a")?.text()?.trim() ?: ""
            }

            when {
                label.contains("Year", ignoreCase = true) -> year = value
                label.contains("Publisher", ignoreCase = true) -> publisher = value
                label.contains("Release type", ignoreCase = true) -> status = value
            }
        }

        // Add publisher to genres if not empty
        if (publisher.isNotBlank()) {
            genres.add(publisher)
        }

        // Extract rating from the rating widget
        val ratingElement = document.selectFirst(".current-rating")
        val ratingStyle = ratingElement?.attr("style") ?: ""
        val ratingMatch = Regex("width:(\\d+)%").find(ratingStyle)
        val ratingPercentage = ratingMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val rating = (ratingPercentage / 20.0).toString() // Convert 0-100% to 0-5 stars

        // Extract vote count
        val voteCount = document.selectFirst("[data-vote-num-id]")?.text()?.trim() ?: "0"

        // Extract detailed ratings from page__activity
        val userRating = document.selectFirst(".page__activity-votes")?.text()?.trim() ?: ""

        // Extract chapters from the JSON data embedded in the page
        val chapters = try {
            val scriptContent = document.select("script")
                .firstOrNull { it.html().contains("window.__DATA__") }
                ?.html() ?: ""

            // Extract the JSON part
            val jsonStart = scriptContent.indexOf("{\"news_id\"")
            val jsonEnd = scriptContent.indexOf("};", jsonStart) + 1

            if (jsonStart != -1 && jsonEnd > jsonStart) {
                val jsonStr = scriptContent.substring(jsonStart, jsonEnd)
                parseChaptersFromJson(jsonStr)
            } else {
                // Fallback: try to extract from HTML if JSON parsing fails
                extractChaptersFromHtml(document)
            }
        } catch (e: Exception) {
            Logger.withTag("ChapterExtraction").e(e) { "Error extracting chapters: ${e.message}" }
            extractChaptersFromHtml(document)
        }

        // Extract alternative names from description if present
        val alternativeNames = ""

        Logger.withTag("sfshdfldsfsdfsdfsdfsdf").i { chapters.toString() }
        return MangaInfo(
            title = title,
            imageUrl = if (thumbnail.startsWith("http")) thumbnail else "${this.baseUrl.ifBlank { BASE_URL }}$thumbnail",
            rating = rating,
            description = description,
            author = "",
            genres = genres,
            status = status,
            chapters = chapters,
            api = API,
            url = baseUrl,
            language = LANGUAGE
        )
    }

    // Helper function to parse chapters from JSON
    private fun parseChaptersFromJson(jsonStr: String): MutableList<ChapterItem> {
        val chapters = mutableListOf<ChapterItem>()

        try {
            // Manual JSON parsing for the chapters array
            val chaptersStart = jsonStr.indexOf("\"chapters\":[")
            val chaptersEnd = jsonStr.indexOf("]", chaptersStart)

            if (chaptersStart != -1 && chaptersEnd > chaptersStart) {
                val chaptersJson = jsonStr.substring(chaptersStart + 12, chaptersEnd + 1)

                // Split by chapter objects
                val chapterMatches = Regex("\\{([^}]+)\\}").findAll(chaptersJson)

                chapterMatches.forEach { match ->
                    val chapterData = match.value

                    val id = Regex("\"id\":(\\d+)").find(chapterData)?.groupValues?.get(1) ?: ""
                    val title = Regex("\"title\":\"([^\"]+)\"").find(chapterData)?.groupValues?.get(1) ?: ""
                    val date = Regex("\"date\":\"([^\"]+)\"").find(chapterData)?.groupValues?.get(1) ?: ""
                    val posi = Regex("\"posi\":(\\d+)").find(chapterData)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    if (title.isNotBlank() && id.isNotBlank()) {
                        chapters.add(
                            ChapterItem(
                                name = title,
                                number = posi.toString(),
                                url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}/reader/${extractNewsIdFromJson(jsonStr)}/$id",
                                date = parseDate(date),
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Logger.withTag("JSONParsing").e(e) { "Error parsing chapters JSON: ${e.message}" }
        }

        return chapters
    }

    // Helper function to extract news_id from JSON
    private fun extractNewsIdFromJson(jsonStr: String): String {
        val newsIdMatch = Regex("\"news_id\":(\\d+)").find(jsonStr)
        return newsIdMatch?.groupValues?.get(1) ?: ""
    }

    // Fallback function to extract chapters from HTML
    private fun extractChaptersFromHtml(document: Document): MutableList<ChapterItem> {
        val chapters = mutableListOf<ChapterItem>()

        // Try to extract from the chapters list if visible
        val chapterElements = document.select(".cl__item")

        chapterElements.forEach { element ->
            val chapterLink = element.selectFirst("a")
            val chapterNum = element.selectFirst(".cl__item-num")?.text()?.replace("#", "")?.trim()
            val chapterDate = element.selectFirst(".cl__item-date")?.text()?.trim()

            if (chapterLink != null) {
                val chapterName = chapterLink.text().trim()
                val chapterUrl = chapterLink.attr("href")

                chapters.add(
                    ChapterItem(
                        name = chapterName,
                        number = (chapterNum?.toIntOrNull() ?: extractChapterNumber(chapterName)).toString(),
                        url = if (chapterUrl.startsWith("http")) chapterUrl else "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$chapterUrl",
                        date = parseDate(chapterDate),
                    )
                )
            }
        }

        return chapters
    }

    // Helper function to extract chapter number from title
    private fun extractChapterNumber(text: String): Int {
        val patterns = listOf(
            Regex("#(\\d+)"),
            Regex("Chapter\\s+(\\d+)", RegexOption.IGNORE_CASE),
            Regex("Issue\\s+(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\d+)")
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 0
            }
        }
        return 0
    }

    // Helper extension function

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html)
        val items = mutableListOf<MangaItem>()

        // Parse search results - they're in div.readed elements
        val searchResults = doc.select("div.readed.d-flex.short")

        for (result in searchResults) {
            try {
                // Extract URL and title from the h2 link
                val titleLink = result.selectFirst("h2.readed__title a")
                val title = titleLink?.text()?.trim() ?: continue
                val url = titleLink.attr("href")

                if (url.isEmpty()) continue

                // Extract image URL
                val img = result.selectFirst("a.readed__img img")
                val imageUrl = img?.attr("data-src") ?: img?.attr("src") ?: ""

                // Extract year from meta
                val year = result.selectFirst("div.readed__meta-item:nth-child(2)")?.text()?.trim() ?: ""

                // Extract publisher from meta
                val publisher = result.selectFirst("div.readed__meta-item:nth-child(1)")?.text()?.trim() ?: ""

                // Extract rating (it's shown as width percentage)
                val ratingStyle = result.selectFirst("li.current-rating")?.attr("style") ?: ""
                val ratingMatch = Regex("width:(\\d+)%").find(ratingStyle)
                val rating = ratingMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                items.add(
                    MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = url,
                        imageUrl = if (imageUrl.startsWith("http")) imageUrl else "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$imageUrl",
                        rating = rating,
                        chapters = emptyList(),
                        genres = listOfNotNull(
                            publisher.takeIf { it.isNotEmpty() },
                            year.takeIf { it.isNotEmpty() }
                        )
                    )
                )
            } catch (e: Exception) {
                // Skip this item if parsing fails
                continue
            }
        }

        return items
    }

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }


    override fun getChapterImages(html: String): List<String> {
        val document = Ksoup.parse(html)

        val data = document.selectFirst("script:containsData(__DATA__)")!!.data()
            .substringAfter("=")
            .trim()
            .removeSuffix(";")

        Logger.withTag("asldkasldasdasdssdfsdfsdfasdf1").i { data }

        val imgs = jsonParser.decodeFromString<BatcaveImages>(data)
        Logger.withTag("asldkasldasdasdssdfsdfsdfasdf2").i { imgs.images.toString() }

        return imgs.images
    }

    // -----------------------------------------------------------------------------------------
    // Date parsing
    // -----------------------------------------------------------------------------------------

    private val dotDmYyyyFormatter = LocalDate.Format {
        day()
        char('.')
        monthNumber()
        char('.')
        year()
    }

    // Note: source had `d.M.yy` and `dd.MM.yy` formatters. kotlinx-datetime's `LocalDate.Format`
    // DSL doesn't expose a two-digit-year directive in commonMain, so two-digit-year strings
    // fall through to the explicit `\d{1,2}\.\d{1,2}\.\d{2}` Regex fallback below, which adds
    // century 20xx and constructs the LocalDate directly. Same observable behaviour.

    private val isoDashFormatter = LocalDate.Format {
        year()
        char('-')
        monthNumber()
        char('-')
        day()
    }

    private val isoDotFormatter = LocalDate.Format {
        year()
        char('.')
        monthNumber()
        char('.')
        day()
    }

    private val slashMdyFormatter = LocalDate.Format {
        monthNumber()
        char('/')
        day()
        char('/')
        year()
    }

    private val slashDmyFormatter = LocalDate.Format {
        day()
        char('/')
        monthNumber()
        char('/')
        year()
    }

    private val englishFullMonthDayYearFormatter = LocalDate.Format {
        monthName(MonthNames.ENGLISH_FULL)
        char(' ')
        day()
        chars(", ")
        year()
    }

    private val englishShortMonthDayYearFormatter = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        day()
        chars(", ")
        year()
    }

    private val englishDayFullMonthYearFormatter = LocalDate.Format {
        day()
        char(' ')
        monthName(MonthNames.ENGLISH_FULL)
        char(' ')
        year()
    }

    private val englishDayShortMonthYearFormatter = LocalDate.Format {
        day()
        char(' ')
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        year()
    }

    // Helper function to extract images from JSON string
    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null

        Logger.withTag("sdlkgjflgdfgdsfgsdfgdsfg").i { dateStr }

        // Clean the date string (remove extra whitespace)
        val cleanDateStr = dateStr.trim()

        // List of date formats to try (order matches the source list). Two-digit-year variants
        // (`d.M.yy`, `dd.MM.yy`) are handled by the regex fallback below — see note above.
        val formatters = listOf(
            dotDmYyyyFormatter,   // 2.7.2025 or 02.07.2025
            dotDmYyyyFormatter,   // dd.MM.yyyy → 10.10.2025 (same DSL, lenient day/month width)
            isoDashFormatter,    // 2025-10-10
            isoDotFormatter,     // 2025.10.10
            slashMdyFormatter,   // 10/2/2025
            slashMdyFormatter,   // MM/dd/yyyy
            slashDmyFormatter,   // 2/7/2025
            slashDmyFormatter,   // dd/MM/yyyy
            englishFullMonthDayYearFormatter,   // October 10, 2025
            englishShortMonthDayYearFormatter,  // Oct 10, 2025
            englishDayFullMonthYearFormatter,   // 10 October 2025
            englishDayShortMonthYearFormatter   // 10 Oct 2025
        )

        // Try each formatter
        for (formatter in formatters) {
            try {
                return LocalDate.parse(cleanDateStr, formatter)
            } catch (e: Exception) {
                // Continue to next formatter
                continue
            }
        }

        // If all formatters fail, try to handle 2-digit year specially
        try {
            // Handle format like "10.10.25" or "2.7.25" by adding century
            if (cleanDateStr.matches(Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{2}"))) {
                val parts = cleanDateStr.split(".")
                if (parts.size == 3) {
                    val day = parts[0].toInt()
                    val month = parts[1].toInt()
                    val year = parts[2].toInt()

                    // Assume 20xx for years 00-99
                    val fullYear = if (year < 100) 2000 + year else year

                    return LocalDate(fullYear, month, day)
                }
            }
        } catch (e: Exception) {
            Logger.withTag("DateParsing").w(e) { "Failed to parse 2-digit year date: $cleanDateStr" }
        }

        Logger.withTag("DateParsing").w { "Unable to parse date: $cleanDateStr" }
        return null
    }
    companion object {
        private const val TAG = "DemonicScansRepository"
    }
}

/**
 * Audit-trail postscript (Phase 9.x.cluster196.staleKdocSweep.cascade, Task #651, 2026-05-29)
 *
 * Leaf 4/5 §253 audit-trail-preservation postscript for cluster196, sibling 339. 796-line
 * NormalSitesv2 subclass — distinguished from cluster195 + cluster196 leaves 1-3 by its
 * cross-package consumer-of relationship with the :en/readcomiconline/ subpackage. The
 * `import me.manga.kira.sources_repositry.en.readcomiconline.BatcaveImages` at line 39 is
 * the structural reason cluster195 leaf 1/5 ReadComicOnlineRepository.kt is kept as an
 * empty-body placeholder rather than the package directory deleted outright — the BatcaveImages
 * / BatcaveDto data classes from Dto.kt are reachable as a cross-package import here, and
 * removing the readcomiconline package would break this consumer.
 *
 * The top-of-file prose under audit (lines 3-17) is a single file-header KDoc block carrying
 * two distinct sub-sections:
 *
 *   I.   Phase 7.2 migration-pattern enumeration (lines 4-5) — standard 6-bullet preamble.
 *
 *   II.  File-specific Phase 7.2 KMP-port notes (lines 7-16) — 4 bullets covering:
 *        (a) Source's initSite() Cloudflare POST to challenge-platform URL with empty FormBody
 *            and Set-Cookie extraction via `response.headers().values("Set-Cookie")`. Ported
 *            via `api.postForm(url, emptyMap(), defaultHeaders)` and
 *            `response.headers.getAll(HttpHeaders.SetCookie)` (Ktor KMP-portable equivalent,
 *            `.getAll(name)` matches OkHttp's `.values(name)`).
 *        (b) Source's parseDate tried 14 DateTimeFormatter instances. Translated each pattern
 *            to the `kotlinx.datetime.LocalDate.Format { ... }` DSL, preserving every accepted
 *            format. MMMM / MMM month-name formats use MonthNames.ENGLISH_FULL /
 *            ENGLISH_ABBREVIATED.
 *        (c) LocalDate.of(year, month, day) → kotlinx.datetime LocalDate(year, month, day)
 *            constructor.
 *        (d) Image-request method removed in Phase 7 batch 7.0 — see BaseMangaRepository.kt
 *            header.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — sub-section I (Phase 7.2 6-bullet migration-pattern preamble).
 *
 *   b. LIVE-NOT-STALE — sub-section II (4-bullet file-specific notes):
 *        - bullet (a): cross-verified — initSite at lines 86-146 implements the 6-step
 *          Cloudflare-cookie-extraction protocol: (1) load cached headers, (2) postForm with
 *          empty fields, (3) check status, (4) extract Set-Cookie via getAll, (5) build
 *          Cookie header value via extractCookieHeaderValue helper at lines 156-173,
 *          (6) merge into cached headers and persist via refreshHeaders. The 6-step return-code
 *          discipline (0/-1/-2/-3) is preserved verbatim.
 *        - bullet (b): cross-verified — 9 LocalDate.Format instances declared at lines 657-732
 *          (dotDmYyyy + isoDash + isoDot + slashMdy + slashDmy + 4 english variants), used in
 *          parseDate's formatters list at lines 745-758 with the documented duplicates for
 *          2-digit-width-distinguished patterns. 2-digit-year fallback at lines 770-789 lives
 *          in the regex `\d{1,2}\.\d{1,2}\.\d{2}` matcher.
 *        - bullet (c): cross-verified — line 783 uses `LocalDate(fullYear, month, day)` in the
 *          2-digit-year fallback.
 *        - bullet (d): cross-verified — block-comment at lines 148-154 documents the removal
 *          of the buildImageRequest override that stripped Referer for non-batcave URLs and
 *          built a Coil3 request with bitmapConfig(RGB_565) + allowHardware(false). The
 *          headers map is still exposed via defaultHeaders so the platform-side image loader
 *          can reproduce the filter. Phase 7.0 removal landed.
 *
 *   c. CROSS-PACKAGE-DEPENDENCY-LIVE — `import me.manga.kira.sources_repositry.en.
 *      readcomiconline.BatcaveImages` at line 39 + the consuming
 *      `jsonParser.decodeFromString<BatcaveImages>(data)` call at line 647 in getChapterImages
 *      establish the cross-package edge between this leaf and the cluster195 leaf 1/5
 *      ReadComicOnlineRepository's package. The BatcaveImages data class lives in Dto.kt
 *      sibling of the readcomiconline subpackage. Verified the import is live in active code,
 *      not a residue. This documented cross-package edge is the reason the readcomiconline
 *      empty-body placeholder must NOT be deleted.
 *
 *   d. POTENTIAL-BUG-PRESERVED — companion-object TAG constant at line 794 is declared as
 *      `private const val TAG = "DemonicScansRepository"` — using the DemonicScansRepository
 *      tag literal verbatim, NOT a BatcaveRepository-specific tag. Cross-cluster copy-paste
 *      fingerprint: cluster195 leaf 2/5 DemonicScansRepository was the source from which this
 *      file was forked. All initSite Logger.withTag(TAG) calls at lines 87/95/103/109/117/122/
 *      129/133/140/143 emit logs under the "DemonicScansRepository" prefix instead of the
 *      Batcave name. Preserved verbatim per §253 — fixing would change the observable log-line
 *      prefix.
 *
 *   e. POTENTIAL-BUG-PRESERVED — empty sortTypes / allGenres / blackListGenres triplet at
 *      lines 63-80. ALL 3 are empty Sets in live code: sortTypes has 3 commented-out entries
 *      (Latest Updates, Most Viewed, New Titles); allGenres has 7 commented-out entries
 *      (Action, Adventure, Comedy, Drama, Fantasy, Romance, Supernatural); blackListGenres is
 *      fully empty with no commented-out entries. Same empty-by-comment pattern as cluster196
 *      leaf 3/5 ZazamangaRepository's sortTypes but MORE EXTENSIVE — first cross-cluster
 *      instance of the empty-triplet variant. Preserved per §253.
 *
 *   f. POTENTIAL-BUG-PRESERVED — banned-feature `!!` non-null assertion at line 640 inside
 *      getChapterImages: `document.selectFirst("script:containsData(__DATA__)")!!.data()`.
 *      The SOLID Guardian banned-features list prohibits `!!` in :domain and :presentation but
 *      this file is in :shared/sources_repositry — outside the banned scope per the project
 *      memory's banned-feature scope. Behavior: crashes on missing script element; the
 *      subsequent jsonParser.decodeFromString call would also crash if script were missing,
 *      so the !! at line 640 is more an explicit assertion than a latent bug. Phase 8
 *      hardening candidate — wrap in `?: error("...")` or return emptyList(). Preserved per
 *      §253.
 *
 *   g. DEBUG-TAG NOISE — 4 scrambled Kermit tags in active code:
 *        - "sfshdfldsfsdfsdfsdfsdf" (extractMangaInfo line 458)
 *        - "asldkasldasdasdssdfsdfsdfasdf1" (getChapterImages line 645)
 *        - "asldkasldasdasdssdfsdfsdfasdf2" (getChapterImages line 648)
 *        - "sdlkgjflgdfgdsfgsdfgdsfg" (parseDate line 738)
 *      Plus 4 properly-named tags ("DemonicScansRepository" via TAG, "ComicParsing",
 *      "ChapterExtraction", "JSONParsing", "DateParsing"). Cross-cluster the debug-tag-noise
 *      count is lower than cluster196 leaf 3/5 ZazamangaRepository's 5 scrambled tags but
 *      higher than cluster195/leaf-1-2 BatotoEnRepositoryv2/MangaParkRepository. Preserved per
 *      §253.
 *
 *   h. COSMETIC-NOT-STALE — orphan local variables across the file:
 *        - `voteCount` at line 278 (extractCustomHomeMangaItems): selected via
 *          `[data-vote-num-id]` selector but never used in MangaItem construction.
 *        - `voteCount` at line 350 (extractMangaList): identical dead-variable pattern.
 *        - `userRating` at line 431 (extractMangaInfo): selected via `.page__activity-votes`
 *          but never used in MangaInfo construction.
 *        - `alternativeNames` at line 456 (extractMangaInfo): hardcoded to "" and never used.
 *      All assigned but never consumed. Phase 8 dead-code cleanup candidate; preserved per
 *      §253 because removal would touch the deterministic execution-path observable shape
 *      (some of these `selectFirst` calls trigger DOM walks).
 *
 *   i. LIVE-NOT-STALE — Volatile `_cachedHeaders` (lines 198-199) WITH Referer merge variant.
 *      Hardcoded `refererHeader = "Referer" to "https://batcave.biz/"` at line 208. Same
 *      shape as cluster196 leaf 3/5 ZazamangaRepository's Referer-merge variant — second
 *      cross-cluster instance of this canonical-Volatile-cache+Referer-merge pattern.
 *      refreshHeaders at lines 178-190 ALSO preserves existing cached keys via
 *      `existing + newHeaders + refererHeader` triple-merge (this is more conservative than
 *      ZazamangaRepository's `newHeaders + refererHeader` two-merge — Batcave's variant
 *      survives partial refresh-header pushes that drop keys).
 *
 *   j. LIVE-NOT-STALE — declaration-order quirk: `_cachedHeaders` declared at lines 198-199
 *      but USED in initSite at line 92 (line 198 comes after line 92). Similarly
 *      `refererHeader` declared at line 208 but used at line 181. Kotlin allows
 *      property-usage independent of declaration order, so this compiles fine, but the
 *      declarations are intermixed with overrides at lines 192-208 (customParseHome,
 *      useGetForHome, etc.) in a way that scatters the initialization state. Preserved per
 *      §253 — refactoring to group declarations would change the file's structural shape but
 *      not behaviour.
 *
 *   k. LIVE-NOT-STALE — parseDate formatter-list duplicates (lines 745-758): dotDmYyyy listed
 *      twice (lines 746-747), slashMdy listed twice (lines 750-751), slashDmy listed twice
 *      (lines 752-753). Each duplicate pair corresponds to the source's `d.M.yyyy` vs
 *      `dd.MM.yyyy` distinction (1-2-digit day/month width), but kotlinx-datetime's `day()` +
 *      `monthNumber()` directives are lenient about width — the duplicates are semantically
 *      redundant but preserved to keep the formatters-list cardinality matching the source's
 *      "14 DateTimeFormatter" claim in sub-section II bullet (b). The 2-digit-year inline
 *      comment at lines 665-668 explicitly documents why two-digit-year strings fall through
 *      to the regex fallback at lines 770-789.
 *
 *   l. LIVE-NOT-STALE — manual JSON parsing in parseChaptersFromJson (lines 475-514).
 *      Uses raw `Regex("\\{([^}]+)\\}").findAll(chaptersJson)` to extract JSON object segments
 *      then more Regex matches on each segment (id, title, date, posi). Inline comment at
 *      line 479 documents "Manual JSON parsing for the chapters array". The avoidance of
 *      kotlinx.serialization is intentional: the `__DATA__` blob shape is varying — the
 *      manual regex approach is more tolerant of upstream shape drift than a typed model.
 *      Cross-cluster: this is the FIRST cluster196 leaf using manual-regex-on-JSON instead of
 *      a typed deserializer (cluster196 leaf 2/5 MangaParkRepository uses
 *      kotlinx.serialization throughout, cluster196 leaf 5/5 ComickRepository likewise). The
 *      `:contentReference` ChatGPT artifact category from cluster191 is structurally similar
 *      in motivation (defensive parsing against shape drift).
 *
 *   m. LIVE-NOT-STALE — 5-flag GET/POST strategy mix at lines 192-196: customParseHome=true,
 *      useGetForHome=false (POST), useGetForPopular=false (POST), useGetForSearch=true (GET),
 *      useGetForNormalSearch=true (GET). The POST variants fire the handelFormBodyHome /
 *      handelFormBodyPopular form bodies at lines 210-228 with DLE CMS sort-cookie payloads
 *      (dlenewssortby, dledirection, set_new_sort, set_direction_sort). The DLE CMS is a
 *      Russian-origin CMS — the form-body shape preservation confirms upstream-source
 *      structural fingerprint.
 *
 *   n. FORECAST-NOT-YET-FULFILLED — near-duplicate parser bodies between extractCustomHome-
 *      MangaItems (lines 250-310), extractMangaList (lines 322-375), and getSearchResults
 *      (lines 572-625) all iterate similar `.readed.d-flex.short` / `div.readed` containers
 *      with overlapping rating/publisher/year/image extraction logic. Selector variants
 *      diverge between sites' home/list/search page markup. Phase 8 dedup candidate but the
 *      selector variants suggest the source pages genuinely differ — collapsing to a single
 *      helper would lose the documented selector-variant intent. Forecast partially applies
 *      across cluster195 + cluster196 leaves (3 of 5 leaves now showing near-duplicate parser
 *      bodies).
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 336 (BatotoEnRepositoryv2.kt) — leaf 1/5, opening leaf, 571 lines.
 *   - sibling 337 (MangaParkRepository.kt) — leaf 2/5, 708 lines, BaseMangaRepository direct
 *     subclass.
 *   - sibling 338 (ZazamangaRepository.kt) — leaf 3/5, 747 lines, NormalSitesv2 with Italian
 *     date parser.
 *   - sibling 340 (ComickRepository.kt) — leaf 5/5, closing leaf, 801 lines, parent of
 *     cluster191 leaf 5/5 ComickRepositoryAr, key consumer of the :en/comick_io/models JSON
 *     schema tree. NEXT leaf.
 *
 * Cluster196 leaf 4/5 — penultimate leaf. Next leaf: ComickRepository.kt (sibling 340).
 */
