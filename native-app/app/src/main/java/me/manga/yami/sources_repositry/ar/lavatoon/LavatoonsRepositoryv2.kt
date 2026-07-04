package me.manga.yamiapk.sources_repositry.ar.lavatoon

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject

class LavatoonsRepositoryv2 @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
    ): NormalSites(dataStore,api,sourcesRepository,) {
    override val mangaSource: MangaSource
        get() = MangaSource.LAVATOONS


    override var imgBaseUrl: String = mangaSource.BASEURL
    override var imgUrlVersion: Int = 0

    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL  } }
    override val API: String = mangaSource.API
    override val LANGUAGE: String by lazy { mangaSource.LANGUAGE.Language }
    override val homeUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL  } }

    override val popularUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL  } }



    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  -> normalSearchUrl(q = searchType.toNormalQuery())
            is SearchType.GENRES  -> ""
            is SearchType.SORT    -> ""
        }



    override suspend fun initSite(): Int {

        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }



    private val refererHeader = "Referer" to "https://lavascans.com/"

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



    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // Option A: merge here so that cache always has Referer, and if you persist headers you want to include it
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged

        dataStore.saveHeadersForApi(API, merged)

    }

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        return null
    }





//    override fun getChapterImages(html: String): List<String> = parser.getChapterImages(html)




    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()

    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        return  fetchDataWithHeaders({ api.get(url,defaultHeaders) }){  html -> getSearchResults(html)}
    }

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
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
    fun handelLoadMorebody(page: Int): FormBody {
        return FormBody.Builder()
            .add("action", "ts_homepage_load_more")
            .add("page", page.toString())
            .add("type", "all")
            .build()
    }

    /**
     * Returns the AJAX URL for load more
     */
    override fun handelLoadMoreUrl(page: Int): String {
        return "$baseUrl/wp-admin/admin-ajax.php"
    }



    fun fetchloadmore(url : String,page:Int = 0): Flow<State<MutableList<MangaItem>>> =
        fetchDataWithHeaders({
            Log.i("asdasdasdasdasdasfetchMangaHome1",url)



                api.post(url, headers = defaultHeaders, body = handelLoadMorebody(page))



        }){  html -> extractLoadMoreMangaItems(html)}
    fun extractLoadMoreMangaItems(html: String): MutableList<MangaItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<MangaItem>()

        doc.select("article.legend-card, .legend-card").forEach { card ->
            try {
                // 1. Page URL from poster link
                val posterLink = card.selectFirst("a.legend-poster") ?: return@forEach
                val pageUrl = posterLink.absUrl("href").ifBlank {
                    posterLink.attr("href")
                }
                if (pageUrl.isBlank()) return@forEach

                // 2. Cover image with lazy-load fallback
                val imgEl = posterLink.selectFirst("img.legend-img") ?: return@forEach
                val imageUrl = imgEl.absUrl("src")
                    .takeIf { it.isNotBlank() }
                    ?: imgEl.attr("src").takeIf { it.isNotBlank() }
                    ?: imgEl.attr("data-src").takeIf { it.isNotBlank() }
                    ?: return@forEach

                // 3. Title extraction from .legend-title a
                val title = card.selectFirst(".legend-title a")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@forEach

                // 4. Rating - strip icon text, keep numbers (e.g., "★ 9.0" → 90)
                val rating = card.selectFirst(".legend-rating")
                    ?.text()
                    ?.replace(Regex("[^0-9.]"), "")
                    ?.toFloatOrNull()
                    ?.times(10f)
                    ?.toInt()
                    ?: 0

                // 5. Status from ribbon (e.g., "مستمر" = ongoing, "مكتمل" = completed)
                val status = card.selectFirst(".legend-ribbon span")?.text()?.trim() ?: ""

                // 6. Chapters extraction
                val chapters = card.select(".legend-chapters .legend-ch-link")
                    .filter { chLink ->
                    // Skip if it's a locked chapter
                    chLink.attr("data-coin") != "yes"
                }.mapNotNull { chLink ->
                    val chUrl = chLink.absUrl("href").ifBlank { chLink.attr("href") }
                    if (chUrl.isBlank()) return@mapNotNull null

                    // Chapter text (e.g., "فصل 31")
                    val chText = chLink.selectFirst(".ch-txt")?.text()?.trim() ?: ""
                    // Time ago (e.g., "1 يوم")
                    val chTime = chLink.selectFirst(".ch-time")?.text()?.trim() ?: ""
                    // Extract chapter number from text
                    val chNum = chText.replace(Regex("[^0-9]"), "")

                    ChapterItem(
                        name = chText,
                        url = chUrl,
                        number = chNum,
                        date = parseChapterDate(chTime)
                    )
                }

                items.add(
                    MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = pageUrl,
                        imageUrl = imageUrl,
                        rating = rating,
                        chapters = chapters.ifEmpty { null },
                        genres = emptyList()
                    )
                )
            } catch (e: Exception) {
                // Skip malformed items
            }
        }

        return items
    }
    override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?): Flow<State<List<MangaItem>>> =
        flow {
            emit(State.Loading as State<List<MangaItem>>)
            val url = handelLoadMoreUrl(page)

            fetchloadmore(url,page).collect { state ->
                Log.i("loadmoretesterere2",state.toString())


                when (state) {
                    is State.Success -> {
                        val newItems = state.toData() ?: emptyList()

                        Log.i("loadmoretesterere3",newItems.toString())
                        val mergedList = (currentItems?.toMutableList() ?: mutableListOf()).apply {
                            addAll(newItems)
                        }
                        emit(
                            State.Success(
                                if (newItems.isEmpty()) (currentItems ?: emptyList()) else mergedList
                            )
                        )
                    }

                    is State.Error -> emit(state)
                    else -> Unit
                }
            }
        }.catch { e ->
            emit(State.Error(0,e.localizedMessage ?: "Unknown error occurred"))
        }













    fun normalSearchUrl(q : String): String = "${baseUrl.ifBlank { mangaSource.BASEURL  }}?s=$q"


    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)

        // Search results use .legend-card inside .magma-grid
        return doc.select(".magma-grid .legend-card").mapNotNull { card ->
            // 1. Page URL - from the poster link
            val posterLink = card.selectFirst("a.legend-poster")
                ?: return@mapNotNull null
            val pageUrl = posterLink.absUrl("href")
            if (pageUrl.isBlank()) return@mapNotNull null

            // 2. Cover image
            val imgEl = posterLink.selectFirst("img.legend-img")
                ?: return@mapNotNull null
            val imageUrl = imgEl.absUrl("src")
                .takeIf { it.isNotBlank() }
                ?: imgEl.attr("data-src").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            // 3. Title - from .legend-title a
            val title = card.selectFirst(".legend-title a")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            // 4. Rating - from .legend-rating (e.g. "9.0" → 90)
            val rating = card.selectFirst(".legend-rating")
                ?.text()
                ?.replace(Regex("[^0-9.]"), "") // Remove star icon text, keep numbers
                ?.toFloatOrNull()
                ?.times(10f)
                ?.toInt()
                ?: 0

            // 5. Status - from .legend-ribbon
            val status = card.selectFirst(".legend-ribbon span")?.text()?.trim() ?: ""

            // 6. Chapters (optional) - extract from .legend-ch-link
            val chapters = card.select(".legend-ch-link").mapNotNull { chLink ->
                val chUrl = chLink.absUrl("href")
                if (chUrl.isBlank()) return@mapNotNull null

                val chText = chLink.selectFirst(".ch-txt")?.text()?.trim() ?: ""
                val chTime = chLink.selectFirst(".ch-time")?.text()?.trim() ?: ""

                // Extract chapter number from text like "فصل 86"
                val chNum = chText.replace(Regex("[^0-9]"), "")

                ChapterItem(
                    name = chText,
                    url = chUrl,
                    number = chNum,
                    date = parseChapterDate(chTime)
                )
            }

            MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = pageUrl,
                imageUrl = imageUrl,
                rating = rating,
                chapters = chapters,
                genres = emptyList() // Search results don't show genres
            )
        }
    }
    override fun getChapterImages(html: String): List<String> {
        val doc = Jsoup.parse(html)

        // Method 1: Try to extract from ts_reader.run() script
        val scriptImages = extractImagesFromScript(doc)
        if (scriptImages.isNotEmpty()) {
            return scriptImages
        }

        // Method 2: Fallback - Extract directly from reader area img tags
        return extractImagesFromReaderArea(doc)
    }

    private fun extractImagesFromScript(doc: Document): List<String> {
        // Find script containing ts_reader.run
        val scriptContent = doc.select("script")
            .map { it.html() }
            .firstOrNull { it.contains("ts_reader.run") }
            ?: return emptyList()

        // Extract JSON from ts_reader.run({ ... });
        // Handle both "ts_reader.run({" and wrapped in if statement
        val jsonText = try {
            val startMarker = "ts_reader.run("
            val startIndex = scriptContent.indexOf(startMarker)
            if (startIndex == -1) return emptyList()

            val jsonStart = startIndex + startMarker.length
            var braceCount = 0
            var jsonEnd = jsonStart
            var started = false

            for (i in jsonStart until scriptContent.length) {
                val char = scriptContent[i]
                when (char) {
                    '{' -> {
                        braceCount++
                        started = true
                    }
                    '}' -> {
                        braceCount--
                        if (started && braceCount == 0) {
                            jsonEnd = i + 1
                            break
                        }
                    }
                }
            }

            if (!started || braceCount != 0) return emptyList()
            scriptContent.substring(jsonStart, jsonEnd)
        } catch (e: Exception) {
            return emptyList()
        }

        // Parse JSON and extract images
        return try {
            val root = JSONObject(jsonText)
            val sources = root.optJSONArray("sources") ?: return emptyList()
            if (sources.length() == 0) return emptyList()

            val imagesArray = sources.getJSONObject(0).optJSONArray("images")
                ?: return emptyList()

            (0 until imagesArray.length())
                .mapNotNull { idx ->
                    imagesArray.optString(idx)
                        .takeIf { it.isNotBlank() }
                        ?.replace("\\/", "/") // Unescape JSON slashes
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractImagesFromReaderArea(doc: Document): List<String> {
        // Try multiple selectors for the reader area
        val readerSelectors = listOf(
            "#readerarea img",
            ".reader-area img",
            ".reading-content img",
            ".chapter-content img"
        )

        for (selector in readerSelectors) {
            val images = doc.select(selector)
                .mapNotNull { img ->
                    // Try different attributes for image URL
                    img.attr("src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                }
                .filter { url ->
                    // Filter out placeholder/icon images
                    !url.contains("placeholder") &&
                            !url.contains("loading") &&
                            !url.contains("icon") &&
                            !url.contains("avatar") &&
                            !url.contains("gravatar") &&
                            (url.endsWith(".jpg", true) ||
                                    url.endsWith(".jpeg", true) ||
                                    url.endsWith(".png", true) ||
                                    url.endsWith(".webp", true) ||
                                    url.endsWith(".gif", true) ||
                                    url.contains("/manga/"))
                }

            if (images.isNotEmpty()) {
                return images
            }
        }

        return emptyList()
    }

    fun parseChapterDate(dateStr: String): LocalDate? {
        val trimmed = dateStr.trim()

        // 1) Blank or "NEW" → today
        if (trimmed.isBlank() || trimmed.equals("NEW", ignoreCase = true)) {
            return LocalDate.now()
        }

        // 2) Special case: purely-textual "two days ago" in Arabic
        if (trimmed.equals("يومين ago", ignoreCase = true) ||
            trimmed.equals("يومين", ignoreCase = true)) {
            return LocalDate.now().minusDays(2)
        }

        // 3) Relative-time ("X units ago") in Arabic + "ago"
        val relRegex =
            """(\d+)\s*(ثانية|ثواني|دقيقة|دقائق|ساعة|ساعات|يوم|أيام|يومين|يومان|أسبوع|أسابيع|شهر|أشهر)\s*(?:ago|مضت)?""".toRegex()
        relRegex.find(trimmed)?.let { m ->
            val amount = m.groupValues[1].toLong()
            val unit = m.groupValues[2]
            val now = LocalDateTime.now()
            val dt = when (unit) {
                "ثانية", "ثواني" -> now.minusSeconds(amount)
                "دقيقة", "دقائق" -> now.minusMinutes(amount)
                "ساعة", "ساعات" -> now.minusHours(amount)
                "يوم", "أيام", "يومين", "يومان" -> now.minusDays(amount)
                "أسبوع", "أسابيع" -> now.minusWeeks(amount)
                "شهر", "أشهر" -> now.minusMonths(amount)
                else -> now
            }
            return dt.toLocalDate()
        }

        // 4) Slash-separated date formats (yyyy/MM/dd or dd/MM/yyyy)
        val slashRegex = """(\d{2,4})/(\d{1,2})/(\d{1,2})""".toRegex()
        slashRegex.find(trimmed)?.let { m ->
            val part1 = m.groupValues[1].toInt()
            val part2 = m.groupValues[2].toInt()
            val part3 = m.groupValues[3].toInt()

            return try {
                if (part1 > 1000) {
                    // yyyy/MM/dd format (e.g., 2026/01/09)
                    LocalDate.of(part1, part2, part3)
                } else {
                    // dd/MM/yyyy format (e.g., 09/01/2026)
                    LocalDate.of(part3, part2, part1)
                }
            } catch (_: Exception) { null }
        }

        // 5) Dash-separated date formats (yyyy-MM-dd or dd-MM-yyyy)
        val dashRegex = """(\d{2,4})-(\d{1,2})-(\d{1,2})""".toRegex()
        dashRegex.find(trimmed)?.let { m ->
            val part1 = m.groupValues[1].toInt()
            val part2 = m.groupValues[2].toInt()
            val part3 = m.groupValues[3].toInt()

            return try {
                if (part1 > 1000) {
                    // yyyy-MM-dd format
                    LocalDate.of(part1, part2, part3)
                } else {
                    // dd-MM-yyyy format
                    LocalDate.of(part3, part2, part1)
                }
            } catch (_: Exception) { null }
        }

        // 6) Arabic month names ("أبريل 23, 2025" or "23 أبريل 2025")
        val arabicMonths = mapOf(
            "يناير" to 1, "فبراير" to 2, "مارس" to 3, "أبريل" to 4,
            "مايو" to 5, "يونيو" to 6, "يوليو" to 7, "أغسطس" to 8,
            "سبتمبر" to 9, "أكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12
        )

        // Pattern: "month day, year" or "day month year"
        val arabicDateRegex = """(\d{1,2})\s*([^\d\s,]+)\s*,?\s*(\d{4})|([^\d\s,]+)\s*(\d{1,2})\s*,?\s*(\d{4})""".toRegex()
        arabicDateRegex.find(trimmed)?.let { m ->
            try {
                val (day, month, year) = if (m.groupValues[1].isNotEmpty()) {
                    Triple(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3].toInt())
                } else {
                    Triple(m.groupValues[5].toInt(), m.groupValues[4], m.groupValues[6].toInt())
                }
                val monthNum = arabicMonths[month]
                if (monthNum != null) {
                    return LocalDate.of(year, monthNum, day)
                }
            } catch (_: Exception) { /* fall through */ }
        }

        // 7) English month formats ("April 22, 2025" or "22 April 2025")
        val formatters = listOf(
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        )

        for (formatter in formatters) {
            try {
                return LocalDate.parse(trimmed, formatter)
            } catch (_: DateTimeParseException) { /* try next */ }
        }

        // 8) ISO format fallback
        try {
            return LocalDate.parse(trimmed)
        } catch (_: DateTimeParseException) { /* fall through */ }

        return null
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val updates = mutableListOf<MangaItem>()
        val doc = Jsoup.parse(html)

        // Select all legend-card articles in the magma-grid
        for (card in doc.select(".magma-grid .legend-card")) {
            // 1. Get manga URL and title from the poster link or title link
            val posterLink = card.selectFirst("a.legend-poster") ?: continue
            val url = posterLink.attr("href").trim()

            // Title is in .legend-title a
            val titleEl = card.selectFirst(".legend-title a") ?: continue
            val title = titleEl.text().trim()

            // 2. Image URL
            val imageUrl = card.selectFirst(".legend-img")
                ?.attr("src")
                ?.trim()
                ?: continue

            // 3. Rating (e.g., "9.0" from .legend-rating)
            val ratingText = card.selectFirst(".legend-rating")?.text()
                ?.replace(Regex("[^0-9.]"), "") // Remove non-numeric chars
                ?.toFloatOrNull()
                ?.times(10)
                ?.toInt()
                ?: 0

            // 4. Chapters - from .legend-chapters .legend-ch-link
            // Skip locked chapters (those with data-coin="yes" or has lock icon)
            val chapters = card.select(".legend-chapters .legend-ch-link")
                .filter { chLink ->
                    // Skip if it's a locked chapter
                    chLink.attr("data-coin") != "yes"
                }
                .mapNotNull { chLink ->
                    val chapUrl = chLink.attr("href").trim()
                    if (chapUrl.isBlank()) return@mapNotNull null

                    val chapNum = chLink.selectFirst(".ch-txt")?.ownText()?.trim()
                        ?: chLink.selectFirst(".ch-txt")?.text()?.trim()
                        ?: return@mapNotNull null

                    val dateTxt = chLink.selectFirst(".ch-time")?.text()?.trim() ?: ""
                    val date = parseChapterDate(dateTxt) ?: LocalDate.now()

                    ChapterItem(
                        number = chapNum,
                        name = chapNum,
                        url = chapUrl,
                        date = date
                    )
                }

            updates += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = ratingText,
                chapters = chapters,
                genres = emptyList()
            )
        }

        return updates
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        val popularList = mutableListOf<PopularManga>()
        val doc: Document = Jsoup.parse(html)

        // Find all hot-card elements in the hot slider
        val hotCards = doc.select(".legendary-hot-section .hot-card")

        for (card in hotCards) {
            // Skip duplicate cards (the track duplicates items for infinite scroll)
            // We can use data-rank to detect unique items
            val rank = card.attr("data-rank").toIntOrNull() ?: continue

            // Get the poster link
            val linkElement = card.selectFirst("a.hot-poster") ?: continue
            val url = linkElement.attr("href").trim()

            // Skip if we already have this URL (handles duplicates)
            if (popularList.any { it.url == url }) continue

            // Get image from .hot-img background-image style
            val hotImgEl = card.selectFirst(".hot-img")
            val imageUrl = hotImgEl?.attr("style")
                ?.let { style ->
                    // Extract URL from: background-image: url('...');
                    Regex("""url\(['"]?([^'")\s]+)['"]?\)""")
                        .find(style)
                        ?.groupValues
                        ?.getOrNull(1)
                }
                ?: continue

            // Get title from .hot-title a
            val title = card.selectFirst(".hot-title a")?.text()?.trim()
                ?: continue

            popularList.add(
                PopularManga(
                    api = API,
                    language = LANGUAGE,
                    title = title,
                    url = url,
                    imageUrl = imageUrl
                )
            )
        }

        return popularList
    }


    override suspend fun extractMangaInfo(html: String, url: String): MangaInfo {
        val doc = Jsoup.parse(html)

        // 1. Title - from .lh-title
        val title = doc.selectFirst(".lh-title")?.text()?.trim() ?: ""

        // 2. Cover Image - from .lh-poster img
        val imageUrl = doc.selectFirst(".lh-poster img")?.attr("src")?.trim() ?: ""

        // 3. Alternative Title - check for any alt title (not present in this template)
        val altTitle = doc.selectFirst(".lh-alt-title")?.text()?.trim() ?: ""

        // 4. Status - from meta row item containing status icon
        val statusText = doc.selectFirst(".status-badge-lux")?.text()?.trim()
            ?: doc.select(".lh-meta-item").find {
                it.selectFirst("i.fa-info-circle") != null
            }?.text()?.trim()
            ?: ""

        val status = when {
            statusText.contains("مستمر", ignoreCase = true) ||
                    statusText.contains("ongoing", ignoreCase = true) -> "مستمر"
            statusText.contains("مكتمل", ignoreCase = true) ||
                    statusText.contains("completed", ignoreCase = true) -> "مكتمل"
            statusText.contains("متوقف", ignoreCase = true) ||
                    statusText.contains("hiatus", ignoreCase = true) -> "متوقف"
            statusText.contains("ملغي", ignoreCase = true) ||
                    statusText.contains("dropped", ignoreCase = true) ->"ملغي"
            else -> "غير معروف"
        }

        // 5. Author - This site doesn't display author info on the page
        // Check multiple possible locations
        val author = doc.selectFirst(".lh-author")?.text()?.trim()
            ?: doc.selectFirst(".author-name")?.text()?.trim()
            ?: doc.select(".lh-meta-item").find {
                it.text().contains("المؤلف") || it.text().contains("Author")
            }?.text()?.replace(Regex("المؤلف|Author|:"), "")?.trim()
            ?: ""

        // 6. Artist - Similar to author, not typically shown
        val artist = doc.selectFirst(".lh-artist")?.text()?.trim()
            ?: doc.selectFirst(".artist-name")?.text()?.trim()
            ?: ""

        // 7. Rating - from meta item with star icon
        val ratingText = doc.select(".lh-meta-item").find {
            it.selectFirst("i.fa-star") != null
        }?.text()?.replace(Regex("[^0-9.]"), "")?.trim()

        val rating = ratingText?.toFloatOrNull()?.times(10)?.toInt() ?: 0

        // 8. Description/Synopsis - from .lh-story-content or #manga-story
        val description = doc.selectFirst(".lh-story-content")?.text()?.trim()
            ?: doc.selectFirst("#manga-story")?.text()?.trim()
            ?: ""

        // 9. Genres - from .lh-genres .lh-genre-tag
        val genres = doc.select(".lh-genres .lh-genre-tag")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        // 10. Chapters - from .ch-list-grid .ch-item
        val chapters = doc.select(".ch-list-grid .ch-item").mapNotNull { chItem ->
            val chapterUrl = chItem.selectFirst("a.ch-main-anchor")?.attr("href")?.trim()
                ?: return@mapNotNull null

            val chapterNum = chItem.attr("data-ch").trim()
                .ifBlank {
                    chItem.selectFirst(".ch-num")?.text()
                        ?.replace(Regex("[^0-9.]"), "")?.trim()
                }
                ?: return@mapNotNull null

            val chapterName = chItem.selectFirst(".ch-num")?.text()?.trim() ?: "فصل $chapterNum"

            val dateText = chItem.selectFirst(".ch-date")?.text()?.trim() ?: ""
            val date = parseChapterDate(dateText) ?: LocalDate.now()

            // Check if chapter is locked
            val isLocked = chItem.attr("data-coin") == "yes" ||
                    chItem.hasClass("locked")

            if (isLocked) return@mapNotNull null
            ChapterItem(
                number = chapterNum,
                name = chapterName,
                url = chapterUrl,
                date = date,
            )
        }

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            title = title,
            imageUrl = imageUrl,
            status = status,
            author = author,
            artist = artist,
            rating = rating.toString(),
            description = description,
            genres = genres,
            chapters = chapters.toMutableList(),
            url = url,
            ratingCount = "",
            otherNames = "",
            tags = listOf(),
            yearOfProduction = "",
            favoritesCount =""
        )
    }
}