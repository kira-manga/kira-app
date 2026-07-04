package me.manga.kira.sources_repositry.en.manhwatop

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Notes specific to this file:
 *  - Standalone parser class (no @Inject, no base class) — preserved verbatim.
 *  - `DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)` etc. → `LocalDate.Format`
 *    DSL with `MonthNames.ENGLISH_FULL` / `ENGLISH_ABBREVIATED`. ISO `yyyy-MM-dd` keeps
 *    `LocalDate.parse(text)` (default ISO).
 *  - `LocalDate.now()` → `Clock.System.todayIn(TimeZone.currentSystemDefault())`.
 *  - `LocalDate.minusDays/Weeks/Months/Years` → `kotlinx.datetime.minus(amount, DateTimeUnit.*)`.
 *  - `extractMangaInfo` is `suspend` in source (because the V2 repo subclass overrides it and
 *    calls a suspending `fetchChapters`); the parser itself doesn't actually suspend, but the
 *    signature is preserved to keep API compatibility with anyone who calls the parser.
 */

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga

@OptIn(ExperimentalTime::class)
class ManhwatopParser {


    val parserVersion = 1
    val baseUrlVersion = 1
    val API = "Manhwatop"
    val LANGUAGE = "(EN)"
    val baseUrl = "https://manhwatop.com/"
    val homeUrl = "${baseUrl}wp-admin/admin-ajax.php"
    val popularUrl = "${baseUrl}wp-admin/admin-ajax.php"

    fun normalSearchUrl(q: String): String = "${baseUrl}?s=${q}&post_type=wp-manga"

    private fun todayInSystem(): LocalDate =
        Clock.System.todayIn(TimeZone.currentSystemDefault())

    private val englishFullMonthDayYearFormatter = LocalDate.Format {
        monthName(MonthNames.ENGLISH_FULL)
        char(' ')
        day()
        chars(", ")
        year()
    }

