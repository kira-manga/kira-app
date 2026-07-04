//package me.manga.yamiapk.sources_repositry.en.readcomiconline
//
//import android.util.Log
//import androidx.compose.ui.graphics.colorspace.Illuminant.A
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import me.manga.yamiapk.core.storage.DataStoreHelper
//import me.manga.yamiapk.data.local.dao.SourcesDao
//import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
//import me.manga.yamiapk.domain.model.ChapterItem
//import me.manga.yamiapk.domain.model.MangaInfo
//import me.manga.yamiapk.domain.model.MangaItem
//import me.manga.yamiapk.domain.model.PopularManga
//import me.manga.yamiapk.presentation.features.home.data.SearchType
//import me.manga.yamiapk.sources_repositry.ar.user_agents.UserAgents
//import me.manga.yamiapk.sources_repositry.common.NormalSitesv2
//import me.manga.yamiapk.sources_repositry.data.MangaSource
//import okhttp3.FormBody
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import org.jsoup.Jsoup
//import org.jsoup.nodes.Document
//import java.net.URI
//import java.net.URLDecoder
//import java.time.LocalDate
//import java.time.format.DateTimeFormatter
//import java.time.format.DateTimeParseException
//import java.util.Base64
//import java.util.regex.Pattern
//import javax.inject.Inject
//
//class ReadComicOnlineRepository @Inject constructor(
//    private val dataStore: DataStoreHelper,
//    private val api: IMangaDataApiServices,
//    sourcesRepository: SourcesDao,
//): NormalSitesv2(dataStore, api, sourcesRepository) {
//
//
//
//
//    override val mangaSource: MangaSource
//        get() = MangaSource.READCOMICONLINE
//
//    override val BASE_URL: String
//        get() = "https://readcomiconline.li/"
//
//    override val API: String
//        get() = mangaSource.API
//
//    override val LANGUAGE: String
//        get() = mangaSource.LANGUAGE.Language
//
//    override val homeUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL }}ComicList/LatestUpdate"  }
//    override val popularUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL }}ComicList/MostPopular"  }
//
//    override val sortTypes: Set<String>
//        get() = setOf("Latest Update", "Newest", "Most Popular")
//
//    override val allGenres: Set<String>
//        get() = setOf("Action", "Adventure", "Superhero", "Fantasy", "Sci-Fi", "Horror", "Mystery", "Crime")
//
//    override val blackListGenres: Set<String>
//        get() = setOf()
//
//    override var imgBaseUrl: String = BASE_URL
//    override var imgUrlVersion: Int = 0
//    override suspend fun initSite(): Int {
//        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
//        _cachedHeaders = headers
//        return super.initSite()
//    }
//
//
//
//
//    override var customParseHome: Boolean = true
//    override var useGetForHome: Boolean = true
//    override var useGetForPopular: Boolean = true
//    override var useGetForSearch: Boolean = true
//    override var useGetForNormalSearch: Boolean = true
//
//    @Volatile
//    private var _cachedHeaders: Map<String, String>? = null
//
//    override val defaultHeaders: Map<String, String>
//        get() {
//            val cached = _cachedHeaders ?: emptyMap()
//            // cached + required -> required values override cached when key conflict
//            return cached + requiredHeaders()
//        }
//    override fun handelFormBodyHome(page: Int, popular: Boolean): FormBody? = null
//
//    override fun handelFormBodyPopular(page: Int, popular: Boolean): FormBody? = null
//
//    override fun handelLoadMoreUrl(page: Int): String {
//        return  "${baseUrl.ifBlank { BASE_URL }}ComicList/LatestUpdate?page=$page"
//
//    }
//
//    private fun requiredHeaders(): Map<String, String> {
//        val referer = baseUrl.ifBlank { BASE_URL } // or a specific referer URL you want
//        return mapOf(
//            "User-Agent" to UserAgents.desktop.random(),
//            "Referer" to referer
//        )
//    }
//
//
//    override fun handelSearchUrl(searchType: SearchType): String {
//        return "${baseUrl.ifBlank { BASE_URL }}AdvanceSearch?comicName=${searchType.toNormalQuery()}"
//    }
//
//    override fun handelFormBody(page: Int, popular: Boolean): FormBody? = null
//
//    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? =null
//    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? = null
//
//    override fun sortFormBody(searchType: SearchType.SORT): FormBody? = null
//
//    override fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem> {
//        val doc = Jsoup.parse(string)
//        val items = mutableListOf<MangaItem>()
//
//        // Check for mobile version structure first
//        val mobileItems = doc.select("div.item-list div.section.group.list")
//
//        if (mobileItems.isNotEmpty()) {
//            // Mobile version structure
//            for (element in mobileItems) {
//                val coverLink = element.selectFirst("div.col.cover a")
//                val img = element.selectFirst("div.col.cover img")
//                val infoLink = element.selectFirst("div.col.info p a")
//                val issueInfo = element.selectFirst("div.col.info p:nth-child(2)")
//
//                Log.i("asdkasljdklasdjasdda1",img.toString())
//                if (coverLink != null && img != null && infoLink != null) {
//                    val url = coverLink.attr("href")
//                    val imageUrl = img.attr("src")
//                    val title = infoLink.text().trim()
//                    val issue = issueInfo?.text()?.trim() ?: ""
//
//                    // Extract chapter info from issue text
//
//
//                    items.add(MangaItem(
//                        api = API,
//                        language = LANGUAGE,
//                        title = title,
//                        url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
//                        imageUrl = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$imageUrl",
//                        rating = 0,
//                        chapters = emptyList(),
//                        genres = emptyList()
//                    ))
//                }
//            }
//        } else {
//            // Desktop version - grid layout
//            val desktopItems = doc.select("div.list-comic div.item")
//
//            for (element in desktopItems) {
//                val linkElement = element.selectFirst("a")
//                val img = element.selectFirst("img")
//                val titleSpan = element.selectFirst("span.title")
//                val hotLabel = element.selectFirst("a.hot-label")
//                val episodeLink = element.selectFirst("div.ep-bg a")
//                Log.i("asdkasljdklasdjasdda2",img.toString())
//
//                if (linkElement != null && img != null && titleSpan != null) {
//                    val url = linkElement.attr("href")
//                    val imageUrl = img.attr("src")
//                    val title = titleSpan.text().trim()
//
//                    // Extract chapter info if available
//                    val chapters = mutableListOf<ChapterItem>()
//                    if (episodeLink != null) {
//                        val chapterName = episodeLink.text().trim()
//                        val chapterUrl = episodeLink.attr("href")
//                        val chapterNumber = extractChapterNumber(chapterName)
//
//                        chapters.add(ChapterItem(
//                            name = chapterName,
//                            number = chapterNumber,
//                            url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$chapterUrl",
//                            date = null
//                        ))
//                    }
//
//                    items.add(MangaItem(
//                        api = API,
//                        language = LANGUAGE,
//                        title = title,
//                        url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
//                        imageUrl = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$imageUrl",
//                        rating = if (hotLabel != null) 5 else 0,
//                        chapters = chapters,
//                        genres = emptyList()
//                    ))
//                }
//            }
//        }
//        Log.i("safkdjfsdfsfdasfsadfasdf2",items.toString())
//
//        return items
//    }
//    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
//        return extractCustomHomeMangaItems(html)
//    }
//
//    override fun extractMangaList(html: String): List<PopularManga> {
//        val doc = Jsoup.parse(html)
//        val items = mutableListOf<PopularManga>()
//
//        // Check for mobile version structure first
//        val mobileItems = doc.select("div.item-list div.section.group.list")
//
//        if (mobileItems.isNotEmpty()) {
//            // Mobile version structure
//            for (element in mobileItems) {
//                val coverLink = element.selectFirst("div.col.cover a")
//                val img = element.selectFirst("div.col.cover img")
//                val infoLink = element.selectFirst("div.col.info p a")
//                Log.i("asdkasljdklasdjasdda3",img.toString())
//
//                if (coverLink != null && img != null && infoLink != null) {
//                    val url = coverLink.attr("href")
//                    val rawImage = img.attr("src").ifBlank { img.attr("data-src") }
//                    val imageUrl = resolveImageUrl(rawImage)
//                    val title = infoLink.text().trim()
//
//                    items.add(PopularManga(
//                        api = API,
//                        language = LANGUAGE,
//                        title = title,
//                        url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
//                        imageUrl =imageUrl
//                    ))
//                }
//            }
//        } else {
//            // Desktop version - grid layout
//            val desktopItems = doc.select("div.list-comic div.item")
//
//            for (element in desktopItems) {
//                val linkElement = element.selectFirst("a")
//                val img = element.selectFirst("img")
//                val titleSpan = element.selectFirst("span.title")
//                Log.i("asdkasljdklasdjasdda4",img.toString())
//
//                if (linkElement != null && img != null && titleSpan != null) {
//                    val url = linkElement.attr("href")
//                    val rawImage = img.attr("src").ifBlank { img.attr("data-src") }
//                    val imageUrl = resolveImageUrl(rawImage)
//                    val title = titleSpan.text().trim()
//
//                    items.add(PopularManga(
//                        api = API,
//                        language = LANGUAGE,
//                        title = title,
//                        url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
//                        imageUrl = imageUrl
//                    ))
//                }
//            }
//        }
//
//        Log.i("safkdjfsdfsfdasfsadfasdf1",items.toString())
//        return items
//    }
//    // Helper: normalize/resolve image URL
//    private fun resolveImageUrl(rawSrc: String): String {
//        val src = rawSrc.trim()
//        if (src.isEmpty()) return "" // caller should handle empty
//
//        // Absolute URLs (keep as-is)
//        if (src.startsWith("http://") || src.startsWith("https://")) return src
//
//        // Protocol-relative (e.g. //2.bp.blogspot.com/...)
//        if (src.startsWith("//")) {
//            return "https:$src" // prefer https
//        }
//
//        // Otherwise treat as relative and prefix base
//        val base = baseUrl.ifBlank { BASE_URL }.trimEnd('/') // remove trailing slash
//        val cleanedSrc = src.trimStart('/') // remove leading slash to avoid // when concatenating
//        return "$base/$cleanedSrc"
//    }
//
//    override suspend fun extractMangaInfo(html: String, baseUrli: String): MangaInfo {
//        val document = Jsoup.parse(html)
//
//        Log.i("asdasjkhdasdasdasdasdasda", document.toString())
//        val base = if (baseUrli.isBlank()) BASE_URL else baseUrli.removeSuffix("/")
//
//        // --- Title ---
//        val title = document.selectFirst("a.bigChar")?.text()
//            ?: document.selectFirst("title")?.text()?.trim()
//            ?: ""
//
//        // --- Cover / thumbnail ---
//        val coverImg = run {
//            // try a few robust selectors in order
//            val selectors = listOf(
//                // explicit "Cover" rightBox
//                "div.rightBox:has(.barTitle:matchesOwn((?i)Cover)) img",
//                // fallback to head link rel=image_src
//                "link[rel=image_src]",
//                // generic uploads img
//                "img[src~=(?i)/Uploads/.+]"
//            )
//
//            var src: String? = null
//            for (sel in selectors) {
//                val el = document.selectFirst(sel) ?: continue
//                src = when (el.tagName().lowercase()) {
//                    "link" -> el.attr("href")
//                    else -> el.attr("src").ifBlank { el.attr("data-src") } // also check data-src if lazyloaded
//                }
//                if (!src.isNullOrBlank()) break
//            }
//            src ?: ""
//        }.let { src ->
//            fun resolveUrl(base: String, srcIn: String): String {
//                val s = srcIn.trim()
//                if (s.isEmpty()) return ""
//                if (s.startsWith("http", ignoreCase = true)) return s
//                if (s.startsWith("//")) return "https:$s" // protocol-relative -> assume https
//                // ensure base has scheme and trailing slash for URI.resolve
//                val baseForResolve = if (base.isBlank()) BASE_URL else base
//                val baseWithSlash = if (baseForResolve.endsWith("/")) baseForResolve else "$baseForResolve/"
//                return try {
//                    URI(baseWithSlash).resolve(s).toString()
//                } catch (_: Exception) {
//                    // last-resort manual join
//                    when {
//                        s.startsWith("/") -> baseWithSlash.removeSuffix("/") + s
//                        else -> baseWithSlash + s.removePrefix("./")
//
//                    }
//                }
//            }
//
//            resolveUrl(base, src)
//        }
//        // --- Locate the main info container (where bigChar sits) ---
//        val infoContainer = document.selectFirst("a.bigChar")?.closest(".barContent")
//            ?: document.selectFirst(".barContent")
//
//        // --- Genres ---
//        val genres = infoContainer
//            ?.select("a[href*=/Genre/]")
//            ?.map { it.text().trim() }
//            ?.filter { it.isNotEmpty() }
//            ?: emptyList()
//
//        // --- Publisher ---
//        val publisher = infoContainer
//            ?.selectFirst("p:has(span.info:matchesOwn((?i)Publisher)) a")
//            ?.text()
//            ?: ""
//
//        // --- Writer ---
//        val writer = infoContainer
//            ?.selectFirst("p:has(span.info:matchesOwn((?i)Writer)) a")
//            ?.text()
//            ?: ""
//
//        // --- Artist ---
//        val artist = infoContainer
//            ?.selectFirst("p:has(span.info:matchesOwn((?i)Artist)) a")
//            ?.text()
//            ?: ""
//
//        // --- Publication Date ---
//        val publicationDateText = infoContainer
//            ?.selectFirst("p:has(span.info:matchesOwn((?i)Publication date))")
//            ?.ownText()
//            ?.trim()
//            ?: ""
//
//        // --- Status and Views ---
//        val statusParagraph = infoContainer
//            ?.selectFirst("p:has(span.info:matchesOwn((?i)Status))")
//            ?.text() ?: ""
//
//        val status = when {
//            statusParagraph.contains("Ongoing", ignoreCase = true) -> "Ongoing"
//            statusParagraph.contains("Completed", ignoreCase = true) -> "Completed"
//            else -> "Ongoing"
//        }
//
//        val views = Regex("Views:\\s*([0-9,]+)").find(statusParagraph)
//            ?.groups?.get(1)?.value?.replace(",", "") ?: ""
//
//        // --- Description / Summary ---
//        val description = run {
//            val summaryP = infoContainer?.selectFirst("p:has(span:contains(Summary:)) ~ p")
//            // The summary content is in a nested <p> tag
//            val summaryContent = summaryP?.selectFirst("p")?.text()?.trim()
//            summaryContent ?: ""
//        }
//
//        // --- Year of production ---
//        val yearOfProduction = Regex("\\b(20\\d{2}|19\\d{2})\\b")
//            .find(publicationDateText)?.value ?: ""
//
//        // --- Chapters extraction from table.listing ---
//        val chapterRows = document.select("table.listing tr:has(a)")
//
//        val chapters = chapterRows.mapNotNull { row ->
//            val chapterLink = row.selectFirst("a") ?: return@mapNotNull null
//            val dateCell = row.select("td").getOrNull(1)?.text()?.trim()
//
//            val chapterName = chapterLink.text().trim()
//            val href = chapterLink.attr("href").trim()
//            Log.i("asjdhasdasjhdasjkdasdasd1",href)
//
//            val fullUrl = when {
//                href.startsWith("http") -> href
//                href.startsWith("/") -> "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$href&readType=1"
//                else -> "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$href&readType=1"
//            }
//
//            Log.i("asjdhasdasjhdasjkdasdasd2",fullUrl)
//            ChapterItem(
//                name = chapterName,
//                number = extractChapterNumber(chapterName),
//                url = fullUrl,
//                date = parseDate(dateCell)
//            )
//        }
//
//        return MangaInfo(
//            title = title,
//            imageUrl = coverImg,
//            rating = "",
//            ratingCount = "",
//            description = description,
//            otherNames = "",
//            author = writer,
//            artist = artist,
//            genres = genres,
//            tags = emptyList(),
//            yearOfProduction = yearOfProduction,
//            status = status,
//            favoritesCount = "",
//            chapters = chapters.toMutableList(),
//            api = API,
//            url = baseUrli,
//            language = LANGUAGE
//        )
//    }
//    override suspend fun getSearchResults(html: String): List<MangaItem> {
//        val doc = Jsoup.parse(html)
//        val items = mutableListOf<MangaItem>()
//
//        // Extract search results - uses grid layout
//        val comicItems = doc.select("div.list-comic div.item")
//
//        for (element in comicItems) {
//            val linkElement = element.selectFirst("a")
//            val img = element.selectFirst("img")
//            val titleSpan = element.selectFirst("span.title")
//            val episodeLink = element.selectFirst("div.ep-bg a")
//
//            if (linkElement != null && img != null && titleSpan != null) {
//                val url = linkElement.attr("href")
//                val rawImage = img.attr("src").ifBlank { img.attr("data-src") }
//                val imageUrl = resolveImageUrl(rawImage)
//                val title = titleSpan.text().trim()
//
//                // Extract chapter info if available
//                val chapters = mutableListOf<ChapterItem>()
//                if (episodeLink != null) {
//                    val chapterName = episodeLink.text().trim()
//                    val chapterUrl = episodeLink.attr("href")
//                    val chapterNumber = extractChapterNumber(chapterName)
//
//                    chapters.add(ChapterItem(
//                        name = chapterName,
//                        number = chapterNumber,
//                        url = resolveImageUrl(chapterUrl), // Use resolveImageUrl for consistency
//                        date = null
//                    ))
//                }
//
//                items.add(MangaItem(
//                    api = API,
//                    language = LANGUAGE,
//                    title = title,
//                    url = resolveImageUrl(url), // Use resolveImageUrl for consistency
//                    imageUrl = imageUrl,
//                    rating = 0,
//                    chapters = chapters,
//                    genres = emptyList()
//                ))
//            }
//        }
//
//        return items
//    }
//    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
//        // Merge new headers with required headers to ensure User-Agent/Referer are always present
//        val merged = (newHeaders ?: emptyMap()) + requiredHeaders()
//        _cachedHeaders = merged
//        dataStore.saveHeadersForApi(API, merged)
//    }
//
//    override fun getChapterImages(html: String): List<String> {
//        val doc = Jsoup.parse(html)
////        logLongText("SDDSFSDFASKHFLSDSDGFDGDGD", doc.toString())
//        val imageUrls = mutableListOf<String>()
//
//        try {
//            // Extract all image URLs pushed to _LCDEi7H array
//            // Pattern matches: _LCDEi7H.push('url')
//            val pushPattern = Regex("""_LCDEi7H\.push\(['"]([^'"]+)['"]\)""")
//            val matches = pushPattern.findAll(doc.html())
//
//            for (match in matches) {
//                val encodedUrl = match.groupValues[1]
//
//                // Decode the obfuscated URL
//                val decodedUrl = decodeImageUrl(encodedUrl)
//
//                // Only add if it's a valid URL
//                if (decodedUrl.startsWith("http")) {
//                    imageUrls.add(decodedUrl)
//                }
//            }
//
//            // If no URLs found with push pattern, try alternative arrays
//            if (imageUrls.isEmpty()) {
//                Log.w(TAG, "No URLs found in _LCDEi7H, trying _fkWqQff array")
//
//                // Try _fkWqQff array
//                val altPattern = Regex("""_fkWqQff\.push\(['"]([^'"]+)['"]\)""")
//                val altMatches = altPattern.findAll(doc.html())
//
//                for (match in altMatches) {
//                    val encodedUrl = match.groupValues[1]
//                    val decodedUrl = decodeImageUrl(encodedUrl)
//
//                    if (decodedUrl.startsWith("http")) {
//                        imageUrls.add(decodedUrl)
//                    }
//                }
//            }
//
//            // If still no URLs found, try direct blogspot URLs
//            if (imageUrls.isEmpty()) {
//                Log.w(TAG, "No URLs found in JavaScript arrays, trying direct extraction")
//
//                // Look for any blogspot URLs in the HTML
//                val urlPattern = Regex("""https://\d+\.bp\.blogspot\.com/[^\s'"<>]+""")
//                val directMatches = urlPattern.findAll(doc.html())
//
//                for (match in directMatches) {
//                    val url = match.value
//                    // Clean up URL - remove any trailing characters
//                    val cleanUrl = url.replace(Regex("""['"<>].*$"""), "")
//                    imageUrls.add(cleanUrl)
//                }
//            }
//
//            Log.i(TAG, "Extracted ${imageUrls.size} chapter images")
//
//            imageUrls.forEach {
//                Log.i(TAG, "Image URL: $it")
//            }
//
//            // Remove duplicates while preserving order
//            return imageUrls.distinct()
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error extracting chapter images", e)
//            return emptyList()
//        }
//    }
//
//    /**
//     * Decodes the obfuscated image URLs from ReadComicOnline.
//     * The site uses "4k__GN1ue5_" as a placeholder for 'a' character.
//     * Based on the JavaScript function: fqGx8sfQfoZ
//     */
//    private fun decodeImageUrl(encodedUrl: String): String {
//        // The site's JavaScript does these replacements:
//        // l = l.replace(/4k__GN1ue5_/g, 'a');
//        // l = l.replace(/b/g, 'pw_.g28x');
//        // l = l.replace(/h/g, 'd2pr.x_27');
//
//        // We need to do the REVERSE to decode:
//        // Replace the obfuscation token '4k__GN1ue5_' with 'a'
//        var decoded = encodedUrl.replace("4k__GN1ue5_", "a")
//
//        // Note: The 'b' and 'h' replacements are encoding steps in JavaScript,
//        // but in the HTML source, the URLs already have '4k__GN1ue5_' which needs
//        // to be replaced with 'a'. We don't need to reverse the b/h replacements
//        // because those are applied during encoding, not in the stored URLs.
//
//        return decoded
//    }
//
//    private fun extractChapterNumber(chapterName: String): String {
//        // Extract number from formats like "Issue #123" or "Chapter 123" or "#123"
//        val regex = """(?:Issue|Chapter|#)\s*#?(\d+(?:\.\d+)?)""".toRegex(RegexOption.IGNORE_CASE)
//        return regex.find(chapterName)?.groupValues?.get(1) ?: chapterName
//    }
//
//    private fun parseDate(dateStr: String?): LocalDate? {
//        if (dateStr.isNullOrBlank()) return null
//
//        return try {
//            // ReadComicOnline uses format: "10/4/2025"
//            LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("M/d/yyyy"))
//        } catch (e: DateTimeParseException) {
//            try {
//                // Alternative format: "10/04/2025"
//                LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("MM/dd/yyyy"))
//            } catch (e2: DateTimeParseException) {
//                null
//            }
//        }
//    }
//
//    companion object {
//        private const val TAG = "ReadComicOnlineRepository"
//    }
//}