package me.manga.yamiapk.sources_repositry.ar.mangalek

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.NormalSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import kotlin.text.ifEmpty

class MangaLekRepositoryv2  @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
    ): NormalSites(dataStore,api,sourcesRepository) {


    override suspend fun initSite(): Int {
        searchGet = false
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }


    override val mangaSource: MangaSource
        get() = MangaSource.MANGA_LEK
    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL  } }
    override val API: String
        get() = "Lekmanga"
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL  } }
    override var imgBaseUrl: String = "https://io.lekmanga.net/"
    override var imgUrlVersion: Int = 0
    override val popularUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL  } }

    override fun handelLoadMoreUrl(page: Int): String {
        return loadMoreUrl(page)
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  -> "${baseUrl.ifBlank {mangaSource.BASEURL}}wp-admin/admin-ajax.php"
            is SearchType.GENRES  -> "${baseUrl.ifBlank {mangaSource.BASEURL}}wp-admin/admin-ajax.php"
            is SearchType.SORT    -> "${baseUrl.ifBlank {mangaSource.BASEURL}}wp-admin/admin-ajax.php"
        }


//    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> = parser.extractHomeMangaItems(html)

//    override fun extractMangaList(html: String): List<PopularManga> =parser.extractMangaList(html)


    override suspend fun getSearchResults(string: String): List<MangaItem> {
        return emptyList()
    }

    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)


        Log.i("gfhfbcvbcvbcvbcvbcv",url.toString())
        Log.i("gfhfbcvbcvbcvbcvbcv1",searchFormBody(searchType).toString())

        return  fetchDataWithHeaders({ api.post(url, body = searchFormBody(searchType),headers = defaultHeaders) }){  html ->

//            val searchResponse =  parseMangaSearchResponse(html)
//            SearchResults(searchResponse)
            extractHomeMangaItems(html)
        }
    }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        return  fetchDataWithHeaders(

            { api.post(url, body = searchFormBody(searchType),headers = defaultHeaders) }){  html -> extractHomeMangaItems(html)}
    }

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        return  fetchDataWithHeaders({ api.post(url, body = searchFormBody(searchType),headers = defaultHeaders) }){  html -> extractHomeMangaItems(html)}
    }

//    override fun getChapterImages(html: String): List<String> =parser.getChapterImages(html)

