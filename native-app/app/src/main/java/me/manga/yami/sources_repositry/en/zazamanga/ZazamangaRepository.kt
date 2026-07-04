package me.manga.yamiapk.sources_repositry.en.zazamanga

import android.util.Log
import androidx.compose.ui.text.toLowerCase
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
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

class ZazamangaRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSitesv2(dataStore,api,sourcesRepository) {
    override val mangaSource: MangaSource
        get() = MangaSource.ZAZAMANGA
    override val homeUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL }}manga?orderby=latest" }
    override val popularUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL }}manga?orderby=views" }
    override var customParseHome: Boolean = false


    override var imgBaseUrl: String = "https://img-r1.2xstorage.com/"
    override var imgUrlVersion: Int = 0

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

    private val refererHeader = "Referer" to "https://www.zazamanga.com/"

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}manga?orderby=latest&page=${page}"
    }


    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  ->  "${baseUrl.ifBlank { BASE_URL }}?s=${searchType.toNormalQuery()}&post_type=wp-manga"
            is SearchType.GENRES  -> "${baseUrl.ifBlank { BASE_URL }}?s=${searchType.query}&post_type=wp-manga&genre%5B%5D=${searchType.genres}&op=&author=&artist=&release=&adult=0"
            is SearchType.SORT    -> "${baseUrl.ifBlank { BASE_URL }}?adult=0&genre%5B0%5D=${searchType.genres}&post_type=wp-manga&s=${searchType.query}&orderby=${searchType.sortType}"
        }
    override val sortTypes: Set<String>
        get() = setOf(
//            "most_read",   // most read
//            "less_read",   // less read (if your UI uses this label)
//            "newest",      // newest first
//            "oldest",      // oldest first
//            "a-z",         // title ascending
//            "z-a"          // title descending
        )

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    /**
     * Genre slugs used by the site (use these for ?genre=<slug> requests)
     */
    /**
     * Map of genre display names to their URL slug values
     */
    val genreMap: Map<String, String> = mapOf(
        "Action" to "action",
        "Adaptation" to "adaptation",
        "Adult" to "adult",
        "Adventure" to "adventure",
        "Anime" to "anime",
        "Comedy" to "comedy",
        "Comic" to "comic",
        "Cooking" to "cooking",
        "Crime" to "crime",
        "Crossdressin" to "crossdressin",
        "Delinquents" to "delinquents",
        "Demons" to "demons",
        "Drama" to "drama",
        "Ecchi" to "ecchi",
        "Fantasy" to "fantasy",
        "Full Color" to "full-color",
        "Game" to "game",
        "Gender Bender" to "gender-bender",
        "Ghosts" to "ghosts",
        "Harem" to "harem",
        "Historical" to "historical",
        "Horror" to "horror",
        "Isekai" to "isekai",
        "Josei" to "josei",
        "Korean" to "korean",
        "Liexing" to "liexing",
        "Long strip" to "long-strip",
        "Magic" to "magic",
        "Manga" to "manga",
        "Manhua" to "manhua",
        "Manhwa" to "manhwa",
        "Martial Arts" to "martial-arts",
        "Mature" to "mature",
        "Mecha" to "mecha",
        "Medical" to "medical",
        "Monster Girls" to "monster-girls",
        "Monsters" to "monsters",
        "Music" to "music",
        "Mystery" to "mystery",
        "Office Workers" to "office-workers",
        "Official colored" to "official-colored",
        "One shot" to "one-shot",
        "Philosophical" to "philosophical",
        "Ping Ping Jun" to "ping-ping-jun",
        "Police" to "police",
        "Psychological" to "psychological",
        "Reincarnation" to "reincarnation",
        "Reverse" to "reverse",
        "Reverse harem" to "reverse-harem",
        "Romance" to "romance",
        "Royal family" to "royal-family",
        "School Life" to "school-life",
        "Sci fi" to "sci-fi",
        "Seinen" to "seinen",
        "Shoujo" to "shoujo",
        "Shoujo Ai" to "shoujo-ai",
        "Shounen" to "shounen",
        "Shounen Ai" to "shounen-ai",
        "Si-fi" to "si-fi",
        "Slice of Life" to "slice-of-life",
        "Smut" to "smut",
        "Soft Yaoi" to "soft-yaoi",
        "Sports" to "sports",
        "Super power" to "super-power",
        "Superhero" to "superhero",
        "Supernatural" to "supernatural",
        "Survival" to "survival",
        "Thriller" to "thriller",
        "Time Travel" to "time-travel",
        "Tragedy" to "tragedy",
        "Video games" to "video-games",
        "Yaoi" to "yaoi",
        "Yuri" to "yuri",
        "Zombies" to "zombies",
        "Villainess" to "villainess",
        "Violence" to "violence",
        "Web comic" to "web-comic",
        "Webtoons" to "webtoons",
        "Wuxia" to "wuxia",
        "Kids" to "kids",
        "Genderswap" to "genderswap",
        "Virtual Reality" to "virtual-reality",
        "Vampires" to "vampires",
        "Gyaru" to "gyaru",
        "Ninja" to "ninja",
        "Gore" to "gore",
        "Animals" to "animals",
        "Doujinshi" to "doujinshi",
        "Award Winning" to "award-winning",
        "Sci-Fi" to "sci-fi",
        "4-Koma" to "4-koma",
        "Mafia" to "mafia",
        "Sexual Violence" to "sexual-violence",
        "Shota" to "shota",
        "Self-Published" to "self-published",
        "Crossdressing" to "crossdressing",
        "Military" to "military",
        "Anthology" to "anthology",
        "Webtoon" to "webtoon",
        "Aliens" to "aliens",
        "Magical Girls" to "magical-girls",
        "Loli" to "loli",
        "Incest" to "incest",
        "Fan Colored" to "fan-colored",
        "Samurai" to "samurai",
        "User Created" to "user-created",
        "Post-Apocalyptic" to "post-apocalyptic",
        "Transmigration" to "transmigration",
        "Royalty" to "royalty",
        "Yaoi(BL)" to "yaoi-bl",
        "Western" to "western",
        "Revenge" to "revenge",
        "Omegaverse" to "omegaverse",
        "College life" to "college-life",
        "Regression" to "regression",
        "Erotica" to "erotica",
        "BDSM" to "bdsm",
        "Blackmail" to "blackmail",
        "Dubious consent" to "dubious-consent",
        "Mahou shoujo" to "mahou-shoujo",
        "NSFW" to "nsfw",
        "School girl" to "school-girl",
        "Post Apocalyptic" to "post-apocalyptic",
        "Traditional Games" to "traditional-games",
        "Rebirth" to "rebirth",
        "Overpowered" to "overpowered",
        "Necromancer" to "necromancer",
        "Pornographic" to "pornographic",
        "Returner" to "returner",
        "Heartwarming" to "heartwarming",
        "Informative" to "informative",
        "System" to "system",
        "Bloody" to "bloody",
        "Cartoon" to "cartoon",
        "Murim" to "murim",
        "Cultivation" to "cultivation",
        "Showbiz" to "showbiz",
        "Moder" to "moder",
        "Beasts" to "beasts",
        "Chinese" to "chinese",
        "School" to "school",
        "Boys Love" to "boys-love",
        "Space" to "space",
        "Girls Love" to "girls-love",
        "Suspense" to "suspense",
        "Iyashikei" to "iyashikei",
        "Graphic Novel" to "graphic-novel",
        "Smart MC" to "smart-mc",
        "Weaktostrong" to "weaktostrong",
        "Gourmet" to "gourmet",
        "Parody" to "parody",
        "Xianxia" to "xianxia",
        "Imageset" to "imageset",
        "Childhood friends" to "childhood-friends",
        "Death game" to "death-game",
        "Avant Garde" to "avant-garde",
        "Age gap" to "age-gap",
        "Cheating infidelity" to "cheating-infidelity",
        "Netorare" to "netorare",
        "Degeneratemc" to "degeneratemc",
        "Fetish" to "fetish",
        "SM BDSM" to "sm-bdsm",
        "Netori" to "netori",
        "Master servant" to "master-servant",
        "Bodyswap" to "bodyswap",
        "Creators" to "creators",
        "Others" to "others",
        "Step family" to "step-family",
        "AI art" to "ai-art",
        "Brocon siscon" to "brocon-siscon",
        "Old people" to "old-people",
        "Dementia" to "dementia",
        "Artbook" to "artbook",
        "Cars" to "cars",
        "Office" to "office",
        "Science fiction" to "science-fiction"
    )

    /**
     * All available genre slugs
     */
    override val allGenres: Set<String>
        get() = genreMap.values.toSet()

    /**
     * Genres to exclude from queries
     */
    override val blackListGenres: Set<String>
        get() = setOf(
//            "adult",
//            "ecchi",
            "hentai",
            "smut",
            "yaoi",
            "Yaoi",
            "yuri",
            "shoujo-ai",
            "shounen-ai",
            "sexual-violence",
            "shota",
            "loli",
            "incest",
            "erotica",
            "sm_bdsm",
            "master_servant",
            "fetish",
            "nsfw",
            "pornographic"
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
    private fun parseMangaEntries(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)
        logLongText("parseMangaEntries - start", "html length=${html.length}")

        val entries = doc.select("div.page-item-detail:not(:has(a[href*='bilibilicomics.com'])).manga , .manga__item")
        logLongText("parseMangaEntries - found entries", "count=${entries.size}")

        val items = entries.mapNotNull { entry ->
            try {
                val genres = entry.select("div.tags a, div.tags span a").map {

                    it.text().trim()
                }.filter { it.isNotEmpty() }
                val title = entry.selectFirst("p.widget-title a, div.post-title a")?.text()?.trim().orEmpty()

//                if (genres.hasBlacklistedGenre()) {
//
//                    Log.i("dsglsjgdfgdflkgdsfgsdsgdf",title)
//                    return@mapNotNull null
//                }
                val thumbLink = entry.selectFirst("div.item-thumb a, div.c-image-hover a")
                val img = thumbLink?.selectFirst("img")
                val url = thumbLink?.attr("href")?.trim().orEmpty()
                val imageUrl = img?.attr("src")?.trim().orEmpty()


                if (title.isEmpty() || url.isEmpty()) {
                    logLongText("parseMangaEntries - skip entry", "missing title or url (title='$title', url='$url')")
                    null
                } else {
                    MangaItem(
                        title = title,
                        imageUrl = imageUrl,
                        url = url,
                        api = API,
                        language = LANGUAGE,
                        rating = 0,
                        genres = genres,
                        chapters = emptyList()
                    ).also {
                        // small per-item log to help debug weird entries
                        logLongText("parseMangaEntries - parsed item", "title='${it.title}' url='${it.url}' img='${it.imageUrl}' genres=${it.genres.size} chapters=${it.chapters?.size}")
                    }
                }
            } catch (e: Exception) {
                logLongText("parseMangaEntries - entry parse error", e.message ?: "unknown")
                null
            }
        }

        logLongText("parseMangaEntries - done", "parsedCount=${items.size}")
        return items
    }
    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> = parseMangaEntries(html).toMutableList()
    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> = parseMangaEntries(html).toMutableList()

    override fun extractMangaList(html: String): List<PopularManga> = parseMangaEntries(html).toPopularMangaList()
    override suspend fun extractMangaInfo(
        html: String,
        baseUrl: String
    ): MangaInfo {
        val doc = Jsoup.parse(html, baseUrl) // set baseUrl so absUrl works

        // helper to extract "label → value" patterns (e.g. "Status:", "Release:", "View:")
        fun extractLabeledValue(label: String): String {
            // find element whose own text matches the label (with or without colon)
            val labelEl = doc.select("*").firstOrNull {
                val t = it.ownText().trim()
                t.equals(label, ignoreCase = true) || t.equals("$label:", ignoreCase = true)
            }
            labelEl?.let { el ->
                // prefer sibling text (next element sibling)
                el.nextElementSibling()?.let { return it.text().trim() }
                // else parent text minus label text
                el.parent()?.let { parent ->
                    val full = parent.text().replace(el.ownText(), "").trim()
                    if (full.isNotBlank()) return full
                }
            }
            return ""
        }

        // fallback relative english parser (e.g. "1 hour ago", "2 weeks ago", "1 month ago")
        fun parseRelativeEnglish(text: String): LocalDate {
            if (text.isBlank()) return LocalDate.now()
            val lower = text.trim().lowercase()
            // if text already a precise date, try some common formats (dd/MM/yyyy, yyyy)
            try {
                // try ISO-ish
                val iso = DateTimeFormatter.ISO_DATE
                return LocalDate.parse(text, iso)
            } catch (_: Exception) { /* ignore */ }

            // patterns like "1 hour ago", "2 weeks ago", "1 month ago", "yesterday", "today"
            when {
                lower.contains("today") -> return LocalDate.now()
                lower.contains("yesterday") -> return LocalDate.now().minusDays(1)
                else -> {
                    val m = Regex("(\\d+)\\s*(hour|hours|day|days|week|weeks|month|months|year|years)\\s*ago")
                        .find(lower)
                    if (m != null) {
                        val num = m.groupValues[1].toLongOrNull() ?: 0L
                        return when (m.groupValues[2].removeSuffix("s")) {
                            "hour", "hours" -> LocalDate.now() // hours -> same day
                            "day" -> LocalDate.now().minusDays(num)
                            "week" -> LocalDate.now().minusWeeks(num)
                            "month" -> LocalDate.now().minusMonths(num)
                            "year" -> LocalDate.now().minusYears(num)
                            else -> LocalDate.now()
                        }
                    }
                }
            }
            return LocalDate.now()
        }

        // combined parser: try Italian parser first (if available), else relative english
        fun parseAnyDateText(text: String): LocalDate {
            if (text.isBlank()) return LocalDate.now()
            return try {
                // keep your existing Italian parser in use if it exists
                parseItalianDateText(text)
            } catch (_: Exception) {
                try {
                    parseRelativeEnglish(text)
                } catch (_: Exception) {
                    LocalDate.now()
                }
            }
        }

        // Title
        val title = doc.selectFirst("h1.post-title, h1.post-title.font-title, h1.name.bigger, div.comic-info h1")?.text()?.trim()
            .orEmpty()

        // Thumbnail
        val thumbnailUrl = doc.selectFirst("div.summary_image img, div.comic-info div.thumb img, img.img-comic")?.let {
            it.absUrl("src").ifBlank { it.attr("src") }
        }.orEmpty()

        // Description
        val description = doc.selectFirst("div.description-summary, div.comic-description, #noidungm, .summary__content")?.text()?.trim()
            .orEmpty()

        // Alternative titles (label may be "Alternative:" or "Alternative Names:")
        val altTitles = extractLabeledValue("Alternative").ifBlank { extractLabeledValue("Alternative:") }

        // Genres / tags
        val genres = doc.select(".tags a[rel=tag], .meta-data a.badge")    // pick anchors inside .tags and .meta-data
            .map { it.text().trim().lowercase(Locale.getDefault()).replace('\u00A0', ' ') }               // remove non-breaking spaces
            .map { it.replace(Regex("\\s+"), " ") }                       // collapse multiple spaces
            .filter { it.isNotBlank() }                                   // drop blanks
            .distinct()                                                   // remove duplicates
            .toMutableList()

        // type (if present) - try several selectors
        val type = doc.selectFirst("div.meta-data .col-12:has(span:contains(Tipo)) a, .type, span:contains(Tipo) + a")?.text()?.trim()
        type?.let { if (it.isNotBlank()) genres.add(it) }

        // Status (try labeled extraction, else fallback to searching for common words)
        var status = extractLabeledValue("Status")
        if (status.isBlank()) {
            val statusCandidate = doc.select("*:matchesOwn(\\b(OnGoing|Ongoing|Completed|Finished|On going|OnGoing)\\b)").firstOrNull()
            status = statusCandidate?.ownText()?.trim()?.ifBlank { statusCandidate.text().trim() } ?: "Unknown"
        }

        // Author / Artist / Year
        val author = extractLabeledValue("Author").ifBlank {
            doc.select("a[rel=author], .author a").firstOrNull()?.text()?.trim().orEmpty()
        }
        val artist = extractLabeledValue("Artist").ifBlank { "" }
        val year = extractLabeledValue("Release").ifBlank { extractLabeledValue("Release:") }

        // Views / rating / ratingCount
        val views = extractLabeledValue("View").ifBlank {
            doc.selectFirst(".summary-content-bookmark, .summary-content .summary-content-bookmark, .post-content_item .summary-content-bookmark")?.text()?.trim()
                ?.replace(",", "") ?: ""
        }
        val rating = doc.selectFirst("#averagerate, span[property=ratingValue]")?.text()?.trim().orEmpty()
        val ratingCount = doc.selectFirst("#countrate, span[property=ratingCount]")?.text()?.trim().orEmpty()

        // Chapters extraction - support both Mangaworld-style and Madara/wp-manga style
        val chapters = mutableListOf<ChapterItem>()

        // 1) WP-Manga / Madara layout: .wp-manga-chapter inside .chapter-list / .listing-chapters_wrap
        val wpChapterEls = doc.select(".wp-manga-chapter, .chapter-list .wp-manga-chapter")
        if (wpChapterEls.isNotEmpty()) {
            wpChapterEls.forEach { el ->
                val a = el.selectFirst("a")
                val chapterName = a?.text()?.trim().orEmpty()
                val chapterUrl = a?.absUrl("href")?.ifBlank { a?.attr("href").orEmpty() }.orEmpty()
                val dateText = el.selectFirst(".chapter-release-date i")?.text()?.trim().orEmpty()
                val chapterNumber = chapterName.replace(Regex("[^\\d.]"), "").trim()
                val parsed = try { parseAnyDateText(dateText) } catch (_: Exception) { LocalDate.now() }
                logLongText("safjslakjfklasfsadsadasdas2",dateText.toString())

                logLongText("kjskfaklhfalshfkashflas1",chapterUrl.toString())


                chapters.add(
                    ChapterItem(
                        number = chapterNumber,
                        name = chapterName,
                        url = chapterUrl,
                        date = parsed
                    )
                )
            }
        } else {
            // 2) older / alternate layout used previously in your code
            val chapterElements = doc.select("div.chapters-wrapper div.chapter a.chap, .listing-chapters a.chap, .chap")
            if (chapterElements.isNotEmpty()) {
                chapterElements.forEach { chapterLink ->
                    val chapterName = chapterLink.selectFirst("span.d-inline-block")?.text()?.trim()
                        ?: chapterLink.text().trim()
                    val chapterUrl = chapterLink.absUrl("href").ifBlank { chapterLink.attr("href") }
                    val dateText = chapterLink.selectFirst("i.chap-date")?.text()?.trim().orEmpty()
                    val chapterNumber = chapterName.replace(Regex("[^\\d.]"), "").trim()
                    val parsed = try { parseAnyDateText(dateText) } catch (_: Exception) { LocalDate.now() }
                    logLongText("safjslakjfklasfsadsadasdas3",dateText.toString())

                    chapters.add(
                        ChapterItem(
                            number = chapterNumber,
                            name = chapterName,
                            url = chapterUrl,
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
            rating = rating,
            ratingCount = ratingCount.ifBlank { views },
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

        logLongText("dsghsldgkhdkhgdslkgsdgsdgsd", doc.toString())

        return doc.select("div.page-item-detail").mapNotNull { entry ->
            try {
                // Get the link and image from item-thumb
                val thumbLink = entry.selectFirst("div.item-thumb a, a[title]")
                val url = thumbLink?.attr("href")?.trim().orEmpty()

                // Try multiple selectors for the image
                val img = entry.selectFirst("img[data-src], img[src]")
                val imageUrl = img?.attr("data-src")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: img?.attr("src")?.trim().orEmpty()

                // Get title from post-title or widget-title
                val title = entry.selectFirst("h3.h5 a, p.widget-title a")?.text()?.trim().orEmpty()

                // Get genres from tags div
                val genres = entry.select("div.tags a").map { it.text().trim() }

                // Get latest chapter info
                val chapterLink = entry.selectFirst("div.list-chapter a, div.chapter-detail a")

                // Only add if we have at least a title and URL
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    MangaItem(
                        title = title,
                        imageUrl = imageUrl,
                        url = url,
                        api = API,
                        language = LANGUAGE,
                        rating = 0,
                        genres = genres,
                        chapters = emptyList()
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                logLongText("Error parsing manga entry", e.message ?: "unknown")
                null
            }
        }.toMutableList()
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged
        dataStore.saveHeadersForApi(API, newHeaders)

    }

    override fun getChapterImages(html: String): List<String> {
        val doc = Jsoup.parse(html)
        logLongText("kjskfaklhfalshfkashflas", doc.toString())

        // Method 1: Extract images from wp-manga-chapter-img class
        val images = doc.select("img.wp-manga-chapter-img")
        if (images.isNotEmpty()) {
            return images.mapNotNull { img ->
                val src = img.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    src
                } else {
                    null
                }
            }
        }

        // Method 2: Try to extract from the main image container
        val containerImages = doc.select("div.page-break img, div.reading-content img")
        if (containerImages.isNotEmpty()) {
            return containerImages.mapNotNull { img ->
                val src = img.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    src
                } else {
                    null
                }
            }
        }

        // Method 3: Try data-src attribute (for lazy loading)
        val lazyImages = doc.select("img[data-src]")
        if (lazyImages.isNotEmpty()) {
            return lazyImages.mapNotNull { img ->
                val dataSrc = img.attr("data-src")
                if (dataSrc.isNotBlank() && dataSrc.startsWith("http")) {
                    dataSrc
                } else {
                    val src = img.attr("src")
                    if (src.isNotBlank() && src.startsWith("http")) {
                        src
                    } else {
                        null
                    }
                }
            }
        }

        // Method 4: Fallback - try to extract from any img tag with alt containing chapter info
        val allImages = doc.select("img[alt*='chapter'], img[alt*='Chapter']")
        if (allImages.isNotEmpty()) {
            return allImages.mapNotNull { img ->
                val src = img.attr("src")
                val dataSrc = img.attr("data-src")
                when {
                    src.isNotBlank() && src.startsWith("http") -> src
                    dataSrc.isNotBlank() && dataSrc.startsWith("http") -> dataSrc
                    else -> null
                }
            }
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

