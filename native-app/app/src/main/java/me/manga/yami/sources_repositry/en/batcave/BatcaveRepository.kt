package me.manga.yamiapk.sources_repositry.en.batcave

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.size.Dimension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
import me.manga.yamiapk.sources_repositry.en.readcomiconline.BatcaveImages
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject

class BatcaveRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
) : NormalSitesv2(dataStore, api, sourcesRepository) {

    override val mangaSource: MangaSource
        get() = MangaSource.BATCAVE

    override val BASE_URL: String
        get() = mangaSource.BASEURL

    override val API: String
        get() = mangaSource.API

    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL } }comix/" }
    override val popularUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL } }comix/"}


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
        Log.i(TAG, "initSite: starting")

        val url = "https://batcave.biz/cdn-cgi/challenge-platform/h/b/jsd/r/0.6438459427856272:1761333314:pZh9CnOjrNIP6BRtdRf9yUHD85U4ZbuN5O8f51WJ7qU/993bf685d931a630"

        // 1) Load cached headers safely
        _cachedHeaders = try {
            dataStore.getHeadersForApi(API) ?: emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "initSite: failed reading saved headers, proceeding with empty map", e)
            emptyMap()
        }

        // 2) Perform request
        val response = try {
            api.post(url, body = FormBody.Builder().build())
        } catch (e: Exception) {
            Log.w(TAG, "initSite: network/request error", e)
            return -1 // network / request failed
        }

        // 3) Check HTTP status
        if (!response.isSuccessful) {
            Log.w(TAG, "initSite: request failed with HTTP ${response.code()}")
            return -1
        }

        // 4) Extract Set-Cookie headers
        val cookies = try {
            response.headers().values("Set-Cookie")
        } catch (e: Exception) {
            Log.w(TAG, "initSite: failed reading response headers", e)
            return -2 // header extraction error
        }

        if (cookies.isEmpty()) {
            Log.i(TAG, "initSite: no Set-Cookie received")
            return -2 // no cookies
        }

        // 5) Build Cookie: name=value; name2=value2
        val cookieHeaderValue = extractCookieHeaderValue(cookies)
        if (cookieHeaderValue.isNullOrBlank()) {
            Log.i(TAG, "initSite: no cookie name=value pairs extracted")
            return -2
        }

        Log.i(TAG, "initSite: Cookie header resolved: $cookieHeaderValue")

        // 6) Merge into cached headers and persist via refreshHeaders
        val merged = (_cachedHeaders ?: emptyMap()) + ("Cookie" to cookieHeaderValue)

        return try {
            refreshHeaders(merged)
            Log.i(TAG, "initSite: cookies saved to headers")
            0 // success
        } catch (e: Exception) {
            Log.w(TAG, "initSite: failed persisting headers", e)
            -3 // save/persist failed
        }
    }

    override fun buildImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int

    ): ImageRequest {
        val headersMap = if (
            !url.contains("batcave", ignoreCase = true)) {
            defaultHeaders.filterKeys { !it.equals("Referer", ignoreCase = true) }
        } else {
            defaultHeaders
        }

        val coilHeaders = NetworkHeaders.Builder()
            .apply { headersMap.forEach { (k, v) -> add(k, v) } }
            .build()


        return ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(coilHeaders)
            .apply {
                if (screenWidthPx != 0){
                    size(Dimension.Pixels(screenWidthPx), Dimension.Undefined)

                }
            }
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(true)
            .build()
    }
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
            Log.i(TAG, "Headers saved for API: ${merged.keys}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving headers to datastore: ${e.message}")
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

    override fun handelFormBodyHome(page: Int, popular: Boolean): FormBody? {

        return FormBody.Builder()
            .add("dlenewssortby","editdate")
            .add("dledirection","desc")
            .add("set_new_sort","dle_sort_cat_1")
            .add("set_direction_sort","dle_direction_cat_1")
            .build()
    }

    override fun handelFormBodyPopular(page: Int, popular: Boolean): FormBody?{

        return FormBody.Builder()
            .add("dlenewssortby","news_read")
            .add("dledirection","desc")
            .add("set_new_sort","dle_sort_cat_1")
            .add("set_direction_sort","dle_direction_cat_1")
            .build()
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

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? = null

    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? = null

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? = null

    override fun sortFormBody(searchType: SearchType.SORT): FormBody? = null

    override fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem> {
        val doc = Jsoup.parse(string)
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
                Log.e("ComicParsing", "Error parsing comic item: ${e.message}")
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
        val doc = Jsoup.parse(html)
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
                Log.e("ComicParsing", "Error parsing comic item: ${e.message}")
            }
        }

        return items
    }

    override suspend fun extractMangaInfo(html: String, baseUrli: String): MangaInfo {
        val document = Jsoup.parse(html)


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
            Log.e("ChapterExtraction", "Error extracting chapters: ${e.message}")
            extractChaptersFromHtml(document)
        }

        // Extract alternative names from description if present
        val alternativeNames = ""

        Log.i("sfshdfldsfsdfsdfsdfsdf",chapters.toString())
        return MangaInfo(
            title = title,
            imageUrl = if (thumbnail.startsWith("http")) thumbnail else "${baseUrl.ifBlank { BASE_URL }}$thumbnail",
            rating = rating,
            ratingCount = voteCount,
            description = description,
            otherNames = alternativeNames,
            author = "",
            artist = "",
            genres = genres,
            tags = emptyList(),
            yearOfProduction = year,
            status = status,
            favoritesCount = "",
            chapters = chapters,
            api = API,
            url = baseUrli,
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
            Log.e("JSONParsing", "Error parsing chapters JSON: ${e.message}")
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
        val doc = Jsoup.parse(html)
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
        } }


    override fun getChapterImages(html: String): List<String> {
        val document = Jsoup.parse(html)

        val data = document.selectFirst("script:containsData(__DATA__)")!!.data()
            .substringAfter("=")
            .trim()
            .removeSuffix(";")

        Log.i("asldkasldasdasdssdfsdfsdfasdf1",data.toString())

        val imgs = jsonParser.decodeFromString<BatcaveImages>(data)
        Log.i("asldkasldasdasdssdfsdfsdfasdf2",imgs.images.toString())

        return imgs.images
    }

    // Helper function to extract images from JSON string
    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null

        Log.i("sdlkgjflgdfgdsfgsdfgdsfg", dateStr.toString())

        // Clean the date string (remove extra whitespace)
        val cleanDateStr = dateStr.trim()

        // List of date formats to try
        val formatters = listOf(
            // Formats with optional leading zeros (d and M instead of dd and MM)
            DateTimeFormatter.ofPattern("d.M.yyyy"),             // 2.7.2025 or 02.07.2025
            DateTimeFormatter.ofPattern("d.M.yy"),               // 2.7.25 or 02.07.25
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),           // 10.10.2025
            DateTimeFormatter.ofPattern("dd.MM.yy"),             // 10.10.25
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),           // 2025-10-10
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),           // 2025.10.10
            DateTimeFormatter.ofPattern("M/d/yyyy"),             // 10/2/2025
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),           // 10/10/2025
            DateTimeFormatter.ofPattern("d/M/yyyy"),             // 2/7/2025
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),           // 10/10/2025
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),  // October 10, 2025
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),   // Oct 10, 2025
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),   // 10 October 2025
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)     // 10 Oct 2025
        )

        // Try each formatter
        for (formatter in formatters) {
            try {
                return LocalDate.parse(cleanDateStr, formatter)
            } catch (e: DateTimeParseException) {
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

                    return LocalDate.of(fullYear, month, day)
                }
            }
        } catch (e: Exception) {
            Log.w("DateParsing", "Failed to parse 2-digit year date: $cleanDateStr", e)
        }

        Log.w("DateParsing", "Unable to parse date: $cleanDateStr")
        return null
    }
    companion object {
        private const val TAG = "DemonicScansRepository"
    }
}