    private val englishAbbrMonthDayYearFormatter = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        day()
        chars(", ")
        year()
    }

    private val dayMonthAbbrYearFormatter = LocalDate.Format {
        day()
        char(' ')
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        year()
    }

    fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val document = Ksoup.parse(html)
        val elements = document.select(".page-listing-item .page-item-detail")

        return elements.mapNotNull { el ->
            // ----- ID & Thumb -----
            val thumb = el.selectFirst(".item-thumb")
            val id = thumb?.attr("data-post-id")?.toIntOrNull() ?: return@mapNotNull null

            // ----- Title & Link -----
            val titleLink = el.selectFirst(".item-summary .post-title a") ?: return@mapNotNull null
            val title = titleLink.text().trim()
            val url = titleLink.attr("href").trim()

            // ----- Image URL -----
            val imgEl = thumb.selectFirst("img")
            // use data-src for the real image
            val imageUrl = imgEl?.attr("data-src")?.trim().orEmpty()

            // ----- Rating -----
            // note: now parsing as Double (e.g. "4.2")
            val ratingText = el.selectFirst(".item-summary .total_votes")?.text()?.trim()
            val rating = ratingText?.toDoubleOrNull() ?: 0.0

            // ----- Chapters -----
            val chapterElements = el.select(".list-chapter .chapter-item")
            val chapters = chapterElements.mapNotNull { chapEl ->
                val linkEl = chapEl.selectFirst(".chapter a")
                val postedOnEl = chapEl.selectFirst(".post-on")
                if (linkEl != null && postedOnEl != null) {
                    val chapTitle = linkEl.text().trim()
                    val chapUrl = linkEl.attr("href").trim()
                    val postedOn = postedOnEl.text().trim()
                    ChapterItem(
                        name = chapTitle,
                        number = chapTitle,
                        url = chapUrl,
                        date = parseChapterDate(postedOn)
                    )
                } else null
            }

            MangaItem(
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = rating.toInt(),
                chapters = chapters,
                api = API,
                language = LANGUAGE,
                genres = listOf()
            )
        }.toMutableList()
    }

    fun extractMangaList(html: String): List<PopularManga> {
        val document = Ksoup.parse(html)
        val elements = document.select(".page-listing-item .page-item-detail")

        return elements.mapNotNull { el ->
            // ----- ID & Thumb -----
            val thumb = el.selectFirst(".item-thumb")

            // ----- Title & Link -----
            val titleLink = el.selectFirst(".item-summary .post-title a") ?: return@mapNotNull null
            val title = titleLink.text().trim()
            val url = titleLink.attr("href").trim()

            // ----- Image URL -----
            val imgEl = thumb?.selectFirst("img")
            // use data-src for the real image
            val imageUrl = imgEl?.attr("data-src")?.trim().orEmpty()


            PopularManga(
                title = title,
                url = url,
                imageUrl = imageUrl,
                api = API,
                language = LANGUAGE,
            )
        }
    }

    suspend fun extractMangaInfo(html: String, url: String): MangaInfo {
        val doc = Ksoup.parse(html)

        // Title
        val title = doc.selectFirst("div.post-title h1")
            ?.text()?.trim()
            ?: ""  // :contentReference[oaicite:0]{index=0}

        // Cover image (using data-src for the high-res URL)
        val imageUrl = doc.selectFirst("div.summary_image img")
            ?.attr("data-src")
            ?: ""  // :contentReference[oaicite:1]{index=1}

        // Rating (score) and rating count (total votes)
        val rating = doc.selectFirst("div.post-total-rating span.score")
            ?.text()
            ?: "0"  // :contentReference[oaicite:2]{index=2}
        val ratingCount = doc.selectFirst("div.post-total-rating span.total_votes")
            ?.text()
            ?: "0"  // :contentReference[oaicite:3]{index=3}

        // Description
        val description = doc.selectFirst("div.summary_content_wrap div.post-content")
            ?.text()?.trim()
            ?: ""  // :contentReference[oaicite:4]{index=4}

        // Author(s)
        val authors = doc.select("div.author-content a")
            .eachText()
            .toMutableList()  // :contentReference[oaicite:5]{index=5}

        // Artist(s)
        val artists = doc.select("div.artist-content a")
            .eachText()
            .toMutableList()  // :contentReference[oaicite:6]{index=6}

        // Genre(s)
        val genres = doc.select("div.genres-content a")
            .eachText()
            .toMutableList()  // :contentReference[oaicite:7]{index=7}

        // Status (e.g. OnGoing, Completed)
        val status = doc.selectFirst("div.summary-content.mg_status")
            ?.text()?.trim()
            ?: "Unknown"  // :contentReference[oaicite:8]{index=8}

        // Chapters (unchanged from your original logic)
        val chapters = doc.select("ul.main.version-chap li.wp-manga-chapter:not(.premium-block)")
            .map { element ->
                val link = element.selectFirst("a")!!
                val chapterNumber = link.text()
                val chapterUrl = link.attr("href")
                val dateElem = element.selectFirst("span.chapter-release-date")
                val isNew = dateElem?.select("span.c-new-tag")?.isNotEmpty() == true
                val dateText = if (isNew) {
                    dateElem!!.select("img").attr("alt").ifEmpty { "NEW" }
                } else {
                    dateElem?.select("i")?.text() ?: "UNKNOWN"
                }
                val chpNumOnly = chapterNumber.replace(Regex("[^\\d.]"), "")  // removes all non‐digits, yields "245"

                ChapterItem(
                    number = chpNumOnly.ifBlank { chapterNumber },
                    name = chapterNumber,
                    url = chapterUrl,
                    date = parseChapterDate(dateText) ?: todayInSystem(),
                    isDownloaded = false
                )
            }.toMutableList()

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = title,
            imageUrl = imageUrl,
            rating = rating,
            description = description,
            author = authors.toString(),
            genres = genres,
            status = status,
            chapters = chapters
        )
    }

    fun getSearchResults(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html)

        return doc
            // each "card" is a row with class c-tabs-item__content
            .select("div.row.c-tabs-item__content")                                       // :contentReference[oaicite:0]{index=0}
            .mapNotNull { card ->
                // 1. Cover image: they lazy-load with data-src, fallback to src if needed
                val imgEl = card.selectFirst("div.tab-thumb a img")
                    ?: return@mapNotNull null
                val imageUrl = imgEl.attr("data-src")
                    .takeIf { it.isNotBlank() }
                    ?: imgEl.absUrl("src")                                                 // :contentReference[oaicite:1]{index=1}

                // 2. Title and page URL
                val titleA = card.selectFirst("div.post-title h2.h5 a")
                    ?: return@mapNotNull null
                val title = titleA.text().trim()
                val pageUrl = titleA.absUrl("href")                                       // :contentReference[oaicite:2]{index=2}

                // 3. Genres (if any): look for the "mg_genres" block under post-content
                val genres = card
                    .select("div.post-content_item.mg_genres .summary-content a")
                    .map { it.text().trim() }                                              // :contentReference[oaicite:3]{index=3}

                // 4. Rating: count full-star and half-star icons
                val rating = card.selectFirst("div.meta-item.rating")
                    ?.select("i.ion-ios-star, i.ion-ios-star-half")
                    ?.size
                    ?: 0                                                                  // :contentReference[oaicite:4]{index=4}

                MangaItem(
                    api = API,
                    language = LANGUAGE,
                    title = title,
                    url = pageUrl,
                    imageUrl = imageUrl,
                    rating = rating,
                    chapters = emptyList(),  // search results do not include chapters
                    genres = genres
                )
            }
    }

    fun getChapterImages(html: String): List<String> {
        val doc: Document = Ksoup.parse(html)
        // select all the chapter images (they're lazy-loaded with data-src)
        return doc.select("div.read-container img.wp-manga-chapter-img")
            .map { img ->
                // prefer data-src if present, otherwise fall back to src
                val urlAttr = if (img.hasAttr("data-src")) "data-src" else "src"
                img.absUrl(urlAttr)
            }
    }

    private fun parseChapterDate(dateText: String): LocalDate? {
        val now = todayInSystem()
        val txt = dateText.trim().lowercase()

        // 1) "NEW" → today
        if (txt == "new") return now

        // 2) Relative times: "5 days ago", "2 weeks ago", "3 hours ago", etc.
        val relRegex = """(\d+)\s*(second|minute|hour|day|week|month|year)s?\s*ago""".toRegex()
        relRegex.find(txt)?.let { match ->
            val (amountStr, unit) = match.destructured
            val amount = amountStr.toLong()
            return when (unit) {
                "second", "minute", "hour" -> now
                "day" -> now.minus(amount.toInt(), DateTimeUnit.DAY)
                "week" -> now.minus(amount.toInt(), DateTimeUnit.WEEK)
                "month" -> now.minus(amount.toInt(), DateTimeUnit.MONTH)
                "year" -> now.minus(amount.toInt(), DateTimeUnit.YEAR)
                else -> now
            }
        }

        // 3) Try absolute date formats
        val formatters = listOf(
            englishFullMonthDayYearFormatter,  // e.g. March 5, 2025
            englishAbbrMonthDayYearFormatter,  // e.g. Mar 5, 2025
            dayMonthAbbrYearFormatter          // e.g. 5 Mar 2025
        )
        for (fmt in formatters) {
            try {
                return LocalDate.parse(dateText, fmt)
            } catch (_: Exception) {
                // try next
            }
        }

        // Try ISO yyyy-MM-dd (default parser)
        try {
            return LocalDate.parse(dateText)
        } catch (_: Exception) {
            // give up
        }

        // If nothing matched, give up
        return null
    }


    val blackListGenres: Set<String>
        get() = setOf(
//            "Smut",
//            "Yaoi",
//            "Doujinshi",
//            "Lolicon",
//            "Yaoi",
//            "Adult",
//            "Yuri",
//            "Soft Yuri",
//            "Soft Yaoi",
//            "Yaoi",
//            "Shoujo Ai",
//            "Shounen Ai",
        )


}

