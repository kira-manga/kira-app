/**
 * Migration note (Phase 7.9): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 */
package me.manga.kira.sources_repositry.tr.timenaight

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.NormalSitesv2
import me.manga.kira.sources_repositry.data.MangaSource

@OptIn(ExperimentalTime::class)
class TimenaightRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
): NormalSitesv2(api, sourcesRepository) {
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
     * Just like your old `defaultHeaders` – will block once on first call,
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

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        return null
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

    override fun extractCustomHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc = Ksoup.parse(html)

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
                Logger.withTag("Error parsing manga item").e { e.message.orEmpty() }
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
        val doc = Ksoup.parse(html)

        Logger.withTag("sdgjfhdsgdfgdafgsdasdsfgm").i { doc.toString() }

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
                val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                when {
                    rawDate.contains("saat", true) -> today // "saat önce" = hours ago
                    rawDate.contains("gün", true) -> today.minus(num.toInt(), DateTimeUnit.DAY) // "gün önce" = days ago
                    rawDate.contains("hafta", true) -> today.minus(num.toInt(), DateTimeUnit.WEEK) // "hafta önce" = weeks ago
                    rawDate.contains("ay", true) -> today.minus(num.toInt(), DateTimeUnit.MONTH) // "ay önce" = months ago
                    rawDate.contains("yıl", true) -> today.minus(num.toInt(), DateTimeUnit.YEAR) // "yıl önce" = years ago
                    else -> today
                }
            } catch (e: Exception) {
                Clock.System.todayIn(TimeZone.currentSystemDefault())
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
            description = fullDescription,
            author = author,
            genres = genres,
            status = statusText,
            chapters = chapters,
            api = API,
            url = baseUrl,
            language = LANGUAGE
        )
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html)
        Logger.withTag("sdgsadsajfhdsgdfgdafgsdasdsfg2").i { doc.toString() }

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
        val doc = Ksoup.parse(html)

        Logger.withTag("sdgsadsajfhdsgdfgdafgsdasdsfgm").i { doc.toString() }

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

    // Inlined from `core.util.data_classes.HandelDataClasses.toPopularMangaList` — that helper has
    // not been ported to commonMain yet. The original behaviour just maps MangaItem -> PopularManga.
    private fun List<MangaItem>.toPopularMangaList(): List<PopularManga> = this.map {
        PopularManga(
            api = it.api,
            language = it.language,
            title = it.title,
            url = it.url,
            imageUrl = it.imageUrl,
        )
    }


}
