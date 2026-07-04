package me.manga.yamiapk.sources_repositry.it.mangaworld

import android.util.Log
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toPopularMangaList
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.NormalSitesv2
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.work.Logs.logLongText
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import javax.inject.Inject

class MangaworldItRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSitesv2(dataStore,api,sourcesRepository) {
    override val mangaSource: MangaSource
        get() = MangaSource.MANGAWORLD
    override val homeUrl: String by lazy { baseUrl.ifBlank { BASE_URL } }
    override val popularUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL }}archive?sort=most_read" }
    override var customParseHome: Boolean = true


    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()


    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}?page=$page"
    }


    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  ->  "${baseUrl.ifBlank { BASE_URL }}archive?keyword=${searchType.toNormalQuery()}"
            is SearchType.GENRES  -> "${baseUrl.ifBlank { BASE_URL }}archive?keyword=${searchType.query}&genre=${searchType.genres}"
            is SearchType.SORT    -> "${baseUrl.ifBlank { BASE_URL }}archive?keyword=${searchType.query}&genre=${searchType.genres}&sort=${searchType.sortType}"
        }
    override val sortTypes: Set<String>
        get() = setOf(
            "most_read",   // most read
            "less_read",   // less read (if your UI uses this label)
            "newest",      // newest first
            "oldest",      // oldest first
            "a-z",         // title ascending
            "z-a"          // title descending
        )

    /**
     * Genre slugs used by the site (use these for ?genre=<slug> requests)
     */
    override val allGenres: Set<String>
        get() = setOf(
//            "adulti",
            "arti-marziali",
            "avventura",
            "azione",
            "commedia",
            "doujinshi",
            "drammatico",
//            "ecchi",
            "fantasy",
            "gender-bender",
            "harem",
//            "hentai",
            "horror",
            "josei",
            "lolicon",
            "maturo",
            "mecha",
            "mistero",
            "psicologico",
            "romantico",
            "sci-fi",
            "scolastico",
            "seinen",
            "shotacon",
            "shoujo",
//            "shoujo-ai",
            "shounen",
//            "shounen-ai",
            "slice-of-life",
//            "smut",
            "soprannaturale",
            "sport",
            "storico",
            "tragico",
//            "yaoi",
//            "yuri"
        )
    override val blackListGenres: Set<String>
        get() = setOf(
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
    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc = Jsoup.parse(html)

        // Select all manga items (e.g. “Ultimi capitoli aggiunti”)
        return doc.select("div.comics-grid div.entry").mapNotNull { entry ->
            try {
                val thumb = entry.selectFirst("a.thumb")
                val img = thumb?.selectFirst("img")
                val url = thumb?.attr("href")?.trim().orEmpty()
                val imageUrl = img?.attr("src")?.trim().orEmpty()

                val titleLink = entry.selectFirst("a.manga-title")
                val title = titleLink?.text()?.trim().orEmpty()

                // Get genre (like “Tipo: Manga”) and status (“In corso”)
                val genre = entry.select("div.genre a").firstOrNull()?.text()?.trim().orEmpty()
                val status = entry.select("div.status a").firstOrNull()?.text()?.trim().orEmpty()

                // Extract last few chapters
                val chapters = entry.select("div.d-flex.flex-wrap.flex-row a.xanh").map { chapterLink ->
                    val chapterName = chapterLink.text().trim()
                    val chapterUrl = chapterLink.attr("href").trim()
                    ChapterItem(
                        name = chapterName,
                        number = chapterName,
                        url = "${chapterUrl}?style=list"
                    )
                }

                MangaItem(
                    title = title,
                    imageUrl = imageUrl,
                    url = url,
                    api = API,
                    language = LANGUAGE,
                    rating = 0,
                    genres = if (genre.isNotEmpty()) listOf(genre) else emptyList(),
                    chapters = chapters
                )
            } catch (e: Exception) {
                logLongText("Error parsing manga entry", e.message ?: "unknown")
                null
            }
        }.toMutableList()
    }
    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        return extractCustomHomeMangaItems(html)

    }

    override fun extractMangaList(html: String): List<PopularManga> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<PopularManga>()

        // Each manga card is inside <div class="entry">
        val blocks = doc.select("div.entry")

        for (el in blocks) {
            val linkEl = el.selectFirst("a.thumb") ?: continue
            val url = linkEl.attr("href").orEmpty()

            // Image
            val img = linkEl.selectFirst("img")?.attr("abs:src").orEmpty()

            // Title
            val title = el.selectFirst("a.manga-title")?.text()?.trim().orEmpty()

            if (url.isNotEmpty() && title.isNotEmpty()) {
                items += PopularManga(
                    api = API,
                    language = LANGUAGE,
                    title = title,
                    url = url,
                    imageUrl = img
                )
            }
        }

        return items
    }

    override suspend fun extractMangaInfo(
        html: String,
        baseUrl: String
    ): MangaInfo {
        val doc = Jsoup.parse(html)


        // Extract title
        val title = doc.selectFirst("div.comic-info h1.name.bigger")?.text()?.trim().orEmpty()

        // Extract thumbnail
        val thumbnailUrl = doc.selectFirst("div.comic-info div.thumb img")?.attr("src").orEmpty()

        // Extract description
        val description = doc.selectFirst("div.comic-description #noidungm")?.text()?.trim().orEmpty()

        // Extract alternative titles
        val altTitles = doc.selectFirst("div.meta-data .col-12:has(span:contains(Titoli alternativi))")
            ?.text()
            ?.replace("Titoli alternativi:", "")
            ?.trim()
            .orEmpty()

        // Extract genres
        val genres = doc.select("div.meta-data .col-12:has(span:contains(Generi)) a.badge")
            .map { it.text().trim() }
            .toMutableList()

        // Extract type (Manga, Manhwa, etc.)
        val type = doc.selectFirst("div.meta-data .col-12:has(span:contains(Tipo)) a")
            ?.text()?.trim()
        type?.let { if (it.isNotBlank()) genres.add(it) }

        // Extract status
        val rawStatus = doc.selectFirst("div.meta-data .col-12:has(span:contains(Stato)) a")
            ?.text()?.trim().orEmpty()
        val status = when {
            rawStatus.contains("corso", ignoreCase = true) -> "Corso"
            rawStatus.contains("finito", ignoreCase = true) ||
                    rawStatus.contains("completato", ignoreCase = true) -> "Completato"
            rawStatus.isBlank() -> "Unknown"
            else -> rawStatus
        }

        // Extract author
        val author = doc.selectFirst("div.meta-data .col-12:has(span:contains(Autore)) a")
            ?.text()?.trim().orEmpty()

        // Extract artist
        val artist = doc.selectFirst("div.meta-data .col-12:has(span:contains(Artista)) a")
            ?.text()?.trim().orEmpty()

        // Extract year
        val year = doc.selectFirst("div.meta-data .col-12:has(span:contains(Anno di uscita)) a")
            ?.text()?.trim().orEmpty()

        // Extract views count (using as rating count for now)
        val views = doc.selectFirst("div.meta-data .col-12:has(span:contains(Visualizzazioni)) span:not(.font-weight-bold)")
            ?.text()?.trim().orEmpty()

        // Process each volume
        // Extract chapters
        val chapters = mutableListOf<ChapterItem>()

// First: try MangaWorld layout (no volumes)
        val chapterElements = doc.select("div.chapters-wrapper div.chapter a.chap")

        if (chapterElements.isNotEmpty()) {
            chapterElements.forEach { chapterLink ->
                val chapterName = chapterLink.selectFirst("span.d-inline-block")?.text()?.trim().orEmpty()
                val chapterUrl = chapterLink.attr("href")
                val dateText = chapterLink.selectFirst("i.chap-date")?.text()?.trim().orEmpty()

                val chapterNumber = chapterName.replace(Regex("[^\\d.]"), "").trim()
                val parsed = try {
                    parseItalianDateText(dateText)
                } catch (e: Exception) {
                    LocalDate.now()
                }

                chapters.add(
                    ChapterItem(
                        number = chapterNumber,
                        name = chapterName,
                        url = "${chapterUrl}?style=list",
                        date = parsed
                    )
                )
            }
        } else {
            // Fallback: layout with volumes
            doc.select("div.volume-element").forEach { volumeElement ->

                volumeElement.select("div.chapter a.chap").forEach { chapterLink ->
                    val chapterName = chapterLink.selectFirst("span.d-inline-block")?.text()?.trim().orEmpty()
                    val chapterUrl = chapterLink.attr("href")
                    val dateText = chapterLink.selectFirst("i.chap-date")?.text()?.trim().orEmpty()

                    val chapterNumber = chapterName.replace(Regex("[^\\d.]"), "").trim()
                    val parsed = try {
                        parseItalianDateText(dateText)
                    } catch (e: Exception) {
                        LocalDate.now()
                    }

                    chapters.add(
                        ChapterItem(
                            number = chapterNumber,
                            name = chapterName,
                            url = "${chapterUrl}?style=list",
                            date = parsed
                        )
                    )
                }
            }
        }


        val fullDescription = if (altTitles.isNotBlank()) {
            "$description\n\nAlternative Titles: $altTitles"
        } else {
            description
        }

        return MangaInfo(
            title = title,
            imageUrl = thumbnailUrl,
            rating = "", // Not available in this HTML
            ratingCount = views,
            description = fullDescription,
            otherNames = altTitles,
            author = author,
            artist = artist,
            genres = genres,
            tags = emptyList(),
            yearOfProduction = year,
            status = status,
            favoritesCount = "",
            chapters = chapters,
            api = API,
            url = baseUrl,
            language = LANGUAGE
        )
    }

    // Helper function to parse Italian dates
    private fun parseItalianDateText(dateText: String): LocalDate {
        val months = mapOf(
            "Gennaio" to 1, "Febbraio" to 2, "Marzo" to 3, "Aprile" to 4, "Maggio" to 5,
            "Giugno" to 6, "Luglio" to 7, "Agosto" to 8, "Settembre" to 9,
            "Ottobre" to 10, "Novembre" to 11, "Dicembre" to 12
        )
        val regex = Regex("(\\d{1,2})\\s+([A-Za-z]+)\\s+(\\d{4})")
        val match = regex.find(dateText) ?: return LocalDate.now()
        val (day, monthName, year) = match.destructured
        val month = months[monthName.capitalize()] ?: return LocalDate.now()
        return LocalDate.of(year.toInt(), month, day.toInt())
    }
    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)

        return doc.select("div.comics-grid div.entry").mapNotNull { entry ->
            try {
                val thumb = entry.selectFirst("a.thumb")
                val img = thumb?.selectFirst("img")
                val url = thumb?.attr("href")?.trim().orEmpty()
                val imageUrl = img?.attr("src")?.trim().orEmpty()

                val titleLink = entry.selectFirst("a.manga-title")
                val title = titleLink?.text()?.trim().orEmpty()

                // Get genre (like “Tipo: Manga”) and status (“In corso”)
                val genre = entry.select("div.genre a").firstOrNull()?.text()?.trim().orEmpty()

                // Extract last few chapters


                MangaItem(
                    title = title,
                    imageUrl = imageUrl,
                    url = url,
                    api = API,
                    language = LANGUAGE,
                    rating = 0,
                    genres = if (genre.isNotEmpty()) listOf(genre) else emptyList(),
                    chapters = emptyList()
                )
            } catch (e: Exception) {
                logLongText("Error parsing manga entry", e.message ?: "unknown")
                null
            }
        }.toMutableList()
    }


    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

    }
    fun extractTotalPages(doc: Document): Int {
        val select = doc.selectFirst("select.page.custom-select") ?: return 0

        // 1) Try the selected option text like "1/15"
        val selectedText = select.selectFirst("option[selected]")?.text()
        if (selectedText != null) {
            Regex("/(\\d+)").find(selectedText)?.let { return it.groupValues[1].toInt() }
        }

        // 2) Try first option text (sometimes shows "1/15")
        val firstText = select.selectFirst("option")?.text()
        if (firstText != null) {
            Regex("/(\\d+)").find(firstText)?.let { return it.groupValues[1].toInt() }
        }

        // 3) Try last option text (sometimes last option is "15/15" or "15")
        val lastText = select.select("option").last()?.text()
        if (lastText != null) {
            // prefer form "x/y" -> get y, otherwise take last integer in the text
            Regex("/(\\d+)").find(lastText)?.let { return it.groupValues[1].toInt() }
            Regex("(\\d+)").findAll(lastText).lastOrNull()?.let { return it.value.toInt() }
        }

        // 4) Fallback: number of options
        return select.select("option").size
    }

    override fun getChapterImages(html: String): List<String> {
        val doc = Jsoup.parse(html)

        try {
            // Find the script tag containing $MC data
            val scriptContent = doc.select("script").firstOrNull { script ->
                script.html().contains("\$MC=")
            }?.html()

            if (scriptContent != null) {
                // Extract the chapter data which contains pages array
                val pagesRegex = """"pages":\[(.*?)\]""".toRegex()
                val match = pagesRegex.find(scriptContent)

                if (match != null) {
                    val pagesJson = match.groupValues[1]
                    // Parse the page filenames (they include extensions: "1.jpg","2.png",...)
                    val pageFiles = pagesJson.split(",")
                        .map { it.trim().removeSurrounding("\"") }
                        .filter { it.isNotBlank() }

                    // Extract the CDN_URL
                    val cdnUrlRegex = """"CDN_URL":"([^"]+)"""".toRegex()
                    val cdnUrl = cdnUrlRegex.find(scriptContent)?.groupValues?.get(1)
                        ?: "https://cdn.mangaworld.cx"

                    // Extract manga slugFolder and _id
                    val mangaDataRegex = """"manga":\{[^}]*"_id":"([^"]+)"[^}]*"slugFolder":"([^"]+)"""".toRegex()
                    val mangaMatch = mangaDataRegex.find(scriptContent)
                    val mangaId = mangaMatch?.groupValues?.get(1)
                    val mangaSlug = mangaMatch?.groupValues?.get(2)

                    // Extract volume slugFolder and _id
                    val volumeDataRegex = """"volume":\{[^}]*"_id":"([^"]+)"[^}]*"slugFolder":"([^"]+)"""".toRegex()
                    val volumeMatch = volumeDataRegex.find(scriptContent)
                    val volumeId = volumeMatch?.groupValues?.get(1)
                    val volumeSlug = volumeMatch?.groupValues?.get(2)

                    // Extract chapter slugFolder and _id from the chapter object
                    val chapterDataRegex = """"chapter":\{[^}]*"_id":"([^"]+)"[^}]*"slugFolder":"([^"]+)"""".toRegex()
                    val chapterMatch = chapterDataRegex.find(scriptContent)
                    val chapterId = chapterMatch?.groupValues?.get(1)
                    val chapterSlug = chapterMatch?.groupValues?.get(2)

                    if (mangaSlug != null && volumeSlug != null && chapterSlug != null &&
                        mangaId != null && volumeId != null && chapterId != null) {

                        // Build the base URL following the pattern from the HTML
                        val baseUrl = "$cdnUrl/chapters/$mangaSlug-$mangaId/$volumeSlug-$volumeId/$chapterSlug-$chapterId/"

                        // Build complete image URLs
                        return pageFiles.map { filename ->
                            "$baseUrl$filename"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChapterImages", "Error parsing chapter data", e)
        }

        // Fallback: try to extract images directly from img tags
        val images = doc.select("div#page img.page-image")
            .mapNotNull { it.attr("src").takeIf { url -> url.isNotBlank() } }

        if (images.isNotEmpty()) {
            return images
        }

        return emptyList()
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

