package me.manga.yamiapk.sources_repositry.fr.manga_origine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.NormalSitesv2
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
import kotlin.lazy

class MangaOrigineRepository  @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSitesv2(dataStore,api,sourcesRepository){
    override val mangaSource: MangaSource
        get() = MangaSource.MANGAORIGINES
    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE:   String
        get() = mangaSource.LANGUAGE.Language

    override val homeUrl: String by lazy {  "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php" }
    override val popularUrl: String by lazy {  "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php" }
    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() =setOf()
    override val blackListGenres: Set<String>
        get() = setOf()


    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0


    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    override var customParseHome: Boolean = false
    override var useGetForHome: Boolean = false
    override var useGetForPopular: Boolean = false
    override var useGetForSearch: Boolean = false
    override var useGetForNormalSearch: Boolean = false











    override fun handelFormBodyHome(page: Int, popular: Boolean): FormBody? {


        return FormBody.Builder().apply {
            add("page","${page-1}")
            add("action","madara_load_more")
            add("template","madara-core/content/content-archive")
            add("vars[orderby]", "meta_value_num")
            add("vars[paged]", "1")
            add("vars[post_type]", "wp-manga")
            add("vars[post_status]", "publish")
            add("vars[meta_key]", "_latest_update")
            add("vars[order]", "desc")
            add("vars[sidebar]", "right")
            add("vars[manga_archives_item_layout]", "big_thumbnail")
        }.build()
    }

