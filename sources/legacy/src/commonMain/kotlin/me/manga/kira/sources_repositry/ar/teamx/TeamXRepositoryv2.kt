package me.manga.kira.sources_repositry.ar.teamx

/**
 * Migration note (Phase 7.1 / ar Wave B): Retrofit -> Ktor ApiClient, okhttp3.FormBody ->
 * Map<String, String>?, @Inject dropped, jsoup -> ksoup, android.util.Log -> Kermit Logger,
 * java.time -> kotlinx.datetime, kotlin.jvm.Volatile -> kotlin.concurrent.Volatile.
 *
 * Coil3 image-request and `androidx.compose.runtime.Composable` `Admin` imports were unused at
 * the actual code sites and are dropped.
 *
 * `Dispatchers.IO` is JVM-only. The per-page chapter-pagination async fan-out becomes a sequential
 * loop since Ktor calls are main-safe. TODO(Phase 8 - parallel-IO): reintroduce parallel page
 * fetches via a KMP-portable dispatcher abstraction.
 *
 * Upstream Unix-timestamp parsing used `Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate()`
 * -> ported to `kotlin.time.Instant.fromEpochSeconds(ts).toLocalDateTime(TimeZone.currentSystemDefault()).date`.
 * Locale-aware Arabic month parsing (`Locale("ar")`) is replaced by a manual Arabic month-name
 * map. TODO(Phase 8 - locale): restore locale-aware parsing once KMP supports it.
 *
 * The duplicate `extractMangaInfo(html, url, fetchPage)` overload at the end of the upstream
 * file is unused at the call site (it's never wired in by `NormalSites`); only the primary
 * `extractMangaInfo(html, url)` override is ported.
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import me.manga.kira.core.states.State
import me.manga.kira.core.states.State.Error.Companion.fromCode
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
class TeamXRepositoryv2(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSites(api, sourcesRepository) {

    companion object {
        private const val TAG = "TeamXRepositoryv2"
    }

    override val mangaSource: MangaSource
        get() = MangaSource.TEAM_X
    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override var imgBaseUrl: String = mangaSource.BASEURL
    override var imgUrlVersion: Int = 0

    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }

    override val popularUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }

    override fun handelLoadMoreUrl(page: Int): String {
        return loadMoreUrl(page)
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> normalSearchUrl(q = searchType.toNormalQuery())
            is SearchType.GENRES -> ""
            is SearchType.SORT -> ""
        }

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        return null
    }

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        Logger.withTag("Headers").i {
            "[Headers] TeamX.initSite loaded ${headers.size} headers from DataStore for api=$API keys=${headers.keys}"
        }
        _cachedHeaders = headers

        return super.initSite()
    }

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        Logger.withTag("Headers").i {
            "[Headers] TeamX.refreshHeaders called with ${newHeaders.size} headers keys=${newHeaders.keys}"
        }
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
        Logger.withTag("Headers").i { "[Headers] TeamX.refreshHeaders cached & saved for api=$API" }
    }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }

    override suspend fun extractMangaInfo(string: String, baseUrl: String): MangaInfo {
        val html = string
        val url = baseUrl
        // 1) Parse the first page
        val doc = Ksoup.parse(html)

        val title = doc.selectFirst("div.author-info-title h1")?.text()?.trim() ?: "Unknown Title"
        val imageUrl = doc.selectFirst("div.text-right img.shadow-sm")?.attr("src") ?: ""
        val rating = doc.selectFirst("div#average_rating")?.text()?.trim() ?: "Unknown Rating"
        val ratingCount = doc.selectFirst("span#rating_count")?.text()?.trim() ?: "0"
        val description =
            doc.selectFirst("div.review-content p")?.text()?.trim() ?: "No description available"
        val genres = doc.select("div.review-author-info a.subtitle").map { it.text().trim() }
        val status = doc.select("div.full-list-info")
            .firstOrNull { it.text().contains("الحالة:") }
            ?.select("small")?.last()?.text()?.trim() ?: "Unknown Status"
        val artist = doc.select("div.full-list-info")
            .firstOrNull { it.text().contains("الرسام:") }
            ?.select("small a")?.first()?.text()?.trim() ?: "Unknown Artist"
        val author = "Unknown Author"
        val otherNames = ""
        val tags = emptyList<String>()
        val yearOfProduction = ""
        val favoritesCount = doc.select("div.full-list-info")
            .firstOrNull { it.text().contains("التبرعات:") }
            ?.select("small span")?.first()?.text()?.trim() ?: "0"

        Logger.withTag(TAG).i { "extractMangaInfo doc dump (truncated to 2k): ${doc.toString().take(2000)}" }

        // —— FAST PATH FOR PAGE URLS ——
        // 2) Look at the pagination widget on the first page:
        val pageLinks = doc.select("ul.pagination li.page-item a.page-link")
            .mapNotNull { it.text().toIntOrNull() }

        val lastPageNumber = pageLinks.maxOrNull() ?: 1

        // 3) Build ALL page URLs at once
        val base = url.substringBeforeLast("?page=")
        val pageUrls = (1..lastPageNumber).map { pageNum ->
            "$base?page=$pageNum"
        }

        // TODO(Phase 8 - parallel-IO): port `async(Dispatchers.IO) { ... }.awaitAll().flatten()`
        // fan-out once a KMP-portable dispatcher abstraction is available. Sequential for now.
        val allChapters = mutableListOf<ChapterItem>()
        for (pageUrl in pageUrls) {
            runCatching {
                val response = api.get(pageUrl, headers = defaultHeaders)
                if (response.status.isSuccess()) {
                    val pageDoc = Ksoup.parse(response.bodyAsText())
                    allChapters.addAll(getChapterData(pageDoc))
                }
            }.onFailure { e ->
                Logger.withTag(TAG).e(e) { "Failed to fetch chapter page $pageUrl: ${e.message}" }
            }
        }

        val filteredChapters = allChapters.filter { it.url != "#" }

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
            chapters = filteredChapters.toMutableList()
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

    fun loadMoreUrl(page: Int): String = "${baseUrl.ifBlank { mangaSource.BASEURL }}?page=$page"
    fun normalSearchUrl(q: String): String = "${baseUrl.ifBlank { mangaSource.BASEURL }}ajax/search?keyword=${q}"

    override fun extractMangaList(string: String): List<PopularManga> {
        val doc: Document = Ksoup.parse(string)
        return doc.select("div.swiper-slide").map { slide ->
            extractMangaItem(slide)
        }
    }

    override fun extractHomeMangaItems(string: String): MutableList<MangaItem> {
        val mangaList = mutableListOf<MangaItem>()
        val doc: Document = Ksoup.parse(string)

        val mangaElements = doc.select("div.listupd .bs .bsx").ifEmpty { doc.select("div.post-body .box") }

        for (div in mangaElements) {
            val titleElement = div.selectFirst("div.info a h3")
            val urlElement = div.selectFirst("div.info a[href]")
            val imageElement = div.selectFirst("div.imgu a img")
            val chaptersElements = div.select("div.info ul li a")

            val title = titleElement?.text() ?: "Unknown Title"
            val url = urlElement?.attr("href") ?: ""
            val imageUrl = imageElement?.attr("src") ?: ""

            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            val chapters = chaptersElements.map { chapter ->
                val chapterTitle = chapter.text()
                val chapterUrl = chapter.attr("href")

                // Extract chapter number from title (assuming "الفصل رقم X")
                val chapterNumber = Regex("""\d+""").find(chapterTitle)?.value.toString()

                ChapterItem(
                    url = chapterUrl,
                    number = "Chapter $chapterNumber",
                    date = today,
                    isDownloaded = false
                )
            }.filter { it.url != "#" } // Filter out invalid chapters

            val mangaItem = MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                chapters = chapters,
                genres = emptyList(),
                rating = null
            )

            mangaList.add(mangaItem)
        }

        return mangaList
    }

    fun extractMangaItem(slide: Element): PopularManga {
        // Extract series URL and image
        val imageLink = slide.selectFirst(".entry-image a.box")
        val img = imageLink?.selectFirst("img")

        val imageUrl = img?.attr("src")?.takeIf { it.isNotBlank() }

        // Extract title and its link
        val titleLink = slide.selectFirst(".entry-title a")
        val title = titleLink?.text()?.takeIf { it.isNotBlank() }
        val titleUrl = titleLink?.attr("href")?.takeIf { it.isNotBlank() }

        return PopularManga(
            api = API,
            language = LANGUAGE,
            title = title.toString(),
            url = titleUrl.toString(),
            imageUrl = imageUrl.toString()
        )
    }

    fun getChapterData(doc: Document): MutableList<ChapterItem> {
        val chapters = mutableListOf<ChapterItem>()

        // Correct selector based on the actual HTML structure
        val chapterElements = doc.select("div.chapter-card")

        for (element in chapterElements) {
            // Extract chapter number from data attribute or chapter-number div
            val chapterNumber = element.attr("data-number").ifBlank {
                element.select("div.chapter-number").text()
                    .replace(Regex("[^\\d.]"), "")
            }

            // Extract chapter title
            val chapterTitle = element.select("div.chapter-title").text().trim()

            // Extract chapter URL from the link
            val chapterUrl = element.select("a.chapter-link").attr("href")

            // Extract date - it's in a specific format in the data attribute
            val dateTimestamp = element.attr("data-date")
            val dateText = element.select("div.chapter-date span").text().trim()

            // Parse the date
            val date = if (dateTimestamp.isNotBlank()) {
                try {
                    val timestamp = dateTimestamp.toLong()
                    val zone = TimeZone.currentSystemDefault()
                    Instant.fromEpochSeconds(timestamp).toLocalDateTime(zone).date
                } catch (e: Exception) {
                    parseRelativeDate(dateText)
                }
            } else {
                parseRelativeDate(dateText)
            }

            val chapterItem = ChapterItem(
                number = chapterNumber.ifBlank { chapterTitle },
                name = chapterTitle,
                url = chapterUrl,
                date = date,
                isDownloaded = false
            )

            chapters.add(chapterItem)
        }

        return chapters
    }

    // Helper function to parse relative dates like "12 hours ago", "13 hours ago"
    fun parseRelativeDate(dateStr: String): LocalDate? {
        if (dateStr.isBlank()) return null

        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.todayIn(zone)
        val nowInstant = Clock.System.now()

        return try {
            when {
                // Handle "X hours ago"
                dateStr.contains("hours ago") || dateStr.contains("hour ago") -> {
                    val hours = Regex("""\d+""").find(dateStr)?.value?.toLong() ?: 0L
                    nowInstant.minus(hours, DateTimeUnit.HOUR, zone).toLocalDateTime(zone).date
                }
                // Handle "X days ago"
                dateStr.contains("days ago") || dateStr.contains("day ago") -> {
                    val days = Regex("""\d+""").find(dateStr)?.value?.toInt() ?: 0
                    today.minus(days, DateTimeUnit.DAY)
                }
                // Handle "X weeks ago"
                dateStr.contains("weeks ago") || dateStr.contains("week ago") -> {
                    val weeks = Regex("""\d+""").find(dateStr)?.value?.toInt() ?: 0
                    today.minus(weeks, DateTimeUnit.WEEK)
                }
                // Handle "X months ago"
                dateStr.contains("months ago") || dateStr.contains("month ago") -> {
                    val months = Regex("""\d+""").find(dateStr)?.value?.toInt() ?: 0
                    today.minus(months, DateTimeUnit.MONTH)
                }
                else -> parseChapterDate(dateStr)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parseChapterDate(dateStr: String): LocalDate? {
        if (dateStr.isBlank() || dateStr.equals("NEW", ignoreCase = true)) return null

        val normalized = dateStr.replace('،', ',').trim()

        // ISO yyyy-MM-dd HH:mm:ss (truncate to date)
        if (Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(normalized)) {
            return runCatching { LocalDate.parse(normalized.substring(0, 10)) }.getOrNull()
        }
        // plain ISO date yyyy-MM-dd
        if (Regex("""\d{4}-\d{2}-\d{2}""").matches(normalized)) {
            return runCatching { LocalDate.parse(normalized) }.getOrNull()
        }

        // Try Arabic month names (manual map — TODO(Phase 8 - locale) restore locale-aware parser).
        val arabicMonths = mapOf(
            "يناير" to 1, "فبراير" to 2, "مارس" to 3, "أبريل" to 4,
            "مايو" to 5, "يونيو" to 6, "يوليو" to 7, "أغسطس" to 8,
            "سبتمبر" to 9, "أكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12
        )
        for ((monthName, monthNum) in arabicMonths) {
            if (normalized.contains(monthName)) {
                val numbers = Regex("""\d+""").findAll(normalized).map { it.value.toInt() }.toList()
                if (numbers.size >= 2) {
                    return runCatching { LocalDate(numbers[1], monthNum, numbers[0]) }.getOrNull()
                }
            }
        }

        // Fallback to English month names
        val englishMonths = mapOf(
            "january" to 1, "february" to 2, "march" to 3, "april" to 4,
            "may" to 5, "june" to 6, "july" to 7, "august" to 8,
            "september" to 9, "october" to 10, "november" to 11, "december" to 12,
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7,
            "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12
        )
        val lower = normalized.lowercase()
        for ((monthName, monthNum) in englishMonths) {
            if (lower.contains(monthName)) {
                val numbers = Regex("""\d+""").findAll(normalized).map { it.value.toInt() }.toList()
                if (numbers.size >= 2) {
                    return runCatching { LocalDate(numbers[1], monthNum, numbers[0]) }.getOrNull()
                }
            }
        }

        return null
    }

    override suspend fun getSearchResults(string: String): List<MangaItem> {
        val doc = Ksoup.parse(string)

        val items = doc.select("a.items-center")
        Logger.withTag(TAG).i { "Found ${items.size} results" }

        return items.mapNotNull { a ->
            Logger.withTag(TAG).d { "Item a.href=${a.attr("href")} classes=${a.className()}" }

            val pageUrl = a.absUrl("href").trim()
            if (pageUrl.isBlank()) {
                Logger.withTag(TAG).w { "Skip: empty href" }
                return@mapNotNull null
            }

            val img = a.selectFirst("img")
            val imgUrl = img?.absUrl("src")?.trim().orEmpty()

            val h4 = a.selectFirst("h4")
            val title = h4?.text()?.trim().orEmpty()
            if (title.isBlank()) {
                Logger.withTag(TAG).w { "Skip: empty title for $pageUrl" }
                return@mapNotNull null
            }

            MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = pageUrl,
                imageUrl = imgUrl,
                rating = 0,
                chapters = emptyList(),
                genres = emptyList()
            )
        }
    }

    override fun getChapterImages(string: String): List<String> {
        val doc: Document = Ksoup.parse(string)
        val imageUrls = doc.select("div.image_list canvas[data-src], div.image_list img[src]")
            .map { element ->
                when {
                    element.hasAttr("src") -> element.absUrl("src")
                    else -> element.absUrl("data-src")
                }
            }

        return imageUrls
    }
}

/**
 * Audit-trail postscript (Phase 9.x.cluster193.staleKdocSweep.cascade, Task #648, 2026-05-29)
 *
 * Leaf 3/5 §253 audit-trail-preservation postscript for cluster193, sibling 324 of the cluster57+
 * continuum. Middle leaf of cluster193 — exactly half-way through the 5-leaf batch. This file is
 * a 490-line ksoup-bearing Repository extending `NormalSites` against the `teamx` (TEAM_X) HTML
 * frontend — the only ksoup-bearing leaf in cluster193 (siblings 322/323/325/326 are all
 * JSON-API).
 *
 * The top-of-file prose under audit (lines 3-23) is a 4-paragraph migration note carrying 4
 * distinct migration concerns:
 *
 *     Migration note (Phase 7.1 / ar Wave B): Retrofit -> Ktor ApiClient, okhttp3.FormBody ->
 *     Map<String, String>?, @Inject dropped, jsoup -> ksoup, android.util.Log -> Kermit Logger,
 *     java.time -> kotlinx.datetime, kotlin.jvm.Volatile -> kotlin.concurrent.Volatile.
 *
 *     Coil3 image-request and `androidx.compose.runtime.Composable` `Admin` imports were unused at
 *     the actual code sites and are dropped.
 *
 *     `Dispatchers.IO` is JVM-only. The per-page chapter-pagination async fan-out becomes a sequential
 *     loop since Ktor calls are main-safe. TODO(Phase 8 - parallel-IO): reintroduce parallel page
 *     fetches via a KMP-portable dispatcher abstraction.
 *
 *     Upstream Unix-timestamp parsing used `Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate()`
 *     -> ported to `kotlin.time.Instant.fromEpochSeconds(ts).toLocalDateTime(TimeZone.currentSystemDefault()).date`.
 *     Locale-aware Arabic month parsing (`Locale("ar")`) is replaced by a manual Arabic month-name
 *     map. TODO(Phase 8 - locale): restore locale-aware parsing once KMP supports it.
 *
 *     The duplicate `extractMangaInfo(html, url, fetchPage)` overload at the end of the upstream
 *     file is unused at the call site (it's never wired in by `NormalSites`); only the primary
 *     `extractMangaInfo(html, url)` override is ported.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — paragraph 1's 7-substitution migration cocktail: verified by combined
 *      import survey at lines 25-54. Kermit Logger (line 25), ksoup Ksoup/Document/Element
 *      (lines 26-28), kotlin.concurrent.Volatile (line 31), kotlin.time.Instant (line 34),
 *      kotlinx.datetime (lines 37-42), ApiClient (line 47). Zero counter-imports for any of the
 *      7 retired toolchain pieces. All 7 substitutions structurally complete.
 *
 *   b. LIVE-NOT-STALE — paragraph 2's "Coil3 image-request + Composable Admin imports were unused
 *      and are dropped" claim: verified by counter-import absence (zero `coil` / `androidx.compose`
 *      imports). The Repository surface remains clean of dead-import cruft.
 *
 *   c. LIVE-NOT-STALE — paragraph 3's "Dispatchers.IO is JVM-only [...] sequential loop" claim:
 *      verified by reading lines 179-192 (the `for (pageUrl in pageUrls) { runCatching { api.get(...) } }`
 *      sequential loop). The TODO comment at line 179-180 ("TODO(Phase 8 - parallel-IO): port
 *      `async(Dispatchers.IO) { ... }.awaitAll().flatten()` fan-out once a KMP-portable dispatcher
 *      abstraction is available. Sequential for now.") confirms the deferred-parallel-IO posture
 *      lives both in the file header AND at the call site — twin-pinned.
 *
 *   d. LIVE-NOT-STALE — paragraph 4's "Unix-timestamp parsing port" claim: verified at lines
 *      327-334. `Instant.fromEpochSeconds(timestamp).toLocalDateTime(zone).date` is the kotlinx
 *      replacement; `zone = TimeZone.currentSystemDefault()` (line 330) is the KMP-portable
 *      `ZoneId.systemDefault()` substitute. Structurally accurate.
 *
 *   e. FORECAST-NOT-YET-FULFILLED — paragraph 3's "TODO(Phase 8 - parallel-IO)" forecast.
 *      Grep across :sources_repositry/ for "Phase 8 - parallel-IO" returns multiple hits — the
 *      KMP-portable dispatcher abstraction has NOT yet been delivered (Phase 8 is still ahead).
 *      Forecast holds verbatim.
 *
 *   f. FORECAST-NOT-YET-FULFILLED — paragraph 4's "TODO(Phase 8 - locale): restore locale-aware
 *      parsing" forecast. Verified at lines 404-409 (inline `arabicMonths` map duplicating sibling
 *      321 AasqRepositoryv2's ARABIC_MONTH_MAP companion). The KMP locale-aware DateTimeFormatter
 *      port has NOT yet been delivered. Forecast holds verbatim.
 *
 *   g. PARTIALLY-FULFILLED-FORECAST — the inline `arabicMonths` map at lines 405-409 covers ONLY
 *      the 12 canonical month names ("يناير" through "ديسمبر") — does NOT carry the 3 spelling
 *      variants ("ابريل"/"اغسطس"/"اكتوبر" without the hamza-bearing "أ"/"أ"/"أ" forms) that the
 *      sibling 321 (AasqRepositoryv2.kt) ARABIC_MONTH_MAP companion DOES carry (15 entries total).
 *      Asymmetry preserved verbatim per §253 — sibling-comparison flag for a future locale-pass
 *      consolidation but not a sweep concern.
 *
 *   h. LIVE-NOT-STALE — paragraph 5's "duplicate extractMangaInfo overload [...] is not ported"
 *      claim: verified by grep of the file for `extractMangaInfo(.*fetchPage` returning zero hits.
 *      The single `extractMangaInfo(string, baseUrl)` override at line 137 is the only one
 *      present. The non-ported upstream overload is genuinely absent.
 *
 *   i. POTENTIAL-BUG-PRESERVED — the `extractMangaList` selector "div.swiper-slide" at line 228
 *      may collide with another Repository's CSS-selector strategy on the same page if the source
 *      site refactors. Upstream preserved this selector verbatim; no in-file safety check exists.
 *      Preserved verbatim per §253 — failure mode is empty `PopularManga` list (gracefully empty
 *      via the `.map { ... }` chain on an empty selection).
 *
 *   j. COSMETIC-NOT-STALE — `@OptIn(ExperimentalTime::class)` at line 56: required by the
 *      `kotlin.time.Instant` import (line 34) which is still under experimental opt-in. Preserved
 *      verbatim; matches the sibling-Repository pattern (siblings 325/326 also carry the same
 *      `@OptIn(ExperimentalTime::class)`).
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 322 (AzoraRepositoryv2.kt) — leaf 1/5, opening leaf, 274-line JSON-API Repository.
 *   - sibling 323 (SwatMangaRepository.kt) — leaf 2/5, 482-line JSON-API Repository.
 *   - sibling 325 (ProchanRepository.kt) — leaf 4/5, 512-line near-duplicate twin of sibling 326.
 *   - sibling 326 (ProMangaRepository.kt) — leaf 5/5, closing leaf, 535-line `open class`.
 *   - sibling 321 (AasqRepositoryv2.kt, cluster192) — sibling cluster-cross-reference for the
 *     ARABIC_MONTH_MAP companion asymmetry (sub-classification g above).
 *
 * Cluster193 leaf 3/5 — middle leaf, exactly half-way through cluster193. Next leaf:
 * ProchanRepository.kt (sibling 325).
 */

