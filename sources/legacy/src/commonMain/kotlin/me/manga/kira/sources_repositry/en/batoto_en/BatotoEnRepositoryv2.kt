package me.manga.kira.sources_repositry.en.batoto_en

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Notes specific to this file:
 *  - `java.util.regex.Pattern.compile(re, DOTALL or CASE_INSENSITIVE)` → Kotlin `Regex(re,
 *    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))`. Behaviour-preserving (same
 *    PCRE-ish dialect via Kotlin's underlying regex implementation).
 *  - `LocalDate.now()` → `Clock.System.todayIn(TimeZone.currentSystemDefault())`.
 *  - `LocalDate.now().minusDays(n)` → `today.minus(n.toInt(), DateTimeUnit.DAY)`.
 *  - The source threw on text not matching the "X mins/hours/days ago" regex (returns null);
 *    that branch is preserved verbatim.
 */

import com.fleeksoft.ksoup.Ksoup
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.NormalSites
import me.manga.kira.sources_repositry.data.MangaSource

@OptIn(ExperimentalTime::class)
class BatotoEnRepositoryv2(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSites(api, sourcesRepository) {
    override val mangaSource: MangaSource
        get() = MangaSource.BATOTO
    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language
    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}browse?langs=en&sort=update.za" }
    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}browse?langs=ar,en&chapters=200&sort=views_d.za" }


    override fun handelLoadMoreUrl(page: Int): String = "${baseUrl.ifBlank { BASE_URL }}browse?langs=en&sort=update.za&page=$page"



    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  ->  "${baseUrl.ifBlank { BASE_URL }}search?word=${searchType.query}"
            is SearchType.GENRES  -> "${baseUrl.ifBlank { BASE_URL }}browse?genres=${searchType.genres}&langs=ar,en&sort=views_m.za"
            is SearchType.SORT    -> "${baseUrl.ifBlank { BASE_URL }}browse?genres=${searchType.genres}&langs=ar,en&sort=${searchType.sortType}"
        }

    override val sortTypes: Set<String>
        get() = setOf(
            "views_m.za",
            "title.az",
            "update.za",
            "create.za"
        )
    override val allGenres: Set<String>
        get() = setOf(
            "All",
            "Artbook",
            "Cartoon",
            "Comic",
            "Doujinshi",
            "Imageset",
            "Manga",
            "Manhua",
            "Manhwa",
            "Webtoon",
            "Western",
            "4-Koma",
            "Oneshot",
            "Shoujo(G)",
            "Shounen(B)",
            "Josei(W)",
            "Seinen(M)",
            "Yuri(GL)",
            "Yaoi(BL)",
            "Bara(ML)",
            "Kodomo(Kid)",
            "Non-human",
            "Gore",
            "Bloody",
            "Violence",
            "Action",
            "Adaptation",
            "Adventure",
            "Age_Gap",
            "Aliens",
            "Animals",
            "Anthology",
            "Beasts",
            "Bodyswap",
            "Boys",
            "Cars",
            "Cheating/Infidelity",
            "Childhood_Friends",
            "College_life",
            "Comedy",
            "Contest_winning",
            "Cooking",
            "Crime",
            "Crossdressing",
            "Delinquents",
            "Dementia",
            "Demons",
            "Drama",
            "Dungeons",
            "emperor_daughte",
            "Fantasy",
            "Fan-Colored",
            "Full_Color",
            "Game",
            "Gender_Bender",
            "Genderswap",
            "Ghosts",
            "Girls",
            "Gyaru",
            "Harem",
            "Harlequin",
            "Historical",
            "Horror",
            "Incest",
            "Isekai",
            "Kids",
            "Magic",
            "Magical_Girls",
            "Martial_Arts",
            "Mecha",
            "Medical",
            "Military",
            "Monster_Girls",
            "Monsters",
            "Music",
            "Mystery",
            "netorare",
            "Ninja",
            "gore",
            "Omegaverse",
            "Parody",
            "Philosophical",
            "Police",
            "Post-Apocalyptic",
            "Psychological",
            "Regression",
            "Reincarnation",
            "Reverse Harem",
            "Revenge",
            "Reverse_Isekai",
            "Romance",
            "Royal_family",
            "Royalty",
            "Samurai",
            "School_Life",
            "Sci-Fi",
            "Showbiz",
            "slice_of_life",
            "Space",
            "Sports",
            "Super_Power",
            "Superhero",
            "Supernatural",
            "Survival",
            "Thriller",
            "Time_Travel",
            "Tower_Climbing",
            "Traditional Games",
            "Tragedy",
            "Transmigration",
            "Vampires",
            "Villainess",
            "Video_Games",
            "Virtual_Reality",
            "Wuxia",
            "Xianxia",
            "Xuanhuan",
            "Yakuzas",
            "Zombies",
        )
    override val blackListGenres: Set<String>
        get() = setOf(
            "Adult",
            "Yaoi(BL)",
            "Smut",
//        "Mature",
            "Ecchi",
            "Hentai",
            "Yuri(GL)",
            "Fetish",
            "SM/BDSM/SUB-DOM",
            "Incest",
            "Omegaverse",
            "Netorare/NTR",
            "Bara(ML)",
//        "Shoujo(G)",
            "Yaoi(BL)",
            "Shounen ai",
            "Gender Bender"
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

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val items = mutableListOf<MangaItem>()
        val doc = Ksoup.parse(html)

        // 1) Grab the container by ID
        val seriesList = doc.getElementById("series-list") ?: return items

        // 2) Each .col.item is one manga entry
        for (entry in seriesList.select(".col.item")) {
            // 2a) Manga cover link + series URL
            val coverLink = entry.selectFirst("a.item-cover") ?: continue
            val url       = coverLink.attr("href").trim()

            // 2b) Title
            val titleElem = entry.selectFirst("a.item-title") ?: continue
            val title     = titleElem.text().trim()

            // 2c) Thumbnail image
            val imageUrl = coverLink.selectFirst("img")?.attr("src")?.trim() ?: continue

            // 2d) Chapters container: .item-volch > a.visited
            val volCh = entry.selectFirst(".item-volch") ?: continue
            val chapLink = volCh.selectFirst("a.visited") ?: continue
            val chapName = chapLink.text().trim()
            val chapUrl  = chapLink.attr("href").trim()

            // 2e) Date text is inside the <i> tag in the same .item-volch
            val dateTxt = volCh.selectFirst("i")?.text()?.trim() ?: ""
            // You'll need a helper to turn things like "7 mins ago", "1 hour ago", etc. into LocalDate
            val date    = parseChapterDate(dateTxt) ?: Clock.System.todayIn(TimeZone.currentSystemDefault())
            val genresContainer = entry.selectFirst(".item-genre")
            val genres = genresContainer
                ?.select("span, u, b")
                ?.map { it.text().trim() }
                ?: emptyList()
            if (genres.hasBlacklistedGenre()) continue

            val chapter = ChapterItem(
                number = chapName,
                name   = chapName,
                url    = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$chapUrl",
                date   = date
            )


            items += MangaItem(
                api       = API,
                language  = LANGUAGE,
                title     = title,
                url       = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                imageUrl  = imageUrl,
                rating    = 0,
                chapters  = listOf(chapter),
                genres    = genres
            )
        }

        return items
    }

    override fun extractMangaList(html: String): List<PopularManga>  {
        val items = mutableListOf<PopularManga>()
        val doc = Ksoup.parse(html)

        // 1) Grab the container by ID
        val seriesList = doc.getElementById("series-list") ?: return items

        // 2) Each .col.item is one manga entry
        for (entry in seriesList.select(".col.item")) {
            // 2a) Manga cover link + series URL
            val coverLink = entry.selectFirst("a.item-cover") ?: continue
            val url       = coverLink.attr("href").trim()
            // 2b) Title
            val titleElem = entry.selectFirst("a.item-title") ?: continue
            val title     = titleElem.text().trim()

            // 2c) Thumbnail image
            val imageUrl = coverLink.selectFirst("img")?.attr("src")?.trim() ?: continue


            // You'll need a helper to turn things like "7 mins ago", "1 hour ago", etc. into LocalDate
            val genresContainer = entry.selectFirst(".item-genre")
            val genres = genresContainer
                ?.select("span, u, b")
                ?.map { it.text().trim() }
                ?: emptyList()
            if (genres.hasBlacklistedGenre()) continue



            items += PopularManga(
                api       = API,
                language  = LANGUAGE,
                title     = title,
                url       = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                imageUrl  = imageUrl,

                )
        }

        return items
    }

    override suspend fun extractMangaInfo(
        html: String,
        baseUrl: String
    ): MangaInfo {
        val doc = Ksoup.parse(html, baseUrl)
        val infoEl = doc.selectFirst("div#mainer div.container-fluid")
            ?: error("Series info container not found at $baseUrl")

        // Title handling
        val rawTitle = infoEl.selectFirst("h3, h1")?.text().orEmpty()
        val title = rawTitle.trim()

        // Description builder
        val description = buildString {
            append(infoEl.selectFirst("div.limit-html")?.text().orEmpty())

            infoEl.selectFirst(".episode-list > .alert-warning")?.let {
                append("\n\n" + it.text().trim())
            }

            infoEl.selectFirst("h5:containsOwn(Extra Info:) + div")?.let {
                append("\n\nExtra Info:\n" + it.wholeText().trim())
            }

            doc.selectFirst("div.pb-2.alias-set.line-b-f")
                ?.takeIf { it.hasText() }
                ?.let {
                    append("\n\nAlternative Titles:\n")
                    it.text().split('/')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .forEach { alt -> append("• $alt\n") }
                }
        }.trim()

        // Basic attributes
        val author = infoEl.selectFirst("div.attr-item:contains(author) span")?.text().orEmpty()
        val artist = infoEl.selectFirst("div.attr-item:contains(artist) span")?.text().orEmpty()
        val workStatus = infoEl.selectFirst("div.attr-item:contains(original work) span")?.text()
        val uploadStatus = infoEl.selectFirst("div.attr-item:contains(upload status) span")?.text()
        val status = "$workStatus"
        val genres = infoEl.select(".attr-item b:contains(genres) + span ").joinToString { it.text() }
        val genresList = genres
            .split(",")
            .map { it.trim() }

        // Thumbnail URL
        val imageUrl = doc.selectFirst("div.attr-cover img")
            ?.absUrl("src").orEmpty()

        // Chapters parsing
        val chaptersElements = doc.select("div.mt-4.episode-list div.item")

        val chapters = chaptersElements.map { item ->
            val link = item.selectFirst("a.chapt")
            val title = link?.text() ?: ""
            val chpNumOnly = title.replace(Regex("[^\\d.]"), "")  // removes all non‐digits, yields "245"

            val url = link?.attr("href") ?: ""

            val dateText = item.select("div.extra i").last()?.text() ?: ""
            val parsedDate = parseChapterDate(dateText) ?: Clock.System.todayIn(TimeZone.currentSystemDefault())

            ChapterItem(
                number = title,
                name = title,
                url = "${this.baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                date = parsedDate,
                isDownloaded = false,
                isBookmarked = false,
            )
        }



        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = baseUrl,
            title = title,
            imageUrl = imageUrl,
            rating = "",
            description = description,
            author = author,
            genres = genresList,
            status = status.toString(),
            chapters = chapters.toMutableList()
        )
    }




    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val items = mutableListOf<MangaItem>()
        val doc = Ksoup.parse(html)

        // 1) Grab the container by ID
        val seriesList = doc.getElementById("series-list") ?: return items

        // 2) Each .col.item is one manga entry
        for (entry in seriesList.select(".col.item")) {
            // 2a) Manga cover link + series URL
            val coverLink = entry.selectFirst("a.item-cover") ?: continue
            val url       = coverLink.attr("href").trim()

            // 2b) Title
            val titleElem = entry.selectFirst("a.item-title") ?: continue
            val title     = titleElem.text().trim()

            // 2c) Thumbnail image
            val imageUrl = coverLink.selectFirst("img")?.attr("src")?.trim() ?: continue

            // 2d) Chapters container: .item-volch > a.visited
            val volCh = entry.selectFirst(".item-volch") ?: continue
            val chapLink = volCh.selectFirst("a.visited") ?: continue
            val chapName = chapLink.text().trim()
            val chapUrl  = chapLink.attr("href").trim()

            // 2e) Date text is inside the <i> tag in the same .item-volch
            val dateTxt = volCh.selectFirst("i")?.text()?.trim() ?: ""
            // You'll need a helper to turn things like "7 mins ago", "1 hour ago", etc. into LocalDate
            val date    = parseChapterDate(dateTxt) ?: Clock.System.todayIn(TimeZone.currentSystemDefault())
            val genresContainer = entry.selectFirst(".item-genre")
            val genres = genresContainer
                ?.select("span, u, b")
                ?.map { it.text().trim() }
                ?: emptyList()
            if (genres.hasBlacklistedGenre()) continue

            val chapter = ChapterItem(
                number = chapName,
                name   = chapName,
                url    = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$chapUrl",
                date   = date
            )


            items += MangaItem(
                api       = API,
                language  = LANGUAGE,
                title     = title,
                url       = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                imageUrl  = imageUrl,
                rating    = 0,
                chapters  = listOf(chapter),
                genres    = genres
            )
        }

        return items.filter {
            !it.genres.hasBlacklistedGenre()
        }
    }




    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()




    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

    }

    override fun getChapterImages(html: String): List<String> {

        val doc = Ksoup.parse(html)

        // 1) Try real <img> tags first
        val imgs = doc.select("img.wp-manga-chapter-img")
            .mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }


        // 2) Fallback: extract the JS array `const imgHttps = [ ... ]`
        // Source used java.util.regex.Pattern with DOTALL|CASE_INSENSITIVE flags. Kotlin's
        // RegexOption.DOT_MATCHES_ALL + IGNORE_CASE provide the same semantics in commonMain.
        val jsArrayPattern = Regex(
            """const\s+imgHttps\s*=\s*\[\s*(.*?)\s*];""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val matchResult = jsArrayPattern.find(html) ?: return emptyList()

        // Group 1 is everything between [ and ]
        val arrayBody = matchResult.groupValues.getOrNull(1)

        // Split on commas that separate entries, trim quotes/spaces

        return arrayBody
            ?.split(Regex(""",(?=(?:[^"']*"[^"']*")*[^"']*$)""")) // split on commas outside quotes
            ?.map { it.trim().trim('"', '\'') }
            ?.filter(String::isNotBlank) ?: listOf()

    }


    fun parseChapterDate(text: String): LocalDate? {
        // Normalize and trim. `lowercase()` without an explicit Locale is locale-insensitive in
        // Kotlin stdlib (matches the implicit Locale.getDefault() used by source for this
        // ASCII-only pattern).
        val normalized = text.trim().lowercase()
        // Regex: capture an integer + unit (min(s), hour(s), day(s)) + "ago"
        val regex = """^(\d+)\s*(min(?:s)?|hour(?:s)?|day(?:s)?)\s*ago$""".toRegex()
        val match = regex.find(normalized) ?: return null

        val (rawNum, rawUnit) = match.destructured
        val number = rawNum.toLongOrNull() ?: return null

        // Compute how many days back
        val daysBack = when {
            rawUnit.startsWith("day") -> number
            rawUnit.startsWith("hour") -> number / 24  // floor hours into days
            rawUnit.startsWith("min") -> 0             // all minutes → same day
            else -> 0
        }

        return Clock.System.todayIn(TimeZone.currentSystemDefault())
            .minus(daysBack.toInt(), DateTimeUnit.DAY)
    }

}

/**
 * Audit-trail postscript (Phase 9.x.cluster196.staleKdocSweep.cascade, Task #651, 2026-05-29)
 *
 * Leaf 1/5 §253 audit-trail-preservation postscript for cluster196, sibling 336 of the cluster57+
 * continuum. Opening leaf of cluster196 (the :en/ Repository implementation tier heavy-half
 * batch). 571-line NormalSites subclass — interesting nomenclature mismatch: file-name suffix
 * `v2` but extends the non-v2 NormalSites base class (sibling ManhwatopRepositoryV2 from
 * cluster195 leaf 3/5 also follows the same v2-suffix-without-base pattern; sibling
 * DemonicScansRepository from cluster195 leaf 2/5 conversely extends NormalSitesv2 without a
 * v2-suffix in its file name — the v2 suffix tracks file-level migration generation, not
 * base-class taxonomy).
 *
 * The top-of-file prose under audit (lines 3-15) is a single file-header KDoc block carrying two
 * distinct sub-sections:
 *
 *   I.   Phase 7.2 migration-pattern enumeration (lines 4-5) — standard 6-bullet list used across
 *        the entire :en/ Repository tier (Retrofit→Ktor ApiClient, jsoup→ksoup, FormBody→Map,
 *        @Inject dropped, android.util.Log→Kermit Logger, java.time→kotlinx.datetime).
 *
 *   II.  File-specific Phase 7.2 KMP-port notes (lines 7-14) — 4 bullets covering:
 *        (a) java.util.regex.Pattern.compile(re, DOTALL|CASE_INSENSITIVE) →
 *            Kotlin Regex(re, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)) with
 *            explicit behaviour-preservation note ("same PCRE-ish dialect via Kotlin's
 *            underlying regex implementation").
 *        (b) LocalDate.now() → Clock.System.todayIn(TimeZone.currentSystemDefault()).
 *        (c) LocalDate.now().minusDays(n) → today.minus(n.toInt(), DateTimeUnit.DAY).
 *        (d) Preservation of the source's null-branch on unmatched "X mins/hours/days ago"
 *            regex ("that branch is preserved verbatim").
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — sub-section I (Phase 7.2 6-bullet migration-pattern preamble). Canonical
 *      preamble verbatim-shared across all 5 cluster195 siblings (DemonicScansRepository,
 *      ManhwatopRepositoryV2, MangaBuddyRepositoryV2, TapasticRepository, and the empty-body
 *      ReadComicOnlineRepository placeholder) and forecast verbatim across the remaining 4
 *      cluster196 leaves.
 *
 *   b. LIVE-NOT-STALE — sub-section II (4-bullet file-specific notes). Cross-verified against
 *      the file body:
 *        - bullet (a): getChapterImages at lines 516-544 uses Regex with DOT_MATCHES_ALL +
 *          IGNORE_CASE flags (line 530) — verified verbatim. Inline comment at lines 526-527
 *          duplicates the preamble note ("Source used java.util.regex.Pattern with
 *          DOTALL|CASE_INSENSITIVE flags. Kotlin's RegexOption.DOT_MATCHES_ALL + IGNORE_CASE
 *          provide the same semantics in commonMain.") — preserved as redundant-but-LIVE.
 *        - bullets (b)+(c): parseChapterDate at lines 547-569 uses Clock.System.todayIn(
 *          TimeZone.currentSystemDefault()).minus(daysBack.toInt(), DateTimeUnit.DAY) — verified.
 *          @OptIn(ExperimentalTime::class) opt-in at line 37 marks the kotlin.time.Clock API as
 *          experimental — preserved (Kotlin stdlib has not stabilised Clock as of K2 release).
 *        - bullet (d): parseChapterDate returns null on unmatched regex (line 554) — verified.
 *
 *   c. POTENTIAL-BUG-PRESERVED — duplicate "Yaoi(BL)" entry in blackListGenres at lines 200 AND
 *      213. Set<String> deduplicates by hashCode/equals so this is harmless at runtime, but
 *      documents authoring drift in the source data. Preserved verbatim per §253 — fixing would
 *      change the observable contents of the source-list literal and could mask future re-edits
 *      that accidentally rely on a single entry's presence.
 *
 *   d. COSMETIC-NOT-STALE — 2 commented-out blackListGenres entries: the `Mature` literal at
 *      line 202 and the `Shoujo(G)` literal at line 212. The 8-space indent on both entries is
 *      structurally preserved verbatim from the source — both entries previously toggled in/out
 *      during upstream content-policy iterations. Preserved per §253 to maintain the rollback
 *      escape hatch.
 *
 *   e. COSMETIC-NOT-STALE — 3 instances of the cosmetic placeholder comment
 *      "You'll need a helper to turn things like 7 mins ago, 1 hour ago, etc. into LocalDate"
 *      at lines 263, 315, 459. Leftover ChatGPT/porting-prompt artifact (similar in spirit to
 *      the cluster191 leaf 5/5 ComickRepositoryAr contentReference ChatGPT artifact category but
 *      structurally distinct — these are prompt-instruction comments that survived the port
 *      rather than citation markers). The helper has actually been implemented as
 *      parseChapterDate at lines 547-569, so the comments are stale-in-intent but
 *      preserved-in-structure per §253. A future Phase 8 cleanup slice could strip them in one
 *      batch.
 *
 *   f. FULFILLED-PORT — getChapterImages at lines 516-544 dual-path parser: (1) real img tags
 *      via the `img.wp-manga-chapter-img` selector; (2) JS array fallback regex matching the
 *      `const imgHttps = [ ... ]` literal in raw HTML. Cross-quote-comma split via
 *      Regex with quote-aware-comma lookahead preserves source's quote-aware tokeniser. Java
 *      Pattern.compile → Kotlin Regex flag mapping per sub-section II bullet (a).
 *
 *   g. LIVE-NOT-STALE — parseChapterDate at lines 547-569 with floor-hours-into-days semantics
 *      (line 562: `number / 24`). The "all minutes → same day" branch (line 563) intentionally
 *      collapses any sub-hour delta into today. Preserved verbatim per §253; matches source.
 *      Note at lines 548-550 about lowercase() without Locale being locale-insensitive in Kotlin
 *      stdlib is LIVE-NOT-STALE — true for ASCII-only inputs that this regex accepts.
 *
 *   h. LIVE-NOT-STALE — @Volatile `_cachedHeaders` pattern (lines 496-514). Canonical across the
 *      BaseMangaRepository taxonomy: same shape as cluster195 leaves' Volatile-cache pattern,
 *      cluster194+193+192+191 :ar/ siblings, and the broader :en/ + :ar/ Repository tier. The
 *      `defaultHeaders` getter elvis-fallback to emptyMap() matches the canonical shape.
 *      Cross-cluster Nth-sibling reference.
 *
 *   i. FORECAST-NOT-YET-FULFILLED — 3 near-duplicate parser bodies between extractHomeMangaItems
 *      (lines 235-293), extractMangaList (lines 295-336), and getSearchResults (lines 431-491)
 *      all iterate the same `series-list .col.item` container with overlapping field-extraction
 *      logic. extractMangaList differs by emitting PopularManga (no chapter/date/genre-filter
 *      overlap) while extractHomeMangaItems + getSearchResults are nearly identical bodies that
 *      diverge only in (i) the final `.filter { !it.genres.hasBlacklistedGenre() }` at lines
 *      488-490 (getSearch filters AGAIN after the in-loop `continue`) and (ii) the suspend-fun
 *      modifier on getSearchResults. Phase 8 dedup forecast candidate — could collapse into a
 *      private helper parseSeriesListItem(entry) that returns a structured tuple. Forecast not
 *      yet fulfilled — the duplication remains.
 *
 *   j. DEBT-NOT-STALE — extractMangaInfo at lines 338-426 shadows the outer `title` local-val
 *      with an inner `val title = link?.text() ?: ""` at line 393 inside the chapter map.
 *      Behaviour is correct (Kotlin shadowing is intentional here — the chapter loop uses
 *      chapter-title, not series-title) but the shadowing is a known readability snag.
 *      Preserved verbatim. Additionally the `workStatus` + `uploadStatus` pair at lines 376-378
 *      ends up stringified to `"$workStatus"` only (uploadStatus is read but never composed
 *      into the status string) — likely an upstream oversight, preserved per §253 since fixing
 *      would change the observable MangaInfo.status field.
 *
 * Closing-opening summary (cluster196):
 *
 *   Cluster196 opens the :en/ Repository implementation tier sweep (heavy-half) with 5 §253
 *   postscripts to be authored across siblings 336-340. The batch is heavier than cluster195
 *   (3623 total lines vs cluster195's ~1916 total lines) and contains the parent classes of two
 *   prior cluster-leaves: MangaParkRepository (sibling 337) parents cluster192 leaf 1/5
 *   MangaParkRepositoryAr (sibling 317), and ComickRepository (sibling 340) parents cluster191
 *   leaf 5/5 ComickRepositoryAr (sibling 316). BatcaveRepository (sibling 339) is the
 *   cross-package consumer of :en/readcomiconline/Dto.kt data classes that motivated cluster195
 *   leaf 1/5's empty-body preservation. ZazamangaRepository (sibling 338) carries the heaviest
 *   non-inheritance-graph leaf at 747 lines.
 *
 *   Cumulative §253-postscript count brought to 61 across wave-57-to-wave-60 (cluster195 closed
 *   at 60). The :sources_repositry/en/ Repository tier sweep continues heavy-half — cluster196
 *   leaf 1/5 opens; cluster197 forecast targets either the :en/ Parser tier or the
 *   cross-package :common/ Base tier depending on remaining audit-trail coverage.
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 337 (MangaParkRepository.kt) — leaf 2/5, 708 lines, parent of cluster192 leaf 1/5
 *     MangaParkRepositoryAr.
 *   - sibling 338 (ZazamangaRepository.kt) — leaf 3/5, 747 lines.
 *   - sibling 339 (BatcaveRepository.kt) — leaf 4/5, 796 lines, cross-package consumer of the
 *     :en/readcomiconline/Dto.kt data classes.
 *   - sibling 340 (ComickRepository.kt) — leaf 5/5, closing leaf, 801 lines, parent of
 *     cluster191 leaf 5/5 ComickRepositoryAr, key consumer of the :en/comick_io/models JSON
 *     schema tree.
 *
 * Cluster196 leaf 1/5 — opening leaf. Next leaf: MangaParkRepository.kt (sibling 337).
 */
