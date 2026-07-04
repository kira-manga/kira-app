package me.manga.kira.sources_repositry.en.mangabuddy

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Notes specific to this file:
 *  - Source's `ZonedDateTime.now(ZoneId.of("Africa/Cairo"))` → `Clock.System.now().toLocalDateTime(
 *    TimeZone.of("Africa/Cairo"))` then `.date`. Cairo zone is preserved because the source used
 *    it as a hard-coded "site-local" assumption for relative date parsing.
 *  - `now.minusMinutes(n)` / `.minusHours(n)` → applied to the underlying Instant before
 *    re-converting to LocalDate. minus-by-days/weeks/months/years applied directly to the
 *    LocalDate via `kotlinx.datetime.minus(amount, DateTimeUnit.*)`.
 *  - `DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)` →
 *    `LocalDate.Format { monthName(MonthNames.ENGLISH_ABBREVIATED); ... }`.
 *  - Standalone parser class (no @Inject, no base class) — preserved verbatim.
 */

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga

@OptIn(ExperimentalTime::class)
class MangaBuddyParser {

    val parserVersion = 1
    val baseUrlVersion = 1
    val API = "Mangabuddy"
    val LANGUAGE = "(EN)"
    val baseUrl = "https://mangabuddy.com"
    val homeUrl = "${baseUrl}/latest"
    val popularUrl = "${baseUrl}/home"

    fun loadMoreUrl(page: Int): String = "${baseUrl}/latest?page=$page/"
    fun normalSearchUrl(q: String): String = "${baseUrl}/search?q=${q}"

    fun createInfoUrl(mangaId: String): String {
        return if (mangaId.startsWith("http", ignoreCase = true)) {
            mangaId
        } else {
            // BASE_URL already ends with no slash, so we add it
            "$baseUrl$mangaId"
        }
    }


    fun createChaptersUrl(mangaId: String): String {
        return if (mangaId.startsWith("http", ignoreCase = true)) {
            mangaId
        } else {
            // BASE_URL already ends with no slash, so we add it
            "${baseUrl}/api/manga/${mangaId}/chapters?source=detail"
        }
    }

    private val abbreviatedMonthFormatter = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        day()
        chars(", ")
        year()
    }

    fun parseChapters(
        html: String,
    ): List<ChapterItem> {
        val document = Ksoup.parse(html)
        val chapterElements = document.select("ul.chapter-list > li")

        return chapterElements.map { element ->
            // Link and URL
            val anchor = element.selectFirst("a")!!
            val href = anchor.attr("href").trim()
            val url = href

            // Chapter number
            val titleEl = anchor.selectFirst("strong.chapter-title")!!
            val rawNumber = titleEl.text().trim()
            val number = rawNumber.removePrefix("Chapter").trim()
            val chpNumOnly = number.replace(Regex("[^\\d.]"), "")  // removes all non‐digits, yields "245"

            // Update date
            val timeEl = anchor.selectFirst("time.chapter-update")
            val dateText = timeEl?.text()?.trim()

            val date = dateText?.let {
                parseDateString(it) ?: nowInCairo()
            }

            ChapterItem(
                number = chpNumOnly,
                name = titleEl.text().toString(),
                url = url,
                date = date
            )
        }
    }

    private val cairoZone = TimeZone.of("Africa/Cairo")

    private fun nowInCairo(): LocalDate =
        Clock.System.now().toLocalDateTime(cairoZone).date

    fun parseDateString(text: String): LocalDate? {
        val instantNow = Clock.System.now()
        val nowDate = instantNow.toLocalDateTime(cairoZone).date
        val trimmed = text.trim().lowercase()

        // Handle “just now”
        if (trimmed == "just now") {
            return nowDate
        }

        // Handle “yesterday”
        if (trimmed.startsWith("yesterday")) {
            // Could be “yesterday” or “yesterday at HH:mm”; we only care about the date
            return nowDate.minus(1, DateTimeUnit.DAY)
        }

        // Regex for “X unit(s) ago”
        val regex = Regex("(\\d+)\\s+(minute|hour|day|week|month|year)s?\\s+ago")
        val match = regex.find(trimmed)
        if (match != null) {
            val (valueStr, unit) = match.destructured
            val value = valueStr.toLongOrNull() ?: return null
            val adjustedDate: LocalDate = when (unit) {
                "minute" -> (instantNow - value.minutes).toLocalDateTime(cairoZone).date
                "hour"   -> (instantNow - value.hours).toLocalDateTime(cairoZone).date
                "day"    -> nowDate.minus(value.toInt(), DateTimeUnit.DAY)
                "week"   -> nowDate.minus(value.toInt(), DateTimeUnit.WEEK)
                "month"  -> nowDate.minus(value.toInt(), DateTimeUnit.MONTH)
                "year"   -> nowDate.minus(value.toInt(), DateTimeUnit.YEAR)
                else     -> nowDate
            }
            return adjustedDate
        }

        // Fallback: parse absolute date, e.g. “May 27, 2025”
        return try {
            LocalDate.parse(text.trim(), abbreviatedMonthFormatter)
        } catch (e: Exception) {
            // If parsing fails, return null or log warning
            null
        }
    }
    fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val mangaList = mutableListOf<MangaItem>()
        val doc: Document = Ksoup.parse(html)
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

    fun extractMangaList(html: String): List<PopularManga> {
        val updates = mutableListOf<PopularManga>()
        val doc: Document = Ksoup.parse(html)

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

    fun extractMangaInfo(
        html: String,
        url: String
    ): MangaInfo {
        val doc = Ksoup.parse(html)

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
            .filter { it.isNotEmpty() }

        val tags            = doc.select("div.detail .tags a").eachText()

        // Year of Production & Status
        val yearOfProduction = doc.select("div.detail .info span:contains(Year) + span").text()

        // Favorites / Bookmarks count
        val favoritesCount  = doc.selectFirst("button.bookmark-btn span.count")?.text() ?: "0"


        return MangaInfo(
            api             = API,
            language        = LANGUAGE,
            url             = url,
            title           = title,
            imageUrl        = imageUrl,
            rating          = rating,
            description     = description,
            author          = author,
            genres          = genres,
            status          = status,
            chapters        = mutableListOf()
        )
    }


    fun getSearchResults(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html)

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
    fun getChapterImages(html: String): List<String> {
        val doc = Ksoup.parse(html, baseUrl)  // where BASE_URL == "https://s2.mbcdnsab.org/"

        val doc2 = doc.html()
        val realDocument = Ksoup.parse(doc2, doc.location() ?: "")

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



    fun List<String>.hasBlacklistedGenre(): Boolean =
        this.any { it in blackListGenres }

    val blackListGenres = setOf(
        "mmmmmm"
//        "Adult",
//        "Smut",
////        "Mature",
//        "Ecchi",
//        "Hentai",
//        "Yuri",
//        "Fetish",
//        "SM/BDSM/SUB-DOM",
//        "Incest",
//        "Omegaverse",
//        "Netorare/NTR",
//        "Bara(ML)",
////        "Shoujo(G)",
//        "Yaoi",
//        "Shounen ai",
//        "Gender Bender",
//        "Shouja ai",

    )




}

/*
 * Audit-trail postscript (Phase 9.x.cluster197.staleKdocSweep.cascade, Task #652, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster197 leaf 1/4 — opening leaf, sibling 341. Opens the :en/ Repository tier closing
 * 4-leaf batch (parser helpers + models): MangaBuddyParser (453) + ManhwatopParser (365) +
 * TapasticModels (258) + MangaParkDto (114) = 1190 lines.
 *
 * Preamble (lines 3-17) classified LIVE-NOT-STALE — Phase 7.2 migration receipts (Retrofit→
 * Ktor, jsoup→ksoup, FormBody→Map, @Inject drop, Logger, kotlinx.datetime). Same preamble
 * fingerprint as cluster196 leaves 336-340 — uniform Phase 7.2 sweep prose extended into
 * the standalone-parser helper tier.
 *
 * File-specific preamble classifications (4-bullet tail):
 *
 *   1. LIVE-NOT-STALE — Cairo timezone hard-coding (preamble bullet 8-10, code lines 113/116).
 *      `cairoZone = TimeZone.of("Africa/Cairo")` is preserved as a load-bearing "site-local"
 *      assumption — MangaBuddy's relative-date strings ("X hours ago") were generated against
 *      Cairo wall-clock in the upstream Android source. Preserving the zone keeps the
 *      relative→absolute date conversion correct against the same upstream baseline.
 *
 *   2. LIVE-NOT-STALE — `now.minusMinutes(n)`/`now.minusHours(n)` Instant-arithmetic migration
 *      (preamble bullet 11-13, code lines 141-142). Sub-day units are applied to the underlying
 *      Instant via `kotlin.time.Duration.minutes`/`.hours` THEN converted back to LocalDate;
 *      day+ units use `kotlinx.datetime.minus(amount, DateTimeUnit.*)` directly. Two-tier
 *      arithmetic preserves the upstream's day-boundary semantics.
 *
 *   3. LIVE-NOT-STALE — `abbreviatedMonthFormatter` DSL port (preamble bullet 14-15, code
 *      lines 70-76). DateTimeFormatter.ofPattern("MMM d, yyyy")→LocalDate.Format DSL with
 *      `monthName(MonthNames.ENGLISH_ABBREVIATED)`. Standard migration shape now confirmed
 *      across MangaBuddyParser sibling 341 + ManhwatopParser sibling 342 + cluster196 siblings
 *      337-340 + cluster195 + cluster194 — universal across :en/ tier.
 *
 *   4. LIVE-NOT-STALE — Standalone parser class preservation (preamble bullet 16). No @Inject,
 *      no `BaseMangaRepository` extension — `MangaBuddyParser` is a pure parser helper consumed
 *      by `MangaBuddyRepositoryV2` (cluster195 sibling, 681 lines). The separation-of-concerns
 *      is intentional: parser is stateless except for `parserVersion`/`baseUrlVersion` rotation
 *      counters; the Repository owns network + cache.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • POTENTIAL-BUG-PRESERVED — `blackListGenres` set at lines 428-448 contains a SINGLE live
 *     entry `"mmmmmm"` (scrambled keyboard-mash) PLUS 17 commented-out entries (Adult/Smut/
 *     Mature/Ecchi/Hentai/Yuri/Fetish/SM-BDSM-SUB-DOM/Incest/Omegaverse/Netorare-NTR/Bara(ML)/
 *     Shoujo(G)/Yaoi/Shounen ai/Gender Bender/Shouja ai). The "mmmmmm" string ensures
 *     `hasBlacklistedGenre()` (extension at lines 425-426) returns false for any real-world
 *     genre — effectively DISABLING the blacklist filter site-wide. Either intentional (user-
 *     content unfiltered on MangaBuddy) or development placeholder never replaced. Two adjacent
 *     levels of cross-out: 1-slash `//"Adult"` AND 2-slash `////"Mature"` + `////"Shoujo(G)"`
 *     suggesting a multi-stage filter-disable history. Preserved per §253 — observable behaviour
 *     is "no genre filtering on MangaBuddy".
 *
 *   • POTENTIAL-BUG-PRESERVED — Empty conditional block at lines 218-219: `if (hotSection !=
 *     null) { }`. The body is empty — does nothing. Likely a stripped-out Logger.i call or
 *     state-mutation that was deleted during cleanup but the conditional wrapper remained.
 *     Dead code. Preserved.
 *
 *   • COSMETIC-NOT-STALE — ChatGPT `:contentReference[oaicite:N]{index=N}` artifacts at lines
 *     164, 181, 320. LLM-paste fingerprint from the original Android port — characteristic
 *     trailing-comment shape that ChatGPT's web UI sometimes generates when copying code with
 *     citation badges. Recurring fingerprint across the :en/ tier (cluster196 leaves had
 *     similar; this leaf has 3 occurrences).
 *
 *   • FORECAST-NOT-YET-FULFILLED — `parserVersion = 1` (line 40) + `baseUrlVersion = 1` (line
 *     41) rotation counters. Cross-cluster pattern: same shape in ManhwatopParser sibling 342
 *     (lines 39-40). Scaffolding for an eventual parser-rotation or base-URL-rotation registry,
 *     not yet wired. The counters are READ by callers (presumably MangaBuddyRepositoryV2's
 *     init or rotate-baseURL flow) but the rotation mechanism itself is FORECAST.
 *
 *   • DEBT-NOT-STALE — `extractMangaInfo` at lines 255-311: 6 intermediate locals computed and
 *     DISCARDED (`otherNames` line 263, `ratingCount` line 268, `artist` line 275, `tags` line
 *     289, `yearOfProduction` line 292, `favoritesCount` line 295). The returned `MangaInfo`
 *     constructor at lines 298-310 sets only api/language/url/title/imageUrl/rating/description/
 *     author/genres/status/chapters — the unused locals are dead-write debt preserved for the
 *     eventual `MangaInfo` schema widening. Same fingerprint as ComickRepository sibling 340.
 *
 *   • POTENTIAL-BUG-PRESERVED — `MangaItem.rating = rating.toInt()` at line 199. Site provides
 *     a float (`toFloatOrNull()` at line 184, default 0f); cast to Int loses fractional
 *     precision (4.7 → 4). Domain `MangaItem` schema uses Int — this is a schema-conformance
 *     shim, not a bug per se, but observable as star-rating downgrade. Preserved.
 *
 *   • LIVE-NOT-STALE — `getChapterImages` 4-path fallback at lines 362-421 with a DATE-STAMPED
 *     historical incident note at lines 372-375 ("17/03/2023: Certain hosts only embed two
 *     pages in their `#chapter-images` and leave the rest to be lazily(?) loaded by javascript").
 *     The four paths: (a) JS-var `var mainServer` + `var chapImages` extraction with absolute-
 *     URL validation; (b) HTML `#chapter-images img, .chapter-image[data-src]` selector; (c)
 *     heuristic count comparison between JS and HTML to pick the higher count; (d) absolute-
 *     URL prefix recovery via `if (mainServer.startsWith("//")) "https:" else ""`. The 4-path
 *     fallback handles the upstream's gradual migration from server-side to JS-injected
 *     page lists. Historical-incident note is LIVE-NOT-STALE — still relevant context.
 *
 *   • LIVE-NOT-STALE — `parseDateString` relative-date chain at lines 118-159: "just now" →
 *     today, "yesterday" → today-1, "(N) (unit) ago" regex → minus the right unit, MMM d, yyyy
 *     fallback. Same shape as ManhwatopParser sibling 342's `parseChapterDate` (lines 300-345)
 *     but cluster197 leaf 1's variant uses Cairo timezone where leaf 2/4 uses system default.
 *     Cross-cluster pattern across parser-helper tier.
 *
 * Cross-cluster note: this leaf is the first :en/ Parser helper to receive a §253 postscript.
 * The parser-helper tier consumes the same kotlinx.datetime + ksoup + standalone-class
 * conventions as the Repository tier but lacks the BaseMangaRepository inheritance, the
 * `_cachedHeaders` Volatile cache, and the network-fetching surface. Cleaner separation
 * (parser is pure-function over HTML→Domain) than the Repository tier observed.
 *
 * Next leaf: ManhwatopParser.kt (sibling 342).
 */
