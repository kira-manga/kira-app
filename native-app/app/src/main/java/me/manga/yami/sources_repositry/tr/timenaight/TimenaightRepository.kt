package me.manga.yamiapk.sources_repositry.tr.timenaight

import android.util.Log
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toPopularMangaList
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.NormalSitesv2
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.work.Logs.logLongText
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import javax.inject.Inject

class TimenaightRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSitesv2(dataStore,api,sourcesRepository) {
    override val mangaSource: MangaSource
    get() = MangaSource.TIMENAGHT
    override val homeUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL }}manga/?m_orderby=new-manga" }
    override val popularUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL }}manga/?m_orderby=views" }
    override var customParseHome: Boolean = true


    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
    get() = _cachedHeaders ?: emptyMap()


    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}manga/page/${page}/?m_orderby=new-manga"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { BASE_URL }}?s=${searchType.toNormalQuery()}&post_type=wp-manga&adult=0"
    }

    override val sortTypes: Set<String>
    get() = setOf()
    override val allGenres: Set<String>
    get() = setOf()
    override val blackListGenres: Set<String>
    get() = setOf(
        "Mature",
        "Yuri",
        "Shoujo Ai"
    )

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        return null
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

    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc = Jsoup.parse(html)

        return doc.select("div.page-item-detail.manga").mapNotNull { item ->
            try {
                // Get genres if available
                val genres = item.select("div.mg_genres a")
                    .map { it.text().trim() }
                if (genres.hasBlacklistedGenre()) return@mapNotNull null
                // Get the link from item-thumb
                val thumbDiv = item.selectFirst("div.item-thumb.c-image-hover")
                val anchor = thumbDiv?.selectFirst("a")
                val url = anchor?.attr("href")?.trim().orEmpty()

                // Get title from post-title
                val title = item.selectFirst("div.post-title.font-title h3 a")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                // Get image - handle lazy loading
                val img = thumbDiv?.selectFirst("img")
                val imageUrl = when {
                    img?.hasAttr("data-wpfc-original-src") == true ->
                        img.attr("data-wpfc-original-src")
                    img?.hasAttr("src") == true ->
                        img.attr("src")
                    else -> ""
                }.trim()

                // Get rating if available
                val ratingElement = item.selectFirst("div.post-total-rating span.score")
                val rating = ratingElement?.text()?.toIntOrNull() ?: 0

                // Get chapters
                val chapters = item.select("div.list-chapter div.chapter-item").map { chapterItem ->
                    val chapterLink = chapterItem.selectFirst("span.chapter a")
                    val chapterName = chapterLink?.text()?.trim().orEmpty()
                    val chapterUrl = chapterLink?.attr("href")?.trim().orEmpty()

                    ChapterItem(
                        name = chapterName,
                        number = chapterName,
                        url = chapterUrl
                    )
                }




                MangaItem(
                    title = title,
                    imageUrl = imageUrl,
                    url = url,
                    api = API,
                    language = LANGUAGE,
                    rating = rating,
                    chapters = chapters,
                    genres = genres
                )

            } catch (e: Exception) {
                logLongText("Error parsing manga item", e.message.orEmpty())
                null
            }
        }.toMutableList()
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
       return extractCustomHomeMangaItems(html)

    }

    override fun extractMangaList(html: String): List<PopularManga> {
        return extractCustomHomeMangaItems(html).toPopularMangaList()
    }

    override suspend fun extractMangaInfo(
        html: String,
        baseUrl: String
    ): MangaInfo {
        val doc = Jsoup.parse(html)

        logLongText("sdgjfhdsgdfgdafgsdasdsfgm", doc.toString())

        // The main manga info container
        val mangaProfile = doc.selectFirst("div.profile-manga")
        val summaryContent = mangaProfile?.selectFirst("div.summary_content_wrap div.summary_content")
        val postContent = summaryContent?.selectFirst("div.post-content")

        // Extract chapters - they're in a different location
        val chapters = doc.select("div.listing-chapters_wrap ul.main.version-chap li.wp-manga-chapter").map { el ->
            val link = el.selectFirst("a")
            val chapterName = link?.text()?.trim().orEmpty()
            val chapterNumber = chapterName.replace(Regex("[^\\d.]"), "").trim()
            val chapterUrl = link?.attr("href").orEmpty()

            val rawDate = el.selectFirst("span.chapter-release-date i")?.text().orEmpty()

            val parsed = try {
                val num = rawDate.filter { it.isDigit() }.toLongOrNull() ?: 0L
                when {
                    rawDate.contains("saat", true) -> LocalDate.now() // "saat önce" = hours ago
                    rawDate.contains("gün", true) -> LocalDate.now().minusDays(num) // "gün önce" = days ago
                    rawDate.contains("hafta", true) -> LocalDate.now().minusWeeks(num) // "hafta önce" = weeks ago
                    rawDate.contains("ay", true) -> LocalDate.now().minusMonths(num) // "ay önce" = months ago
                    rawDate.contains("yıl", true) -> LocalDate.now().minusYears(num) // "yıl önce" = years ago
                    else -> LocalDate.now()
                }
            } catch (e: Exception) {
                LocalDate.now()
            }

            ChapterItem(
                number = chapterNumber,
                name = chapterName,
                url = chapterUrl,
                date = parsed,
            )
        }.toMutableList()

        // Title
        val title = doc.selectFirst("div.post-title h1")?.text()?.trim().orEmpty()

        // Description
        val description = doc.selectFirst("div.description-summary div.summary__content")
            ?.textNodes()
            ?.joinToString("\n") { it.text().trim() }
            ?.substringBefore("İlgili İçerikler:") // Remove "Related Content" section
            ?.trim().orEmpty()

        // Alternative names
        val altName = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Diğer Adları)) .summary-content")
            ?.text()?.trim()?.takeIf { it != "Güncelleniyor" && it.isNotBlank() }

        val fullDescription = if (!altName.isNullOrEmpty()) {
            "$description\n\nAlternative Name: $altName"
        } else {
            description
        }

        // Genres
        val genres = postContent?.select(".post-content_item:has(.summary-heading:contains(Kategoriler)) .summary-content a")
            ?.map { it.text() }?.toMutableList() ?: mutableListOf()

        // Type (Webtoon, Manga, etc.)
        val type = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Tür)) .summary-content")
            ?.text()?.trim()
        type?.let { if (it.isNotBlank()) genres.add(it) }

        // Status
        val statusText = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Durumu)) .summary-content")
            ?.text()?.trim().orEmpty()

        // Thumbnail
        val thumbnailUrl = doc.selectFirst("div.summary_image a img")?.attr("src").orEmpty()

        // Rating
        val rating = postContent?.selectFirst(".post-rating .post-total-rating .total_votes")
            ?.text()?.trim().orEmpty()

        // Author
        val author = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Yazar)) .summary-content")
            ?.text()?.trim()?.takeIf { it != "Güncelleniyor" }.orEmpty()

        // Artist
        val artist = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Sanatçı)) .summary-content")
            ?.text()?.trim()?.takeIf { it != "Güncelleniyor" }.orEmpty()

        // Year
        val year = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Çıkış Yılı)) .summary-content a")
            ?.text()?.trim().orEmpty()

        return MangaInfo(
            title = title,
            imageUrl = thumbnailUrl,
            rating = rating,
            ratingCount = "",
            description = fullDescription,
            otherNames = altName.orEmpty(),
            author = author,
            artist = artist,
            genres = genres,
            tags = emptyList(),
            yearOfProduction = year,
            status = statusText,
            favoritesCount = "",
            chapters = chapters,
            api = API,
            url = baseUrl,
            language = LANGUAGE
        )
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)
        logLongText("sdgsadsajfhdsgdfgdafgsdasdsfg2", doc.toString())

        return doc.select("div.c-tabs-item__content").mapNotNull { item ->
            try {
                // Get image URL from tab-thumb
                val imageUrl = item.selectFirst("div.tab-thumb a img")?.attr("src").orEmpty()

                // Get URL and title from post-title
                val titleElement = item.selectFirst("div.post-title h3 a")
                val url = titleElement?.attr("href").orEmpty()
                val title = titleElement?.text()?.trim().orEmpty()

                // Get genres
                val genres = item.select("div.post-content_item.mg_genres div.summary-content a")
                    .map { it.text().trim() }

                // Get rating
                val ratingElement = item.selectFirst("div.post-total-rating span.total_votes")
                val rating = ratingElement?.text()?.toIntOrNull() ?: 0

                // Get latest chapter (optional)

                // Skip if essential data is missing
                if (title.isEmpty() || url.isEmpty()) {
                    return@mapNotNull null
                }

                MangaItem(
                    title = title,
                    imageUrl = imageUrl,
                    url = url,
                    api = API,
                    language = LANGUAGE,
                    rating = rating,
                    chapters = emptyList(),
                    genres = genres
                )
            } catch (e: Exception) {
                // Log error and skip this item
                null
            }
        }
    }


    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders

        dataStore.saveHeadersForApi(API, newHeaders)

    }

    override fun getChapterImages(html: String): List<String> {
        val doc = Jsoup.parse(html)

        logLongText("sdgsadsajfhdsgdfgdafgsdasdsfgm", doc.toString())

        // Images are in div.reading-content with class "page-break no-gaps"
        // They use lazy loading with data-wpfc-original-src attribute
        val imgs = doc.select("div.reading-content div.page-break img.wp-manga-chapter-img")
            .mapNotNull { img ->
                // Try to get the actual image URL from different possible attributes
                img.attr("data-wpfc-original-src").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() && !it.contains("blank.gif") }
            }
            .distinct()

        return imgs
    }

    fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-cfsrc") -> attr("abs:data-cfsrc")
        else -> attr("abs:src")
    }

    override val BASE_URL: String
    get() = mangaSource.BASEURL
    override val API: String
    get() = mangaSource.API
    override val LANGUAGE: String
    get() = mangaSource.LANGUAGE.Language


}

