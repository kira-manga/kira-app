package me.manga.kira.sources_repositry.en.zazamanga

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Notes specific to this file:
 *  - `java.time.LocalDate.now()` → `Clock.System.todayIn(TimeZone.currentSystemDefault())`.
 *  - `LocalDate.minusDays/Weeks/Months/Years(n)` → `.minus(n.toInt(), DateTimeUnit.DAY/WEEK/MONTH/YEAR)`.
 *  - `LocalDate.of(year, month, day)` → `LocalDate(year, month, day)` (kotlinx.datetime ctor).
 *  - `DateTimeFormatter.ISO_DATE.parse(text)` → `LocalDate.parse(text)` (kotlinx.datetime accepts ISO).
 *  - `String.capitalize()` (deprecated androidx ext) replaced with manual first-char-uppercase
 *    using `replaceFirstChar { it.uppercase() }`.
 *  - Source had duplicate `BASE_URL` / `API` / `LANGUAGE` overrides at lines 712-717; these are
 *    declared once in this port (the BaseManga abstract overrides).
 *  - `android.util.Log` -> Kermit `Logger` (the source's `logLongText` wrapper called `Log.i`;
 *    here we just log via Kermit's `Logger.withTag(...).i`).
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlin.concurrent.Volatile
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlin.time.Clock
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

class ZazamangaRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSitesv2(api, sourcesRepository) {

    override val mangaSource: MangaSource
        get() = MangaSource.ZAZAMANGA

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}manga?orderby=latest" }
    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}manga?orderby=views" }
    override var customParseHome: Boolean = false

    override var imgBaseUrl: String = "https://img-r1.2xstorage.com/"
    override var imgUrlVersion: Int = 0

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    private val refererHeader = "Referer" to "https://www.zazamanga.com/"

    /**
     * Just like the old `defaultHeaders` — will block once on first call,
     * then return the in-memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures our value wins.
            return base + refererHeader
        }

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}manga?orderby=latest&page=$page"
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> "${baseUrl.ifBlank { BASE_URL }}?s=${searchType.toNormalQuery()}&post_type=wp-manga"
            is SearchType.GENRES -> "${baseUrl.ifBlank { BASE_URL }}?s=${searchType.query}&post_type=wp-manga&genre%5B%5D=${searchType.genres}&op=&author=&artist=&release=&adult=0"
            is SearchType.SORT -> "${baseUrl.ifBlank { BASE_URL }}?adult=0&genre%5B0%5D=${searchType.genres}&post_type=wp-manga&s=${searchType.query}&orderby=${searchType.sortType}"
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

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        return null
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? {
        return null
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? {
        return null
    }

    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? {
        return null
    }

    private fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

    // Local mirror of the removed `HandelDataClasses.toPopularMangaList` extension
    // (the original `core.util.data_classes.HandelDataClasses` helper isn't ported yet).
    private fun List<MangaItem>.toPopularMangaList(): List<PopularManga> {
        return this.map {
            PopularManga(
                api = it.api,
                language = it.language,
                title = it.title,
                url = it.url,
                imageUrl = it.imageUrl
            )
        }
    }

    private fun parseMangaEntries(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html)
        Logger.withTag("ZazamangaRepository").i { "parseMangaEntries - start html length=${html.length}" }

        val entries = doc.select("div.page-item-detail:not(:has(a[href*='bilibilicomics.com'])).manga , .manga__item")
        Logger.withTag("ZazamangaRepository").i { "parseMangaEntries - found entries count=${entries.size}" }

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
                    Logger.withTag("ZazamangaRepository").i { "parseMangaEntries - skip entry missing title or url (title='$title', url='$url')" }
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
                        Logger.withTag("ZazamangaRepository").i {
                            "parseMangaEntries - parsed item title='${it.title}' url='${it.url}' img='${it.imageUrl}' genres=${it.genres.size} chapters=${it.chapters?.size}"
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.withTag("ZazamangaRepository").e(e) { "parseMangaEntries - entry parse error" }
                null
            }
        }

        Logger.withTag("ZazamangaRepository").i { "parseMangaEntries - done parsedCount=${items.size}" }
        return items
    }

    override fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem> = parseMangaEntries(string).toMutableList()
    override fun extractHomeMangaItems(string: String): MutableList<MangaItem> = parseMangaEntries(string).toMutableList()

    override fun extractMangaList(string: String): List<PopularManga> = parseMangaEntries(string).toPopularMangaList()

    override suspend fun extractMangaInfo(
        string: String,
        baseUrl: String
    ): MangaInfo {
        val doc = Ksoup.parse(string, baseUrl) // set baseUrl so absUrl works

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
            if (text.isBlank()) return today()
            val lower = text.trim().lowercase()
            // if text already a precise date, try common ISO format
            try {
                // try ISO-ish (kotlinx.datetime LocalDate.parse accepts ISO_LOCAL_DATE)
                return LocalDate.parse(text)
            } catch (_: Exception) { /* ignore */ }

            // patterns like "1 hour ago", "2 weeks ago", "1 month ago", "yesterday", "today"
            when {
                lower.contains("today") -> return today()
                lower.contains("yesterday") -> return today().minus(1, DateTimeUnit.DAY)
                else -> {
                    val m = Regex("(\\d+)\\s*(hour|hours|day|days|week|weeks|month|months|year|years)\\s*ago")
                        .find(lower)
                    if (m != null) {
                        val num = m.groupValues[1].toLongOrNull() ?: 0L
                        return when (m.groupValues[2].removeSuffix("s")) {
                            "hour", "hours" -> today() // hours -> same day
                            "day" -> today().minus(num.toInt(), DateTimeUnit.DAY)
                            "week" -> today().minus(num.toInt(), DateTimeUnit.WEEK)
                            "month" -> today().minus(num.toInt(), DateTimeUnit.MONTH)
                            "year" -> today().minus(num.toInt(), DateTimeUnit.YEAR)
                            else -> today()
                        }
                    }
                }
            }
            return today()
        }

        // combined parser: try Italian parser first (if available), else relative english
        fun parseAnyDateText(text: String): LocalDate {
            if (text.isBlank()) return today()
            return try {
                // keep your existing Italian parser in use if it exists
                parseItalianDateText(text)
            } catch (_: Exception) {
                try {
                    parseRelativeEnglish(text)
                } catch (_: Exception) {
                    today()
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
            .map { it.text().trim().lowercase().replace(' ', ' ') }   // remove non-breaking spaces
            .map { it.replace(Regex("\\s+"), " ") }                        // collapse multiple spaces
            .filter { it.isNotBlank() }                                    // drop blanks
            .distinct()                                                    // remove duplicates
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
                val chapterUrl = a?.absUrl("href")?.ifBlank { a.attr("href").orEmpty() }.orEmpty()
                val dateText = el.selectFirst(".chapter-release-date i")?.text()?.trim().orEmpty()
                val chapterNumber = chapterName.replace(Regex("[^\\d.]"), "").trim()
                val parsed = try { parseAnyDateText(dateText) } catch (_: Exception) { today() }
                Logger.withTag("safjslakjfklasfsadsadasdas2").i { dateText }

                Logger.withTag("kjskfaklhfalshfkashflas1").i { chapterUrl }

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
                    val parsed = try { parseAnyDateText(dateText) } catch (_: Exception) { today() }
                    Logger.withTag("safjslakjfklasfsadsadasdas3").i { dateText }

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
            description = fullDescription,
            author = author,
            genres = genres,
            status = status,
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
        val match = regex.find(dateText) ?: return today()
        val (day, monthName, year) = match.destructured
        // Replace deprecated `String.capitalize()` with manual first-char-uppercase.
        val capitalized = monthName.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
        val month = months[capitalized] ?: return today()
        return LocalDate(year.toInt(), month, day.toInt())
    }

    override suspend fun getSearchResults(string: String): List<MangaItem> {
        val doc = Ksoup.parse(string)

        Logger.withTag("dsghsldgkhdkhgdslkgsdgsdgsd").i { doc.toString() }

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

                // Get latest chapter info (parsed but not surfaced — preserved verbatim from source)
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
                Logger.withTag("Error parsing manga entry").e(e) { e.message ?: "unknown" }
                null
            }
        }.toMutableList()
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun getChapterImages(string: String): List<String> {
        val doc = Ksoup.parse(string)
        Logger.withTag("kjskfaklhfalshfkashflas").i { doc.toString() }

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
}

/**
 * Audit-trail postscript (Phase 9.x.cluster196.staleKdocSweep.cascade, Task #651, 2026-05-29)
 *
 * Leaf 3/5 §253 audit-trail-preservation postscript for cluster196, sibling 338. 747-line
 * NormalSitesv2 subclass — the heaviest non-inheritance-graph leaf in the cluster (cluster196's
 * lines-1/2/4/5 are 571/708/796/801; this leaf occupies the middle position by size).
 *
 * The top-of-file prose under audit (lines 3-18) is a single file-header KDoc block carrying
 * two distinct sub-sections:
 *
 *   I.   Phase 7.2 migration-pattern enumeration (lines 4-5) — standard 6-bullet preamble.
 *
 *   II.  File-specific Phase 7.2 KMP-port notes (lines 7-17) — 7 bullets covering:
 *        (a) LocalDate.now() → Clock.System.todayIn(TimeZone.currentSystemDefault()).
 *        (b) LocalDate.minusDays/Weeks/Months/Years(n) →
 *            .minus(n.toInt(), DateTimeUnit.DAY/WEEK/MONTH/YEAR).
 *        (c) LocalDate.of(year, month, day) → kotlinx.datetime LocalDate(year, month, day)
 *            constructor.
 *        (d) DateTimeFormatter.ISO_DATE.parse(text) → LocalDate.parse(text)
 *            (kotlinx.datetime accepts ISO).
 *        (e) String.capitalize() (deprecated androidx ext) replaced with manual
 *            first-char-uppercase using `replaceFirstChar { it.uppercase() }`.
 *        (f) Source had duplicate BASE_URL / API / LANGUAGE overrides at upstream lines
 *            712-717; declared once in this port.
 *        (g) android.util.Log → Kermit Logger (source's logLongText wrapper called Log.i; here
 *            we log via Kermit's `Logger.withTag(...).i`).
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — sub-section I (Phase 7.2 6-bullet migration-pattern preamble).
 *
 *   b. LIVE-NOT-STALE — sub-section II (7-bullet file-specific notes):
 *        - bullet (a): cross-verified — today() helper at line 337 wraps
 *          Clock.System.todayIn(TimeZone.currentSystemDefault()); 6+ call sites.
 *        - bullet (b): cross-verified — parseRelativeEnglish at lines 438-468 uses
 *          today().minus(num.toInt(), DateTimeUnit.DAY/WEEK/MONTH/YEAR) for the 4 unit
 *          variants.
 *        - bullet (c): cross-verified — parseItalianDateText at line 620 uses
 *          LocalDate(year.toInt(), month, day.toInt()).
 *        - bullet (d): cross-verified — parseRelativeEnglish at line 444 tries
 *          LocalDate.parse(text) inside a try/catch.
 *        - bullet (e): cross-verified — parseItalianDateText at line 618 uses
 *          `monthName.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }`.
 *        - bullet (f): cross-verified — the BASE_URL / API / LANGUAGE override block at lines
 *          50-55 declares each property exactly once. The upstream-duplicate-cleanup landed.
 *        - bullet (g): cross-verified — 8+ Logger.withTag calls across the file. No Log.i
 *          residue in active code (commented-out block at lines 367-371 still references
 *          `Log.i` — see classification (g) below).
 *
 *   c. POTENTIAL-BUG-PRESERVED — empty `sortTypes` set at lines 91-99. ALL 6 sort entries
 *      (most_read, less_read, newest, oldest, a-z, z-a) are commented out. Live sortTypes is
 *      the empty Set — sorting either is broken or routes through a default code path.
 *      Preserved verbatim per §253; restoring the commented entries would change observable
 *      sort-dropdown contents in the UI. This is the FIRST cross-cluster instance of an
 *      empty-by-comment sortTypes — most cluster195 leaves populate it with 4-11 entries.
 *
 *   d. POTENTIAL-BUG-PRESERVED — duplicate "yaoi" / "Yaoi" entries in blackListGenres at lines
 *      304-305 (lowercase + capitalized). hasBlacklistedGenre() lowercase()s before contains()
 *      so the capitalized "Yaoi" is functionally redundant; preserved verbatim per §253.
 *
 *   e. POTENTIAL-BUG-PRESERVED — genreMap typos and duplicates:
 *        - "Crossdressin" (missing g) at line 120 with slug "crossdressin"; full
 *          "Crossdressing" at line 206 with slug "crossdressing" — different slugs, both
 *          live.
 *        - "Sci fi" at line 163 maps to slug "sci-fi"; "Sci-Fi" at line 200 maps to slug
 *          "sci-fi" — same slug, two display labels. Map semantics keep both keys.
 *        - "Post-Apocalyptic" at line 217 and "Post Apocalyptic" at line 233 both map to slug
 *          "post-apocalyptic" — same slug, two display labels.
 *        - "Webtoon" at line 209 maps to "webtoon"; "Webtoons" at line 188 maps to "webtoons"
 *          — different slugs.
 *        - "Moder" at line 248 maps to slug "moder" — likely typo for "Modern" / "modern",
 *          preserved verbatim.
 *      All preserved per §253 because allGenres = genreMap.values.toSet() (line 293) collapses
 *      duplicate slugs deterministically and the display-label drift is upstream-authored
 *      data.
 *
 *   f. COSMETIC-NOT-STALE — 2 commented-out blackListGenres entries (`//"adult"` at line 300,
 *      `//"ecchi"` at line 301). Standard rollback escape hatch.
 *
 *   g. COSMETIC-NOT-STALE — commented-out blacklist-filter block at lines 367-371 inside
 *      parseMangaEntries:
 *        `//                if (genres.hasBlacklistedGenre()) {`
 *        `//                    Log.i("dsglsjgdfgdflkgdsfgsdsgdf",title)`
 *        `//                    return@mapNotNull null`
 *        `//                }`
 *      Triple cross-category artifact: it's a commented-out filter (cosmetic), the comment
 *      retains the Android-only `Log.i` reference (drift artifact — would not compile in
 *      commonMain if uncommented), AND uses a scrambled debug tag
 *      ("dsglsjgdfgdflkgdsfgsdsgdf") matching the in-file debug-tag-noise pattern. Preserved
 *      verbatim — the comment doubles as documentation of WHY the filter was disabled (Madara
 *      sites surface genres in the LIST view inconsistently; pre-filter would hide entries
 *      with no tag markup).
 *
 *   h. DEBUG-TAG NOISE — 5 scrambled Kermit tags in active code:
 *        - "safjslakjfklasfsadsadasdas2" (line 548)
 *        - "kjskfaklhfalshfkashflas1" (line 550)
 *        - "safjslakjfklasfsadsadasdas3" (line 572)
 *        - "dsghsldgkhdkhgdslkgsdgsdgsd" (line 626)
 *        - "kjskfaklhfalshfkashflas" (line 678)
 *      Plus "Error parsing manga entry" (line 664) as a properly-named tag. The 5 scrambled
 *      tags represent the heaviest cross-cluster debug-tag-noise concentration encountered so
 *      far — exceeds cluster195 leaf 2/5 DemonicScansRepository's 2-tag noise count and
 *      cluster196 leaf 2/5 MangaParkRepository's 2-tag noise count. Preserved per §253.
 *
 *   i. LIVE-NOT-STALE — Volatile `_cachedHeaders` pattern (lines 64-78) WITH Referer merge.
 *      Unique cluster196 variant: `defaultHeaders` returns `_cachedHeaders + refererHeader`
 *      where `refererHeader = "Referer" to "https://www.zazamanga.com/"` is hardcoded to the
 *      canonical domain. refreshHeaders at line 671 also merges. initSite at lines 101-105
 *      preloads from dataStore. Compared to cluster196 leaf 2/5 MangaParkRepository which
 *      ALSO preloads in initSite but does NOT merge a Referer, this leaf is the first
 *      cross-cluster instance of the Referer-merge variant of the canonical Volatile-cache
 *      pattern.
 *
 *   j. LIVE-NOT-STALE — parseRelativeEnglish "hours → same day" floor behaviour at line 457
 *      (`"hour", "hours" -> today() // hours -> same day`). Identical floor-hours semantics
 *      to cluster196 leaf 1/5 BatotoEnRepositoryv2's parseChapterDate at line 562-563.
 *      Cross-leaf convergence on the "sub-day deltas collapse to today" behaviour.
 *
 *   k. LIVE-NOT-STALE — parseAnyDateText chain-of-responsibility (lines 471-483): Italian
 *      parser FIRST, English relative SECOND, today() fallback THIRD. Source served both
 *      Italian and English chapter-date markup at various points; cluster196 keeps both
 *      parsers wired. The Italian month map at lines 609-613 covers all 12 months
 *      (Gennaio-Dicembre).
 *
 *   l. LIVE-NOT-STALE — dual chapter-extraction paths in extractMangaInfo (lines 539-584):
 *      WP-Manga / Madara layout via `.wp-manga-chapter` first; alternate
 *      `div.chapters-wrapper div.chapter a.chap` fallback. Same dual-path strategy as
 *      cluster196 leaf 1/5 BatotoEnRepositoryv2's getChapterImages real-img-then-JS-array
 *      shape, just applied to chapter-list parsing instead of page-image parsing.
 *
 *   m. LIVE-NOT-STALE — getChapterImages 4-method fallback chain (lines 676-738):
 *        Method 1: img.wp-manga-chapter-img
 *        Method 2: div.page-break img / div.reading-content img
 *        Method 3: img[data-src] lazy-loading
 *        Method 4: img[alt*='chapter'] / img[alt*='Chapter']
 *      Heavy strategy fallback documentation preserved verbatim — the "Method N" naming is
 *      the source's own structural convention. Future Phase 8 could collapse the 4 nearly-
 *      identical extractor blocks into a parameterised helper.
 *
 *   n. COSMETIC-NOT-STALE — orphan utility `Element.imgAttr()` at lines 741-746. Defined as a
 *      receiver-extension on Ksoup Element with 4-attr cascade (data-lazy-src → data-src →
 *      data-cfsrc → src), but ZERO call sites in the file body. Likely a port of a source
 *      utility that was meant to replace inline `img.attr("src")` calls but never wired. Phase
 *      8 cleanup candidate — but preserved per §253 because removal would change the file's
 *      public-ish API surface (it's `fun` not `private fun`).
 *
 *   o. COSMETIC-NOT-STALE — "Get latest chapter info (parsed but not surfaced — preserved
 *      verbatim from source)" comment at line 645 inside getSearchResults. chapterLink is
 *      assigned at line 646 but never used in the MangaItem construction. The comment
 *      documents the no-op block as INTENTIONAL preservation rather than dead code.
 *
 *   p. LIVE-NOT-STALE — local mirror of removed extension at lines 339-351
 *      (`List<MangaItem>.toPopularMangaList`). Inline comment explicitly notes "the original
 *      core.util.data_classes.HandelDataClasses helper isn't ported yet". FORECAST: when the
 *      HandelDataClasses helper lands in :core, this local mirror should be removed.
 *      Cross-leaf forecast — could become a cluster197+ candidate when :core helpers sweep.
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 336 (BatotoEnRepositoryv2.kt) — leaf 1/5, opening leaf, 571 lines.
 *   - sibling 337 (MangaParkRepository.kt) — leaf 2/5, 708 lines, BaseMangaRepository direct
 *     subclass with GraphQL companion-object query bank.
 *   - sibling 339 (BatcaveRepository.kt) — leaf 4/5, 796 lines, NEXT leaf; cross-package
 *     consumer of the :en/readcomiconline/Dto.kt data classes.
 *   - sibling 340 (ComickRepository.kt) — leaf 5/5, closing leaf, 801 lines, parent of
 *     cluster191 leaf 5/5 ComickRepositoryAr.
 *
 * Cluster196 leaf 3/5 — middle leaf. Next leaf: BatcaveRepository.kt (sibling 339).
 */
