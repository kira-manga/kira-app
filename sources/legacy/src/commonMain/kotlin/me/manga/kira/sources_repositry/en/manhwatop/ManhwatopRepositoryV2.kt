package me.manga.kira.sources_repositry.en.manhwatop

/**
 * Migration note (Phase 7.2): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 *
 * Notes specific to this file:
 *  - `okhttp3.HttpUrl.Companion.toHttpUrl().newBuilder().addPathSegment(...).addQueryParameter(...)`
 *    has no KMP equivalent that ships in commonMain — rewritten as manual string composition.
 *    The original endpoint shape was:
 *      "${baseUrl}wp-admin/admin-ajax.php/browse?langs=en&sort=update.za&page=N"
 *    which is exactly what the manual string produces.
 *  - `api.post(ajaxUrl, body, defaultHeaders)` (where `body = FormBody.Builder().build()`, an
 *    empty form body) → `api.postForm(url, fields = emptyMap(), headers = defaultHeaders)`.
 *    Response `.body() ?: ""` → `response.bodyAsText()`.
 *  - `DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH)` → `LocalDate.Format` DSL
 *    with `MonthNames.ENGLISH_ABBREVIATED`.
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.client.statement.bodyAsText
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
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
class ManhwatopRepositoryV2(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSites(api, sourcesRepository) {

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    override val mangaSource: MangaSource
        get() = MangaSource.MANHWATOP


    override var homeGet: Boolean = false
    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy {
        "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php/browse?langs=en&sort=update.za&page=1"
    }

    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php" }


    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php/browse?langs=en&sort=update.za&page=$page"
    }

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        return mapOf(
            "action" to "madara_load_more",
            "page" to (page - 1).toString(),
            "template" to "madara-core/content/content-archive",
            "vars[orderby]" to "meta_value_num",
            "vars[paged]" to "1",
            "vars[posts_per_page]" to "20",
            "vars[post_type]" to "wp-manga",
            "vars[post_status]" to "publish",
            "vars[meta_key]" to if (popular) "_wp_manga_views" else "_latest_update",
            "vars[order]" to "desc",
            "vars[sidebar]" to "right",
            "vars[manga_archives_item_layout]" to "big_thumbnail",
        )
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

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> normalSearchUrl(searchType.query)
            is SearchType.GENRES -> ""
            is SearchType.SORT -> ""
        }

    fun normalSearchUrl(q: String): String = "${baseUrl.ifBlank { BASE_URL }}?s=${q}&post_type=wp-manga"

    private fun todayInSystem(): LocalDate =
        Clock.System.todayIn(TimeZone.currentSystemDefault())

    private val abbrMonthDayYearFormatter = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        day()
        chars(", ")
        year()
    }

    override fun extractHomeMangaItems(string: String): MutableList<MangaItem> {
        val document = Ksoup.parse(string)
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

    override fun extractMangaList(string: String): List<PopularManga> {
        val document = Ksoup.parse(string)
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

    override suspend fun extractMangaInfo(string: String, baseUrl: String): MangaInfo {
        val doc = Ksoup.parse(string)

        // Title
        val title = doc.selectFirst("div.post-title h1")
            ?.text()?.trim()
            ?: ""

        // Cover image (using data-src for the high-res URL)
        val imageUrl = doc.selectFirst("div.summary_image img")
            ?.attr("data-src")
            ?: ""

        // Rating (score) and rating count (total votes)
        val rating = doc.selectFirst("div.post-total-rating span.score")
            ?.text()
            ?: "0"
        val ratingCount = doc.selectFirst("div.vote-details span[property='ratingCount']")
            ?.text()
            ?: "0"

        // Description
        val description = doc.selectFirst("div.description-summary div.summary__content")
            ?.text()?.trim()
            ?: ""

        // Author(s)
        val authors = doc.select("div.author-content a")
            .eachText()
            .toMutableList()

        // Artist(s)
        val artists = doc.select("div.artist-content a")
            .eachText()
            .toMutableList()

        // Genre(s)
        val genres = doc.select("div.genres-content a")
            .eachText()
            .toMutableList()

        // Status (e.g. OnGoing, Completed)
        val status = doc.selectFirst("div.summary-content.mg_status")
            ?.text()?.trim()
            ?: "Unknown"

        // Extract manga ID for AJAX request
        val mangaId = doc.selectFirst("div#manga-chapters-holder")
            ?.attr("data-id")
            ?: baseUrl.split("/").lastOrNull { it.isNotBlank() }

        // Fetch chapters via AJAX
        val chapters = fetchChapters(baseUrl)

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = baseUrl,
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

    private suspend fun fetchChapters(mangaUrl: String): MutableList<ChapterItem> {
        return try {
            // Make AJAX request to get chapters
            val ajaxUrl = "${mangaUrl}ajax/chapters"

            Logger.withTag("aslkfjsdlkfjskldfsdfsadfsdfsda0").i { ajaxUrl }

            // Source used `FormBody.Builder().build()` — an empty form body. Map equivalent
            // is `emptyMap()`, which postForm encodes as an empty body.
            val response = api.postForm(ajaxUrl, fields = emptyMap(), headers = defaultHeaders)
            Logger.withTag("aslkfjsdlkfjskldfsdfsadfsdfsda1").i { response.toString() }

            val chaptersHtml = response.bodyAsText()
            val doc = Ksoup.parse(chaptersHtml)
            Logger.withTag("aslkfjsdlkfjskldfsdfsadfsdfsda2").i { doc.toString() }


            doc.select("li.wp-manga-chapter")
                .mapNotNull { element ->
                    try {
                        val link = element.selectFirst("a") ?: return@mapNotNull null
                        val chapterText = link.text().trim()
                        val chapterUrl = link.attr("abs:href").ifEmpty {
                            link.attr("href")
                        }

                        // Get date element
                        val dateElem = element.selectFirst("span.chapter-release-date")
                        val dateText = dateElem?.selectFirst("i")?.text()?.trim() ?: "Complete"

                        // Extract chapter number (handles "Chapter 95", "Chapter 20.4", etc.)
                        val chapterNum = chapterText
                            .replace("Chapter", "", ignoreCase = true)
                            .trim()
                            .replace(Regex("[^\\d.]"), "")

                        ChapterItem(
                            number = chapterNum.ifBlank { chapterText },
                            name = chapterText,
                            url = chapterUrl,
                            date = parseChapterDate(dateText) ?: todayInSystem(),
                            isDownloaded = false
                        )
                    } catch (e: Exception) {
                        Logger.withTag("ManhwaTop").e(e) { "Error parsing chapter: ${e.message}" }
                        null
                    }
                }
                .toMutableList()
                .also { chapters ->
                    Logger.withTag("ManhwaTop").d { "Successfully parsed ${chapters.size} chapters" }
                }
        } catch (e: Exception) {
            Logger.withTag("ManhwaTop").e(e) { "Error fetching chapters: ${e.message}" }
            mutableListOf()
        }
    }

    override suspend fun getSearchResults(string: String): List<MangaItem> {
        val doc = Ksoup.parse(string)

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

    override fun getChapterImages(string: String): List<String> {
        val doc: Document = Ksoup.parse(string)
        // select all the chapter images (they're lazy-loaded with data-src)
        return doc.select("div.read-container img.wp-manga-chapter-img")
            .map { img ->
                // prefer data-src if present, otherwise fall back to src
                val urlAttr = if (img.hasAttr("data-src")) "data-src" else "src"
                img.absUrl(urlAttr)
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
        // persist in background
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

        // update the cache immediately
    }

    override val blackListGenres: Set<String>
        get() = setOf(
            "Smut",
            "Yaoi",
            "Doujinshi",
            "Lolicon",
            "Yaoi",
            "Adult",
            "Yuri",
            "Soft Yuri",
            "Soft Yaoi",
            "Yaoi",
            "Shoujo Ai",
            "Shounen Ai",
        )
    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()

    private fun parseChapterDate(dateText: String): LocalDate? {
        return try {
            when {
                dateText.contains("ago", ignoreCase = true) -> {
                    // Handle relative dates like "25 minutes ago"
                    todayInSystem()
                }
                dateText.equals("Complete", ignoreCase = true) -> {
                    // For completed chapters, use a past date or null
                    null
                }
                else -> {
                    // Try to parse dates like "Oct 26, 2025"
                    LocalDate.parse(dateText, abbrMonthDayYearFormatter)
                }
            }
        } catch (e: Exception) {
            Logger.withTag("ManhwaTop").e(e) { "Error parsing date: $dateText" }
            todayInSystem()
        }
    }

}

/**
 * Audit-trail postscript (Phase 9.x.cluster195.staleKdocSweep.cascade, Task #650, 2026-05-29)
 *
 * Leaf 3/5 §253 audit-trail-preservation postscript for cluster195, sibling 333 of the cluster57+
 * continuum. Middle-leaf medium-weight 461-line NormalSites subclass for the ManhwaTop English
 * source. The FIRST cluster195 leaf to carry a verbose Phase 7.2 migration KDoc with file-specific
 * KMP-port-decision notes (3-bullet sub-section beyond the canonical 6-bullet preamble) — this
 * documentation density pattern continues into leaves 4/5 (MangaBuddyRepositoryV2 — Africa/Cairo
 * timezone notes) and 5/5 (TapasticRepository — Dispatchers.IO→Default rationale).
 *
 * The top-of-file prose under audit (lines 3-18) is a file-header KDoc carrying TWO distinct
 * sub-sections:
 *
 *   I.   Canonical Phase 7.2 6-bullet migration-pattern preamble (lines 4-5) — same verbatim
 *        block as cluster195 leaves 1/5, 2/5, 4/5, 5/5.
 *
 *   II.  File-specific KMP-port-decision notes (lines 7-17) — 3 detailed bullets:
 *        - okhttp3.HttpUrl.Companion.toHttpUrl().newBuilder().addPathSegment(...).
 *          addQueryParameter(...) → manual string composition rewrite. Documents exact endpoint
 *          shape: "${baseUrl}wp-admin/admin-ajax.php/browse?langs=en&sort=update.za&page=N".
 *        - api.post(ajaxUrl, body, defaultHeaders) where body = FormBody.Builder().build() (an
 *          empty form body) → api.postForm(url, fields = emptyMap(), headers = defaultHeaders).
 *          Response .body() ?: "" → response.bodyAsText().
 *        - DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH) → LocalDate.Format DSL
 *          with MonthNames.ENGLISH_ABBREVIATED.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — sub-section I (canonical 6-bullet preamble). See cluster195 leaf 2/5
 *      classification (a) for full verification rationale. Same canonical preamble, same import-
 *      survey verification approach.
 *
 *   b. LIVE-NOT-STALE — sub-section II bullet 1 (okhttp3.HttpUrl rewrite). Verified by reading
 *      lines 68-70 (`override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL
 *      }}wp-admin/admin-ajax.php/browse?langs=en&sort=update.za&page=1" }`) and lines 78-80
 *      (`override fun handelLoadMoreUrl(page: Int): String { return "${baseUrl.ifBlank { BASE_URL
 *      }}wp-admin/admin-ajax.php/browse?langs=en&sort=update.za&page=$page" }`). The manual string
 *      composition exactly matches the documented endpoint shape. Zero `okhttp3.HttpUrl` or
 *      `HttpUrl.Builder` imports — the rewrite is complete.
 *
 *   c. LIVE-NOT-STALE — sub-section II bullet 2 (api.postForm + bodyAsText migration). Verified by
 *      reading line 294 (`val response = api.postForm(ajaxUrl, fields = emptyMap(), headers =
 *      defaultHeaders)`) and line 297 (`val chaptersHtml = response.bodyAsText()`). Both calls
 *      structurally match the documented migration. `io.ktor.client.statement.bodyAsText` import
 *      present at line 23. Zero `FormBody.Builder` or `okhttp3.FormBody` imports.
 *
 *   d. LIVE-NOT-STALE — sub-section II bullet 3 (kotlinx.datetime ENGLISH_ABBREVIATED LocalDate
 *      formatter). Verified by reading lines 124-130 (`private val abbrMonthDayYearFormatter =
 *      LocalDate.Format { monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); day(); chars(",
 *      "); year() }`). The DSL form structurally matches the documented migration. Imports at
 *      lines 27-31 (`kotlinx.datetime.LocalDate`, `kotlinx.datetime.TimeZone`, `kotlinx.datetime.
 *      format.MonthNames`, `kotlinx.datetime.format.char`, `kotlinx.datetime.todayIn`) — all
 *      kotlinx.datetime imports, zero `java.time.DateTimeFormatter` or `java.util.Locale` imports.
 *
 *   e. LIVE-NOT-STALE — Madara `madara_load_more` POST-form action (lines 82-97). 12-key form-body
 *      map matching the canonical Madara WordPress-theme convention (cross-clusters reference per
 *      cluster194 closer's catalogue: Madara conventions also appear in MangaLekRepositoryv2
 *      sibling 327, MangatukRepository sibling 329). The popular-vs-latest branch on
 *      `vars[meta_key]` (`if (popular) "_wp_manga_views" else "_latest_update"`) is the canonical
 *      Madara metadata-key swap.
 *
 *   f. LIVE-NOT-STALE — `@OptIn(ExperimentalTime::class)` opt-in (line 43) required by `kotlin.
 *      time.Clock` import (line 25). Phase 7.2 standard pattern for files using `Clock.System.now()`
 *      or `Clock.System.todayIn()`. Reused across cluster195 leaves 4/5 (MangaBuddyRepositoryV2
 *      line 49) and 5/5 (TapasticRepository line 61).
 *
 *   g. POTENTIAL-BUG-PRESERVED — debug-tag noise. Three keyboard-mashed Logger tags appear in
 *      this file:
 *        - line 290: `Logger.withTag("aslkfjsdlkfjskldfsdfsadfsdfsda0").i { ajaxUrl }`
 *        - line 295: `Logger.withTag("aslkfjsdlkfjskldfsdfsadfsdfsda1").i { response.toString() }`
 *        - line 299: `Logger.withTag("aslkfjsdlkfjskldfsdfsadfsdfsda2").i { doc.toString() }`
 *      These appear inside `fetchChapters()` for diagnostic chapter-AJAX response inspection.
 *      PROPER tags ARE used elsewhere in the file (e.g. line 329: `Logger.withTag("ManhwaTop").e(e)
 *      { "Error parsing chapter: ${e.message}" }`, line 335: `Logger.withTag("ManhwaTop").d {
 *      "Successfully parsed ${chapters.size} chapters" }`). The mixed-tag pattern suggests
 *      mid-development upstream diagnostic noise was never cleaned up. Phase 8 cleanup candidate.
 *      Preserved verbatim per §253.
 *
 *   h. COSMETIC-NOT-STALE — ChatGPT/AI authorship artifacts. Lines 348, 355, 361, 366, 372 carry
 *      stray `:contentReference[oaicite:0]{index=0}` markers (and 1, 2, 3, 4 variants) inside
 *      Kotlin line comments. These are remnants of upstream code-generation via an AI assistant
 *      whose response format includes inline citation tags that were not stripped before paste.
 *      Functionally inert (they're inside `//` line comments). Preserved verbatim per §253 —
 *      informational note for any future investigator who wonders about the markers' origin.
 *
 *   i. LIVE-NOT-STALE — defaultHeaders Volatile cache pattern (lines 399-407). Same canonical
 *      pattern as cluster195 leaf 2/5 (DemonicScansRepository lines 71-75) — `@Volatile private
 *      var _cachedHeaders: Map<String, String>? = null` + `override val defaultHeaders: ... get()
 *      = _cachedHeaders ?: emptyMap()`. Cross-cluster reference: this is the 6th sibling using
 *      the identical pattern across cluster192-195.
 *
 *   j. LIVE-NOT-STALE — homeGet override at line 60 (`override var homeGet: Boolean = false`).
 *      ManhwaTop uses POST for the home endpoint (the Madara load_more pattern requires POST with
 *      action+vars body). The override is the canonical mechanism for indicating per-source
 *      HTTP-method differences against the NormalSites base default. Verified by the absence of a
 *      corresponding `useGetForHome` override (the override exists in cluster195 leaf 2/5
 *      DemonicScansRepository line 66 for the GET-for-home case, and leaf 5/5 TapasticRepository
 *      lines 104-111 for all 8 GET-for-* overrides).
 *
 *   k. POTENTIAL-BUG-PRESERVED — `mangaId` extraction at lines 263-265 (`val mangaId = doc.
 *      selectFirst("div#manga-chapters-holder")?.attr("data-id") ?: baseUrl.split("/").lastOrNull
 *      { it.isNotBlank() }`). The extracted `mangaId` is then NEVER USED in the function body
 *      (the immediately-following line 268 calls `fetchChapters(baseUrl)` passing `baseUrl`, not
 *      `mangaId`). Either the extraction is a leftover from an earlier implementation that
 *      passed mangaId to fetchChapters, or the AJAX endpoint was changed to be derivable from
 *      baseUrl alone. Preserved verbatim per §253 — Phase 8 cleanup candidate to either drop the
 *      unused extraction or restore its use.
 *
 *   l. COSMETIC-NOT-STALE — `eachText()` extension method on ksoup `Elements` (lines 244, 249,
 *      254). The extension presumably lives on the ksoup library's `Elements` class (or is a
 *      cross-source utility). Used consistently across all multi-element-text-extraction sites.
 *      Not a sweep concern.
 *
 *   m. FACTUALLY-DRIFTED-IN-PROSE-ONLY — none in this file. The 3-bullet sub-section II's claims
 *      are all structurally verified in classifications (b/c/d). The 6-bullet preamble's claims
 *      are verified in (a).
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 331 (ReadComicOnlineRepository.kt) — leaf 1/5, opening leaf, 18-line empty-body
 *     placeholder + cluster-opening summary.
 *   - sibling 332 (DemonicScansRepository.kt) — leaf 2/5, 377-line NormalSitesv2 with debug-tag
 *     noise + unused companion TAG.
 *   - sibling 334 (MangaBuddyRepositoryV2.kt) — leaf 4/5, 521-line SeparatedDetailsSites with
 *     Africa/Cairo timezone + duplicate parser body.
 *   - sibling 335 (TapasticRepository.kt) — leaf 5/5, 541-line SeparatedDetailsSitesv2 with
 *     Semaphore-bounded parallel chapter-fetch.
 *
 * Cluster195 leaf 3/5 — middle leaf. Next leaf: MangaBuddyRepositoryV2.kt (sibling 334).
 */
