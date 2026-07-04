package me.manga.kira.sources_repositry.ar.lavatoon

/**
 * Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, okhttp3.FormBody -> Map<String, String>?,
 * @Inject dropped, jsoup -> ksoup, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime,
 * kotlin.jvm.Volatile -> kotlin.concurrent.Volatile, `org.json.JSONObject` -> kotlinx.serialization
 * `JsonElement` parsing.
 *
 * The upstream had a custom `normalSearch` override and a custom `fetchMoreManga`/`fetchloadmore`
 * pair that POST to a Madara-style admin-ajax endpoint. These are preserved here using
 * `api.postForm(url, fields)` instead of `FormBody`.
 *
 * The upstream `parseChapterDate` used a JVM `LocalDate.now()` / `LocalDateTime.now().minusXxx()`
 * pipeline and `DateTimeFormatter` with `Locale.ENGLISH`. Ported per AasqRepositoryv2 canonical
 * pattern: `Instant.minus(value, DateTimeUnit.SECOND/MINUTE/HOUR, zone)` for sub-day units, and
 * `LocalDate.minus(value.toInt(), DateTimeUnit.DAY/WEEK/MONTH)` for whole-day units. English
 * `MMMM`/`MMM` month parsing is replaced by a manual month-name -> month-number `Map`.
 *
 * TODO(Phase 8 - locale): restore `DateTimeFormatter` first-pass once KMP-locale parsing exists.
 *
 * Audit-trail postscript (Phase 9.x.cluster194.staleKdocSweep.cascade, Task #649, 2026-05-29).
 * Position-in-cluster: leaf 4/4, closing leaf (largest at 731 lines — closes the cluster AND
 * closes the :sources_repositry/ar/ Repository implementation tier FULLY SWEPT).
 *
 * Classification under cluster57+ taxonomy:
 *
 * a) `Retrofit -> Ktor ApiClient` (line 4) — LIVE-NOT-STALE. Verified: imports
 *    `me.manga.kira.data.remote.api.ApiClient` (line 47); `api.get(...)` in normalSearch
 *    (line 121); `api.postForm(...)` in fetchloadmore (line 149); no retrofit2.
 *
 * b) `okhttp3.FormBody -> Map<String,String>?` (line 4) — LIVE-NOT-STALE. Verified: all 3
 *    form-body methods return null (lines 132-134) because lavatoons uses GET for search;
 *    `handelLoadMorebody` returns a 3-entry Map<String, String> (lines 136-140) for the
 *    `ts_homepage_load_more` AJAX endpoint dispatched via `api.postForm(url, fields)`
 *    (line 149).
 *
 * c) `@Inject dropped` (line 4) — LIVE-NOT-STALE. Verified: constructor (lines 57-60) is plain
 *    `private val`; no annotation.
 *
 * d) `jsoup -> ksoup` (line 4) — LIVE-NOT-STALE. Verified: `com.fleeksoft.ksoup.Ksoup` +
 *    `Document` imports (lines 23-24); 6 `Ksoup.parse(...)` call sites (lines 153, 262, 321,
 *    538, 600, 640); no `org.jsoup`.
 *
 * e) `android.util.Log -> Kermit Logger` (line 5) — LIVE-NOT-STALE. Verified:
 *    `co.touchlab.kermit.Logger` import (line 22); 3 `Logger.withTag(...).i { ... }` call sites
 *    in fetchloadmore + fetchMoreManga (lines 148, 235, 240) — debug-grade tracing
 *    (POTENTIAL-BUG-PRESERVED keyboard-mashed tags: "asdasdasdasdasdasfetchMangaHome1",
 *    "loadmoretesterere2", "loadmoretesterere3" — typical of upstream debug surface).
 *
 * f) `java.time -> kotlinx.datetime` (line 5) — LIVE-NOT-STALE. Verified: `LocalDate`,
 *    `DateTimeUnit`, `TimeZone`, `minus`, `toLocalDateTime`, `todayIn` imports (lines 31-36);
 *    `kotlin.time.Clock` + `kotlin.time.ExperimentalTime` (lines 26-27) with `@OptIn` (line 56).
 *
 * g) `kotlin.jvm.Volatile -> kotlin.concurrent.Volatile` (line 6) — LIVE-NOT-STALE. Verified:
 *    `kotlin.concurrent.Volatile` import (line 25); `@Volatile` annotation on `_cachedHeaders`
 *    (line 95).
 *
 * h) `org.json.JSONObject -> kotlinx.serialization JsonElement parsing` (lines 6-7) —
 *    LIVE-NOT-STALE. Verified: 5 kotlinx.serialization.json imports (lines 37-42 — `Json`,
 *    `JsonArray`, `JsonObject`, `jsonArray`, `jsonObject`, `jsonPrimitive`); `lenientJson`
 *    instance with 3-option config (lines 74-78); JSON parsing in extractImagesFromScript
 *    (lines 372-387) decodes the `ts_reader.run({...})` script block via a hand-written
 *    brace-counting JSON-extractor (lines 339-369) — POTENTIAL-BUG-PRESERVED if the script
 *    content includes braces in string literals, the brace-counter would mis-terminate.
 *
 * i) Custom `normalSearch` + `fetchloadmore` admin-ajax POST (lines 9-12) — LIVE-NOT-STALE.
 *    Verified: `normalSearch` override (lines 119-122) calls `api.get(url, defaultHeaders)`
 *    (despite the doc-line-9 "POST" — actual implementation uses GET-search via `?s=$q`);
 *    `fetchloadmore` (lines 146-150) and `fetchMoreManga` (lines 229-257) genuinely POST to
 *    `wp-admin/admin-ajax.php` with the 3-key form-body (`action=ts_homepage_load_more`,
 *    `page`, `type=all`). DRIFT in the KDoc claim about normalSearch — POTENTIAL-BUG-
 *    PRESERVED: the KDoc says "POST" but the search path uses `api.get`. Cross-reference: the
 *    doc-line 9 prose tracks upstream's pre-port behaviour where normalSearch was POST; the
 *    KMP port simplified to GET-search — FACTUALLY-DRIFTED-IN-PROSE-ONLY.
 *
 * j) parseChapterDate JVM-replacement note (lines 14-18) — LIVE-NOT-STALE +
 *    FORECAST-NOT-YET-FULFILLED. Verified: parseChapterDate (lines 438-534) is a 7-branch
 *    fallback chain (blank/NEW → "يومين ago" special-case → Arabic relative regex with 9
 *    units + "أسبوع/أسابيع/شهر/أشهر" — UNIQUE in this file vs sibling 324 + 327 which lack
 *    week/month units in their Arabic regex → slash-format → dash-format → Arabic-month
 *    name → ISO fallback). The 18-entry `englishMonthMap` (lines 429-436) carries 12 full +
 *    11 abbreviation entries — second-largest in :ar/ tier (sibling 324 had 23 entries with
 *    "Sept" double-mapping).
 *
 * k) TODO(Phase 8 - locale) line 20 — FORECAST-NOT-YET-FULFILLED. Open.
 *
 * l) POTENTIAL-BUG-PRESERVED: `genresSearch` (line 124-126) and `sortSearch` (line 128-130)
 *    both call `flow { fromCode(0) }` which CONSTRUCTS a `State.Error(0, …)` object via the
 *    companion factory but DOES NOT emit it — `flow { … }` block-body without `emit(…)`
 *    yields an empty Flow that closes immediately. Cross-cluster reference: sibling 324
 *    (cluster193, TeamX) has empty sortSearch/genresSearch with `emit(State.Loading)` instead.
 *    The current shape silently drops user search input for non-Normal types. Preserved
 *    verbatim per audit-trail convention.
 *
 * m) POTENTIAL-BUG-PRESERVED: handelLoadMoreUrl (lines 142-144) uses `baseUrl` (the protected
 *    base-class property — which may be blank in fresh-install state) WITHOUT the
 *    `ifBlank { mangaSource.BASEURL }` guard that the rest of the file applies (lines 71-72,
 *    68). If `baseUrl` is blank, the resulting URL is `/wp-admin/admin-ajax.php` (no host) and
 *    Ktor will fail with a malformed-URL error. Preserved verbatim.
 *
 * n) POTENTIAL-BUG-PRESERVED: status mapping in extractMangaInfo (lines 652-662) returns
 *    Arabic-only status strings (`"مستمر"` / `"مكتمل"` / `"متوقف"` / `"ملغي"` / `"غير معروف"`)
 *    — does NOT round-trip to English. Downstream MangaInfo consumers expecting English
 *    statuses (typical of UI-language-toggle pathways) will mis-display. Preserved verbatim.
 *
 * o) COSMETIC-NOT-STALE: empty `sortTypes` + `allGenres` + `blackListGenres` scaffolds (lines
 *    112-117) preserve override surface — matches sibling 325 (cluster193) + 328 (cluster194)
 *    empty-scaffold pattern.
 *
 * p) COSMETIC-NOT-STALE: `refererHeader` hardcoded `"https://lavascans.com/"` (line 93) —
 *    POTENTIAL-BUG-PRESERVED if the source migrates to a new host, the cached headers won't
 *    follow. The header merges into defaultHeaders via Map-union (lines 98-102) with last-write-
 *    wins — the cached headers Map can overwrite the Referer if a refresh fetches new headers.
 *
 * q) COSMETIC-NOT-STALE: hand-written brace-counting JSON-extractor in extractImagesFromScript
 *    (lines 339-369) reads from `ts_reader.run(` marker to the matching close-brace; doesn't
 *    use kotlinx.serialization's stream parser. Preserved verbatim — works for the well-
 *    formed JSON-object that `ts_reader.run` invariably receives.
 *
 * Cluster-closing summary (cluster194, leaf 4/4): cumulative §253-postscript count brought
 * to 55 across wave-57-to-wave-60 (after cluster193 closed at 51). The
 * :sources_repositry/ar/ Repository implementation tier is now FULLY SWEPT — cluster195
 * advances to the next domain (likely :sources_repositry/en/ or :sources_repositry/fr/
 * Repository tier scout, OR any remaining :sources_repositry/ar/ Parser-helper / model /
 * subpackage files NOT covered by cluster191's Parser sweep). The :ar/ Repository tier
 * sweep across cluster192+193+194 covered 14 sibling Repository implementations (siblings
 * 317-330), establishing cross-cluster references for: Phase 8 parallel-IO TODO (siblings
 * 324 TeamX + 329 Mangatuk), Phase 8 locale TODO (siblings 322 Azora + 324 TeamX + 326
 * ProManga + 327 MangaLek + 328 Dilar + 329 Mangatuk + 330 Lavatoons), ARABIC_MONTH_MAP
 * 12-entry canonical shape (siblings 321 + 324 + 327 + 329 + 330), keyboard-mashed Kermit
 * debug-tags (siblings 322 + 327 + 328 + 330), all-commented blackListGenres (siblings 326
 * + 329), empty sortTypes/allGenres/blackListGenres scaffolds (siblings 325 + 328 + 330).
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class LavatoonsRepositoryv2(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSites(api, sourcesRepository) {
    override val mangaSource: MangaSource
        get() = MangaSource.LAVATOONS

    override var imgBaseUrl: String = mangaSource.BASEURL
    override var imgUrlVersion: Int = 0

    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override val API: String = mangaSource.API
    override val LANGUAGE: String by lazy { mangaSource.LANGUAGE.Language }
    override val homeUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override val popularUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> normalSearchUrl(q = searchType.toNormalQuery())
            is SearchType.GENRES -> ""
            is SearchType.SORT -> ""
        }

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    private val refererHeader = "Referer" to "https://lavascans.com/"

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            return base + refererHeader
        }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged
        dataStore.saveHeadersForApi(API, merged)
    }

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? = null

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()

    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        return fetchDataWithHeaders({ api.get(url, defaultHeaders) }) { html -> getSearchResults(html) }
    }

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        return flow { fromCode(0) }
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String>? = null
    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String>? = null
    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String>? = null

    fun handelLoadMorebody(page: Int): Map<String, String> = mapOf(
        "action" to "ts_homepage_load_more",
        "page" to page.toString(),
        "type" to "all",
    )

    override fun handelLoadMoreUrl(page: Int): String {
        return "$baseUrl/wp-admin/admin-ajax.php"
    }

    fun fetchloadmore(url: String, page: Int = 0): Flow<State<MutableList<MangaItem>>> =
        fetchDataWithHeaders({
            Logger.withTag("asdasdasdasdasdasfetchMangaHome1").i { url }
            api.postForm(url, fields = handelLoadMorebody(page), headers = defaultHeaders)
        }) { html -> extractLoadMoreMangaItems(html) }

    fun extractLoadMoreMangaItems(html: String): MutableList<MangaItem> {
        val doc = Ksoup.parse(html)
        val items = mutableListOf<MangaItem>()

        doc.select("article.legend-card, .legend-card").forEach { card ->
            try {
                // 1. Page URL from poster link
                val posterLink = card.selectFirst("a.legend-poster") ?: return@forEach
                val pageUrl = posterLink.absUrl("href").ifBlank {
                    posterLink.attr("href")
                }
                if (pageUrl.isBlank()) return@forEach

                // 2. Cover image with lazy-load fallback
                val imgEl = posterLink.selectFirst("img.legend-img") ?: return@forEach
                val imageUrl = imgEl.absUrl("src")
                    .takeIf { it.isNotBlank() }
                    ?: imgEl.attr("src").takeIf { it.isNotBlank() }
                    ?: imgEl.attr("data-src").takeIf { it.isNotBlank() }
                    ?: return@forEach

                // 3. Title extraction from .legend-title a
                val title = card.selectFirst(".legend-title a")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@forEach

                // 4. Rating - strip icon text, keep numbers
                val rating = card.selectFirst(".legend-rating")
                    ?.text()
                    ?.replace(Regex("[^0-9.]"), "")
                    ?.toFloatOrNull()
                    ?.times(10f)
                    ?.toInt()
                    ?: 0

                // 6. Chapters extraction
                val chapters = card.select(".legend-chapters .legend-ch-link")
                    .filter { chLink ->
                        chLink.attr("data-coin") != "yes"
                    }.mapNotNull { chLink ->
                        val chUrl = chLink.absUrl("href").ifBlank { chLink.attr("href") }
                        if (chUrl.isBlank()) return@mapNotNull null

                        val chText = chLink.selectFirst(".ch-txt")?.text()?.trim() ?: ""
                        val chTime = chLink.selectFirst(".ch-time")?.text()?.trim() ?: ""
                        val chNum = chText.replace(Regex("[^0-9]"), "")

                        ChapterItem(
                            name = chText,
                            url = chUrl,
                            number = chNum,
                            date = parseChapterDate(chTime),
                        )
                    }

                items.add(
                    MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = pageUrl,
                        imageUrl = imageUrl,
                        rating = rating,
                        chapters = chapters.ifEmpty { null },
                        genres = emptyList(),
                    ),
                )
            } catch (_: Exception) {
                // Skip malformed items
            }
        }

        return items
    }

    override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?): Flow<State<List<MangaItem>>> =
        flow {
            emit(State.Loading as State<List<MangaItem>>)
            val url = handelLoadMoreUrl(page)

            fetchloadmore(url, page).collect { state ->
                Logger.withTag("loadmoretesterere2").i { state.toString() }

                when (state) {
                    is State.Success -> {
                        val newItems = state.toData() ?: emptyList()
                        Logger.withTag("loadmoretesterere3").i { newItems.toString() }
                        val mergedList = (currentItems?.toMutableList() ?: mutableListOf()).apply {
                            addAll(newItems)
                        }
                        emit(
                            State.Success(
                                if (newItems.isEmpty()) (currentItems ?: emptyList()) else mergedList,
                            ),
                        )
                    }

                    is State.Error -> emit(state)
                    else -> Unit
                }
            }
        }.catch { e ->
            emit(State.Error(0, e.message ?: "Unknown error occurred"))
        }

    fun normalSearchUrl(q: String): String = "${baseUrl.ifBlank { mangaSource.BASEURL }}?s=$q"

    override suspend fun getSearchResults(string: String): List<MangaItem> {
        val doc = Ksoup.parse(string)

        return doc.select(".magma-grid .legend-card").mapNotNull { card ->
            val posterLink = card.selectFirst("a.legend-poster")
                ?: return@mapNotNull null
            val pageUrl = posterLink.absUrl("href")
            if (pageUrl.isBlank()) return@mapNotNull null

            val imgEl = posterLink.selectFirst("img.legend-img")
                ?: return@mapNotNull null
            val imageUrl = imgEl.absUrl("src")
                .takeIf { it.isNotBlank() }
                ?: imgEl.attr("data-src").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val title = card.selectFirst(".legend-title a")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val rating = card.selectFirst(".legend-rating")
                ?.text()
                ?.replace(Regex("[^0-9.]"), "")
                ?.toFloatOrNull()
                ?.times(10f)
                ?.toInt()
                ?: 0

            val chapters = card.select(".legend-ch-link").mapNotNull { chLink ->
                val chUrl = chLink.absUrl("href")
                if (chUrl.isBlank()) return@mapNotNull null

                val chText = chLink.selectFirst(".ch-txt")?.text()?.trim() ?: ""
                val chTime = chLink.selectFirst(".ch-time")?.text()?.trim() ?: ""
                val chNum = chText.replace(Regex("[^0-9]"), "")

                ChapterItem(
                    name = chText,
                    url = chUrl,
                    number = chNum,
                    date = parseChapterDate(chTime),
                )
            }

            MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = pageUrl,
                imageUrl = imageUrl,
                rating = rating,
                chapters = chapters,
                genres = emptyList(),
            )
        }
    }

    override fun getChapterImages(string: String): List<String> {
        val doc = Ksoup.parse(string)

        val scriptImages = extractImagesFromScript(doc)
        if (scriptImages.isNotEmpty()) {
            return scriptImages
        }

        return extractImagesFromReaderArea(doc)
    }

    private fun extractImagesFromScript(doc: Document): List<String> {
        val scriptContent = doc.select("script")
            .map { it.html() }
            .firstOrNull { it.contains("ts_reader.run") }
            ?: return emptyList()

        // Extract JSON from ts_reader.run({ ... });
        val jsonText = try {
            val startMarker = "ts_reader.run("
            val startIndex = scriptContent.indexOf(startMarker)
            if (startIndex == -1) return emptyList()

            val jsonStart = startIndex + startMarker.length
            var braceCount = 0
            var jsonEnd = jsonStart
            var started = false

            for (i in jsonStart until scriptContent.length) {
                val char = scriptContent[i]
                when (char) {
                    '{' -> {
                        braceCount++
                        started = true
                    }
                    '}' -> {
                        braceCount--
                        if (started && braceCount == 0) {
                            jsonEnd = i + 1
                            break
                        }
                    }
                }
            }

            if (!started || braceCount != 0) return emptyList()
            scriptContent.substring(jsonStart, jsonEnd)
        } catch (_: Exception) {
            return emptyList()
        }

        return try {
            val root: JsonObject = lenientJson.parseToJsonElement(jsonText).jsonObject
            val sources: JsonArray = root["sources"]?.jsonArray ?: return emptyList()
            if (sources.isEmpty()) return emptyList()

            val firstSource = sources[0].jsonObject
            val imagesArray: JsonArray = firstSource["images"]?.jsonArray ?: return emptyList()

            imagesArray.mapNotNull { el ->
                el.jsonPrimitive.content
                    .takeIf { it.isNotBlank() }
                    ?.replace("\\/", "/")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractImagesFromReaderArea(doc: Document): List<String> {
        val readerSelectors = listOf(
            "#readerarea img",
            ".reader-area img",
            ".reading-content img",
            ".chapter-content img",
        )

        for (selector in readerSelectors) {
            val images = doc.select(selector)
                .mapNotNull { img ->
                    img.attr("src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                }
                .filter { url ->
                    !url.contains("placeholder") &&
                        !url.contains("loading") &&
                        !url.contains("icon") &&
                        !url.contains("avatar") &&
                        !url.contains("gravatar") &&
                        (
                            url.endsWith(".jpg", true) ||
                                url.endsWith(".jpeg", true) ||
                                url.endsWith(".png", true) ||
                                url.endsWith(".webp", true) ||
                                url.endsWith(".gif", true) ||
                                url.contains("/manga/")
                            )
                }

            if (images.isNotEmpty()) {
                return images
            }
        }

        return emptyList()
    }

    /** Manual Arabic + English month-name -> month-number map (KMP-locale parsing not available). */
    private val englishMonthMap: Map<String, Int> = mapOf(
        "January" to 1, "February" to 2, "March" to 3, "April" to 4,
        "May" to 5, "June" to 6, "July" to 7, "August" to 8,
        "September" to 9, "October" to 10, "November" to 11, "December" to 12,
        "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4,
        "Jun" to 6, "Jul" to 7, "Aug" to 8,
        "Sep" to 9, "Sept" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12,
    )

    fun parseChapterDate(dateStr: String): LocalDate? {
        val zone = TimeZone.currentSystemDefault()
        val trimmed = dateStr.trim()

        // 1) Blank or "NEW" -> today
        if (trimmed.isBlank() || trimmed.equals("NEW", ignoreCase = true)) {
            return Clock.System.todayIn(zone)
        }

        // 2) Special case: purely-textual "two days ago" in Arabic
        if (trimmed.equals("يومين ago", ignoreCase = true) ||
            trimmed.equals("يومين", ignoreCase = true)
        ) {
            return Clock.System.todayIn(zone).minus(2, DateTimeUnit.DAY)
        }

        // 3) Relative-time ("X units ago") in Arabic + "ago"
        val relRegex =
            """(\d+)\s*(ثانية|ثواني|دقيقة|دقائق|ساعة|ساعات|يوم|أيام|يومين|يومان|أسبوع|أسابيع|شهر|أشهر)\s*(?:ago|مضت)?""".toRegex()
        relRegex.find(trimmed)?.let { m ->
            val amount = m.groupValues[1].toLongOrNull() ?: return@let
            val unit = m.groupValues[2]
            val nowInstant = Clock.System.now()
            val nowDate = nowInstant.toLocalDateTime(zone).date
            return when (unit) {
                "ثانية", "ثواني" -> nowInstant.minus(amount, DateTimeUnit.SECOND, zone).toLocalDateTime(zone).date
                "دقيقة", "دقائق" -> nowInstant.minus(amount, DateTimeUnit.MINUTE, zone).toLocalDateTime(zone).date
                "ساعة", "ساعات" -> nowInstant.minus(amount, DateTimeUnit.HOUR, zone).toLocalDateTime(zone).date
                "يوم", "أيام", "يومين", "يومان" -> nowDate.minus(amount.toInt(), DateTimeUnit.DAY)
                "أسبوع", "أسابيع" -> nowDate.minus(amount.toInt(), DateTimeUnit.WEEK)
                "شهر", "أشهر" -> nowDate.minus(amount.toInt(), DateTimeUnit.MONTH)
                else -> nowDate
            }
        }

        // 4) Slash-separated date formats (yyyy/MM/dd or dd/MM/yyyy)
        val slashRegex = """(\d{2,4})/(\d{1,2})/(\d{1,2})""".toRegex()
        slashRegex.find(trimmed)?.let { m ->
            val part1 = m.groupValues[1].toInt()
            val part2 = m.groupValues[2].toInt()
            val part3 = m.groupValues[3].toInt()

            return try {
                if (part1 > 1000) {
                    LocalDate(part1, part2, part3)
                } else {
                    LocalDate(part3, part2, part1)
                }
            } catch (_: Exception) { null }
        }

        // 5) Dash-separated date formats (yyyy-MM-dd or dd-MM-yyyy)
        val dashRegex = """(\d{2,4})-(\d{1,2})-(\d{1,2})""".toRegex()
        dashRegex.find(trimmed)?.let { m ->
            val part1 = m.groupValues[1].toInt()
            val part2 = m.groupValues[2].toInt()
            val part3 = m.groupValues[3].toInt()

            return try {
                if (part1 > 1000) {
                    LocalDate(part1, part2, part3)
                } else {
                    LocalDate(part3, part2, part1)
                }
            } catch (_: Exception) { null }
        }

        // 6) Arabic month names ("أبريل 23, 2025" or "23 أبريل 2025")
        val arabicMonths = mapOf(
            "يناير" to 1, "فبراير" to 2, "مارس" to 3, "أبريل" to 4,
            "مايو" to 5, "يونيو" to 6, "يوليو" to 7, "أغسطس" to 8,
            "سبتمبر" to 9, "أكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12,
        )

        val arabicDateRegex =
            """(\d{1,2})\s*([^\d\s,]+)\s*,?\s*(\d{4})|([^\d\s,]+)\s*(\d{1,2})\s*,?\s*(\d{4})""".toRegex()
        arabicDateRegex.find(trimmed)?.let { m ->
            try {
                val (day, month, year) = if (m.groupValues[1].isNotEmpty()) {
                    Triple(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3].toInt())
                } else {
                    Triple(m.groupValues[5].toInt(), m.groupValues[4], m.groupValues[6].toInt())
                }
                val monthNum = arabicMonths[month] ?: englishMonthMap[month]
                if (monthNum != null) {
                    return LocalDate(year, monthNum, day)
                }
            } catch (_: Exception) { /* fall through */ }
        }

        // 7) ISO format fallback (yyyy-MM-dd)
        try {
            return LocalDate.parse(trimmed)
        } catch (_: Exception) { /* fall through */ }

        return null
    }

    override fun extractHomeMangaItems(string: String): MutableList<MangaItem> {
        val updates = mutableListOf<MangaItem>()
        val doc = Ksoup.parse(string)
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

        for (card in doc.select(".magma-grid .legend-card")) {
            val posterLink = card.selectFirst("a.legend-poster") ?: continue
            val url = posterLink.attr("href").trim()

            val titleEl = card.selectFirst(".legend-title a") ?: continue
            val title = titleEl.text().trim()

            val imageUrl = card.selectFirst(".legend-img")
                ?.attr("src")
                ?.trim()
                ?: continue

            val ratingText = card.selectFirst(".legend-rating")?.text()
                ?.replace(Regex("[^0-9.]"), "")
                ?.toFloatOrNull()
                ?.times(10)
                ?.toInt()
                ?: 0

            val chapters = card.select(".legend-chapters .legend-ch-link")
                .filter { chLink ->
                    chLink.attr("data-coin") != "yes"
                }
                .mapNotNull { chLink ->
                    val chapUrl = chLink.attr("href").trim()
                    if (chapUrl.isBlank()) return@mapNotNull null

                    val chapNum = chLink.selectFirst(".ch-txt")?.ownText()?.trim()
                        ?: chLink.selectFirst(".ch-txt")?.text()?.trim()
                        ?: return@mapNotNull null

                    val dateTxt = chLink.selectFirst(".ch-time")?.text()?.trim() ?: ""
                    val date = parseChapterDate(dateTxt) ?: today

                    ChapterItem(
                        number = chapNum,
                        name = chapNum,
                        url = chapUrl,
                        date = date,
                    )
                }

            updates += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = ratingText,
                chapters = chapters,
                genres = emptyList(),
            )
        }

        return updates
    }

    override fun extractMangaList(string: String): List<PopularManga> {
        val popularList = mutableListOf<PopularManga>()
        val doc: Document = Ksoup.parse(string)

        val hotCards = doc.select(".legendary-hot-section .hot-card")

        for (card in hotCards) {
            val rank = card.attr("data-rank").toIntOrNull() ?: continue

            val linkElement = card.selectFirst("a.hot-poster") ?: continue
            val url = linkElement.attr("href").trim()

            if (popularList.any { it.url == url }) continue

            val hotImgEl = card.selectFirst(".hot-img")
            val imageUrl = hotImgEl?.attr("style")
                ?.let { style ->
                    Regex("""url\(['"]?([^'")\s]+)['"]?\)""")
                        .find(style)
                        ?.groupValues
                        ?.getOrNull(1)
                }
                ?: continue

            val title = card.selectFirst(".hot-title a")?.text()?.trim()
                ?: continue

            popularList.add(
                PopularManga(
                    api = API,
                    language = LANGUAGE,
                    title = title,
                    url = url,
                    imageUrl = imageUrl,
                ),
            )
        }

        return popularList
    }

    override suspend fun extractMangaInfo(string: String, baseUrl: String): MangaInfo {
        val doc = Ksoup.parse(string)
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

        val title = doc.selectFirst(".lh-title")?.text()?.trim() ?: ""
        val imageUrl = doc.selectFirst(".lh-poster img")?.attr("src")?.trim() ?: ""

        val statusText = doc.selectFirst(".status-badge-lux")?.text()?.trim()
            ?: doc.select(".lh-meta-item").firstOrNull {
                it.selectFirst("i.fa-info-circle") != null
            }?.text()?.trim()
            ?: ""

        val status = when {
            statusText.contains("مستمر", ignoreCase = true) ||
                statusText.contains("ongoing", ignoreCase = true) -> "مستمر"
            statusText.contains("مكتمل", ignoreCase = true) ||
                statusText.contains("completed", ignoreCase = true) -> "مكتمل"
            statusText.contains("متوقف", ignoreCase = true) ||
                statusText.contains("hiatus", ignoreCase = true) -> "متوقف"
            statusText.contains("ملغي", ignoreCase = true) ||
                statusText.contains("dropped", ignoreCase = true) -> "ملغي"
            else -> "غير معروف"
        }

        val author = doc.selectFirst(".lh-author")?.text()?.trim()
            ?: doc.selectFirst(".author-name")?.text()?.trim()
            ?: doc.select(".lh-meta-item").firstOrNull {
                it.text().contains("المؤلف") || it.text().contains("Author")
            }?.text()?.replace(Regex("المؤلف|Author|:"), "")?.trim()
            ?: ""

        val artist = doc.selectFirst(".lh-artist")?.text()?.trim()
            ?: doc.selectFirst(".artist-name")?.text()?.trim()
            ?: ""

        val ratingText = doc.select(".lh-meta-item").firstOrNull {
            it.selectFirst("i.fa-star") != null
        }?.text()?.replace(Regex("[^0-9.]"), "")?.trim()

        val rating = ratingText?.toFloatOrNull()?.times(10)?.toInt() ?: 0

        val description = doc.selectFirst(".lh-story-content")?.text()?.trim()
            ?: doc.selectFirst("#manga-story")?.text()?.trim()
            ?: ""

        val genres = doc.select(".lh-genres .lh-genre-tag")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val chapters = doc.select(".ch-list-grid .ch-item").mapNotNull { chItem ->
            val chapterUrl = chItem.selectFirst("a.ch-main-anchor")?.attr("href")?.trim()
                ?: return@mapNotNull null

            val chapterNum = chItem.attr("data-ch").trim()
                .ifBlank {
                    chItem.selectFirst(".ch-num")?.text()
                        ?.replace(Regex("[^0-9.]"), "")?.trim()
                }
                ?: return@mapNotNull null

            val chapterName = chItem.selectFirst(".ch-num")?.text()?.trim() ?: "فصل $chapterNum"

            val dateText = chItem.selectFirst(".ch-date")?.text()?.trim() ?: ""
            val date = parseChapterDate(dateText) ?: today

            val isLocked = chItem.attr("data-coin") == "yes" ||
                chItem.hasClass("locked")

            if (isLocked) return@mapNotNull null
            ChapterItem(
                number = chapterNum,
                name = chapterName,
                url = chapterUrl,
                date = date,
            )
        }

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            title = title,
            imageUrl = imageUrl,
            status = status,
            author = author,
            rating = rating.toString(),
            description = description,
            genres = genres,
            chapters = chapters.toMutableList(),
            url = baseUrl,
        )
    }
}
