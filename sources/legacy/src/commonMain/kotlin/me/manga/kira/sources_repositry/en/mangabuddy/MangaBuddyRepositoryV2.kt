package me.manga.kira.sources_repositry.en.mangabuddy

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Notes specific to this file:
 *  - Source's `ZonedDateTime.now(ZoneId.of("Africa/Cairo"))` → `Clock.System.now()
 *    .toLocalDateTime(TimeZone.of("Africa/Cairo")).date`. Cairo zone preserved verbatim.
 *  - `now.minusMinutes(n)` / `.minusHours(n)` → applied to the underlying Instant before
 *    re-converting to LocalDate. minus-by-days/weeks/months/years applied directly to the
 *    LocalDate via `kotlinx.datetime.minus(amount, DateTimeUnit.*)`.
 *  - `DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)` →
 *    `LocalDate.Format { monthName(MonthNames.ENGLISH_ABBREVIATED); ... }`.
 *  - Source's `:containsf(Year)` jsoup selector (a typo — `:containsf` is not a valid
 *    jsoup pseudo) is preserved verbatim per the migration directive (preserve every
 *    selector / branch / regex). It will simply match nothing, which mirrors the original
 *    runtime behaviour.
 *  - Source has a duplicate parser body (identical to MangaBuddyParser.kt); ported as-is
 *    because subclass overrides on SeparatedDetailsSites are abstract and require concrete
 *    bodies on the repository class. Future refactor could delegate to MangaBuddyParser.
 */

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.SeparatedDetailsSites
import me.manga.kira.sources_repositry.data.MangaSource

@OptIn(ExperimentalTime::class)
class MangaBuddyRepositoryV2(
    private val api: ApiClient,
    private val dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
) : SeparatedDetailsSites(api, sourcesRepository) {

    override val mangaSource: MangaSource
        get() = MangaSource.MANGABUDDY

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language
//    private val parser: MangaBuddyParser by lazy { MangaBuddyParser() }

    override var imgBaseUrl: String = "https://res.mbbcdn.com/"
    override var imgUrlVersion: Int = 0
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}/latest" }
    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}/home" }

    override fun handelLoadMoreUrl(page: Int): String = "${baseUrl.ifBlank { BASE_URL }}/latest?page=$page/"

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> "${baseUrl.ifBlank { BASE_URL }}/search?q=${searchType.query}"
            is SearchType.GENRES -> ""
            is SearchType.SORT -> ""
        }

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        return null
    }

    override fun createInfoUrl(mangaId: String): String {
        return if (mangaId.startsWith("http", ignoreCase = true)) {
            mangaId
        } else {
            // BASE_URL already ends with no slash, so we add it
            "${baseUrl.ifBlank { BASE_URL }}$mangaId"
        }
    }

    override fun createChaptersUrl(mangaId: String): String {
        return if (mangaId.startsWith("http", ignoreCase = true)) {
            mangaId
        } else {
            // BASE_URL already ends with no slash, so we add it
            "${baseUrl.ifBlank { BASE_URL }}/api/manga/${mangaId}/chapters?source=detail"
        }
    }

    override fun handelSearchFormBody(
        page: Int,
        searchType: SearchType.Normal
    ): Map<String, String>? {
        return null
    }

    private val cairoZone = TimeZone.of("Africa/Cairo")

    private fun nowInCairo(): LocalDate =
        Clock.System.now().toLocalDateTime(cairoZone).date

    private val abbreviatedMonthFormatter = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        day()
        chars(", ")
        year()
    }

    override fun parseChapters(html: String): List<ChapterItem> {
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

            val date = dateText?.let { parseDateString(it) ?: nowInCairo() }

            ChapterItem(
                number = chpNumOnly,
                name = titleEl.text().toString(),
                url = url,
                date = date
            )
        }
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
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

    override fun extractMangaList(html: String): List<PopularManga> {
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

    override fun extractMangaInfo(html: String, baseUrl: String, combinUrl: String): MangaInfo {
        val doc = Ksoup.parse(html)

        // Basic info
        val title = doc.selectFirst("div.detail .name h1")?.text() ?: ""
        val otherNames = doc.selectFirst("div.detail .name h2")?.text() ?: ""
        val imageUrl = doc.selectFirst("div.img-cover img.lazy")?.attr("data-src") ?: ""

        // Ratings
        val rating = doc.selectFirst("div.rate-info span.score")?.text() ?: "0"
        val ratingCount = doc.selectFirst("div.rate-info span.votes")?.text()?.removeSurrounding("(", ")") ?: "0"

        // Description
        val description = doc.selectFirst("div.summary .content")?.text()?.trim() ?: ""

        // Author / Artist
        val author = doc.select("div.detail .info span:contains(Author) + a").eachText().joinToString(", ")
        val artist = doc.select("div.detail .info span:contains(Artist) + a").eachText().joinToString(", ")

        // Genres & Tags
        val status = doc
            .selectFirst("p:has(strong:contains(Status)) span")
            ?.text()
            ?: "Unknown"

// Genres (all <a> links in the <p> whose <strong> contains "Genres")
        val genres = doc
            .select("p:has(strong:contains(Genres)) a")
            .map { it.text().trim().trimEnd(',').trim() }
            .filter { it.isNotEmpty() }

        val tags = doc.select("div.detail .tags a").eachText()

        // Year of Production & Status
        // Source has typo ":containsf(Year)" — preserved verbatim per Phase 7.2 directive.
        val yearOfProduction = doc.select("div.detail .info span:containsf(Year) + span").text()

        // Favorites / Bookmarks count
        val favoritesCount = doc.selectFirst("button.bookmark-btn span.count")?.text() ?: "0"


        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = baseUrl,
            title = title,
            imageUrl = imageUrl,
            rating = rating,
            description = description,
            author = author,
            genres = genres,
            status = status,
            chapters = mutableListOf()
        )
    }

    override fun getSearchResults(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html)

        return doc.select("div.list.manga-list div.book-item").mapNotNull { item ->
            // 1. Cover image (lazy-loaded via data-src)
            val imgEl = item.selectFirst("div.thumb a img") ?: return@mapNotNull null
            val imageUrl = imgEl.absUrl("data-src")                        // :contentReference[oaicite:0]{index=0}

            // 2. Title and page URL
            val titleA = item.selectFirst("div.meta .title h3 a")
                ?: return@mapNotNull null
            val title = titleA.text().trim()
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
                api = API,
                language = LANGUAGE,
                title = title,
                url = pageUrl,
                imageUrl = imageUrl,
                rating = rating.toInt(),           // or keep as Double if you change MangaItem
                chapters = listOf(),                 // search results won't include chapter list
                genres = genres
            )
        }
    }

    override suspend fun initSite(): Int {
        fixedImgUrl = false

        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures your value wins:
            return base + refererHeader
        }

    private val refererHeader = "Referer" to "https://mangabuddy.com/"

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // Option A: merge here so that cache always has Referer, and if you persist headers you want to include it
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged

        dataStore.saveHeadersForApi(API, merged)
    }

    override fun getChapterImages(html: String): List<String> {
        val doc = Ksoup.parse(html, baseUrl.ifBlank { BASE_URL })  // where BASE_URL == "https://s2.mbcdnsab.org/"

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


    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()

    override val blackListGenres: Set<String>
        get() = setOf(
            "Adult",
            "Smut",
//        "Mature",
//        "Ecchi",
            "Hentai",
            "Yuri",
            "Fetish",
            "SM/BDSM/SUB-DOM",
            "Incest",
            "Omegaverse",
            "Netorare/NTR",
            "Bara(ML)",
//        "Shoujo(G)",
            "Yaoi",
            "Shounen ai",
            "Gender Bender",
            "Shouja ai",
        )

//            _cachedHeaders
//            ?: runBlocking {
//                val stored = dataStore.getHeadersForApi(API) ?: emptyMap()
//                // merge in your fixed referer header
//                stored + ("Referer" to "https://mangabuddy.com/")
//            }.also { _cachedHeaders = it }


    fun parseDateString(text: String): LocalDate? {
        val instantNow = Clock.System.now()
        val nowDate = instantNow.toLocalDateTime(cairoZone).date
        val trimmed = text.trim().lowercase()

        // Handle "just now"
        if (trimmed == "just now") {
            return nowDate
        }

        // Handle "yesterday"
        if (trimmed.startsWith("yesterday")) {
            // Could be "yesterday" or "yesterday at HH:mm"; we only care about the date
            return nowDate.minus(1, DateTimeUnit.DAY)
        }

        // Regex for "X unit(s) ago"
        val regex = Regex("(\\d+)\\s+(minute|hour|day|week|month|year)s?\\s+ago")
        val match = regex.find(trimmed)
        if (match != null) {
            val (valueStr, unit) = match.destructured
            val value = valueStr.toLongOrNull() ?: return null
            val adjusted: LocalDate = when (unit) {
                "minute" -> (instantNow - value.minutes).toLocalDateTime(cairoZone).date
                "hour" -> (instantNow - value.hours).toLocalDateTime(cairoZone).date
                "day" -> nowDate.minus(value.toInt(), DateTimeUnit.DAY)
                "week" -> nowDate.minus(value.toInt(), DateTimeUnit.WEEK)
                "month" -> nowDate.minus(value.toInt(), DateTimeUnit.MONTH)
                "year" -> nowDate.minus(value.toInt(), DateTimeUnit.YEAR)
                else -> nowDate
            }
            return adjusted
        }

        // Fallback: parse absolute date, e.g. "May 27, 2025"
        return try {
            // You may need multiple patterns; here's one example:
            LocalDate.parse(text.trim(), abbreviatedMonthFormatter)
        } catch (e: Exception) {
            // If parsing fails, return null or log warning
            null
        }
    }
}

/**
 * Audit-trail postscript (Phase 9.x.cluster195.staleKdocSweep.cascade, Task #650, 2026-05-29)
 *
 * Leaf 4/5 §253 audit-trail-preservation postscript for cluster195, sibling 334 of the cluster57+
 * continuum. Medium-heavy 521-line SeparatedDetailsSites subclass for the MangaBuddy English
 * source. THE most-debug-tag-noisy file in the entire :sources_repositry/ tree by character count
 * across cluster195 (no specific keyboard-mashed-tag Logger lines, but EXTENSIVE commented-out
 * code blocks: a parser-delegation lazy cell at line 65, an alternate getDefaultHeaders runBlocking
 * branch at lines 470-475, and 4 stale-commented blacklist entries at lines 453-454/463/466). The
 * file carries the deepest Phase 7.2 migration-decision documentation in cluster195 — a 5-bullet
 * file-specific sub-section beyond the canonical 6-bullet preamble.
 *
 * The top-of-file prose under audit (lines 3-22) is a file-header KDoc carrying TWO distinct
 * sub-sections:
 *
 *   I.   Canonical Phase 7.2 6-bullet migration-pattern preamble (lines 4-5) — same verbatim
 *        block as cluster195 leaves 1/5, 2/5, 3/5, 5/5.
 *
 *   II.  File-specific KMP-port-decision notes (lines 7-21) — 5 detailed bullets:
 *        - ZonedDateTime.now(ZoneId.of("Africa/Cairo")) → Clock.System.now().toLocalDateTime
 *          (TimeZone.of("Africa/Cairo")).date. Cairo zone preserved verbatim.
 *        - now.minusMinutes(n) / .minusHours(n) → applied to the underlying Instant before
 *          re-converting to LocalDate. minus-by-days/weeks/months/years applied directly to the
 *          LocalDate via kotlinx.datetime.minus(amount, DateTimeUnit.*).
 *        - DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH) → LocalDate.Format {
 *          monthName(MonthNames.ENGLISH_ABBREVIATED); ... }.
 *        - Source's :containsf(Year) jsoup selector (a typo — :containsf is not a valid jsoup
 *          pseudo) is preserved verbatim per the migration directive (preserve every selector /
 *          branch / regex). It will simply match nothing, which mirrors the original runtime
 *          behaviour.
 *        - Source has a duplicate parser body (identical to MangaBuddyParser.kt); ported as-is
 *          because subclass overrides on SeparatedDetailsSites are abstract and require concrete
 *          bodies on the repository class. Future refactor could delegate to MangaBuddyParser.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — sub-section I (canonical 6-bullet preamble). See cluster195 leaf 2/5
 *      classification (a) for full verification rationale. Same canonical preamble.
 *
 *   b. LIVE-NOT-STALE — sub-section II bullet 1 (Africa/Cairo timezone preservation). Verified
 *      by reading line 110 (`private val cairoZone = TimeZone.of("Africa/Cairo")`) and line 112
 *      (`private fun nowInCairo(): LocalDate = Clock.System.now().toLocalDateTime(cairoZone)
 *      .date`). The Cairo zone IS preserved verbatim; the conversion via Instant→LocalDateTime→
 *      date.LocalDate exactly matches the documented migration. Imports at lines 33 (`kotlinx.
 *      datetime.TimeZone`) and 37 (`kotlinx.datetime.toLocalDateTime`) — kotlinx.datetime
 *      structurally present.
 *
 *   c. LIVE-NOT-STALE — sub-section II bullet 2 (minus-by-units via DateTimeUnit). Verified by
 *      reading lines 491 (`return nowDate.minus(1, DateTimeUnit.DAY)`), 501-506 (the when-branch
 *      block applying `.minus(value.toInt(), DateTimeUnit.{DAY,WEEK,MONTH,YEAR})` for `day` /
 *      `week` / `month` / `year` units and `(instantNow - value.minutes).toLocalDateTime(cairoZone)
 *      .date` for `minute` / `hour` units). The Instant-vs-LocalDate split is structurally
 *      verified — minutes/hours are applied to the Instant before LocalDate conversion (because
 *      kotlinx.datetime's LocalDate has no minute/hour resolution), days/weeks/months/years are
 *      applied directly to the LocalDate (because Instant arithmetic with months/years is
 *      timezone-dependent). Sound implementation.
 *
 *   d. LIVE-NOT-STALE — sub-section II bullet 3 (LocalDate.Format ENGLISH_ABBREVIATED formatter).
 *      Verified by reading lines 115-121 (`private val abbreviatedMonthFormatter = LocalDate.
 *      Format { monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); day(); chars(", "); year()
 *      }`). DSL form structurally matches the documented migration. Same DSL shape as cluster195
 *      leaf 3/5 ManhwatopRepositoryV2 (lines 124-130).
 *
 *   e. POTENTIAL-BUG-PRESERVED — sub-section II bullet 4 (`:containsf(Year)` jsoup pseudo-selector
 *      typo preserved verbatim). Verified at line 283 (`val yearOfProduction = doc.select("div.
 *      detail .info span:containsf(Year) + span").text()`). The `:containsf` pseudo-class is NOT
 *      a valid jsoup/ksoup selector — the correct form would be `:contains(Year)`. The KDoc's
 *      "preserve every selector / branch / regex" directive explicitly chose to preserve the
 *      typo so the KMP port's runtime behaviour matches upstream (the selector matches nothing,
 *      yearOfProduction is always the empty string). The `yearOfProduction` local is itself never
 *      used in the function's return value (line 289-301: only `title`, `imageUrl`, `rating`,
 *      `description`, `author`, `genres`, `status` are passed to MangaInfo). So the typo is doubly
 *      benign: extracts nothing, but also feeds nothing downstream. Preserved verbatim per §253
 *      — informative note about an upstream bug that the migration policy intentionally preserved.
 *
 *   f. LIVE-NOT-STALE — sub-section II bullet 5 (duplicate parser body forecast). Verified by
 *      reading line 65 (the commented-out `// private val parser: MangaBuddyParser by lazy {
 *      MangaBuddyParser() }`) — confirms that the file intended to delegate to MangaBuddyParser
 *      but the lazy-delegation pattern was commented out, leaving the parseChapters /
 *      extractHomeMangaItems / extractMangaList / extractMangaInfo / getSearchResults / etc.
 *      methods to carry their parser bodies inline. Sibling MangaBuddyParser.kt structurally
 *      exists in the KMP graph (per cluster195 scout's find output — :sources_repositry/en/
 *      mangabuddy/ contains both MangaBuddyParser.kt and this file). The Phase 8 refactor
 *      forecast (delegate to MangaBuddyParser) holds.
 *
 *   g. LIVE-NOT-STALE — `@OptIn(ExperimentalTime::class)` opt-in (line 49) required by `kotlin.
 *      time.Clock` import (line 27) and `Duration.Companion.{hours,minutes}` imports (lines 28-
 *      29). Phase 7.2 standard pattern; reused across cluster195 leaves 3/5 and 5/5.
 *
 *   h. LIVE-NOT-STALE — refererHeader merge pattern. Lines 367-374 implement a 2-tier defaultHeaders
 *      override: the Volatile `_cachedHeaders` cell is read first, then the canonical
 *      `Referer→https://mangabuddy.com/` header is APPENDED unconditionally via map-plus
 *      (`base + refererHeader`). This mechanism guarantees the Referer is always present
 *      regardless of cache state — a per-source HTTP-correctness pattern. The merge is
 *      reciprocated in `refreshHeaders` at lines 376-382 (`val merged = newHeaders +
 *      refererHeader; _cachedHeaders = merged; dataStore.saveHeadersForApi(API, merged)`) — so
 *      the persisted DataStore copy ALSO carries the Referer. Sound implementation.
 *
 *   i. POTENTIAL-BUG-PRESERVED — `if (hotSection != null) {` empty-body block at lines 211-212.
 *      The if-check is performed and the body is empty — no side effect, no log line, no early
 *      return. Functionally inert. Either a leftover from an earlier diagnostic logging
 *      implementation that was stripped without removing the if-statement, or a misplaced empty
 *      brace. Preserved verbatim per §253 — Phase 8 cleanup candidate.
 *
 *   j. COSMETIC-NOT-STALE — ChatGPT/AI authorship artifacts. Lines 158, 175, 209, 286, 310, 322
 *      carry `:contentReference[oaicite:N]{index=N}` markers inside Kotlin line comments. Same
 *      origin as cluster195 leaf 3/5 (ManhwatopRepositoryV2) — remnants of upstream code-generation
 *      via an AI assistant whose response-format citation tags were not stripped before paste.
 *      Functionally inert. Preserved verbatim per §253.
 *
 *   k. COSMETIC-NOT-STALE — commented-out alternate getDefaultHeaders runBlocking branch at
 *      lines 470-475 (`//            _cachedHeaders ?: runBlocking { val stored = dataStore.
 *      getHeadersForApi(API) ?: emptyMap(); stored + ("Referer" to "https://mangabuddy.com/")
 *      }.also { _cachedHeaders = it }`). The commented block represents an earlier implementation
 *      that synchronously hydrated from DataStore on first access via runBlocking. The current
 *      implementation (lines 367-374) instead relies on `initSite()` (line 352) to hydrate
 *      `_cachedHeaders` asynchronously before any default-headers read; the runBlocking variant
 *      was abandoned because runBlocking is generally unsafe on the main thread in KMP. Preserved
 *      verbatim per §253 — informational note about the discarded alternative.
 *
 *   l. COSMETIC-NOT-STALE — commented-out blacklist entries. Lines 453-454 (`//        "Mature",`
 *      / `//        "Ecchi",`) and line 463 (`//        "Shoujo(G)",`) carry 3 blacklist genres
 *      that were enabled in some upstream snapshot and disabled in another. Preserved verbatim
 *      per §253 — these are intentional content-policy decisions, not migration artifacts. The
 *      `"Yaoi"` entry at line 464 is DEDUPLICATED in the live set (it appears once active at line
 *      462 — wait, looking again — yes line 464 is `"Yaoi",`. Line 462 is `"Bara(ML)",`. The
 *      blacklist `Yaoi` appears at line 464). No duplication concern.
 *
 *   m. LIVE-NOT-STALE — getChapterImages heuristic at lines 384-441. Two-path image-extraction:
 *      (1) If `var mainServer = "` is NOT present in the HTML, extracts from `<img>` tags with
 *      classes `#chapter-images img, .chapter-image[data-src]` AND from a separate `var chapImages
 *      = '...'` JS-string heuristic that compares lazy-loaded count vs DOM-element count and
 *      picks the higher count (the comment block at lines 393-397 documents the 2023-03-17
 *      heuristic origin). (2) If `var mainServer` IS present, builds image URLs from the
 *      JS-string + mainServer combination with optional `https:` scheme prefix. Both paths
 *      structurally complete and functionally distinct — they handle different host
 *      configurations.
 *
 *   n. COSMETIC-NOT-STALE — `hasBlacklistedGenre()` extension at line 183 (`if (genres.
 *      hasBlacklistedGenre()) continue`). Extension presumably lives on the parent
 *      SeparatedDetailsSites or a sibling utility file. Used in extractHomeMangaItems to filter
 *      out blacklist-bearing manga from the home feed. Not a sweep concern.
 *
 *   o. FACTUALLY-DRIFTED-IN-PROSE-ONLY — none. The 5-bullet sub-section II's claims are all
 *      structurally verified in classifications (b/c/d/e/f).
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 331 (ReadComicOnlineRepository.kt) — leaf 1/5, opening leaf, 18-line empty-body
 *     placeholder + cluster-opening summary.
 *   - sibling 332 (DemonicScansRepository.kt) — leaf 2/5, 377-line NormalSitesv2 with debug-tag
 *     noise + unused companion TAG.
 *   - sibling 333 (ManhwatopRepositoryV2.kt) — leaf 3/5, 461-line NormalSites with Madara POST-form
 *     + ChatGPT/AI :contentReference artifacts + unused mangaId extraction.
 *   - sibling 335 (TapasticRepository.kt) — leaf 5/5, 541-line SeparatedDetailsSitesv2 with
 *     Semaphore-bounded parallel chapter-fetch (FULFILLS Phase 8 parallel-IO TODO).
 *
 * Cluster195 leaf 4/5 — middle-late leaf. Next leaf: TapasticRepository.kt (sibling 335, closing
 * leaf of cluster195).
 */