    override fun handelFormBodyPopular(page: Int, popular: Boolean): FormBody? {
        return FormBody.Builder().apply {
            add("page","0")
            add("vars[posts_per_page]", "25")
            add("action","madara_load_more")
            add("template","madara-core/content/content-archive")
            add("vars[orderby]", "meta_value_num")
            add("vars[paged]", "1")
            add("vars[post_type]", "wp-manga")
            add("vars[post_status]", "publish")
            add("vars[meta_key]", "_wp_manga_views")
            add("vars[order]", "desc")
            add("vars[sidebar]", "right")
            add("vars[manga_archives_item_layout]", "big_thumbnail")
        }.build()
    }
    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php"
    }
    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
      return null
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? {
        return FormBody.Builder().apply {
            add("vars[s]", searchType.query)
            add("vars[posts_per_page]", "25")
            add("action", "madara_load_more")
            add("page", (0).toString())
            add("template", "madara-core/content/content-search")
            add("vars[paged]", "1")
            add("vars[template]", "archive")
            add("vars[sidebar]", "right")
            add("vars[post_type]", "wp-manga")
            add("vars[post_status]", "publish")
            add("vars[manga_archives_item_layout]", "big_thumbnail")
        }.build()
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? {
        return null
    }

    override fun sortFormBody(searchType: SearchType.SORT): FormBody? {
        return null
    }

    override fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem> {
        return mutableListOf()

    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {


        val doc = Jsoup.parse(html)
        val items = mutableListOf<MangaItem>()


        val elements = doc.select(".page-item-detail")

        for (el in elements) {
            val imageAnchor = el.selectFirst(".item-thumb a")
            val titleAnchor = el.selectFirst(".post-title a")

            val img = imageAnchor?.selectFirst("img")

            val imageUrl = when {
                img == null -> ""
                img.hasAttr("data-src") && img.attr("data-src").isNotBlank() ->
                    img.absUrl("data-src")

                img.hasAttr("data-srcset") && img.attr("data-srcset").isNotBlank() ->
                    img.attr("data-srcset")
                        .split(",")
                        .first()
                        .trim()
                        .substringBefore(" ")

                else ->
                    img.absUrl("src")
            }

            Log.i("sadljashfadfsadfasdfas",imageUrl)
            val title = titleAnchor?.text()?.trim().orEmpty()
            val url = titleAnchor?.absUrl("href").orEmpty()

            val chapterElements = el.select(".list-chapter .chapter-item")

            val chapters = chapterElements.mapNotNull { chapterEl ->
                val a = chapterEl.selectFirst("a") ?: return@mapNotNull null
                val name = a.text().trim()

                val chapterUrl = a.absUrl("href").trim()
                ChapterItem(name = name, number = name, url = chapterUrl)
            }


            items +=  MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = 0,
                chapters = chapters,
                genres = emptyList()
            )
        }

        return items
    }


    override fun extractMangaList(html: String): List<PopularManga> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<PopularManga>()

        val elements = doc.select(".page-item-detail")

        for (el in elements) {
            val imageAnchor = el.selectFirst(".item-thumb a")
            val titleAnchor = el.selectFirst(".post-title a")

            val img = imageAnchor?.selectFirst("img")

            val imageUrl = when {
                img == null -> ""
                img.hasAttr("data-src") && img.attr("data-src").isNotBlank() ->
                    img.absUrl("data-src")

                img.hasAttr("data-srcset") && img.attr("data-srcset").isNotBlank() ->
                    img.attr("data-srcset")
                        .split(",")
                        .first()
                        .trim()
                        .substringBefore(" ")

                else ->
                    img.absUrl("src")
            }

            val title = titleAnchor?.text()?.trim().orEmpty()
            val url = titleAnchor?.absUrl("href").orEmpty()
            Log.i("sadljashfadfsadfasdfas1",imageUrl)

            items +=  PopularManga(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,

                )
        }

        return items
    }


 val updatingRegex = "Updating|Atualizando".toRegex(RegexOption.IGNORE_CASE)

    fun String.notUpdating(): Boolean {
        return this.contains(updatingRegex).not()
    }
    override suspend fun extractMangaInfo(
        html: String,
        baseUrl: String
    ): MangaInfo {
        val document: Document = Jsoup.parse(html)

        val title = document.selectFirst("div.post-title h3, div.post-title h1, #manga-title > h1")?.ownText().orEmpty()

        val author = document.select("div.author-content > a, div.manga-authors > a")
            .map { it.text() }


        val artist = document.select("div.artist-content > a")
            .map { it.text() }

        val img = document.selectFirst("div.summary_image img")?.selectFirst("img")

        val thumbnail = when {
            img == null -> ""
            img.hasAttr("data-src") && img.attr("data-src").isNotBlank() ->
                img.absUrl("data-src")

            img.hasAttr("data-srcset") && img.attr("data-srcset").isNotBlank() ->
                img.attr("data-srcset")
                    .split(",")
                    .first()
                    .trim()
                    .substringBefore(" ")

            else ->
                img.absUrl("src")
        }

        val statusText = document.select("div.summary-content, div.summary-heading:contains(Status) + div").last()?.text().orEmpty()


        val description = buildString {
            val descEl = document.select("div.summary__content > p")
            if (descEl.select("p").isNotEmpty()) {
                append(descEl.select("p").joinToString("\n\n") { it.text().replace("<br>", "\n") })
            } else {
                append(descEl.text())
            }
        }

        val genres = document.select("div.genres-content a").map { it.text().lowercase(Locale.ROOT) }.toMutableSet()

        document.select("div.tags-content a").forEach { tag ->
            val txt = tag.text().lowercase(Locale.ROOT)
            if (txt !in genres && txt.length <= 25 &&
                !txt.contains("read", true) &&
                !txt.contains(title, true)
//                &&
//                txt.notUpdating()
            ) {
                genres.add(txt)
            }
        }

        document.selectFirst(".post-content_item:contains(Type) .summary-content")?.ownText()?.let {
            val type = it.lowercase(Locale.ROOT)
//            if (type.isNotEmpty() && type.notUpdating() && type != "-" && type !in genres) {
                genres.add(type)
//            }
        }

        val otherNames = document.selectFirst(".post-content_item:contains(Alt) .summary-content")?.ownText()?.takeIf {
            it.notUpdating() }.orEmpty()

        val rating = document.select("span#averagerate").text().ifBlank { null }
        val ratingCount = document.select("span#countrate").text().ifBlank { null }
        val favoritesCount = document.select("div.add-bookmark .action_detail span").text().ifBlank { null }
        val yearOfProduction = document.select("div.summary-content:has(h5:contains(سنة الانتاج))").text()

        var chapterElements = document.select("ul.main.version-chap li.wp-manga-chapter")
        if (chapterElements.isEmpty()) {
//            val baseUrl = document.location().removeSuffix("/")
            try {

                val response  = api.post(
                    "${baseUrl}ajax/chapters/",
                    body = FormBody.Builder().build(),
                )

                chapterElements = Jsoup.parse(response.body().orEmpty()).select("li.wp-manga-chapter")
            } catch (e: Exception) {
//                e.printStackTrace()
            }
        }

        val chapters = chapterElements.map { element ->
            val chapterNumber = element.select("a").text()
            val chapterUrl = element.select("a").attr("href")
            val dateElement = element.select("span.chapter-release-date")
            val date = if (dateElement.select("span.c-new-tag").isNotEmpty()) {
                dateElement.select("img").attr("alt").ifEmpty { "NEW" }
            } else {
                dateElement.select("i").text()
            }
            val chnumber = Regex("""\d+(\.\d+)?""").find(chapterNumber)?.value ?: chapterNumber

            ChapterItem(
                name = chapterNumber,
                number = chnumber,
                url = chapterUrl,
                date = parseChapterDate(date) ?: LocalDate.now(),
            )
        }.toMutableList()

        return MangaInfo(
            title = title,
            imageUrl = thumbnail,
            rating = rating ?: "",
            ratingCount = ratingCount ?: "",
            description = if (otherNames.isNotBlank()) "$description\n\n $otherNames" else description,
            otherNames = otherNames,
            author = author.joinToString { it },
            artist = artist.joinToString { it },
            genres = genres.map { it.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) } },
            tags = emptyList(), // could duplicate genre if needed
            yearOfProduction = yearOfProduction,
            status = statusText,
            favoritesCount = favoritesCount ?: "",
            chapters = chapters,
            api = API,
            url = baseUrl,
            language = LANGUAGE
        )
    }
    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<MangaItem>()

        val elements = doc.select("div.row.c-tabs-item__content")

        for (el in elements) {
            val thumbAnchor = el.selectFirst(".tab-thumb a")
            val img = thumbAnchor?.selectFirst("img")

            val imageUrl = when {
                img == null -> ""
                img.hasAttr("data-src") && img.attr("data-src").isNotBlank() ->
                    img.absUrl("data-src")

                img.hasAttr("data-srcset") && img.attr("data-srcset").isNotBlank() ->
                    img.attr("data-srcset")
                        .split(",")
                        .first()
                        .trim()
                        .substringBefore(" ")

                else ->
                    img.absUrl("src")
            }

            val titleAnchor = el.selectFirst(".post-title h3 a")
            val title = titleAnchor?.text()?.trim() ?: ""
            val url = titleAnchor?.absUrl("href") ?: ""


            val genreElements = el.select(".mg_genres .summary-content a")
            val genres = genreElements.map { it.text().trim() }



            items +=  MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = 0,
                chapters = listOf(),
                genres = genres
            )
        }

        return items
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun getChapterImages(html: String): List<String> {
        val document = Jsoup.parse(html)
        // Mangas Origines uses 'img' tags inside div.reading-content
        return document.select("div.page-break, li.blocks-gallery-item, .reading-content .text-left:not(:has(.blocks-gallery-item)) img")
            .mapNotNull { it.selectFirst("img")?.let{ imageFromElement(it) } }
            }

    fun imageFromElement(element: Element): String? {
        return when {
            element.hasAttr("data-src") -> element.attr("abs:data-src")
            element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
            element.hasAttr("srcset") -> element.attr("abs:srcset").getSrcSetImage()
            element.hasAttr("data-cfsrc") -> element.attr("abs:data-cfsrc")
            else -> element.attr("abs:src")
        }
    }
    protected fun String.getSrcSetImage(): String? {
        return this.split(" ")
            .filter(URL_REGEX::matches)
            .maxOfOrNull(String::toString)
    }
    companion object {
        const val URL_SEARCH_PREFIX = "slug:"
        val URL_REGEX = """^(https?://[^\s/$.?#].[^\s]*)${'$'}""".toRegex()
    }
    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()



     fun parseChapterDate(dateStr: String?): LocalDate? {
        // 0) Null → null
        if (dateStr == null) return null

        val trimmed = dateStr.trim()

        // 0b) Log‑line style “… I  03/07/2025” or standalone “17/07/2025”
        //     Look for dd/MM/yyyy at the very end of the string
        val slashDateRegex = """\b(\d{2}/\d{2}/\d{4})$""".toRegex()
        slashDateRegex.find(trimmed)?.groupValues?.get(1)?.let { d ->
            return try {
                LocalDate.parse(d, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            } catch (_: DateTimeParseException) {
                null
            }
        }

        // 1) Blank or “NEW” → today
        if (trimmed.isEmpty() || trimmed.equals("NEW", ignoreCase = true)) {
            return LocalDate.now()
        }

        // 1b) Special case: purely‑textual “two days ago” in Arabic + English
        if (trimmed.equals("يومين ago", ignoreCase = true)) {
            return LocalDate.now().minusDays(2)
        }

        // 2) Relative‑time (“X units ago”) in Arabic + “ago”
        val relRegex =
            """(\d+)\s*(ثانية|ثواني|دقيقة|دقائق|ساعة|ساعات|يوم|أيام|يومين|يومان)\s*ago""".toRegex(RegexOption.IGNORE_CASE)
        relRegex.find(trimmed)?.let { m ->
            val amount = m.groupValues[1].toLong()
            val unit   = m.groupValues[2]
            val now = LocalDateTime.now()
            val dt = when (unit.lowercase(Locale.getDefault())) {
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
            return LocalDate.parse(trimmed, arabicFormatter)
        } catch (_: DateTimeParseException) { /* fall through */ }

        //    English fallback (“April 22, 2025”)
        val englishFormatter =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
        return try {
            LocalDate.parse(trimmed, englishFormatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}