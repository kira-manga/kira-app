package me.manga.kira.sources_repositry.en.demonicscans


/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import kotlin.concurrent.Volatile
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
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

class DemonicScansRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSitesv2(api, sourcesRepository) {

    override val mangaSource: MangaSource
        get() = MangaSource.DEMONICSCANS

    override val BASE_URL: String
        get() = "https://demonicscans.org/"

    override val API: String
        get() = mangaSource.API

    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { baseUrl.ifBlank { BASE_URL } }
    override val popularUrl: String by lazy { baseUrl.ifBlank { BASE_URL } }

    override val sortTypes: Set<String>
        get() = setOf("Latest Updates", "Most Viewed", "New Titles")

    override val allGenres: Set<String>
        get() = setOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance", "Supernatural")

    override val blackListGenres: Set<String>
        get() = setOf()

    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0

    override suspend fun initSite(): Int {

        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override var customParseHome: Boolean = true
    override var useGetForHome: Boolean = true
    override var useGetForPopular: Boolean = true
    override var useGetForSearch: Boolean = false
    override var useGetForNormalSearch: Boolean = false

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override fun handelFormBodyHome(page: Int, popular: Boolean): Map<String, String>? = null

    override fun handelFormBodyPopular(page: Int, popular: Boolean): Map<String, String>? = null

    override fun handelLoadMoreUrl(page: Int): String {
        return if (page > 1) {
            "${baseUrl.ifBlank { BASE_URL }}lastupdates.php?list=${page}"
        } else {
            "${baseUrl.ifBlank { BASE_URL }}index.php"
        }
    }

    override fun handelSearchUrl(searchType: SearchType): String {

        return "${baseUrl.ifBlank { BASE_URL }}search.php?manga=${searchType.toNormalQuery()}"
    }

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? = null

    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? {
        return mapOf("manga" to searchType.query)
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? = null

    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? = null

    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc = Ksoup.parse(html)
        val items = mutableListOf<MangaItem>()

        // Extract from "Latest Updates" section
        val updateElements = doc.select("#updates-container .updates-element")
        for (element in updateElements) {
            val thumb = element.selectFirst(".thumb a")
            val titleLink = element.selectFirst(".updates-element-info h2 a")
            val img = element.selectFirst(".thumb img")

            if (thumb != null && titleLink != null && img != null) {
                val url = titleLink.attr("href")
                val imageUrl = img.attr("src")
                val title = titleLink.text().trim()

                // Extract recent chapters
                val chapterElements = element.select(".chap-date")
                val chapters = chapterElements.mapNotNull { chapterEl ->
                    val chapterLink = chapterEl.selectFirst("a")
                    val dateEl = chapterEl.selectFirst("div:last-child a")

                    if (chapterLink != null) {
                        val chapterName = chapterLink.text().trim()
                        val chapterUrl = chapterLink.attr("href")
                        val date = dateEl?.text()?.trim()

                        Logger.withTag("dslkfjslkdfjklsdfsdfsdfdfsd").i { chapterUrl }
                        ChapterItem(
                            name = chapterName,
                            number = extractChapterNumber(chapterName),
                            url = "${baseUrl.ifBlank { BASE_URL }}$chapterUrl",
                            date = parseDate(date)
                        )
                    } else null
                }

                items.add(MangaItem(
                    api = API,
                    language = LANGUAGE,
                    title = title,
                    url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                    imageUrl = imageUrl,
                    rating = 0,
                    chapters = chapters,
                    genres = emptyList()
                ))
            }
        }

        return items
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {

        val items = extractCustomHomeMangaItems(html)
        Logger.withTag("sfgjsfkdjgsfdgsdfgfdgdsfg").i { items.toString() }
        return items
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        val doc = Ksoup.parse(html)
        val items = mutableListOf<PopularManga>()

        // Extract from carousel for popular manga
        val carouselElements = doc.select("#carousel .owl-element")
        for (element in carouselElements) {
            val link = element.selectFirst("a")
            val img = element.selectFirst("img")
            val title = element.selectFirst("h1")

            if (link != null && img != null && title != null) {
                val url = link.attr("href")
                val imageUrl = img.attr("src")
                val mangaTitle = title.text().split("<br>").firstOrNull()?.trim() ?: title.text().trim()

                items.add(PopularManga(
                    api = API,
                    language = LANGUAGE,
                    title = mangaTitle,
                    url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                    imageUrl = imageUrl
                ))
            }
        }

        return items
    }

    override suspend fun extractMangaInfo(html: String, baseUrl: String): MangaInfo {
        val document = Ksoup.parse(html)

        val title = document.selectFirst("#manga-info-rightColumn h1")?.text()?.trim() ?: ""

        val thumbnail = document.selectFirst("#manga-page img")?.attr("src") ?: ""

        val description = document.select("#manga-info-rightColumn .white-font")
            .text().trim().let { desc ->
                // Remove the generic intro text
                val startIndex = desc.indexOf("The Summary is")
                if (startIndex != -1) {
                    desc.substring(startIndex + "The Summary is".length).trim()
                } else desc
            }

        val genres = document.select(".genres-list li")
            .map { it.text().trim() }

        // Extract rating
        val ratingElements = document.select("#R-V-B .RVB")
        val rating = ratingElements.getOrNull(0)?.text()?.trim() ?: ""
        val views = ratingElements.getOrNull(1)?.text()?.trim() ?: ""
        val bookmarks = ratingElements.getOrNull(2)?.text()?.trim() ?: ""

        // Extract manga info stats
        val statsElements = document.select("#manga-info-stats .flex")
        val author = statsElements.find { it.text().contains("Author") }
            ?.select("li")?.getOrNull(1)?.text()?.trim() ?: "Updating"

        val status = statsElements.find { it.text().contains("Status") }
            ?.select("li")?.getOrNull(1)?.text()?.trim() ?: "Ongoing"

        val lastUpdate = statsElements.find { it.text().contains("Last Update") }
            ?.select("li")?.getOrNull(1)?.text()?.trim() ?: ""

        val alternativeNames = statsElements.find { it.text().contains("Alternatives") }
            ?.select("li")?.getOrNull(1)?.text()?.trim() ?: ""

        // Extract chapters
        val chapterElements = document.select("#chapters-list li")
        val chapters = chapterElements.mapNotNull { element ->
            val chapterLink = element.selectFirst("a")
            val dateSpan = element.selectFirst("span[style*='float:right']")

            if (chapterLink != null) {
                val chapterName = chapterLink.text().trim()
                val chapterUrl = chapterLink.attr("href")
                val date = dateSpan?.text()?.trim()

                ChapterItem(
                    name = chapterName,
                    number = extractChapterNumber(chapterName),
                    url = "${this.baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$chapterUrl",

                    date = parseDate(date)
                )
            } else null
        } // Reverse to get ascending order

        return MangaInfo(
            title = title,
            imageUrl = thumbnail,
            rating = rating,
            description = if (alternativeNames.isNotBlank()) "$description\n\nAlternative Names: $alternativeNames" else description,
            author = author,
            genres = genres,
            status = status,
            chapters = chapters.toMutableList(),
            api = API,
            url = baseUrl,
            language = LANGUAGE
        )
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html)
        val items = mutableListOf<MangaItem>()
        Logger.withTag("dfljshdlfjsdfsdfsdfsdfsdfsgjhbdf").i { html }

        // Parse search results based on the actual HTML structure from the response
        // The search returns a list of <a> tags containing <li> elements with manga info
        val searchResults = doc.select("a")

        for (result in searchResults) {
            val liElement = result.selectFirst("li.flex.flex-row")
            if (liElement != null) {
                // Extract image
                val img = liElement.selectFirst("img.search-thumb")
                val imageUrl = img?.attr("src") ?: ""

                // Extract title from the div content
                val titleDiv = liElement.selectFirst("div.flex.flex-col div")
                val title = titleDiv?.text()?.trim() ?: ""

                // Get the URL from the href attribute of the <a> tag
                val url = result.attr("href")

                if (url.isNotEmpty() && title.isNotEmpty()) {
                    items.add(MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$url",
                        imageUrl = imageUrl,
                        rating = 0,
                        chapters = emptyList(),
                        genres = emptyList()
                    ))
                }
            }
        }

        return items
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun getChapterImages(html: String): List<String> {
        val document = Ksoup.parse(html)

        Logger.withTag("saflhdsflksjdfsdfsdfsd").i { html }
        // Based on the actual HTML structure from the response file,
        // the images are stored in <img> tags with class "imgholder"
        val images = document.select("img.imgholder")
            .mapNotNull { img ->
                when {
                    img.hasAttr("src") -> {
                        val src = img.attr("src")
                        if (src.isNotEmpty() && !src.contains("btn_close.gif") && !src.contains("free_ads.jpg")) {
                            src
                        } else null
                    }
                    else -> null
                }
            }
            .filter { it.isNotEmpty() }

        return images
    }

    private fun extractChapterNumber(chapterName: String): String {
        val regex = """Chapter\s+(\d+(?:\.\d+)?)""".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(chapterName)?.groupValues?.get(1) ?: chapterName
    }

    private val isoDateFormatter = LocalDate.Format {
        year()
        char('-')
        monthNumber()
        char('-')
        day()
    }

    private val englishFullMonthDayYearFormatter = LocalDate.Format {
        monthName(MonthNames.ENGLISH_FULL)
        char(' ')
        day()
        chars(", ")
        year()
    }

    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null

        return try {
            // DemonicScans uses format: "2025-09-26"
            LocalDate.parse(dateStr, isoDateFormatter)
        } catch (e: Exception) {
            try {
                // Alternative format: "September 26, 2025"
                LocalDate.parse(dateStr, englishFullMonthDayYearFormatter)
            } catch (e2: Exception) {
                null
            }
        }
    }

