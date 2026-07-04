package me.manga.yamiapk.sources_repositry.ar.teamx

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.manga.yamiapk.admin.Admin
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.states.State.Error.Companion.fromCode
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.di.coli.getImageLoader
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.NormalSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import kotlin.collections.ifEmpty

class TeamXRepositoryv2  @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
    ): NormalSites(dataStore,api,sourcesRepository) {


    override val mangaSource: MangaSource
        get() = MangaSource.TEAM_X
    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL  } }
    override var imgBaseUrl: String = mangaSource.BASEURL
    override var imgUrlVersion: Int = 0

    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL  } }

    override val popularUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL  } }

    override fun handelLoadMoreUrl(page: Int): String {
        return loadMoreUrl(page)
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  -> normalSearchUrl(q = searchType.toNormalQuery())
            is SearchType.GENRES  -> ""
            is SearchType.SORT    -> ""
        }



    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        return null
    }




  
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




    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // persist in background
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

        // update the cache immediately
    }





    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()


    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }



//    override fun extractHomeMangaItems(html: String): MutableList<MangaItem>  = parser.extractHomeMangaItems(html)
//    override fun extractMangaList(html: String): List<PopularManga> = parser.extractMangaList(html)
//    override suspend fun getSearchResults(html: String): List<MangaItem> = parser.getSearchResults(html)
//    override fun getChapterImages(html: String): List<String> = parser.getChapterImages(html)
    override suspend fun extractMangaInfo(html: String, url: String): MangaInfo = coroutineScope {
        // 1) Parse the first page
        val doc = Jsoup.parse(html)

        // … your existing basic fields extraction here …
        val title = doc.selectFirst("div.author-info-title h1")?.text()?.trim() ?: "Unknown Title"
        val imageUrl = doc.selectFirst("div.text-right img.shadow-sm")?.attr("src") ?: ""
        val rating = doc.selectFirst("div#average_rating")?.text()?.trim() ?: "Unknown Rating"
        val ratingCount = doc.selectFirst("span#rating_count")?.text()?.trim() ?: "0"
        val description =
            doc.selectFirst("div.review-content p")?.text()?.trim() ?: "No description available"
        val genres = doc.select("div.review-author-info a.subtitle").map { it.text().trim() }
        val status = doc.select("div.full-list-info")
            .firstOrNull { it.text().contains("الحالة:") }
            ?.select("small")?.last()?.text()?.trim() ?: "Unknown Status"
        val artist = doc.select("div.full-list-info")
            .firstOrNull { it.text().contains("الرسام:") }
            ?.select("small a")?.first()?.text()?.trim() ?: "Unknown Artist"
        val author = "Unknown Author"
        val otherNames = ""
        val tags = emptyList<String>()
        val yearOfProduction = ""
        val favoritesCount = doc.select("div.full-list-info")
            .firstOrNull { it.text().contains("التبرعات:") }
            ?.select("small span")?.first()?.text()?.trim() ?: "0"
    Log.i("alsdsfsgfsgfdgjfasdasdasdsfdsaa",doc.toString())

        // —— FAST PATH FOR PAGE URLS ——
        // 2) Look at the pagination widget on the first page:
        val pageLinks = doc.select("ul.pagination li.page-item a.page-link")
            .mapNotNull { it.text().toIntOrNull() }

        val lastPageNumber = pageLinks.maxOrNull() ?: 1

        // 3) Build ALL page URLs at once:
        //    assume your base chapter URL is like "https://example.com/manga/123/chapter/1"
        //    and that pages are "?page=1", "?page=2", etc.
        val base = url.substringBeforeLast("?page=")  // or however your URLs work
        val pageUrls = (1..lastPageNumber).map { pageNum ->
            "$base?page=$pageNum"

        }


        val chaptersDeferred = pageUrls.map { pageUrl ->
            async(Dispatchers.IO) {
                runCatching {
                    val response = api.get(pageUrl, headers = defaultHeaders)
                    if (!response.isSuccessful) return@runCatching emptyList<ChapterItem>()
                    val pageDoc = Jsoup.parse(response.body().orEmpty())
                    getChapterData(pageDoc)
                }.onFailure {  e ->
                }
                    .getOrDefault(emptyList())
            }
        }

        val allChapters = chaptersDeferred.awaitAll().flatten()
            .filter { it.url != "#" } // Filter out invalid chapters



        // 5) Build and return your MangaInfo
        MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = title,
            imageUrl = imageUrl,
            rating = rating,
            ratingCount = ratingCount,
            description = description,
            genres = genres,
            status = status,
            artist = artist,
            author = author,
            otherNames = otherNames,
            tags = tags,
            yearOfProduction = yearOfProduction,
            favoritesCount = favoritesCount,
            chapters = allChapters.toMutableList()
        )
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









    fun loadMoreUrl(page : Int): String = "${baseUrl.ifBlank { mangaSource.BASEURL}}?page=$page"
    fun normalSearchUrl(q : String): String = "${baseUrl.ifBlank { mangaSource.BASEURL}}ajax/search?keyword=${q}"







    suspend fun extractMangaInfo(
        html: String,
        url: String,
        fetchPage: suspend (pageUrl: String) -> String
    ): MangaInfo = coroutineScope {
        // 1) Parse the first page
        val doc = Jsoup.parse(html)

        // … your existing basic fields extraction here …
        val title = doc.selectFirst("div.author-info-title h1")?.text()?.trim() ?: "Unknown Title"
        val imageUrl = doc.selectFirst("div.text-right img.shadow-sm")?.attr("src") ?: ""
        val rating = doc.selectFirst("div#average_rating")?.text()?.trim() ?: "Unknown Rating"
        val ratingCount = doc.selectFirst("span#rating_count")?.text()?.trim() ?: "0"
        val description = doc.selectFirst("div.review-content p")?.text()?.trim()
            ?: "No description available"
        val genres = doc.select("div.review-author-info a.subtitle").map { it.text().trim() }
        val status = doc.select("div.full-list-info")
            .firstOrNull { it.text().contains("الحالة:") }
            ?.select("small")?.last()?.text()?.trim() ?: "Unknown Status"
        val artist = doc.select("div.full-list-info")
            .firstOrNull { it.text().contains("الرسام:") }
            ?.select("small a")?.first()?.text()?.trim() ?: "Unknown Artist"
        val author = "Unknown Author"
        val otherNames = ""
        val tags = emptyList<String>()
        val yearOfProduction = ""
        val favoritesCount = doc.select("div.full-list-info")
            .firstOrNull { it.text().contains("التبرعات:") }
            ?.select("small span")?.first()?.text()?.trim() ?: "0"

        // —— FAST PATH FOR PAGE URLS ——
        val pageLinks = doc.select("ul.pagination li.page-item a.page-link")
            .mapNotNull { it.text().toIntOrNull() }
        val lastPageNumber = pageLinks.maxOrNull() ?: 1

        // Derive base URL (strip any existing ?page=…)
        val base = url.substringBeforeLast("?page=")
        val pageUrls = (1..lastPageNumber).map { pageNum ->
            "$base?page=$pageNum"
        }

        // —– NOW PARALLEL FETCH + PARSE —–

        val chaptersDeferred = pageUrls.map { pageUrl ->
            async(Dispatchers.IO) {
                runCatching {
                    val pageHtml = fetchPage(pageUrl)
                    val pageDoc = Jsoup.parse(pageHtml)
                    getChapterData(pageDoc)
                }.getOrDefault(emptyList())
            }
        }

        val allChapters = chaptersDeferred.awaitAll()
            .flatten()
            .filter { it.url != "#" }

        val endTime = System.currentTimeMillis()

        MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = title,
            imageUrl = imageUrl,
            rating = rating,
            ratingCount = ratingCount,
            description = description,
            genres = genres,
            status = status,
            artist = artist,
            author = author,
            otherNames = otherNames,
            tags = tags,
            yearOfProduction = yearOfProduction,
            favoritesCount = favoritesCount,
            chapters = allChapters.toMutableList()
        )
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        val doc: Document = Jsoup.parse(html)
        return doc.select("div.swiper-slide").map { slide ->
            extractMangaItem(slide)
        }
    }




   override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val mangaList = mutableListOf<MangaItem>()
        val doc: Document = Jsoup.parse(html)

        val mangaElements = doc.select("div.listupd .bs .bsx").ifEmpty { doc.select("div.post-body .box") }

        for (div in mangaElements) {
            val titleElement = div.selectFirst("div.info a h3")
            val urlElement = div.selectFirst("div.info a[href]")
            val imageElement = div.selectFirst("div.imgu a img")
            val chaptersElements = div.select("div.info ul li a")

            val title = titleElement?.text() ?: "Unknown Title"
            val url = urlElement?.attr("href") ?: ""
            val imageUrl = imageElement?.attr("src") ?: ""

            val chapters = chaptersElements.map { chapter ->
                val chapterTitle = chapter.text()
                val chapterUrl = chapter.attr("href")

                // Extract chapter number from title (assuming "الفصل رقم X")
                val chapterNumber = Regex("""\d+""").find(chapterTitle)?.value.toString()

                ChapterItem(
                    url = chapterUrl,
                    number = "Chapter $chapterNumber",
                    date = LocalDate.now(),  // You can update this when date info is available
                    isDownloaded = false    // Default false; modify logic if needed
                )
            }.filter { it.url != "#" } // Filter out invalid chapters

            val mangaItem = MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                chapters = chapters,
                genres = emptyList(),
                rating = null // No genres found in provided HTML
            )

            mangaList.add(mangaItem)
        }

        return mangaList
    }
    fun extractMangaItem(slide: Element): PopularManga {

        // Extract series URL and image
        val imageLink = slide.selectFirst(".entry-image a.box")
        val img = imageLink?.selectFirst("img")

        val seriesUrl = imageLink?.attr("href")?.takeIf { it.isNotBlank() }
        val imageUrl = img?.attr("src")?.takeIf { it.isNotBlank() }
        val imageAlt = img?.attr("alt")?.takeIf { it.isNotBlank() }

        // Extract title and its link
        val titleLink = slide.selectFirst(".entry-title a")
        val title = titleLink?.text()?.takeIf { it.isNotBlank() }
        val titleUrl = titleLink?.attr("href")?.takeIf { it.isNotBlank() }



        return PopularManga(
            api = API,
            language = LANGUAGE,
            title = title.toString(),
            url = titleUrl.toString(),
            imageUrl = imageUrl.toString()
        )
    }
    fun getChapterData(doc: Document): MutableList<ChapterItem> {
        val chapters = mutableListOf<ChapterItem>()


        // Correct selector based on the actual HTML structure
        val chapterElements = doc.select("div.chapter-card")

        for (element in chapterElements) {
            // Extract chapter number from data attribute or chapter-number div
            val chapterNumber = element.attr("data-number").ifBlank {
                element.select("div.chapter-number").text()
                    .replace(Regex("[^\\d.]"), "")
            }

            // Extract chapter title
            val chapterTitle = element.select("div.chapter-title").text().trim()

            // Extract chapter URL from the link
            val chapterUrl = element.select("a.chapter-link").attr("href")

            // Extract date - it's in a specific format in the data attribute
            val dateTimestamp = element.attr("data-date")
            val dateText = element.select("div.chapter-date span").text().trim()

            // Parse the date
            val date = if (dateTimestamp.isNotBlank()) {
                try {
                    // The data-date appears to be a Unix timestamp
                    val timestamp = dateTimestamp.toLong()
                    Instant.ofEpochSecond(timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                } catch (e: Exception) {
                    parseRelativeDate(dateText)
                }
            } else {
                parseRelativeDate(dateText)
            }

            val chapterItem = ChapterItem(
                number = chapterNumber.ifBlank { chapterTitle },
                name = chapterTitle,
                url = chapterUrl,
                date = date,
                isDownloaded = false
            )

            chapters.add(chapterItem)
        }

        return chapters
    }

    // Helper function to parse relative dates like "12 hours ago", "13 hours ago"
    fun parseRelativeDate(dateStr: String): LocalDate? {
        if (dateStr.isBlank()) return null

        val now = LocalDateTime.now()

        return try {
            when {
                // Handle "X hours ago"
                dateStr.contains("hours ago") || dateStr.contains("hour ago") -> {
                    val hours = Regex("""\d+""").find(dateStr)?.value?.toLong() ?: 0
                    now.minusHours(hours).toLocalDate()
                }
                // Handle "X days ago"
                dateStr.contains("days ago") || dateStr.contains("day ago") -> {
                    val days = Regex("""\d+""").find(dateStr)?.value?.toLong() ?: 0
                    now.minusDays(days).toLocalDate()
                }
                // Handle "X weeks ago"
                dateStr.contains("weeks ago") || dateStr.contains("week ago") -> {
                    val weeks = Regex("""\d+""").find(dateStr)?.value?.toLong() ?: 0
                    now.minusWeeks(weeks).toLocalDate()
                }
                // Handle "X months ago"
                dateStr.contains("months ago") || dateStr.contains("month ago") -> {
                    val months = Regex("""\d+""").find(dateStr)?.value?.toLong() ?: 0
                    now.minusMonths(months).toLocalDate()
                }
                else -> parseChapterDate(dateStr)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parseChapterDate(dateStr: String): LocalDate? {
        if (dateStr.isBlank() || dateStr.equals("NEW", ignoreCase = true)) return null

        val normalized = dateStr.replace('،', ',').trim()

        try {
            return when {
                // with time component
                Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(normalized) -> {
                    LocalDateTime
                        .parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .toLocalDate()
                }
                // plain ISO date
                Regex("""\d{4}-\d{2}-\d{2}""").matches(normalized) -> {
                    LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
                }
                else -> null
            }
        } catch (_: DateTimeParseException) { /* fall through */ }

        // Try Arabic month names
        val arabicFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale("ar"))
        try {
            return LocalDate.parse(normalized, arabicFmt)
        } catch (_: DateTimeParseException) { /* nope */ }

        // Fallback to English
        val englishFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
        return try {
            LocalDate.parse(normalized, englishFmt)
        } catch (ex: DateTimeParseException) {
            null
        }
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val TAG = "SearchParse"

        val doc = Jsoup.parse(html)

        // اختياري: اطبع عدد النتائج
        val items = doc.select("a.items-center")
        Log.i(TAG, "Found ${items.size} results")

        return items.mapNotNull { a ->
            Log.d(TAG, "Item a.href=${a.attr("href")} classes=${a.className()}")

            val pageUrl = a.absUrl("href").trim()
            if (pageUrl.isBlank()) {
                Log.w(TAG, "Skip: empty href")
                return@mapNotNull null
            }

            val img = a.selectFirst("img")
            val imgUrl = img?.absUrl("src")?.trim().orEmpty()

            val h4 = a.selectFirst("h4")
            val title = h4?.text()?.trim().orEmpty()
            if (title.isBlank()) {
                Log.w(TAG, "Skip: empty title for $pageUrl")
                return@mapNotNull null
            }

            MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = pageUrl,
                imageUrl = imgUrl,
                rating = 0,
                chapters = emptyList(),
                genres = emptyList()
            )
        }
    }

    override fun getChapterImages(html: String): List<String> {
        val doc: Document = Jsoup.parse(html)
        val imageUrls = doc.select("div.image_list canvas[data-src], div.image_list img[src]")
            .map { element ->
                when {
                    element.hasAttr("src") -> element.absUrl("src")
                    else -> element.absUrl("data-src")
                }
            }

        return imageUrls
    }




}