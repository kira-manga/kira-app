package me.manga.yamiapk.sources_repositry.ar.mangatuk

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.manga.yamiapk.admin.Admin
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.states.State.Error.Companion.fromCode
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject

class MangatukRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
) : NormalSites(dataStore, api, sourcesRepository) {

    companion object {
        private const val TAG = "MangatukRepository"
    }

    override val mangaSource: MangaSource
        get() = MangaSource.MANGATUK

    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override var imgBaseUrl: String = mangaSource.BASEURL
    override var imgUrlVersion: Int = 0

    override val API: String
        get() = mangaSource.API

    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { "${baseUrl.ifBlank { mangaSource.BASEURL }}manga/?m_orderby=latest" }

    override val popularUrl: String by lazy { "${baseUrl.ifBlank { mangaSource.BASEURL }}manga/?m_orderby=trending" }

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { mangaSource.BASEURL }}manga/page/$page/?m_orderby=latest"
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> normalSearchUrl(q = searchType.toNormalQuery())
            is SearchType.GENRES -> normalSearchUrl(q = searchType.toNormalQuery())
            is SearchType.SORT -> normalSearchUrl(q = searchType.toNormalQuery())
        }

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        return null
    }

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override val sortTypes: Set<String>
        get() = setOf(
//            "latest",      // الأحدث
//            "alphabet",    // من الألف إلى الياء
//            "rating",      // التقييم
//            "trending",    // الأكثر رواجاً
//            "views",       // معظم المشاهدات
//            "new-manga"    // جديد
        )

    override val allGenres: Set<String>
        get() = setOf(
//            "18+", "إعادة التجسد", "اثارة", "اشرار (شرير او شريرة)", "اعادة احياء",
//            "اكشن", "البقاء على قيد الحياة", "السفر عبر الزمن", "العاب فيديو",
//            "العودة بالزمن", "الفنون القتالية", "انتقام", "ايتشي", "ايسيكاي",
//            "بطل غير اعتيادي", "تاريخي", "تبديل الجنس", "تراجيدي", "تناسخ",
//            "جوسي", "حريم", "حريم عكسي", "خارق للطبيعة", "خيال", "خيال علمي",
//            "دراما", "دموي", "رعب", "رومانسي", "رياضة", "زنزانات", "سحر",
//            "سمَت", "سيد خادم", "سينين", "شريحة من الحياة", "شوجو", "شونين",
//            "شونين آي", "شياطين", "عنف", "غموض", "فقدان الذاكرة", "فنتازيا",
//            "قوة خارقة", "كوميدي", "للبالغين", "مأساوي", "مدرسي", "مغامرات",
//            "موريم", "ناضج", "نظام", "نفسي", "وحوش", "ويب تون"
        )

    override val blackListGenres: Set<String>
        get() = setOf(
            "ناضج",
            "للبالغين"
        )

    private fun normalSearchUrl(q: String): String =
        "${baseUrl.ifBlank { mangaSource.BASEURL }}?s=$q&post_type=wp-manga"




    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val mangaList = mutableListOf<MangaItem>()
        val doc: Document = Jsoup.parse(html)

        // Madara theme uses this structure for manga listing
        val mangaElements = doc.select("div.page-listing-item div.page-item-detail.manga")

        for (element in mangaElements) {
            try {
                val titleElement = element.selectFirst("div.post-title h3 a, div.post-title h5 a")
                val urlElement = element.selectFirst("div.item-thumb a[href]")
                val imageElement = element.selectFirst("div.item-thumb img")

                val title = titleElement?.text()?.trim() ?: continue
                val url = urlElement?.attr("href") ?: continue

                // Handle lazy-loaded images
                val imageUrl = imageElement?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                } ?: ""

                // Extract rating
                val ratingText = element.selectFirst("span.score")?.text()?.trim()
                val rating = ratingText?.toDoubleOrNull()?.toInt()

                // Extract chapters from listing
                val chapterElements = element.select("div.list-chapter div.chapter-item")
                val chapters = chapterElements.mapNotNull { chapterEl ->
                    val chapterLink = chapterEl.selectFirst("span.chapter a")
                    val chapterUrl = chapterLink?.attr("href") ?: return@mapNotNull null
                    val chapterNumber = chapterLink.text().trim()

                    val dateText = chapterEl.selectFirst("span.post-on span.timediff")?.text()?.trim()
                        ?: chapterEl.selectFirst("span.post-on")?.text()?.trim()

                    ChapterItem(
                        url = chapterUrl,
                        number = chapterNumber,
                        date = parseArabicDate(dateText),
                    )
                }

                // Extract genres from badges if available
                val genres = element.select("span.manga-title-badges").map { it.text().trim() }
                if (genres.hasBlacklistedGenre()) continue

                val mangaItem = MangaItem(
                    api = API,
                    language = LANGUAGE,
                    title = title,
                    url = url,
                    imageUrl = imageUrl,
                    chapters = chapters,
                    genres = genres,
                    rating = rating
                )

                mangaList.add(mangaItem)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing manga item: ${e.message}")
            }
        }

        return mangaList
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        val doc: Document = Jsoup.parse(html)
        val popularList = mutableListOf<PopularManga>()

        // Popular slider items from widget-manga-popular-slider
        val sliderItems = doc.select("div.slider__item")

        for (slide in sliderItems) {
            try {
                val titleLink = slide.selectFirst("div.post-title h4 a")
                    ?: slide.selectFirst("div.slider__content_item div.post-title a")
                val imageElement = slide.selectFirst("div.slider__thumb_item img")
                    ?: slide.selectFirst("div.slider__thumb img")

                val title = titleLink?.text()?.trim() ?: continue
                val url = titleLink.attr("href")

                // Handle lazy-loaded images
                val imageUrl = imageElement?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                } ?: ""

                // Extract badge (e.g., "18+", "جديد", etc.)
                val badge = slide.selectFirst("span.manga-title-badges span.text")?.text()?.trim()

                // Extract date from post-on
                val dateText = slide.selectFirst("div.post-on span")?.text()?.trim()

                // Extract latest chapters from chapter-item
                val chapters = slide.select("div.chapter-item span.chapter a").mapNotNull { chapterEl ->
                    val chapterUrl = chapterEl.attr("href")
                    val chapterNumber = chapterEl.text().trim()
                    if (chapterUrl.isNotBlank()) {
                        ChapterItem(
                            url = chapterUrl,
                            number = chapterNumber,
                            date = parseArabicDate(dateText),
                            isDownloaded = false
                        )
                    } else null
                }

                popularList.add(
                    PopularManga(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = url,
                        imageUrl = imageUrl,
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing popular manga: ${e.message}")
            }
        }

        return popularList
    }

    override suspend fun extractMangaInfo(html: String, url: String): MangaInfo = coroutineScope {
        val doc = Jsoup.parse(html)

        // Title extraction - from post-title h1 in profile-manga section
        val title = doc.selectFirst("div.post-title h1")?.ownText()?.trim()
            ?: doc.selectFirst("div.post-title h3")?.text()?.trim()
            ?: "Unknown Title"

        // Image extraction - from summary_image with lazy loading support
        val imageUrl = doc.selectFirst("div.summary_image img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        } ?: ""

        // Rating extraction - from post-total-rating
        val rating = doc.selectFirst("div.post-total-rating span.score")?.text()?.trim()
            ?: doc.selectFirst("span.total_votes")?.text()?.trim()
            ?: "0"

        // Rating count from span#countrate
        val ratingCount = doc.selectFirst("span#countrate")?.text()?.trim()
            ?: doc.selectFirst("span[property=ratingCount]")?.text()?.trim()
            ?: "0"

        // Description extraction - from manga-excerpt summary__content
        val description = doc.selectFirst("div.manga-excerpt.summary__content")?.let { descElement ->
            // Get all paragraph text
            descElement.select("p").joinToString("\n") { it.text().trim() }
        }?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("div.summary__content")?.text()?.trim()
            ?: "No description available"

        // Genres extraction - from genres-content
        val genres = doc.select("div.genres-content a").map { it.text().trim() }

        // Extract metadata from post-content_item sections
        val summaryItems = doc.select("div.post-content div.post-content_item")

        var status = "Unknown"
        var author = "Unknown"
        var artist = "Unknown"
        var otherNames = ""
        var type = ""

        for (item in summaryItems) {
            val heading = item.selectFirst("div.summary-heading h5")?.text()?.trim()?.lowercase() ?: continue
            val content = item.selectFirst("div.summary-content")?.text()?.trim() ?: continue

            when {
                heading.contains("حالة الإصدار") || heading.contains("status") -> status = content
                heading.contains("المؤلف") || heading.contains("author") -> {
                    author = item.selectFirst("div.author-content a")?.text()?.trim() ?: content
                }
                heading.contains("الفنان") || heading.contains("الرسام") || heading.contains("artist") -> {
                    artist = item.selectFirst("div.artist-content a")?.text()?.trim() ?: content
                }
                heading.contains("أسماء أخرى") || heading.contains("alternative") -> otherNames = content
                heading.contains("النوع") || heading.contains("type") -> type = content
            }
        }

        // Extract badges/tags from manga-title-badges
        val badges = doc.select("div.post-title span.manga-title-badges span.text").map { it.text().trim() }

        // Favorites count from bookmark section
        val favoritesCount = doc.selectFirst("div.add-bookmark div.action_detail span")?.text()?.let {
            Regex("""\d+""").find(it)?.value
        } ?: "0"

        // Initial chapter links (first/last chapter) from nav-links
        val firstChapterUrl = doc.selectFirst("div#init-links a#btn-read-last")?.attr("href")
        val lastChapterUrl = doc.selectFirst("div#init-links a#btn-read-first")?.attr("href")

        // Extract chapters - chapters are loaded via AJAX on this site
        // The chapter holder has data-id attribute with manga ID
        val mangaId = doc.selectFirst("div#manga-chapters-holder")?.attr("data-id") ?: ""

        val chapters = mutableListOf<ChapterItem>()

        // If chapters are present in the initial HTML (some pages pre-render them)
        val chapterElements = doc.select("li.wp-manga-chapter")

        for (element in chapterElements) {
            val chapterLink = element.selectFirst("a")
            val chapterUrl = chapterLink?.attr("href") ?: continue
            val chapterText = chapterLink.text().trim()

            // Check if chapter is premium/locked
            val isPremium = element.hasClass("premium") ||
                    element.selectFirst("i.fa-lock") != null

            // Extract chapter number from URL or text
            val chapterNumber = extractChapterNumber(chapterUrl, chapterText)

            val dateText = element.selectFirst("span.chapter-release-date")?.text()?.trim()
                ?: element.selectFirst("span.chapter-release-date i")?.text()?.trim()

            if (isPremium) continue
            chapters.add(
                ChapterItem(
                    number = chapterNumber,
                    name = chapterText,
                    url = chapterUrl,
                    date = parseArabicDate(dateText),
                    isDownloaded = false,
                )
            )
        }

        // If no chapters found in HTML, try to fetch via AJAX endpoint
        if (chapters.isEmpty() && mangaId.isNotBlank()) {
            try {
                val ajaxChapters = fetchChaptersViaAjax(url)
                chapters.addAll(ajaxChapters)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching chapters via AJAX: ${e.message}")
            }
        }

        // Handle pagination for chapters if present
        val paginationLinks = doc.select("ul.pagination li.page-item a.page-link")
            .mapNotNull { it.text().toIntOrNull() }
        val lastPage = paginationLinks.maxOrNull() ?: 1

        if (lastPage > 1) {
            val baseChapterUrl = url.substringBeforeLast("?")
            val additionalChapters = (2..lastPage).map { pageNum ->
                async(Dispatchers.IO) {
                    runCatching {
                        val pageUrl = "$baseChapterUrl?page=$pageNum"
                        val response = api.get(pageUrl, headers = defaultHeaders)
                        if (response.isSuccessful) {
                            val pageDoc = Jsoup.parse(response.body().orEmpty())
                            extractChaptersFromPage(pageDoc)
                        } else {
                            emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()

            chapters.addAll(additionalChapters)
        }

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
            tags = badges,
            yearOfProduction = "",
            favoritesCount = favoritesCount,
            chapters = chapters.distinctBy { it.url }.toMutableList()
        )
    }

    /**
     * Extract chapter number from URL or text
     */
    private fun extractChapterNumber(chapterUrl: String, chapterText: String): String {
        // Try to extract from URL first (e.g., /manga/name/55/ -> 55)
        val urlPattern = Regex("""/(\d+(?:\.\d+)?(?:-\d+)?)/?$""")
        urlPattern.find(chapterUrl)?.groupValues?.get(1)?.let { return it }

        // Try to extract from text
        val textPattern = Regex("""(?:الفصل|Chapter|Ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        textPattern.find(chapterText)?.groupValues?.get(1)?.let { return it }

        // Just return the number if text is only a number
        if (chapterText.matches(Regex("""^\d+(?:\.\d+)?$"""))) {
            return chapterText
        }

        return chapterText
    }

    /**
     * Fetch chapters via AJAX endpoint (Madara theme uses this for lazy loading chapters)
     */
    private suspend fun fetchChaptersViaAjax(mangaUrl: String): List<ChapterItem> {
        val chapters = mutableListOf<ChapterItem>()

        try {
            val ajaxUrl = mangaUrl.removeSuffix("/") + "/ajax/chapters/?t=1"
            val formBody = FormBody.Builder()
                .build()

            val response = api.post(ajaxUrl, formBody, headers = defaultHeaders)


            if (response.isSuccessful) {
                val html = response.body().orEmpty()

                val doc = Jsoup.parse(html)
                chapters.addAll(extractChaptersFromPage(doc))
            }
        } catch (e: Exception) {

            Log.e(TAG, "AJAX chapter fetch failed: ${e.message}")
        }

        return chapters
    }

    private fun extractChaptersFromPage(doc: Document): List<ChapterItem> {
        val chapters = mutableListOf<ChapterItem>()
        val chapterElements = doc.select("li.wp-manga-chapter")

        for (element in chapterElements) {
            val chapterLink = element.selectFirst("a") ?: continue
            val chapterUrl = chapterLink.attr("href")
            if (chapterUrl.isBlank()) continue

            val chapterText = chapterLink.text().trim()

            // Check premium/locked status
            val isPremium = element.hasClass("premium") ||
                    element.hasClass("premium-block") ||
                    element.selectFirst("i.fa-lock") != null

            // Check if it's a free chapter
            val isFree = element.hasClass("free-chap")

            // Skip premium chapters that aren't free
            if (isPremium && !isFree) continue

            val chapterNumber = extractChapterNumber(chapterUrl, chapterText)

            // Date extraction from nested structure:
            // <span class="chapter-release-date">
            //     <span class="timediff"><i><i class="fa fa-calendar"></i> نوفمبر 14, 2024</i></span>
            // </span>
            val dateText = element.selectFirst("span.chapter-release-date span.timediff i")?.text()?.trim()
                ?.replace(Regex("""^\s*"""), "") // Remove leading whitespace
                ?: element.selectFirst("span.chapter-release-date span.timediff")?.text()?.trim()
                ?: element.selectFirst("span.chapter-release-date")?.ownText()?.trim()

            // Clean date text - remove any icon remnants
            val cleanedDate = dateText?.replace(Regex("""^[\s\u00A0]*"""), "")?.trim()

            chapters.add(
                ChapterItem(
                    number = chapterNumber,
                    name = chapterText,
                    url = chapterUrl,
                    date = parseArabicDate(cleanedDate),
                    isDownloaded = false,
                )
            )
        }

        return chapters
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<MangaItem>()

        // Madara search results structure
        val searchItems = doc.select("div.c-tabs-item div.row.c-tabs-item__content")
            .ifEmpty { doc.select("div.page-listing-item div.page-item-detail") }

        for (item in searchItems) {
            try {
                val titleElement = item.selectFirst("div.post-title a, h3.h4 a")
                val imageElement = item.selectFirst("div.tab-thumb img, div.item-thumb img")

                val title = titleElement?.text()?.trim() ?: continue
                val url = titleElement.attr("href")

                val imageUrl = imageElement?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                } ?: ""

                val rating = item.selectFirst("span.score")?.text()?.toDoubleOrNull()?.toInt()
                val genres = item.select("div.mg_genres a, span.manga-title-badges").map { it.text().trim() }

                results.add(
                    MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = url,
                        imageUrl = imageUrl,
                        rating = rating,
                        chapters = emptyList(),
                        genres = genres
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing search result: ${e.message}")
            }
        }

        return results
    }


    override fun getChapterImages(html: String): List<String> {
        val images = mutableListOf<String>()

        // Method 1: Extract from JavaScript array
        // Pattern: var images = ["url1", "url2", ...];
        val jsArrayPattern = Regex("""var\s+images\s*=\s*\[([\s\S]*?)\];""")
        val jsMatch = jsArrayPattern.find(html)
        if (jsMatch != null) {
            val arrayContent = jsMatch.groupValues[1]
            // Extract URLs from the JSON-like array
            val urlPattern = Regex(""""([^"]+)"""")
            urlPattern.findAll(arrayContent).forEach { match ->
                val url = match.groupValues[1]
                    .replace("\\/", "/")  // Unescape JSON slashes
                    .trim()
                if (url.isNotBlank() && !url.contains("protection-warning")) {
                    images.add(url)
                }
            }
        }

        // Method 2: Extract from CSS background-image (fallback/verification)
        if (images.isEmpty()) {
            // Pattern: .manga-page.image-X { background-image: url('...'); }
            val cssPattern = Regex("""\.manga-page\.image-\d+\s*\{\s*background-image:\s*url\(['"]?([^'")]+)['"]?\)""")
            cssPattern.findAll(html).forEach { match ->
                val url = match.groupValues[1].trim()
                if (url.isNotBlank() && !url.contains("protection-warning")) {
                    images.add(url)
                }
            }
        }

        // Method 3: Extract from inline style on divs (another fallback)
        if (images.isEmpty()) {
            val doc: Document = Jsoup.parse(html)
            doc.select("div.manga-page[style*='background-image']").forEach { element ->
                val style = element.attr("style")
                val urlMatch = Regex("""background-image:\s*url\(['"]?([^'")]+)['"]?\)""").find(style)
                urlMatch?.let {
                    val url = it.groupValues[1].trim()
                    if (url.isNotBlank() && !url.contains("protection-warning")) {
                        images.add(url)
                    }
                }
            }
        }

        // Method 4: Traditional img tags (unlikely to work on this site but kept as last resort)
        if (images.isEmpty()) {
            val doc: Document = Jsoup.parse(html)
            images.addAll(
                doc.select("div.reading-content div.page-break img")
                    .mapNotNull { element ->
                        val src = element.attr("data-src").ifBlank { element.attr("src") }
                        src.trim().takeIf {
                            it.isNotBlank() && !it.contains("protection-warning")
                        }
                    }
            )
        }

        return images
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? = null

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? = null

    override fun sortFormBody(searchType: SearchType.SORT): FormBody? = null

    /**
     * Parse Arabic date formats commonly used on Mangatuk
     * Examples: "منذ ساعة واحدة", "منذ يومين", "يناير 10, 2026"
     */
    private fun parseArabicDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null

        val now = LocalDateTime.now()
        val normalized = dateStr.trim()

        return try {
            when {
                // Relative time: "منذ X ساعة/ساعات"
                normalized.contains("ساعة") || normalized.contains("ساعات") -> {
                    val hours = Regex("""\d+""").find(normalized)?.value?.toLong() ?: 1
                    now.minusHours(hours).toLocalDate()
                }
                // Relative time: "منذ X يوم/أيام"
                normalized.contains("يوم") || normalized.contains("أيام") -> {
                    val days = Regex("""\d+""").find(normalized)?.value?.toLong() ?: 1
                    now.minusDays(days).toLocalDate()
                }
                // Relative time: "منذ X أسبوع/أسابيع"
                normalized.contains("أسبوع") || normalized.contains("أسابيع") -> {
                    val weeks = Regex("""\d+""").find(normalized)?.value?.toLong() ?: 1
                    now.minusWeeks(weeks).toLocalDate()
                }
                // Relative time: "منذ X شهر/أشهر"
                normalized.contains("شهر") || normalized.contains("أشهر") -> {
                    val months = Regex("""\d+""").find(normalized)?.value?.toLong() ?: 1
                    now.minusMonths(months).toLocalDate()
                }
                else -> {
                    // Try parsing Arabic month format: "يناير 10, 2026"
                    parseArabicMonthDate(normalized)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing date: $dateStr - ${e.message}")
            null
        }
    }

    private fun parseArabicMonthDate(dateStr: String): LocalDate? {
        // Arabic month names mapping
        val arabicMonths = mapOf(
            "يناير" to 1, "فبراير" to 2, "مارس" to 3, "أبريل" to 4,
            "مايو" to 5, "يونيو" to 6, "يوليو" to 7, "أغسطس" to 8,
            "سبتمبر" to 9, "أكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12
        )

        for ((monthName, monthNum) in arabicMonths) {
            if (dateStr.contains(monthName)) {
                val numbers = Regex("""\d+""").findAll(dateStr).map { it.value.toInt() }.toList()
                if (numbers.size >= 2) {
                    val day = numbers[0]
                    val year = numbers[1]
                    return try {
                        LocalDate.of(year, monthNum, day)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

        // Try English format as fallback
        return try {
            val formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
            LocalDate.parse(dateStr, formatter)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}