//    override suspend fun extractMangaInfo(html: String, url:String): MangaInfo = extractMangaInfo(html,url)
















    fun loadMoreUrl(page : Int): String = "${baseUrl.ifBlank {mangaSource.BASEURL}}page/$page/"


    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val mangaList = mutableListOf<MangaItem>()
        val doc: Document = Jsoup.parse(html)
        val mangaElements = doc.select(".page-item-detail.manga")

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
                        date = parseChapterDate(date) ?: LocalDate.now(),
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
                        listOf()
                    )
                )
            }
        }
        return mangaList
    }

    override fun extractMangaList(string: String): List<PopularManga> {
        return emptyList()
    }
    override fun getChapterImages(html: String): List<String> {
        val document = Jsoup.parse(html)
        return document.select("div.reading-content img.wp-manga-chapter-img")
            .map { it.attr("src") }
    }
    override suspend fun extractMangaInfo(html: String, url:String): MangaInfo {
        val document: Document = Jsoup.parse(html)


        val chapters = document.select("ul.main.version-chap li.wp-manga-chapter").map { element ->
            val chapterNumber = element.select("a").text()
            val chapterUrl = element.select("a").attr("href")
            val dateElement = element.select("span.chapter-release-date")

            val date: String = if (dateElement.select("span.c-new-tag").isNotEmpty()) {
                // If marked as new, attempt to get relative time from alt attribute.
                val relativeTime = dateElement.select("img").attr("alt")
                relativeTime.ifEmpty { "NEW" }
            } else {
                dateElement.select("i").text()
            }




            ChapterItem(
                number = chapterNumber,
                url = chapterUrl,
                date = parseChapterDate(date) ?: LocalDate.now(),
                isDownloaded = false
            )
        }.toMutableList()

        return MangaInfo(
            title = document.select("div.post-title h1").text(),
            imageUrl = document.select("div.summary_image img").attr("src"),
            rating = document.select("span#averagerate").text(),
            ratingCount = document.select("span#countrate").text(),
            description = document.select("div.summary__content").text().trim(),
            otherNames = document.select("div.summary-content:has(h5:contains(اسماء اخرى))").text(),
            author = document.select("div.author-content").text(),
            artist = document.select("div.artist-content").text(),
            genres = document.select("div.genres-content a").eachText(),
            tags = document.select("div.tags-content a").eachText(),
            yearOfProduction = document.select("div.summary-content:has(h5:contains(سنة الانتاج))")
                .text(),
            status = document.select("div.summary-content:has(h5:contains(الحالة))").text(),
            favoritesCount = document.select("div.add-bookmark .action_detail span").text(),
            chapters = chapters,
            api = MangaSource.MANGA_LEK.API,
            url = url,
            language = MangaSource.MANGA_LEK.LANGUAGE.Language
        )
    }
    fun parseChapterDate(dateStr: String): LocalDate? {
        // 1) Blank or “NEW” → today
        if (dateStr.isBlank() || dateStr.equals("NEW", ignoreCase = true)) {
            return LocalDate.now()
        }

        // 1b) Special case: purely-textual “two days ago”
        if (dateStr.trim().equals("يومين ago", ignoreCase = true)) {
            return LocalDate.now().minusDays(2)
        }

        // 2) Relative-time (“X units ago”) in Arabic + “ago”
        //    Now includes seconds, minutes, hours, days
        val relRegex =
            """(\d+)\s*(ثانية|ثواني|دقيقة|دقائق|ساعة|ساعات|يوم|أيام|يومين|يومان)\s*ago""".toRegex()
        relRegex.find(dateStr)?.let { m ->
            val amount = m.groupValues[1].toLong()
            val unit   = m.groupValues[2]
            val now = LocalDateTime.now()
            val dt = when (unit) {
                "ثانية", "ثواني"  -> now.minusSeconds(amount)
                "دقيقة", "دقائق" -> now.minusMinutes(amount)
                "ساعة", "ساعات"   -> now.minusHours(amount)
                // any of these → days
                "يوم", "أيام", "يومين", "يومان" -> now.minusDays(amount)
                else -> now
            }
            return dt.toLocalDate()
        }

        // 3) Absolute date formats…
        //    Arabic month names (“أبريل 23, 2025”)
        val arabicFormatter =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale("ar"))
        try {
            return LocalDate.parse(dateStr, arabicFormatter)
        } catch (_: DateTimeParseException) { /* fall through */ }

        //    English fallback (“April 22, 2025”)
        val englishFormatter =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
        return try {
            LocalDate.parse(dateStr, englishFormatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }













    fun List<MangaInfo>.toMangaItemList(): List<MangaItem> =
        this.map { info ->
            MangaItem(
                title = info.title,
                url = info.api, // Using the API field as the URL
                imageUrl = info.imageUrl,
                rating = info.rating.toIntOrNull(),
                chapters = info.chapters,
                genres = info.genres,
                api = MangaSource.MANGA_LEK.API,
                language = MangaSource.MANGA_LEK.BASEURL
            )
        }




    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
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

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        return null
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? {
        return FormBody.Builder().apply {
            add("vars[s]", searchType.query)
            add("vars[posts_per_page]", "20")
            add("template", "madara-core/content/content-archive")
            add("page", "0")
            add("vars[orderby]", "wp-manga")
            add("vars[paged]", "1")
            add("action",  "madara_load_more")
            add("vars[orderby]", "meta_value_num")
            add("vars[posts_per_page]", "25")
            add("vars[sidebar]", "right")
        }.build()
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? {
        return FormBody.Builder().apply {
            add("vars[s]", searchType.query)
            add("vars[wp-manga-genre]", searchType.genres)
            add("template", "madara-core/content/content-archive")
            add("page", "0")
            add("vars[orderby]", "wp-manga")
            add("vars[paged]", "1")
            add("action",  "madara_load_more")
            add("vars[orderby]", "meta_value_num")
            add("vars[sidebar]", "right")
        }.build()
    }

    override fun sortFormBody(searchType: SearchType.SORT): FormBody? {
        return FormBody.Builder().apply {
            add("vars[s]", searchType.query)
            add("vars[wp-manga-genre]", searchType.genres)
            add("vars[meta_key]", searchType.sortType.toMetaKey())
            add("template", "madara-core/content/content-archive")
            add("page", "0")
            add("vars[posts_per_page]", "20")
            add("vars[orderby]", "wp-manga")
            add("vars[paged]", "1")
            add("action",  "madara_load_more")
            add("vars[orderby]", "meta_value_num")
            add("vars[sidebar]", "right")
        }.build()
    }

    /**
     * Call this whenever you get a new header (e.g. from your WebView login).
     * It will:
     *  1) Persist it to your DataStore
     *  2) Replace the in‑memory cache so future .defaultHeaders reflect the new value
     */


    override val blackListGenres: Set<String>
        get() = setOf()

    override val allGenres: Set<String> = setOf(
//        "+18",
        "fantasy",
        "إدارة المناطق",
        "إنتقام",
        "ابراج",
//        "اتشى",
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
//        "ايتشى",
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
//        "يورى",
//        "يورى خفيف"
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