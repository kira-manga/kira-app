package me.manga.kira.sources_repositry.ar.mangalek

/**
 * Migration note (Phase 7.1): Retrofit -> Ktor ApiClient, okhttp3.FormBody -> Map<String, String>?,
 * @Inject dropped, jsoup -> ksoup, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime,
 * kotlin.jvm.Volatile -> kotlin.concurrent.Volatile.
 *
 * `searchGet = false` is set in initSite() to route through `api.postForm(...)` for the Madara
 * `madara_load_more` ajax endpoint.
 *
 * Upstream `parseChapterDate` used `DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale("ar"))` /
 * `Locale.ENGLISH` — replaced with a manual Arabic + English month-name map. TODO(Phase 8 -
 * locale): restore locale-aware parsing once KMP supports it.
 *
 * Audit-trail postscript (Phase 9.x.cluster194.staleKdocSweep.cascade, Task #649, 2026-05-29).
 * Position-in-cluster: leaf 1/4, opening leaf (smallest at 535 lines among the 4 final :ar/
 * Repository implementation candidates — cluster194 is the closing batch for the :ar/ tier).
 *
 * Classification under cluster57+ taxonomy:
 *
 * a) `Retrofit -> Ktor ApiClient` (line 4) — LIVE-NOT-STALE. Verified: imports
 *    `me.manga.kira.data.remote.api.ApiClient` (line 33); no retrofit2 imports; `api.postForm(...)`
 *    calls in normalSearch/sortSearch/genresSearch (lines 90-91, 100, 106).
 *
 * b) `okhttp3.FormBody -> Map<String, String>?` (line 4) — LIVE-NOT-STALE. Verified:
 *    `normalSearchFormBody`/`genresSearchFormBody`/`sortFormBody` all return `Map<String, String>`
 *    (lines 297-330) carrying Madara-style `vars[...]` keys for the `madara_load_more` action;
 *    no okhttp3 import.
 *
 * c) `@Inject dropped` (line 4) — LIVE-NOT-STALE. Verified: constructor params (lines 44-46)
 *    are plain `private val`; no javax/jakarta inject annotation; Koin DI wires the class.
 *
 * d) `jsoup -> ksoup` (line 5) — LIVE-NOT-STALE. Verified: `com.fleeksoft.ksoup.Ksoup` + `Document`
 *    + `Element` imports (lines 17-19); `Ksoup.parse(...)` in extractHomeMangaItems +
 *    getChapterImages + extractMangaInfo (lines 115, 167, 173); no `org.jsoup` imports.
 *
 * e) `android.util.Log -> Kermit Logger` (line 5) — LIVE-NOT-STALE. Verified:
 *    `co.touchlab.kermit.Logger` import (line 16); `Logger.withTag(...).i { ... }` twice in
 *    normalSearch (lines 87-88) — the tag names "gfhfbcvbcvbcvbcvbcv" / "gfhfbcvbcvbcvbcvbcv1"
 *    are upstream verbatim-debug-tags (POTENTIAL-BUG-PRESERVED — keyboard-mashed tags survive
 *    the port; matches sibling 322's tag-debug surface).
 *
 * f) `java.time -> kotlinx.datetime` (line 5) — LIVE-NOT-STALE. Verified: `LocalDate`,
 *    `DateTimeUnit`, `TimeZone`, `minus`, `toLocalDateTime`, `todayIn` imports (lines 24-29);
 *    `kotlin.time.Clock` + `kotlin.time.ExperimentalTime` (lines 21-22) with `@OptIn` (line 42);
 *    `Clock.System.todayIn(zone)` (line 117); `LocalDate(year, monthNum, day)` constructor (line 261).
 *
 * g) `kotlin.jvm.Volatile -> kotlin.concurrent.Volatile` (line 6) — LIVE-NOT-STALE. Verified:
 *    `kotlin.concurrent.Volatile` import (line 20); `@Volatile` annotation on `_cachedHeaders`
 *    backing field (line 284).
 *
 * h) `searchGet = false` initSite() note (lines 8-9) — LIVE-NOT-STALE. Verified: `initSite()`
 *    override sets `searchGet = false` (line 50) before delegating to `super.initSite()` (line 53),
 *    routing all three search shapes through `api.postForm(...)` to the WordPress admin-ajax
 *    `madara_load_more` action.
 *
 * i) `parseChapterDate` Locale-replacement note (lines 11-13) — LIVE-NOT-STALE + FORECAST-NOT-
 *    YET-FULFILLED. Verified: 24-entry `monthMap` (12 Arabic + 12 English) at lines 212-221;
 *    `parseChapterDate` (lines 223-268) handles NEW/blank → today, "يومين ago" special case,
 *    Arabic-relative-time regex with 9 unit aliases, English absolute-date regex with monthMap
 *    lookup. TODO(Phase 8 - locale) remains open. Cross-cluster reference: ARABIC_MONTH_MAP
 *    12-entry shape matches sibling 321 (cluster192) and sibling 324 (cluster193); sibling 326
 *    forthcoming in cluster194 will round out the symmetry.
 *
 * j) POTENTIAL-BUG-PRESERVED: `getSearchResults` (line 80) and `extractMangaList` (line 162)
 *    both return empty list unconditionally — same shape as upstream behaviour where MangaLek
 *    used the `madara_load_more` POST-form pathway for ALL search shapes (normal/genres/sort)
 *    instead of the inherited GET-search route, making the inherited `getSearchResults(html)`
 *    extractor irrelevant. Preserved verbatim.
 *
 * k) POTENTIAL-BUG-PRESERVED: `toMangaItemList` extension function (lines 270-282) maps
 *    `info.api` into `MangaItem.url` and `MangaSource.MANGA_LEK.BASEURL` into
 *    `MangaItem.language` — an obvious field-swap inherited from upstream. The function is
 *    declared but not called inside this file; if any external caller exists, it would yield
 *    nonsensical URLs/languages. Preserved verbatim.
 *
 * l) COSMETIC-NOT-STALE: 182-entry `allGenres` Arabic-tag set (lines 335-516) and 4-entry
 *    `sortTypes` (lines 518-523) are upstream-verbatim cosmetic surface. The genre set is the
 *    largest in the :ar/ tier sweep (vs. blackListGenres empty at line 332-333) — preserved
 *    to maintain admin-UI filter functionality.
 *
 * m) COSMETIC-NOT-STALE: `genreToMetaKeyMap` (lines 525-530) and `String.toMetaKey()` extension
 *    (lines 532-534) translate Arabic sort-type labels to WordPress meta-key names —
 *    `_latest_update` and `_wp_manga_views` are the two backend filter keys; note the 3-way
 *    repeat of `_wp_manga_views` for "شائع" / "التقييم" / "الاكثر مشاهدة" is upstream
 *    behaviour (3 sort labels collapse to 1 backend filter — POTENTIAL-BUG-PRESERVED if the
 *    UX intent was distinct sort orders).
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
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
class MangaLekRepositoryv2(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : NormalSites(api, sourcesRepository) {

    override suspend fun initSite(): Int {
        searchGet = false
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    override val mangaSource: MangaSource
        get() = MangaSource.MANGA_LEK
    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override val API: String
        get() = "Lekmanga"
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override var imgBaseUrl: String = "https://io.lek-manga.net/"
    override var imgUrlVersion: Int = 0
    override val popularUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }

    override fun handelLoadMoreUrl(page: Int): String {
        return loadMoreUrl(page)
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> "${baseUrl.ifBlank { mangaSource.BASEURL }}wp-admin/admin-ajax.php"
            is SearchType.GENRES -> "${baseUrl.ifBlank { mangaSource.BASEURL }}wp-admin/admin-ajax.php"
            is SearchType.SORT -> "${baseUrl.ifBlank { mangaSource.BASEURL }}wp-admin/admin-ajax.php"
        }

    override suspend fun getSearchResults(string: String): List<MangaItem> {
        return emptyList()
    }

    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)

        Logger.withTag("gfhfbcvbcvbcvbcvbcvbase").i { baseUrl.ifBlank { mangaSource.BASEURL } }
        Logger.withTag("gfhfbcvbcvbcvbcvbcv").i { url }
        Logger.withTag("gfhfbcvbcvbcvbcvbcv1").i { searchFormBody(searchType).toString() }

        return fetchDataWithHeaders({
            api.postForm(url, fields = searchFormBody(searchType) ?: emptyMap(), headers = defaultHeaders)
        }) { html ->
            extractHomeMangaItems(html)
        }
    }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        return fetchDataWithHeaders({
            api.postForm(url, fields = searchFormBody(searchType) ?: emptyMap(), headers = defaultHeaders)
        }) { html -> extractHomeMangaItems(html) }
    }

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        return fetchDataWithHeaders({
            api.postForm(url, fields = searchFormBody(searchType) ?: emptyMap(), headers = defaultHeaders)
        }) { html -> extractHomeMangaItems(html) }
    }

    fun loadMoreUrl(page: Int): String = "${baseUrl.ifBlank { mangaSource.BASEURL }}page/$page/"

    override fun extractHomeMangaItems(string: String): MutableList<MangaItem> {
        val mangaList = mutableListOf<MangaItem>()
        val doc: Document = Ksoup.parse(string)
        val mangaElements = doc.select(".page-item-detail.manga")
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

        for (element in mangaElements) {
            val titleElement: Element? = element.selectFirst(".post-title a")
            val imageElement: Element? = element.selectFirst(".item-thumb img")
            val ratingElement: Element? = element.selectFirst(".post-total-rating")
            val chapterElements = element.select(".list-chapter .chapter-item")

            if (titleElement != null && imageElement != null) {
                val title = titleElement.text()
                val url = titleElement.attr("href")
                val imageUrl = imageElement.attr("src")
                val rating = ratingElement?.select("i.rating_current")?.size ?: 0

                val chapters = chapterElements.map { chapter ->
                    val chapterLink = chapter.selectFirst("a")
                    val chapterNum = chapterLink?.text()?.trim() ?: "Unknown"
                    val chapterUrl = chapterLink?.attr("href") ?: ""
                    val dateText = chapter.selectFirst(".post-on")?.text()?.trim()
                    val date = if (!dateText.isNullOrEmpty()) dateText else "NEW"

                    ChapterItem(
                        number = "Chapter $chapterNum",
                        name = chapterNum,
                        url = chapterUrl,
                        date = parseChapterDate(date) ?: today,
                    )
                }
                mangaList.add(
                    MangaItem(
                        MangaSource.MANGA_LEK.API,
                        MangaSource.MANGA_LEK.LANGUAGE.Language,
                        title,
                        url,
                        imageUrl,
                        rating,
                        chapters,
                        listOf(),
                    ),
                )
            }
        }
        Logger.withTag("itemsssss").i { mangaList.toString() }

        return mangaList
    }

    override fun extractMangaList(string: String): List<PopularManga> {
        return emptyList()
    }

    override fun getChapterImages(string: String): List<String> {
        val document = Ksoup.parse(string)
        return document.select("div.reading-content img.wp-manga-chapter-img")
            .map { it.attr("src") }
    }

    override suspend fun extractMangaInfo(string: String, baseUrl: String): MangaInfo {
        val document: Document = Ksoup.parse(string)
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

        val chapters = document.select("ul.main.version-chap li.wp-manga-chapter").map { element ->
            val chapterNumber = element.select("a").text()
            val chapterUrl = element.select("a").attr("href")
            val dateElement = element.select("span.chapter-release-date")

            val date: String = if (dateElement.select("span.c-new-tag").isNotEmpty()) {
                val relativeTime = dateElement.select("img").attr("alt")
                relativeTime.ifEmpty { "NEW" }
            } else {
                dateElement.select("i").text()
            }

            ChapterItem(
                number = chapterNumber,
                url = chapterUrl,
                date = parseChapterDate(date) ?: today,
                isDownloaded = false,
            )
        }.toMutableList()

        return MangaInfo(
            title = document.select("div.post-title h1").text(),
            imageUrl = document.select("div.summary_image img").attr("src"),
            rating = document.select("span#averagerate").text(),
            description = document.select("div.summary__content").text().trim(),
            author = document.select("div.author-content").text(),
            genres = document.select("div.genres-content a").eachText(),
            status = document.select("div.summary-content:has(h5:contains(الحالة))").text(),
            chapters = chapters,
            api = MangaSource.MANGA_LEK.API,
            url = baseUrl,
            language = MangaSource.MANGA_LEK.LANGUAGE.Language,
        )
    }

    /** Manual Arabic + English month-name -> month-number map (KMP-locale parsing not available). */
    private val monthMap: Map<String, Int> = mapOf(
        // Arabic
        "يناير" to 1, "فبراير" to 2, "مارس" to 3, "أبريل" to 4,
        "مايو" to 5, "يونيو" to 6, "يوليو" to 7, "أغسطس" to 8,
        "سبتمبر" to 9, "أكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12,
        // English
        "January" to 1, "February" to 2, "March" to 3, "April" to 4,
        "May" to 5, "June" to 6, "July" to 7, "August" to 8,
        "September" to 9, "October" to 10, "November" to 11, "December" to 12,
    )

    fun parseChapterDate(dateStr: String): LocalDate? {
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.todayIn(zone)

        // 1) Blank or "NEW" -> today
        if (dateStr.isBlank() || dateStr.equals("NEW", ignoreCase = true)) {
            return today
        }

        // 1b) Special case: purely-textual "two days ago"
        if (dateStr.trim().equals("يومين ago", ignoreCase = true)) {
            return today.minus(2, DateTimeUnit.DAY)
        }

        // 2) Relative-time ("X units ago") in Arabic + "ago"
        val relRegex =
            """(\d+)\s*(ثانية|ثواني|دقيقة|دقائق|ساعة|ساعات|يوم|أيام|يومين|يومان)\s*ago""".toRegex()
        relRegex.find(dateStr)?.let { m ->
            val amount = m.groupValues[1].toLongOrNull() ?: return@let
            val unit = m.groupValues[2]
            val nowInstant = Clock.System.now()
            return when (unit) {
                "ثانية", "ثواني" -> nowInstant.minus(amount, DateTimeUnit.SECOND, zone).toLocalDateTime(zone).date
                "دقيقة", "دقائق" -> nowInstant.minus(amount, DateTimeUnit.MINUTE, zone).toLocalDateTime(zone).date
                "ساعة", "ساعات" -> nowInstant.minus(amount, DateTimeUnit.HOUR, zone).toLocalDateTime(zone).date
                "يوم", "أيام", "يومين", "يومان" -> today.minus(amount.toInt(), DateTimeUnit.DAY)
                else -> today
            }
        }

        // 3) Absolute date: try "<Month> <day>, <year>"
        val absRegex = """([^\d\s,]+)\s+(\d{1,2})\s*,?\s*(\d{4})""".toRegex()
        absRegex.find(dateStr.trim())?.let { m ->
            val monthName = m.groupValues[1]
            val day = m.groupValues[2].toIntOrNull() ?: return@let
            val year = m.groupValues[3].toIntOrNull() ?: return@let
            val monthNum = monthMap[monthName] ?: return@let
            return try {
                LocalDate(year, monthNum, day)
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    fun List<MangaInfo>.toMangaItemList(): List<MangaItem> =
        this.map { info ->
            MangaItem(
                title = info.title,
                url = info.api,
                imageUrl = info.imageUrl,
                rating = info.rating.toIntOrNull(),
                chapters = info.chapters,
                genres = info.genres,
                api = MangaSource.MANGA_LEK.API,
                language = MangaSource.MANGA_LEK.BASEURL,
            )
        }

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? = null

    override fun normalSearchFormBody(searchType: SearchType.Normal): Map<String, String> = mapOf(
        "vars[s]" to searchType.query,
        "vars[posts_per_page]" to "25",
        "template" to "madara-core/content/content-archive",
        "page" to "0",
        "vars[orderby]" to "meta_value_num",
        "vars[paged]" to "1",
        "action" to "madara_load_more",
        "vars[sidebar]" to "right",
    )

    override fun genresSearchFormBody(searchType: SearchType.GENRES): Map<String, String> = mapOf(
        "vars[s]" to searchType.query,
        "vars[wp-manga-genre]" to searchType.genres,
        "template" to "madara-core/content/content-archive",
        "page" to "0",
        "vars[orderby]" to "meta_value_num",
        "vars[paged]" to "1",
        "action" to "madara_load_more",
        "vars[sidebar]" to "right",
    )

    override fun sortFormBody(searchType: SearchType.SORT): Map<String, String> = mapOf(
        "vars[s]" to searchType.query,
        "vars[wp-manga-genre]" to searchType.genres,
        "vars[meta_key]" to searchType.sortType.toMetaKey(),
        "template" to "madara-core/content/content-archive",
        "page" to "0",
        "vars[posts_per_page]" to "20",
        "vars[orderby]" to "meta_value_num",
        "vars[paged]" to "1",
        "action" to "madara_load_more",
        "vars[sidebar]" to "right",
    )

    override val blackListGenres: Set<String>
        get() = setOf()

    override val allGenres: Set<String> = setOf(
        "fantasy",
        "إدارة المناطق",
        "إنتقام",
        "ابراج",
        "اثاره",
        "ارتقاء",
        "ارواح",
        "ازياء",
        "اساطير",
        "اساطيز",
        "اسبوعى",
        "اشباح",
        "اضطهاد",
        "اطظهاد",
        "اعادة احياء",
        "اعاده بحث",
        "اعمار",
        "اقتصاد",
        "اكاديميه",
        "اكشن",
        "الات",
        "الالوان الممتلئه",
        "البقاء علي قيد الحياه",
        "الجانب المظلم من الحياه",
        "الحريم العكسي",
        "الحياة المدرسيه",
        "الحياة اليومية",
        "الحيوانات الأليفة",
        "الخيال العلمي",
        "السفر عبر الزمن",
        "العاب",
        "العاب الكترونية",
        "العاب تقليدية",
        "العاب رعب",
        "العاب فيديو",
        "العصور الوسطى",
        "الغموض",
        "الفتاة الوحش",
        "الفنون العسكرية",
        "المخالفون للقانون",
        "النجاة",
        "الهة",
        "الهه",
        "الواقع الافتراضي",
        "اليات",
        "امرأة شريرة",
        "انتقال",
        "انتقام",
        "انمى",
        "ايسكاى",
        "ايشى",
        "بالغ",
        "بطل خارق",
        "بطل غير اعتيادى",
        "بطل غير اعتيادي",
        "بطل مجنون",
        "بطل وحش",
        "بعد الكارثه",
        "بوليسي",
        "تاريخ",
        "تاريخى",
        "تجسيد",
        "تحديث",
        "تحري",
        "تحقيق",
        "تحقيقات",
        "تخطيط",
        "تدريب",
        "تراجع",
        "تراجيدي",
        "ترويض",
        "ترويض وحوش",
        "تشويق",
        "تلوين رسم",
        "تلوين رسمي",
        "تلوين هواة",
        "تملك",
        "تناسخ",
        "تناسخ الارواح",
        "تنانين",
        "تنايخ",
        "ثأر",
        "جانحون",
        "جريمة",
        "جريمه",
        "جندر اسواب",
        "جندر بندر",
        "جوسى",
        "جوسين",
        "جوسيه",
        "حائز علي جائزة",
        "حديث",
        "حرب",
        "حربى",
        "حريم",
        "حريم عكسى",
        "حياة مدرسية",
        "حياة يومية",
        "حيوانات",
        "حيوانات اليفه",
        "خارق",
        "خارق للطبيعه",
        "خيار",
        "خيال",
        "خيال علمى",
        "خيالي",
        "داخل اللعبه",
        "داخل روايه",
        "دراما",
        "دماء",
        "دموى",
        "ذكريات من عالم آخر",
        "راشد",
        "رعاية اطفال",
        "رعب",
        "رواية عربية",
        "روايه",
        "رومانسى",
        "رياضه",
        "رياضى",
        "زراعة",
        "زمكانى",
        "زمنكاني",
        "زنزانات",
        "زواج مدبر",
        "زومبي",
        "ساموراي",
        "ساموري",
        "سايكوباث",
        "سحر",
        "سفر عبر الزمن",
        "سم",
        "سوردا عربية",
        "سياسي",
        "سينين",
        "شرطة",
        "شريحة من الحياة",
        "شرير",
        "شوجو",
        "شونين",
        "شياطين",
        "شينين",
        "صقل",
        "طبخ",
        "طبي",
        "طرد الارواح الشريره",
        "عائلى",
        "عالم مختلف",
        "عامل مكتبي",
        "عسكري",
        "عسكريه",
        "عصر حديث",
        "عصور وسطى",
        "علم نفس",
        "علمى",
        "عنن",
        "فانتازيا",
        "غموض",
        "فتاة وحش",
        "قصة مصورة",
        "قصص قصيرة",
        "كوميديا",
        "مأساوي",
        "مغامرات",
        "ميكا",
        "ميلودراما",
        "موسيقى",
        "ناروتو",
        "نفسى",
        "نهاية العالم",
        "نينجا",
        "هندسة",
        "هواه",
        "هوس",
        "واقع افتراضى",
        "واقعى",
        "وبيتون",
        "وحوش",
        "ون شوت",
        "ويب تون",
    )

    override val sortTypes = setOf(
        "الاحدث",
        "التقييم",
        "شائع",
        "الاكثر مشاهدة",
    )

    private val genreToMetaKeyMap = mapOf(
        "الاحدث" to "_latest_update",
        "شائع" to "_wp_manga_views",
        "التقييم" to "_wp_manga_views",
        "الاكثر مشاهدة" to "_wp_manga_views",
    )

    fun String.toMetaKey(): String {
        return genreToMetaKeyMap[this].toString()
    }
}
