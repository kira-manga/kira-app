package me.manga.yamiapk.sources_repositry.en.manhwatop

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
import me.manga.yamiapk.sources_repositry.common.NormalSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject

class ManhwatopRepositoryV2 @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSites(dataStore,api,sourcesRepository) {

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
        get() =  mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php"
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("browse")
        .addQueryParameter("langs", "en")
        .addQueryParameter("sort", "update.za")
        .addQueryParameter("page", 1.toString())
        .build()
        .toString()

    }


    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php"}



    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0
    override fun handelLoadMoreUrl(page: Int): String {
        val httpUrl = "${baseUrl.ifBlank { BASE_URL }}wp-admin/admin-ajax.php".toHttpUrl()
            .newBuilder()
            .addPathSegment("browse")
            .addQueryParameter("langs", "en")
            .addQueryParameter("sort", "update.za")
            .addQueryParameter("page", page.toString())
            .build()
        return httpUrl.toString()
    }

    override fun handelFormBody(page:Int ,popular: Boolean): FormBody?{
       return FormBody.Builder().apply {
            add("action", "madara_load_more")
            add("page", (page - 1).toString())
            add("template", "madara-core/content/content-archive")
            add("vars[orderby]", "meta_value_num")
            add("vars[paged]", "1")
           add("vars[posts_per_page]", "20")
           add("vars[post_type]", "wp-manga")
            add("vars[post_status]", "publish")
            add("vars[meta_key]", if (popular) "_wp_manga_views" else "_latest_update")
            add("vars[order]", "desc")
            add("vars[sidebar]", "right")
            add("vars[manga_archives_item_layout]", "big_thumbnail")
        }.build()
    }


    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? {
        return null
    }

    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? {
        return null
    }

    override fun sortFormBody(searchType: SearchType.SORT): FormBody? {
        return null
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  -> normalSearchUrl(searchType.query)
            is SearchType.GENRES  -> ""
            is SearchType.SORT    -> ""
        }
    fun normalSearchUrl(q : String): String = "${baseUrl.ifBlank { BASE_URL }}?s=${q}&post_type=wp-manga"

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val document = Jsoup.parse(html)
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

    override fun extractMangaList(html: String): List<PopularManga> {
        val document = Jsoup.parse(html)
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
    override suspend fun extractMangaInfo(html: String, url: String): MangaInfo {
        val doc = Jsoup.parse(html)

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
            ?: url.split("/").lastOrNull { it.isNotBlank() }

        // Fetch chapters via AJAX
        val chapters = fetchChapters(url)

        return MangaInfo(
            api                = API,
            language           = LANGUAGE,
            url                = url,
            title              = title,
            imageUrl           = imageUrl,
            rating             = rating,
            ratingCount        = ratingCount,
            description        = description,
            otherNames         = "",
            author             = authors.toString(),
            artist             = artists.toString(),
            genres             = genres,
            tags               = emptyList(),
            yearOfProduction   = "",
            status             = status,
            favoritesCount     = "0",
            chapters           = chapters
        )
    }

    private suspend fun fetchChapters(mangaUrl: String): MutableList<ChapterItem> {
        return try {
            // Make AJAX request to get chapters
            val ajaxUrl = "${mangaUrl}ajax/chapters"

            Log.i("aslkfjsdlkfjskldfsdfsadfsdfsda0",ajaxUrl)
            val body = FormBody.Builder().build()

            val response = api.post(ajaxUrl, body, defaultHeaders)
            Log.i("aslkfjsdlkfjskldfsdfsadfsdfsda1",response.toString())

            val chaptersHtml = response.body() ?: ""
            val doc = Jsoup.parse(chaptersHtml)
            Log.i("aslkfjsdlkfjskldfsdfsadfsdfsda2",doc.toString())


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
                            number       = chapterNum.ifBlank { chapterText },
                            name         = chapterText,
                            url          = chapterUrl,
                            date         = parseChapterDate(dateText) ?: LocalDate.now(),
                            isDownloaded = false
                        )
                    } catch (e: Exception) {
                        Log.e("ManhwaTop", "Error parsing chapter: ${e.message}")
                        null
                    }
                }
                .toMutableList()
                .also { chapters ->
                    Log.d("ManhwaTop", "Successfully parsed ${chapters.size} chapters")
                }
        } catch (e: Exception) {
            Log.e("ManhwaTop", "Error fetching chapters: ${e.message}", e)
            mutableListOf()
        }
    }
    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)

        return doc
            // each “card” is a row with class c-tabs-item__content
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
                val title   = titleA.text().trim()
                val pageUrl = titleA.absUrl("href")                                       // :contentReference[oaicite:2]{index=2}

                // 3. Genres (if any): look for the “mg_genres” block under post-content
                val genres = card
                    .select("div.post-content_item.mg_genres .summary-content a")
                    .map { it.text().trim() }                                              // :contentReference[oaicite:3]{index=3}

                // 4. Rating: count full-star and half-star icons
                val rating = card.selectFirst("div.meta-item.rating")
                    ?.select("i.ion-ios-star, i.ion-ios-star-half")
                    ?.size
                    ?: 0                                                                  // :contentReference[oaicite:4]{index=4}

                MangaItem(
                    api      = API,
                    language = LANGUAGE,
                    title    = title,
                    url      = pageUrl,
                    imageUrl = imageUrl,
                    rating   = rating,
                    chapters = emptyList(),  // search results do not include chapters
                    genres   = genres
                )
            }
    }

    override fun getChapterImages(html: String): List<String> {
        val doc: Document = Jsoup.parse(html)
        // select all the chapter images (they’re lazy-loaded with data-src)
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
                    LocalDate.now()
                }
                dateText.equals("Complete", ignoreCase = true) -> {
                    // For completed chapters, use a past date or null
                    null
                }
                else -> {
                    // Try to parse dates like "Oct 26, 2025"
                    val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH)
                    LocalDate.parse(dateText, formatter)
                }
            }
        } catch (e: Exception) {
            Log.e("ManhwaTop", "Error parsing date: $dateText", e)
            LocalDate.now()
        }
    }

}