    companion object {
        private const val TAG = "DemonicScansRepository"
    }
}

/**
 * Audit-trail postscript (Phase 9.x.cluster195.staleKdocSweep.cascade, Task #650, 2026-05-29)
 *
 * Leaf 2/5 §253 audit-trail-preservation postscript for cluster195, sibling 332 of the cluster57+
 * continuum. Medium-weight 377-line NormalSitesv2 subclass for the DemonicScans English source.
 * Notably the SECOND most-debug-tag-noisy file in the entire :sources_repositry/ tree (after
 * MangaBuddyRepositoryV2 — cluster195 leaf 4/5, sibling 334) carrying 4 keyboard-mashed Logger
 * tags ("dslkfjslkdfjklsdfsdfsdfdfsd", "sfgjsfkdjgsfdgsdfgfdgdsfg", "dfljshdlfjsdfsdfsdfsdfsdfsgjhbdf",
 * "saflhdsflksjdfsdfsdfsd") alongside an UNUSED companion-object TAG constant ("DemonicScansRepository").
 *
 * The top-of-file prose under audit (lines 4-7) is a thin file-header KDoc carrying only the
 * canonical Phase 7.2 6-bullet migration-pattern preamble — substantially terser than the verbose
 * file-headers of cluster195 leaves 3/5 (ManhwatopRepositoryV2) and 4/5 (MangaBuddyRepositoryV2).
 *
 *     Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 *     @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — Phase 7.2 6-bullet migration-pattern preamble. Verified by import survey
 *      of lines 9-24: `co.touchlab.kermit.Logger` present (line 9), `com.fleeksoft.ksoup.Ksoup`
 *      present (line 10), `kotlin.concurrent.Volatile` present (line 11), `kotlinx.datetime.*`
 *      present (lines 12-14), `data.remote.api.ApiClient` present (line 17). Zero `Retrofit`,
 *      `jsoup`, `okhttp3`, `javax.inject.Inject`, `android.util.Log`, `java.time` imports — the
 *      migration is structurally complete. Same canonical preamble used across the entire :en/
 *      Repository tier (cross-referenced verbatim in cluster195 leaves 1/5, 3/5, 4/5, 5/5).
 *
 *   b. LIVE-NOT-STALE — NormalSitesv2 base-class choice (line 30: extends NormalSitesv2(api,
 *      sourcesRepository)). DemonicScans is the FIRST cluster195 leaf to use the v2 base; cluster195
 *      leaves 3/5 + 4/5 use NormalSites and SeparatedDetailsSites respectively, leaf 5/5 uses
 *      SeparatedDetailsSitesv2. The v2 vs v1 split reflects the BaseMangaRepository taxonomy
 *      (cross-clusters reference per cluster194 closer's cross-cluster catalogue).
 *
 *   c. POTENTIAL-BUG-PRESERVED — debug-tag noise. Four keyboard-mashed Logger tags appear in this
 *      file:
 *        - line 131: `Logger.withTag("dslkfjslkdfjklsdfsdfsdfdfsd").i { chapterUrl }`
 *        - line 160: `Logger.withTag("sfgjsfkdjgsfdgsdfgfdgdsfg").i { items.toString() }`
 *        - line 271: `Logger.withTag("dfljshdlfjsdfsdfsdfsdfsdfsgjhbdf").i { html }`
 *        - line 317: `Logger.withTag("saflhdsflksjdfsdfsdfsd").i { html }`
 *      These are debug/diagnostic remnants from upstream development. None of them is a functional
 *      bug — they emit logger lines under unique tags and have no effect on parsing logic. The TAG
 *      companion-object constant ("DemonicScansRepository") at line 375 is declared but NEVER USED
 *      anywhere in the file (grep verified: zero `TAG` references in the file body). Phase 8
 *      cleanup candidate: replace keyboard-mashed tags with the canonical companion TAG, or drop
 *      the diagnostic logging entirely. Preserved verbatim per §253 — debug-noise IS the upstream
 *      state.
 *
 *   d. LIVE-NOT-STALE — DataStoreHelper headers cache pattern. Lines 71-75 (`@Volatile private var
 *      _cachedHeaders: Map<String, String>? = null` + `override val defaultHeaders: Map<String,
 *      String> get() = _cachedHeaders ?: emptyMap()`) implement the canonical Volatile-cell-of-truth
 *      pattern used across all 4 cluster194 leaves (per cluster194 closer's cross-cluster catalogue).
 *      Same pattern recurs in cluster195 leaves 3/5 (ManhwatopRepositoryV2 lines 399-407), 4/5
 *      (MangaBuddyRepositoryV2 lines 360-372), 5/5 (TapasticRepository lines 165-169) — totaling 8+
 *      :sources_repositry/ siblings using identical pattern across cluster192-195.
 *
 *   e. COSMETIC-NOT-STALE — `dropTrailingSlash()` extension call chain (lines 145, 184, 246, 296).
 *      The extension function is presumed to live on the parent NormalSitesv2 or a sibling utility
 *      file; not declared in this file's body. Used consistently across all URL-composition sites
 *      in the file. Preserved verbatim — not a sweep concern, just an out-of-file extension dep.
 *
 *   f. LIVE-NOT-STALE — kotlinx.datetime LocalDate.Format DSL usage (lines 342-356). Two formatters:
 *      `isoDateFormatter` (yyyy-MM-dd via `year() char('-') monthNumber() char('-') day()`) and
 *      `englishFullMonthDayYearFormatter` (`MonthNames.ENGLISH_FULL` + day + comma-year). The Phase
 *      7.2 java.time→kotlinx.datetime migration is structurally complete. Two-format try/catch
 *      fallback at lines 361-371 — ISO format first, English-full-month-name fallback second,
 *      null on parse failure. Matches upstream's two-format parse semantics.
 *
 *   g. LIVE-NOT-STALE — pure-jsoup-equivalent ksoup selectors. Lines 109 (`#updates-container
 *      .updates-element`), 169 (`#carousel .owl-element`), 196 (`#manga-info-rightColumn h1`),
 *      213 (`#R-V-B .RVB`), 233 (`#chapters-list li`), 275 (`a`) — all standard CSS-selector
 *      syntax. The `com.fleeksoft.ksoup.Ksoup` import (line 10) and `.parse(html)` + `.select(...)`
 *      / `.selectFirst(...)` / `.text()` / `.attr(...)` API surface is the canonical jsoup→ksoup
 *      port pattern verified across the cluster192-195 :sources_repositry/ tier sweep.
 *
 *   h. POTENTIAL-BUG-PRESERVED — comment-vs-code mismatch at line 251 (`} // Reverse to get
 *      ascending order`). The comment suggests reversal but no `.reversed()` call appears in the
 *      .mapNotNull lambda chain. The chapter list as parsed is returned in DOM-document order.
 *      Either the comment is stale (chapters happen to arrive in already-ascending order from the
 *      site's HTML) or the reversal step was dropped during a refactor and the comment was not
 *      removed. Preserved verbatim per §253 — informational note for any future investigator.
 *
 *   i. COSMETIC-NOT-STALE — companion-object TAG constant at lines 374-376. Declared but never
 *      referenced. See classification (c) for the rationale on its preservation. Companion
 *      objects with a single unused TAG constant are a common Android-source pattern carried over
 *      to KMP ports for consistency; some sources use it (cluster195 leaf 5/5 TapasticRepository
 *      uses `Logger.withTag(TAG)` consistently — line 212, 271, etc.) while others (like this
 *      file) declared the constant without consistent use. Phase 8 cleanup candidate.
 *
 *   j. LIVE-NOT-STALE — `Set<String>` membership for sortTypes/allGenres/blackListGenres
 *      overrides (lines 47-54). DemonicScans is a minimal-config source: 3 sort types, 7 genres,
 *      ZERO blacklist genres. The empty `blackListGenres` set is INTENTIONAL — DemonicScans is
 *      not an adult-content source so no genre filtering applies. Contrasts with cluster195 leaf
 *      3/5 (ManhwatopRepositoryV2) which has a 12-entry blacklist and leaf 5/5 (TapasticRepository)
 *      with a 3-entry blacklist (BL/LGBTQ+/GL).
 *
 *   k. FACTUALLY-DRIFTED-IN-PROSE-ONLY — none. This file's KDoc is so terse it makes no claims
 *      that could drift. The 2-line preamble describes cross-cutting migrations all of which were
 *      structurally verified in classification (a).
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 331 (ReadComicOnlineRepository.kt) — leaf 1/5, opening leaf, 18-line empty-body
 *     placeholder + cluster-opening summary.
 *   - sibling 333 (ManhwatopRepositoryV2.kt) — leaf 3/5, 461-line NormalSites with Madara POST-form.
 *   - sibling 334 (MangaBuddyRepositoryV2.kt) — leaf 4/5, 521-line SeparatedDetailsSites with
 *     Africa/Cairo timezone.
 *   - sibling 335 (TapasticRepository.kt) — leaf 5/5, 541-line SeparatedDetailsSitesv2 with
 *     Semaphore-bounded parallel chapter-fetch.
 *
 * Cluster195 leaf 2/5 — middle leaf. Next leaf: ManhwatopRepositoryV2.kt (sibling 333).
 */
