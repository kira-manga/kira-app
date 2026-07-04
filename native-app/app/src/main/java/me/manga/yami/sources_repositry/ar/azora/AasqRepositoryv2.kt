package me.manga.yamiapk.sources_repositry.ar.azora

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.states.State.Error.Companion.fromCode
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.SeparatedDetailsSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

class AasqRepositoryv2  @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
    ): SeparatedDetailsSites(dataStore,api,sourcesRepository) {




    override suspend fun initSite(): Int {

        homeGet =false
        isChapterGet = false
        return super.initSite()
    }

    override val mangaSource: MangaSource
        get() = MangaSource.AASQ
    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language
    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0
    override val homeUrl: String by lazy {"${baseUrl.ifBlank { BASE_URL }}manga/page/1/?m_orderby=latest"}

    override val popularUrl: String by lazy { BASE_URL}

    override fun handelLoadMoreUrl(page: Int): String {


        return "${baseUrl.ifBlank { BASE_URL }}manga/page/${page}/?m_orderby=latest"
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  -> "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php"
            is SearchType.GENRES  -> ""
            is SearchType.SORT    -> ""
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

    }

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        return FormBody.Builder()
            .add("action", "manga_get_chapters")   // exactly the server’s expected Ajax action
            .build()
    }

    override fun createInfoUrl(mangaId: String): String {
        return mangaId
    }

    override fun createChaptersUrl(mangaId: String): String {
        return "${mangaId}ajax/chapters"
    }

    override fun handelSearchFormBody(
        page: Int,
        searchType: SearchType.Normal
    ): FormBody? {
        return null
    }


    override fun getChapterImages(html: String): List<String> {
        // If you have the page’s URL, pass it as baseUri so absUrl() will work:
        val doc =  Jsoup.parse(html)

        return doc.select("img.wp-manga-chapter-img")
            .mapNotNull { img ->
                // First try absUrl (will return non-blank only if baseUri was provided)
                val abs = img.absUrl("src").takeIf { it.isNotBlank() }
                if (abs != null) return@mapNotNull abs

                // Fallback: raw src (trimmed)
                img.attr("src").trim().takeIf { it.isNotBlank() }
            }
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

    override fun parseChapters(html: String): List<ChapterItem> {
        val document = Jsoup.parse("<html><body>$html</body></html>")
        val chapterElements = document.select("ul.main.version-chap.no-volumn li.wp-manga-chapter")

        return chapterElements.mapNotNull { element ->
            try {
                val anchor = element.selectFirst("a") ?: return@mapNotNull null
                val href = anchor.attr("href").trim()
                val url = if (href.startsWith("http")) href else baseUrl.ifBlank { BASE_URL } + href
                val fullTitle = anchor.text().trim()

                val numberMatch = Regex("""\d+(\.\d+)?""").findAll(fullTitle).lastOrNull()
                val chapterNumber = numberMatch?.value ?: ""

                val dateText = element.selectFirst(".chapter-release-date .timediff i")?.text()?.trim() ?: ""


                ChapterItem(
                    name = fullTitle,
                    number = chapterNumber,
                    url = url,
                    date = parseArabicDateToLocalDate(dateText)
                )
            } catch (e: Exception) {
                null
            }
        }
    }



    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val mangaList = mutableListOf<MangaItem>()
        val doc: Document = Jsoup.parse(html)
        val mangaElements = doc.select(".page-item-detail.manga")

        for (element in mangaElements) {
            val titleElement: Element? = element.selectFirst(".item-thumb a")
            val imageElement: Element? = element.selectFirst(".item-thumb img")
            val ratingElement: Element? = element.selectFirst(".post-total-rating")
            val chapterElements = element.select(".list-chapter .chapter-item")

            if (titleElement != null && imageElement != null) {
                val title = titleElement.attr("title").trim()
                val url = titleElement.attr("href")
                val imageUrl = imageElement.attr("src")
                val rating = ratingElement?.select("i.rating_current")?.size ?: 0

                val chapters = chapterElements.map { chapter ->
                    val chapterLink = chapter.selectFirst("a")
                    val chapterNum = chapterLink?.text()?.trim() ?: "Unknown"
                    val chapterUrl = chapterLink?.attr("href") ?: ""
                    val dateText = chapter.selectFirst(".post-on")?.text()?.trim()
                    val date = if (!dateText.isNullOrEmpty()) dateText else "NEW"
                    val cleantext  = cleanDateString(date)

                    ChapterItem(
                        number = "Chapter $chapterNum",
                        name = chapterNum,
                        url = chapterUrl,
                        date = parseArabicDateToLocalDate(cleantext) ?: LocalDate.now()
                    )
                }

                mangaList.add(
                    MangaItem(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = url,
                        imageUrl = imageUrl,
                        rating = rating,
                        chapters = chapters,
                        genres = listOf()
                    )
                )
            }
        }
        return mangaList
    }

    override fun extractMangaList(string: String): List<PopularManga> {
       return emptyList()
    }

    fun searchFormBody(searchType: SearchType.Normal): FormBody? {
        return FormBody.Builder()
            .add("vars[s]", searchType.query)
            .add("action", "madara_load_more")
            .add("vars[posts_per_page]", "20")

            .add("template", "madara-core/content/content-search")
            .build()
    }
    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        Log.i("fslksadfasghfsdgdfgdfgfds3",url.toString())

        return  fetchDataWithHeaders({ api.post(url,searchFormBody(searchType)) }){  html -> getSearchResults(html)}
    }
    override fun extractMangaInfo(html: String, Url: String, combinUrl: String): MangaInfo {
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("div.post-title h1")?.text()?.trim() ?: ""
        val otherNames = doc.select("div.summary-heading:contains(أسماء أخرى) + div.summary-content")?.text()?.trim() ?: ""
        val imageUrl = doc.selectFirst("div.summary_image img")?.attr("src") ?: ""

        val rating = doc.selectFirst("span#averagerate")?.text()?.trim() ?: "0"
        val ratingCount = "0" // Not found in current HTML; placeholder

        val description = doc.select("meta[name=description]")?.attr("content")?.trim() ?: ""

        val author = doc.select("div.summary-heading:contains(الكاتب) + div.summary-content a").eachText().joinToString(", ")
        val artist = doc.select("div.summary-heading:contains(الرسام) + div.summary-content a").eachText().joinToString(", ")

        val genres = doc.select("div.summary-heading:contains(التصنيفات) + div.summary-content a").map { it.text().trim() }

        val tags = doc.select("a.tag-cloud-link").map { it.text().trim() }

        val yearOfProduction = "N/A" // No specific year found in HTML

        val favoritesCount = "0" // Not available in HTML

        val status = "Unknown" // Status element not clearly labeled

        return MangaInfo(
            api = MangaSource.AASQ.API,
            language = MangaSource.AASQ.LANGUAGE.Language,
            url = Url,
            title = title,
            imageUrl = imageUrl,
            rating = rating,
            ratingCount = ratingCount,
            description = description,
            otherNames = otherNames,
            author = author,
            artist = artist,
            genres = genres,
            tags = tags,
            yearOfProduction = yearOfProduction,
            status = status,
            favoritesCount = favoritesCount,
            chapters = mutableListOf()
        )
    }

    override fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)

        return doc.select("div.row.c-tabs-item__content").mapNotNull { card ->

            // Cover image
            val imgEl = card.selectFirst("div.tab-thumb a img") ?: return@mapNotNull null
            val imageUrl = imgEl.absUrl("src")

            // Title and page URL
            val titleA = card.selectFirst("div.tab-summary .post-title a") ?: return@mapNotNull null
            val title = titleA.text().trim()
            val pageUrl = titleA.absUrl("href")





            MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = pageUrl,
                imageUrl = imageUrl,
                rating = 0,
                chapters = listOf(),
                genres = listOf(),
            )
        }
    }
    fun cleanDateString(raw: String): String {
        // Remove any trailing whitespace+digits
        return raw.replace(Regex("\\s+\\d+$"), "").trim()
    }

    fun parseArabicDateToLocalDate(input: String): LocalDate? {
        // Use Cairo zone
        val zone = ZoneId.of("Africa/Cairo")
        val nowZdt = ZonedDateTime.now(zone)
        val trimmed = input.trim()

        // 1. Handle relative times, e.g. "منذ ساعة واحدة", "منذ 3 أيام"
        if (trimmed.startsWith("منذ")) {
            // Regex to capture number and unit after "منذ"
            val regex = Regex("""منذ\s+(\d+)\s+([^\s]+)""")
            val match = regex.find(trimmed)
            if (match != null) {
                val value = match.groupValues[1].toLongOrNull() ?: return nowZdt.toLocalDate()
                val unitWord = match.groupValues[2]
                // Determine unit by checking substrings
                val adjustedZdt = when {
                    unitWord.contains("ساعة") || unitWord.contains("ساعت") ->
                        nowZdt.minusHours(value)
                    unitWord.contains("دقيقة") || unitWord.contains("دقيقة") ->
                        nowZdt.minusMinutes(value)
                    unitWord.contains("يوم") ->
                        nowZdt.minusDays(value)
                    unitWord.contains("أسبوع") ->
                        nowZdt.minusWeeks(value)
                    unitWord.contains("شهر") ->
                        nowZdt.minusMonths(value)
                    unitWord.contains("سنة") || unitWord.contains("سنوات") ->
                        nowZdt.minusYears(value)
                    else ->
                        nowZdt
                }
                return adjustedZdt.toLocalDate()
            }
            // If pattern not matched, return current date
            return nowZdt.toLocalDate()
        }

        // 2. Handle absolute dates, e.g. "3 يونيو، 2025" or "27 مايو 2025"
        // First, normalize Arabic comma and regular comma to space
        val norm = trimmed
            .replace('،', ' ')
            .replace(',', ' ')
            .replace("\\s+".toRegex(), " ")
            .trim()
        // Try DateTimeFormatter with Arabic locale
        try {
            // Pattern: day number + space + full month name in Arabic + space + year
            val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ar"))
            return LocalDate.parse(norm, formatter)
        } catch (_: Exception) {
            // Fallback: manual parsing
            val regex = Regex("""(\d{1,2})\s+([^\s]+)\s+(\d{4})""")
            val match = regex.find(norm)
            if (match != null) {
                val day = match.groupValues[1].toInt()
                val monthName = match.groupValues[2]
                val year = match.groupValues[3].toInt()
                // Map of Arabic month names to month numbers
                val monthMap = mapOf(
                    "يناير" to 1,
                    "فبراير" to 2,
                    "مارس" to 3,
                    "أبريل" to 4,
                    "ابريل" to 4,
                    "مايو" to 5,
                    "يونيو" to 6,
                    "يوليو" to 7,
                    "أغسطس" to 8,
                    "اغسطس" to 8,
                    "سبتمبر" to 9,
                    "اكتوبر" to 10,
                    "أكتوبر" to 10,
                    "نوفمبر" to 11,
                    "ديسمبر" to 12
                )
                val month = monthMap[monthName]
                if (month != null) {
                    return LocalDate.of(year, month, day)
                }
            }
        }
        // If parsing fails
        return null
    }

}