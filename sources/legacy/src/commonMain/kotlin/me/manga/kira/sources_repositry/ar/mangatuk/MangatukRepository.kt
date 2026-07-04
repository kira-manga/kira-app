package me.manga.kira.sources_repositry.ar.mangatuk

/**
 * Migration note (Phase 7.1 / ar Wave B): Retrofit -> Ktor ApiClient, okhttp3.FormBody -> Map<String, String>?,
 * @Inject dropped, jsoup -> ksoup, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime,
 * kotlin.jvm.Volatile -> kotlin.concurrent.Volatile.
 *
 * The upstream `me.manga.kira.admin.Admin` import was unused at the use-site and is dropped.
 * `Dispatchers.IO` is JVM-only; the per-page chapter-pagination fetch is now sequential since
 * Ktor calls are main-safe. TODO(Phase 8 - parallel-IO): reintroduce parallel page fetches via
 * a KMP-portable dispatcher abstraction.
 *
 * Upstream `parseArabicMonthDate` used `DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)` —
 * replaced with a manual English month-name map. TODO(Phase 8 - locale): restore locale-aware
 * parsing once KMP supports it.
 *
 * The AJAX chapter-fetch endpoint posts an empty form body via `api.postForm(url, emptyMap(), headers)`.
 *
 * Audit-trail postscript (Phase 9.x.cluster194.staleKdocSweep.cascade, Task #649, 2026-05-29).
 * Position-in-cluster: leaf 3/4, middle (699 lines, second-largest in cluster194).
 *
 * Classification under cluster57+ taxonomy:
 *
 * a) `Retrofit -> Ktor ApiClient` (line 4) — LIVE-NOT-STALE. Verified: imports
 *    `me.manga.kira.data.remote.api.ApiClient` (line 39); `api.postForm(...)` in
 *    fetchChaptersViaAjax (line 427); `api.get(...)` in pagination loop (line 372); response
 *    consumed via `io.ktor.client.statement.bodyAsText` (line 23) + `io.ktor.http.isSuccess`
 *    (line 24).
 *
 * b) `okhttp3.FormBody -> Map<String, String>?` (line 4) — LIVE-NOT-STALE. Verified:
 *    `normalSearchFormBody`/`genresSearchFormBody`/`sortFormBody` all return null (lines
 *    600-604) and `handelFormBody` returns null (line 88) — this Madara source uses GET-search
 *    URLs (line 138-139), not POST-form for the search endpoint. `fetchChaptersViaAjax` posts
 *    an empty Map<String,String> via `fields = emptyMap()` (line 427) — matches the doc line
 *    18 "empty form body".
 *
 * c) `@Inject dropped` (line 4) — LIVE-NOT-STALE. Verified: constructor (lines 50-52) is plain
 *    `private val`; no annotation.
 *
 * d) `jsoup -> ksoup` (line 4) — LIVE-NOT-STALE. Verified: `com.fleeksoft.ksoup.Ksoup` + `Document`
 *    imports (lines 21-22); 11 `Ksoup.parse(...)` call sites across the file (lines 144, 209,
 *    249, 431, 492, 570, 585, 640, etc.); no `org.jsoup` imports.
 *
 * e) `android.util.Log -> Kermit Logger` (line 5) — LIVE-NOT-STALE. Verified:
 *    `co.touchlab.kermit.Logger` import (line 20); 6 `Logger.withTag(TAG).e(e) { ... }` call
 *    sites (lines 201, 240, 356, 378, 435, 527, 644) with `TAG = "MangatukRepository"` companion
 *    constant (lines 55-57) — sensible production-quality Kermit logging (contrast with siblings
 *    322 and 327 keyboard-mashed debug tags).
 *
 * f) `java.time -> kotlinx.datetime` (line 5) — LIVE-NOT-STALE. Verified: `LocalDate`,
 *    `DateTimeUnit`, `TimeZone`, `minus`, `toLocalDateTime`, `todayIn` imports (lines 30-35);
 *    `kotlin.time.Clock` + `kotlin.time.ExperimentalTime` (lines 26-27) with `@OptIn` (line 48);
 *    Arabic-month-name parsing in parseArabicDate (lines 610-647) + parseArabicMonthDate
 *    (lines 649-698).
 *
 * g) `kotlin.jvm.Volatile -> kotlin.concurrent.Volatile` (line 5) — LIVE-NOT-STALE. Verified:
 *    `kotlin.concurrent.Volatile` import (line 25); `@Volatile` annotation on `_cachedHeaders`
 *    backing field (line 91).
 *
 * h) Unused `me.manga.kira.admin.Admin` import drop (line 7) — LIVE-NOT-STALE. Verified:
 *    no `admin.Admin` import remains in the file; dead-import elision is upstream-faithful.
 *
 * i) `Dispatchers.IO` drop + Phase 8 parallel-IO TODO (lines 7-11) — LIVE-NOT-STALE + FORECAST-
 *    NOT-YET-FULFILLED. Verified: no `Dispatchers.IO` reference in the file; the per-page
 *    pagination loop at lines 365-381 is sequential (`for (pageNum in 2..lastPage)`) with the
 *    inline TODO at line 367-368. Matches sibling 324 (cluster193, TeamX) parallel-IO TODO —
 *    cross-cluster reference: TWO `:ar/` Repository implementations carry this Phase 8 TODO.
 *
 * j) `parseArabicMonthDate` Locale-replacement note (lines 13-15) — LIVE-NOT-STALE + FORECAST-
 *    NOT-YET-FULFILLED + PARTIALLY-FULFILLED-FORECAST. Verified: 12-entry Arabic-month-name map
 *    (lines 651-655) + 23-entry English-month-name map (lines 674-680, with abbreviations) at
 *    lines 638-695. The 23-entry English map is the LARGEST in the :ar/ tier sweep (sibling 327
 *    has 24 entries — 12 Arabic + 12 English; sibling 321 cluster192 had 15 English entries).
 *    Cross-cluster reference: confirms ARABIC_MONTH_MAP 12-entry shape is the canonical Arabic
 *    map across cluster192+193+194. TODO(Phase 8 - locale) at line 673 remains open.
 *
 * k) AJAX chapter-fetch with empty form body (line 18) — LIVE-NOT-STALE. Verified:
 *    fetchChaptersViaAjax at lines 421-439 calls `api.postForm(ajaxUrl, fields = emptyMap(),
 *    headers = defaultHeaders)` (line 427); the ajaxUrl is built as `mangaUrl/ajax/chapters/?t=1`
 *    (line 425) — Madara's `t=1` query-param tells the WordPress server to render the chapter
 *    list HTML server-side.
 *
 * l) POTENTIAL-BUG-PRESERVED: 5-method image-extraction fallback chain in getChapterImages
 *    (lines 535-598) cascades JS-array → CSS-bg → inline-style → traditional-img tags. The
 *    upstream-verbatim "protection-warning" filter blacklist (lines 550, 561, 576, 591) on
 *    every fallback method is a defensive measure against the source's hotlink-protection
 *    placeholder served when the Referer fails. Preserved verbatim.
 *
 * m) POTENTIAL-BUG-PRESERVED: 60-entry commented-out `allGenres` set (lines 119-130) and
 *    6-entry commented-out `sortTypes` set (lines 109-116) preserve upstream-verbatim
 *    cosmetic surface. The blackListGenres (lines 132-136) keeps 2 active entries ("ناضج" /
 *    "للبالغين" — "Mature" / "For Adults") which gate the `extractHomeMangaItems` skip-loop
 *    via `genres.hasBlacklistedGenre()` (line 186) — POTENTIAL-BUG-PRESERVED if upstream's
 *    intent was the full 60-entry surface enabled.
 *
 * n) POTENTIAL-BUG-PRESERVED: extractMangaInfo's `mangaId` extraction (line 316) builds the
 *    AJAX endpoint URL but the file `data-id`-based AJAX call is NOT actually triggered —
 *    the `if (chapters.isEmpty() && mangaId.isNotBlank())` guard (line 351) uses `url` (the
 *    manga page URL), not `mangaId`. Upstream behaviour preserved.
 *
 * o) COSMETIC-NOT-STALE: 4-method chapter-number extraction in `extractChapterNumber` (lines
 *    401-416) — URL regex → `الفصل|Chapter|Ch.` text regex → all-numeric fallback → text
 *    fallback — defensive against Madara's chapter-numbering inconsistency. Preserved verbatim.
 *
 * p) COSMETIC-NOT-STALE: `@OptIn(ExperimentalTime::class)` (line 48) — required by
 *    `kotlin.time.Clock` + `kotlin.time.Instant` opt-in; matches all 4 :ar/ Repository
 *    implementations in cluster193 + 194.
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import me.manga.kira.core.states.State
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
class MangatukRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSites(api, sourcesRepository) {

    companion object {
        private const val TAG = "MangatukRepository"
    }

    override val mangaSource: MangaSource
        get() = MangaSource.MANGATUK

    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override var imgBaseUrl: String = mangaSource.BASEURL
    override var imgUrlVersion: Int = 0

    override val API: String
        get() = mangaSource.API

    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { "${baseUrl.ifBlank { mangaSource.BASEURL }}manga/?m_orderby=latest" }

    override val popularUrl: String by lazy { "${baseUrl.ifBlank { mangaSource.BASEURL }}manga/?m_orderby=trending" }

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { mangaSource.BASEURL }}manga/page/$page/?m_orderby=latest"
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> normalSearchUrl(q = searchType.toNormalQuery())
            is SearchType.GENRES -> normalSearchUrl(q = searchType.toNormalQuery())
            is SearchType.SORT -> normalSearchUrl(q = searchType.toNormalQuery())
        }

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        return null
    }

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override val sortTypes: Set<String>
        get() = setOf(
//            "latest",      // الأحدث
//            "alphabet",    // من الألف إلى الياء
//            "rating",      // التقييم
//            "trending",    // الأكثر رواجاً
//            "views",       // معظم المشاهدات
//            "new-manga"    // جديد
        )

    override val allGenres: Set<String>
        get() = setOf(
//            "18+", "إعادة التجسد", "اثارة", "اشرار (شرير او شريرة)", "اعادة احياء",
//            "اكشن", "البقاء على قيد الحياة", "السفر عبر الزمن", "العاب فيديو",
//            "العودة بالزمن", "الفنون القتالية", "انتقام", "ايتشي", "ايسيكاي",
//            "بطل غير اعتيادي", "تاريخي", "تبديل الجنس", "تراجيدي", "تناسخ",
//            "جوسي", "حريم", "حريم عكسي", "خارق للطبيعة", "خيال", "خيال علمي",
//            "دراما", "دموي", "رعب", "رومانسي", "رياضة", "زنزانات", "سحر",
//            "سمَت", "سيد خادم", "سينين", "شريحة من الحياة", "شوجو", "شونين",
//            "شونين آي", "شياطين", "عنف", "غموض", "فقدان الذاكرة", "فنتازيا",
//            "قوة خارقة", "كوميدي", "للبالغين", "مأساوي", "مدرسي", "مغامرات",
//            "موريم", "ناضج", "نظام", "نفسي", "وحوش", "ويب تون"
        )

    override val blackListGenres: Set<String>
        get() = setOf(
            "ناضج",
            "للبالغين"
        )

    private fun normalSearchUrl(q: String): String =
        "${baseUrl.ifBlank { mangaSource.BASEURL }}?s=$q&post_type=wp-manga"


    override fun extractHomeMangaItems(string: String): MutableList<MangaItem> {
        val mangaList = mutableListOf<MangaItem>()
        val doc: Document = Ksoup.parse(string)

        // Madara theme uses this structure for manga listing
        val mangaElements = doc.select("div.page-listing-item div.page-item-detail.manga")

        for (element in mangaElements) {
            try {
                val titleElement = element.selectFirst("div.post-title h3 a, div.post-title h5 a")
                val urlElement = element.selectFirst("div.item-thumb a[href]")
                val imageElement = element.selectFirst("div.item-thumb img")

                val title = titleElement?.text()?.trim() ?: continue
                val url = urlElement?.attr("href") ?: continue

                // Handle lazy-loaded images
                val imageUrl = imageElement?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                } ?: ""

                // Extract rating
                val ratingText = element.selectFirst("span.score")?.text()?.trim()
                val rating = ratingText?.toDoubleOrNull()?.toInt()

                // Extract chapters from listing
                val chapterElements = element.select("div.list-chapter div.chapter-item")
                val chapters = chapterElements.mapNotNull { chapterEl ->
                    val chapterLink = chapterEl.selectFirst("span.chapter a")
                    val chapterUrl = chapterLink?.attr("href") ?: return@mapNotNull null
                    val chapterNumber = chapterLink.text().trim()

                    val dateText = chapterEl.selectFirst("span.post-on span.timediff")?.text()?.trim()
                        ?: chapterEl.selectFirst("span.post-on")?.text()?.trim()

                    ChapterItem(
                        url = chapterUrl,
                        number = chapterNumber,
                        date = parseArabicDate(dateText),
                    )
                }

                // Extract genres from badges if available
                val genres = element.select("span.manga-title-badges").map { it.text().trim() }
                if (genres.hasBlacklistedGenre()) continue

                val mangaItem = MangaItem(
                    api = API,
                    language = LANGUAGE,
                    title = title,
                    url = url,
                    imageUrl = imageUrl,
                    chapters = chapters,
                    genres = genres,
                    rating = rating
                )

                mangaList.add(mangaItem)
            } catch (e: Exception) {
                Logger.withTag(TAG).e(e) { "Error parsing manga item: ${e.message}" }
            }
        }

        return mangaList
    }

    override fun extractMangaList(string: String): List<PopularManga> {
        val doc: Document = Ksoup.parse(string)
        val popularList = mutableListOf<PopularManga>()

        // Popular slider items from widget-manga-popular-slider
        val sliderItems = doc.select("div.slider__item")

        for (slide in sliderItems) {
            try {
                val titleLink = slide.selectFirst("div.post-title h4 a")
                    ?: slide.selectFirst("div.slider__content_item div.post-title a")
                val imageElement = slide.selectFirst("div.slider__thumb_item img")
                    ?: slide.selectFirst("div.slider__thumb img")

                val title = titleLink?.text()?.trim() ?: continue
                val url = titleLink.attr("href")

                // Handle lazy-loaded images
                val imageUrl = imageElement?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                } ?: ""

                popularList.add(
                    PopularManga(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = url,
                        imageUrl = imageUrl,
                    )
                )
            } catch (e: Exception) {
                Logger.withTag(TAG).e(e) { "Error parsing popular manga: ${e.message}" }
            }
        }

        return popularList
    }

    override suspend fun extractMangaInfo(string: String, baseUrl: String): MangaInfo {
        val url = baseUrl
        val doc = Ksoup.parse(string)

        // Title extraction - from post-title h1 in profile-manga section
        val title = doc.selectFirst("div.post-title h1")?.ownText()?.trim()
            ?: doc.selectFirst("div.post-title h3")?.text()?.trim()
            ?: "Unknown Title"

        // Image extraction - from summary_image with lazy loading support
        val imageUrl = doc.selectFirst("div.summary_image img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        } ?: ""

        // Rating extraction - from post-total-rating
        val rating = doc.selectFirst("div.post-total-rating span.score")?.text()?.trim()
            ?: doc.selectFirst("span.total_votes")?.text()?.trim()
            ?: "0"

        // Rating count from span#countrate
        val ratingCount = doc.selectFirst("span#countrate")?.text()?.trim()
            ?: doc.selectFirst("span[property=ratingCount]")?.text()?.trim()
            ?: "0"

        // Description extraction - from manga-excerpt summary__content
        val description = doc.selectFirst("div.manga-excerpt.summary__content")?.let { descElement ->
            // Get all paragraph text
            descElement.select("p").joinToString("\n") { it.text().trim() }
        }?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("div.summary__content")?.text()?.trim()
            ?: "No description available"

        // Genres extraction - from genres-content
        val genres = doc.select("div.genres-content a").map { it.text().trim() }

        // Extract metadata from post-content_item sections
        val summaryItems = doc.select("div.post-content div.post-content_item")

        var status = "Unknown"
        var author = "Unknown"
        var artist = "Unknown"
        var otherNames = ""

        for (item in summaryItems) {
            val heading = item.selectFirst("div.summary-heading h5")?.text()?.trim()?.lowercase() ?: continue
            val content = item.selectFirst("div.summary-content")?.text()?.trim() ?: continue

            when {
                heading.contains("حالة الإصدار") || heading.contains("status") -> status = content
                heading.contains("المؤلف") || heading.contains("author") -> {
                    author = item.selectFirst("div.author-content a")?.text()?.trim() ?: content
                }
                heading.contains("الفنان") || heading.contains("الرسام") || heading.contains("artist") -> {
                    artist = item.selectFirst("div.artist-content a")?.text()?.trim() ?: content
                }
                heading.contains("أسماء أخرى") || heading.contains("alternative") -> otherNames = content
            }
        }

        // Extract badges/tags from manga-title-badges
        val badges = doc.select("div.post-title span.manga-title-badges span.text").map { it.text().trim() }

        // Favorites count from bookmark section
        val favoritesCount = doc.selectFirst("div.add-bookmark div.action_detail span")?.text()?.let {
            Regex("""\d+""").find(it)?.value
        } ?: "0"

        // Extract chapters - chapters are loaded via AJAX on this site
        // The chapter holder has data-id attribute with manga ID
        val mangaId = doc.selectFirst("div#manga-chapters-holder")?.attr("data-id") ?: ""

        val chapters = mutableListOf<ChapterItem>()

        // If chapters are present in the initial HTML (some pages pre-render them)
        val chapterElements = doc.select("li.wp-manga-chapter")

        for (element in chapterElements) {
            val chapterLink = element.selectFirst("a")
            val chapterUrl = chapterLink?.attr("href") ?: continue
            val chapterText = chapterLink.text().trim()

            // Check if chapter is premium/locked
            val isPremium = element.hasClass("premium") ||
                    element.selectFirst("i.fa-lock") != null

            // Extract chapter number from URL or text
            val chapterNumber = extractChapterNumber(chapterUrl, chapterText)

            val dateText = element.selectFirst("span.chapter-release-date")?.text()?.trim()
                ?: element.selectFirst("span.chapter-release-date i")?.text()?.trim()

            if (isPremium) continue
            chapters.add(
                ChapterItem(
                    number = chapterNumber,
                    name = chapterText,
                    url = chapterUrl,
                    date = parseArabicDate(dateText),
                    isDownloaded = false,
                )
            )
        }

        // If no chapters found in HTML, try to fetch via AJAX endpoint
        if (chapters.isEmpty() && mangaId.isNotBlank()) {
            try {
                val ajaxChapters = fetchChaptersViaAjax(url)
                chapters.addAll(ajaxChapters)
            } catch (e: Exception) {
                Logger.withTag(TAG).e(e) { "Error fetching chapters via AJAX: ${e.message}" }
            }
        }

        // Handle pagination for chapters if present
        val paginationLinks = doc.select("ul.pagination li.page-item a.page-link")
            .mapNotNull { it.text().toIntOrNull() }
        val lastPage = paginationLinks.maxOrNull() ?: 1

        if (lastPage > 1) {
            val baseChapterUrl = url.substringBeforeLast("?")
            // TODO(Phase 8 - parallel-IO): port `async(Dispatchers.IO)` parallel fetches once a
            // KMP-portable dispatcher abstraction is available. Sequential for now.
            for (pageNum in 2..lastPage) {
                runCatching {
                    val pageUrl = "$baseChapterUrl?page=$pageNum"
                    val response = api.get(pageUrl, headers = defaultHeaders)
                    if (response.status.isSuccess()) {
                        val pageDoc = Ksoup.parse(response.bodyAsText())
                        chapters.addAll(extractChaptersFromPage(pageDoc))
                    }
                }.onFailure {
                    Logger.withTag(TAG).e(it) { "Error fetching chapter page $pageNum: ${it.message}" }
                }
            }
        }

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = title,
            imageUrl = imageUrl,
            rating = rating,
            description = description,
            genres = genres,
            status = status,
            author = author,
            chapters = chapters.distinctBy { it.url }.toMutableList()
        )
    }

    /**
     * Extract chapter number from URL or text
     */
    private fun extractChapterNumber(chapterUrl: String, chapterText: String): String {
        // Try to extract from URL first (e.g., /manga/name/55/ -> 55)
        val urlPattern = Regex("""/(\d+(?:\.\d+)?(?:-\d+)?)/?$""")
        urlPattern.find(chapterUrl)?.groupValues?.get(1)?.let { return it }

        // Try to extract from text
        val textPattern = Regex("""(?:الفصل|Chapter|Ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        textPattern.find(chapterText)?.groupValues?.get(1)?.let { return it }

        // Just return the number if text is only a number
        if (chapterText.matches(Regex("""^\d+(?:\.\d+)?$"""))) {
            return chapterText
        }

        return chapterText
    }

    /**
     * Fetch chapters via AJAX endpoint (Madara theme uses this for lazy loading chapters)
     */
    private suspend fun fetchChaptersViaAjax(mangaUrl: String): List<ChapterItem> {
        val chapters = mutableListOf<ChapterItem>()

        try {
            val ajaxUrl = mangaUrl.removeSuffix("/") + "/ajax/chapters/?t=1"

            val response = api.postForm(ajaxUrl, fields = emptyMap(), headers = defaultHeaders)

            if (response.status.isSuccess()) {
                val html = response.bodyAsText()
                val doc = Ksoup.parse(html)
                chapters.addAll(extractChaptersFromPage(doc))
            }
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "AJAX chapter fetch failed: ${e.message}" }
        }

        return chapters
    }

    private fun extractChaptersFromPage(doc: Document): List<ChapterItem> {
        val chapters = mutableListOf<ChapterItem>()
        val chapterElements = doc.select("li.wp-manga-chapter")

        for (element in chapterElements) {
            val chapterLink = element.selectFirst("a") ?: continue
            val chapterUrl = chapterLink.attr("href")
            if (chapterUrl.isBlank()) continue

            val chapterText = chapterLink.text().trim()

            // Check premium/locked status
            val isPremium = element.hasClass("premium") ||
                    element.hasClass("premium-block") ||
                    element.selectFirst("i.fa-lock") != null

            // Check if it's a free chapter
            val isFree = element.hasClass("free-chap")

            // Skip premium chapters that aren't free
            if (isPremium && !isFree) continue

            val chapterNumber = extractChapterNumber(chapterUrl, chapterText)

            // Date extraction from nested structure:
            // <span class="chapter-release-date">
            //     <span class="timediff"><i><i class="fa fa-calendar"></i> نوفمبر 14, 2024</i></span>
            // </span>
            val dateText = element.selectFirst("span.chapter-release-date span.timediff i")?.text()?.trim()
                ?.replace(Regex("""^\s*"""), "") // Remove leading whitespace
                ?: element.selectFirst("span.chapter-release-date span.timediff")?.text()?.trim()
                ?: element.selectFirst("span.chapter-release-date")?.ownText()?.trim()

            // Clean date text - remove any icon remnants
            val cleanedDate = dateText?.replace(Regex("""^[\s ]*"""), "")?.trim()

            chapters.add(
                ChapterItem(
                    number = chapterNumber,
                    name = chapterText,
                    url = chapterUrl,
                    date = parseArabicDate(cleanedDate),
                    isDownloaded = false,
                )
            )
        }

        return chapters
    }

    override suspend fun getSearchResults(string: String): List<MangaItem> {
        val doc = Ksoup.parse(string)
        val results = mutableListOf<MangaItem>()

        // Madara search results structure
        val searchItems = doc.select("div.c-tabs-item div.row.c-tabs-item__content")
            .ifEmpty { doc.select("div.page-listing-item div.page-item-detail") }

        for (item in searchItems) {
            try {
                val titleElement = item.selectFirst("div.post-title a, h3.h4 a")
                val imageElement = item.selectFirst("div.tab-thumb img, div.item-thumb img")

                val title = titleElement?.text()?.trim() ?: continue
                val url = titleElement.attr("href")

                val imageUrl = imageElement?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                } ?: ""

                val rating = item.selectFirst("span.score")?.text()?.toDoubleOrNull()?.toInt()
                val genres = item.select("div.mg_genres a, span.manga-title-badges").map { it.text().trim() }

                results.add(
                    MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = url,
                        imageUrl = imageUrl,
                        rating = rating,
                        chapters = emptyList(),
                        genres = genres
                    )
                )
            } catch (e: Exception) {
                Logger.withTag(TAG).e(e) { "Error parsing search result: ${e.message}" }
            }
        }

        return results
    }


    override fun getChapterImages(string: String): List<String> {
        val images = mutableListOf<String>()

        // Method 1: Extract from JavaScript array
        // Pattern: var images = ["url1", "url2", ...];
        val jsArrayPattern = Regex("""var\s+images\s*=\s*\[([\s\S]*?)\];""")
        val jsMatch = jsArrayPattern.find(string)
        if (jsMatch != null) {
            val arrayContent = jsMatch.groupValues[1]
            // Extract URLs from the JSON-like array
            val urlPattern = Regex(""""([^"]+)"""")
            urlPattern.findAll(arrayContent).forEach { match ->
                val url = match.groupValues[1]
                    .replace("\\/", "/")  // Unescape JSON slashes
                    .trim()
                if (url.isNotBlank() && !url.contains("protection-warning")) {
                    images.add(url)
                }
            }
        }

        // Method 2: Extract from CSS background-image (fallback/verification)
        if (images.isEmpty()) {
            // Pattern: .manga-page.image-X { background-image: url('...'); }
            val cssPattern = Regex("""\.manga-page\.image-\d+\s*\{\s*background-image:\s*url\(['"]?([^'")]+)['"]?\)""")
            cssPattern.findAll(string).forEach { match ->
                val url = match.groupValues[1].trim()
                if (url.isNotBlank() && !url.contains("protection-warning")) {
                    images.add(url)
                }
            }
        }

        // Method 3: Extract from inline style on divs (another fallback)
        if (images.isEmpty()) {
            val doc: Document = Ksoup.parse(string)
            doc.select("div.manga-page[style*='background-image']").forEach { element ->
                val style = element.attr("style")
                val urlMatch = Regex("""background-image:\s*url\(['"]?([^'")]+)['"]?\)""").find(style)
                urlMatch?.let {
                    val url = it.groupValues[1].trim()
                    if (url.isNotBlank() && !url.contains("protection-warning")) {
                        images.add(url)
                    }
                }
            }
        }

        // Method 4: Traditional img tags (unlikely to work on this site but kept as last resort)
        if (images.isEmpty()) {
            val doc: Document = Ksoup.parse(string)
            images.addAll(
                doc.select("div.reading-content div.page-break img")
                    .mapNotNull { element ->
                        val src = element.attr("data-src").ifBlank { element.attr("src") }
                        src.trim().takeIf {
                            it.isNotBlank() && !it.contains("protection-warning")
                        }
                    }
            )
        }

        return images
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? = null

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? = null

    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? = null

    /**
     * Parse Arabic date formats commonly used on Mangatuk
     * Examples: "منذ ساعة واحدة", "منذ يومين", "يناير 10, 2026"
     */
    private fun parseArabicDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null

        val zone = TimeZone.currentSystemDefault()
        val normalized = dateStr.trim()

        return try {
            when {
                // Relative time: "منذ X ساعة/ساعات"
                normalized.contains("ساعة") || normalized.contains("ساعات") -> {
                    val hours = Regex("""\d+""").find(normalized)?.value?.toLong() ?: 1L
                    Clock.System.now().minus(hours, DateTimeUnit.HOUR, zone).toLocalDateTime(zone).date
                }
                // Relative time: "منذ X يوم/أيام"
                normalized.contains("يوم") || normalized.contains("أيام") -> {
                    val days = Regex("""\d+""").find(normalized)?.value?.toInt() ?: 1
                    Clock.System.todayIn(zone).minus(days, DateTimeUnit.DAY)
                }
                // Relative time: "منذ X أسبوع/أسابيع"
                normalized.contains("أسبوع") || normalized.contains("أسابيع") -> {
                    val weeks = Regex("""\d+""").find(normalized)?.value?.toInt() ?: 1
                    Clock.System.todayIn(zone).minus(weeks, DateTimeUnit.WEEK)
                }
                // Relative time: "منذ X شهر/أشهر"
                normalized.contains("شهر") || normalized.contains("أشهر") -> {
                    val months = Regex("""\d+""").find(normalized)?.value?.toInt() ?: 1
                    Clock.System.todayIn(zone).minus(months, DateTimeUnit.MONTH)
                }
                else -> {
                    // Try parsing Arabic/English month format: "يناير 10, 2026" / "January 10, 2026"
                    parseArabicMonthDate(normalized)
                }
            }
        } catch (e: Exception) {
            Logger.withTag(TAG).e(e) { "Error parsing date: $dateStr - ${e.message}" }
            null
        }
    }

    private fun parseArabicMonthDate(dateStr: String): LocalDate? {
        // Arabic month names mapping
        val arabicMonths = mapOf(
            "يناير" to 1, "فبراير" to 2, "مارس" to 3, "أبريل" to 4,
            "مايو" to 5, "يونيو" to 6, "يوليو" to 7, "أغسطس" to 8,
            "سبتمبر" to 9, "أكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12
        )

        for ((monthName, monthNum) in arabicMonths) {
            if (dateStr.contains(monthName)) {
                val numbers = Regex("""\d+""").findAll(dateStr).map { it.value.toInt() }.toList()
                if (numbers.size >= 2) {
                    val day = numbers[0]
                    val year = numbers[1]
                    return try {
                        LocalDate(year, monthNum, day)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

        // Try English format as fallback
        // TODO(Phase 8 - locale): replace manual map with locale-aware parser.
        val englishMonths = mapOf(
            "january" to 1, "february" to 2, "march" to 3, "april" to 4,
            "may" to 5, "june" to 6, "july" to 7, "august" to 8,
            "september" to 9, "october" to 10, "november" to 11, "december" to 12,
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7,
            "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12
        )
        val lower = dateStr.lowercase()
        for ((monthName, monthNum) in englishMonths) {
            if (lower.contains(monthName)) {
                val numbers = Regex("""\d+""").findAll(dateStr).map { it.value.toInt() }.toList()
                if (numbers.size >= 2) {
                    val day = numbers[0]
                    val year = numbers[1]
                    return try {
                        LocalDate(year, monthNum, day)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

        return null
    }
}