/*
 * Audit-trail postscript (Phase 9.x.cluster197.staleKdocSweep.cascade, Task #652, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster197 leaf 2/4 — sibling 342. Continues the :en/ Parser helper closing batch.
 *
 * Preamble (lines 3-17) classified LIVE-NOT-STALE — Phase 7.2 migration receipts with 5
 * file-specific bullets covering: standalone-class preservation, 3-formatter date chain
 * (MMMM d, yyyy / MMM d, yyyy / d MMM yyyy + ISO fallback), Clock.System.todayIn, kotlinx
 * minus-by-unit, and the FORWARD-COMPAT suspend signature on extractMangaInfo (line 160).
 *
 * File-specific preamble classifications:
 *
 *   1. LIVE-NOT-STALE — Standalone parser class (preamble bullet 8). Same pattern as
 *      MangaBuddyParser sibling 341.
 *
 *   2. LIVE-NOT-STALE — `DateTimeFormatter.ofPattern("MMMM d, yyyy")` → LocalDate.Format DSL
 *      with `MonthNames.ENGLISH_FULL` (preamble bullet 9-11, code lines 52-66). Plus the
 *      abbreviated variant + the `d MMM yyyy` (day-first) variant + ISO fallback. 4-tier
 *      date parser. Same shape as ComickRepository sibling 340 and BatcaveRepository sibling
 *      339 — broader than MangaBuddyParser sibling 341 (which has only 1 formatter).
 *
 *   3. LIVE-NOT-STALE — `LocalDate.now()` → `Clock.System.todayIn(TimeZone.currentSystemDefault())`
 *      (preamble bullet 12, code lines 49-50). SYSTEM-DEFAULT timezone, NOT Cairo — contrasts
 *      with MangaBuddyParser sibling 341 which hard-codes Cairo. The two sibling parsers chose
 *      different tz baselines for their respective sites.
 *
 *   4. LIVE-NOT-STALE — `kotlinx.datetime.minus(amount, DateTimeUnit.*)` direct-on-LocalDate
 *      arithmetic (preamble bullet 13, code lines 314-317). Sub-day units collapse to "now"
 *      (lines 313-314 `"second", "minute", "hour" -> now`) — note this drops the amount for
 *      sub-day relative dates. Could be classified POTENTIAL-BUG-PRESERVED — see body section.
 *
 *   5. FULFILLED-PORT — `suspend fun extractMangaInfo` signature preservation (preamble bullet
 *      14-16, code line 160). The function does NOT actually suspend, but the signature is
 *      preserved for API compat with anyone overriding it (e.g. `ManhwatopRepositoryV2` cluster195
 *      sibling). Deliberate forward-compat shim — documented in preamble.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • COSMETIC-NOT-STALE — ChatGPT `:contentReference[oaicite:N]{index=N}` artifacts at lines
 *     166, 171, 176, 179, 184, 189, 194, 199, 204 (NINE occurrences in extractMangaInfo) PLUS
 *     lines 250, 257, 263, 268, 274 (FIVE occurrences in getSearchResults) = 14 total. HEAVIEST
 *     `:contentReference` concentration observed across the cluster196 + cluster197 sweep
 *     (cluster196 leaves had 2-3 occurrences each). Suggests this file was the most direct
 *     ChatGPT-paste port — likely all 14 citations originated from a single multi-turn
 *     ChatGPT conversation when porting `extractMangaInfo`.
 *
 *   • POTENTIAL-BUG-PRESERVED — Sub-day relative-date collapse at lines 313-314:
 *     `"second", "minute", "hour" -> now`. The regex at line 308 CAPTURES the amount for these
 *     units, but the when-branch discards it and returns today's date unchanged. Three reads:
 *     (a) intentional (LocalDate has day-level precision; "5 hours ago" still maps to today —
 *     defensible), (b) bug (should subtract from LocalDateTime then convert), (c) sub-day
 *     resolution simply isn't useful for the chapter-list domain (chapters published in the
 *     last 24h all map to today). Preserved per §253 — observable behaviour unchanged.
 *
 *   • POTENTIAL-BUG-PRESERVED — `MangaInfo.author = authors.toString()` at line 238. The
 *     `authors` is a `MutableList<String>` (line 187-189); calling `.toString()` on a list
 *     produces the format `[a, b, c]` with brackets and comma-space separators. Likely
 *     intended `authors.joinToString(", ")` — the bracket-wrapped format is observable on the
 *     details screen. Same `joinToString` idiom is used correctly in MangaBuddyParser sibling
 *     341 (line 274) for the same field. Preserved per §253.
 *
 *   • COSMETIC-NOT-STALE — Empty `blackListGenres` getter at lines 348-362 (all 12 entries
 *     commented out, including duplicate "Yaoi" entries at lines 351/354/359 — a triple
 *     commented-out copy). The set returns empty — `hasBlacklistedGenre()` is effectively
 *     disabled for Manhwatop. Same "disabled filter" outcome as MangaBuddyParser sibling 341's
 *     `"mmmmmm"` placeholder, achieved via different mechanism (empty set vs unmatchable string).
 *     Cross-cluster pattern: cluster197 parser-helpers both ship disabled blacklist filters.
 *
 *   • FORECAST-NOT-YET-FULFILLED — `parserVersion = 1` + `baseUrlVersion = 1` (lines 39-40).
 *     Identical rotation-counter scaffolding to MangaBuddyParser sibling 341.
 *
 *   • DEBT-NOT-STALE — `extractMangaInfo` unused locals at lines 177, 187-188, 192-193:
 *     `ratingCount`, `authors` (used only via the `.toString()` bug-shim), `artists` (computed
 *     line 192-194, never set on returned MangaInfo). Same dead-write fingerprint as
 *     MangaBuddyParser sibling 341 + ComickRepository sibling 340.
 *
 *   • DEBT-NOT-STALE — `extractHomeMangaItems` unused `id` local at line 83: `val id =
 *     thumb?.attr("data-post-id")?.toIntOrNull() ?: return@mapNotNull null`. The `id` is
 *     COMPUTED + drives a not-null guard (returns null if missing), but is never used in the
 *     `MangaItem` mapping (lines 118-127). Required-for-guard but not required-for-payload.
 *     Subtle "computed-for-side-effect" pattern.
 *
 *   • LIVE-NOT-STALE — `getChapterImages` data-src/src fallback at lines 289-298. 2-path
 *     selector chain (preferred lazy-loaded `data-src` attribute, fallback to `src`). Simpler
 *     than MangaBuddyParser sibling 341's 4-path chain — Manhwatop ships static HTML, no JS
 *     injection layer.
 *
 *   • LIVE-NOT-STALE — `extractMangaInfo` chapters parsing at lines 207-228 handles the
 *     "NEW" tag specially: detects via `span.c-new-tag` presence, extracts via
 *     `dateElem!!.select("img").attr("alt").ifEmpty { "NEW" }` and routes through
 *     `parseChapterDate("NEW")` which short-circuits to `now` at line 305. Domain-specific
 *     freshness marker preserved.
 *
 *   • POTENTIAL-BUG-PRESERVED — `!!` non-null assertion at line 215: `dateElem!!.select("img")
 *     .attr("alt")`. Triggered only inside the `if (isNew)` branch, but the `isNew` check
 *     uses `dateElem?.select(...)?.isNotEmpty() == true` — if `dateElem` is null, `isNew`
 *     is false and the `!!` branch is unreachable. Safety: provable-non-null via control-flow.
 *     Preserved per §253 (outside :domain/:presentation banned-feature scope).
 *
 * Next leaf: TapasticModels.kt (sibling 343).
 */
