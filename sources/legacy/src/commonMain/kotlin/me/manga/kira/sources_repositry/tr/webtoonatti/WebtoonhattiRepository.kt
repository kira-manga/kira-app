/**
 * Migration note (Phase 7.9): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * @Inject dropped, android.util.Log -> Kermit Logger, java.time -> kotlinx.datetime.
 */
package me.manga.kira.sources_repositry.tr.webtoonatti

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
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
class WebtoonhattiRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
): NormalSitesv2(api, sourcesRepository) {
    override val mangaSource: MangaSource
        get() = MangaSource.WEBTOONHATTI
    override val homeUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL }}webtoon/?m_orderby=latest" }
    override val popularUrl: String by lazy { "${ baseUrl.ifBlank { BASE_URL }}webtoon/?m_orderby=views" }
    override var customParseHome: Boolean = true

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 0

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: emptyMap()


    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}manga/page/${page}/?m_orderby=latest"
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
        val postContent = summaryContent?.selectFirst("div.post-status")

        // Extract chapters - they're in a different location
        val chapters = doc.select("div.listing-chapters_wrap ul.main.version-chap li.wp-manga-chapter").map { el ->
            val link = el.selectFirst("a")
            val chapterName = link?.text()?.trim().orEmpty()
            val chapterNumber = chapterName.replace(Regex("[^\\d.]"), "").trim()
            val chapterUrl = link?.attr("href").orEmpty()

            val rawDate = el.selectFirst("span.chapter-release-date i")?.text()?.trim().orEmpty()

            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            val parsed = try {
                when {
                    rawDate.contains("saat", true) || rawDate.contains("ago", true) -> {
                        // "3 saat ago" or just "ago"
                        today
                    }
                    rawDate.contains("gün", true) -> {
                        // "1 gün ago" or "2 gün ago"
                        val num = rawDate.filter { it.isDigit() }.toLongOrNull() ?: 1L
                        today.minus(num.toInt(), DateTimeUnit.DAY)
                    }
                    rawDate.contains("hafta", true) -> {
                        val num = rawDate.filter { it.isDigit() }.toLongOrNull() ?: 1L
                        today.minus(num.toInt(), DateTimeUnit.WEEK)
                    }
                    rawDate.contains("ay", true) -> {
                        val num = rawDate.filter { it.isDigit() }.toLongOrNull() ?: 1L
                        today.minus(num.toInt(), DateTimeUnit.MONTH)
                    }
                    rawDate.contains("yıl", true) -> {
                        val num = rawDate.filter { it.isDigit() }.toLongOrNull() ?: 1L
                        today.minus(num.toInt(), DateTimeUnit.YEAR)
                    }
                    rawDate.contains("/") -> {
                        // Date format: "13/10/2025" or "DD/MM/YYYY"
                        val parts = rawDate.split("/")
                        if (parts.size == 3) {
                            val day = parts[0].toIntOrNull() ?: 1
                            val month = parts[1].toIntOrNull() ?: 1
                            val year = parts[2].toIntOrNull() ?: today.year
                            LocalDate(year, month, day)
                        } else {
                            today
                        }
                    }
                    else -> today
                }
            } catch (e: Exception) {
                today
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

        // Description - Fixed selector
        val descriptionElements = doc.select("div.description-summary div.summary__content p")
        val description = descriptionElements.joinToString("\n") { it.text().trim() }

        // Alternative names
        val altName = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Diğer Adları)) .summary-content")
            ?.text()?.trim()?.takeIf { it != "Güncelleniyor" && it.isNotBlank() }

        val fullDescription = if (!altName.isNullOrEmpty()) {
            "$description\n\nAlternative Name: $altName"
        } else {
            description
        }

        // Genres - Fixed selector
        val genres = postContent?.select(".post-content_item:has(.summary-heading:contains(Kategori)) .genres-content a")
            ?.map { it.text().trim() }?.toMutableList() ?: mutableListOf()

        // Type (Manhwa, Manga, Manhua, etc.) - Fixed selector
        val type = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Cinsi)) .summary-content")
            ?.text()?.trim()
        type?.let { if (it.isNotBlank()) genres.add(it) }

        // Status - Fixed selector
        val rawStatus = postContent
            ?.selectFirst(".post-content_item:has(.summary-heading:contains(Durumu)) .summary-content")
            ?.text()
            ?.trim()
            ?: postContent
                ?.selectFirst(".post-content_item:has(.summary-heading:matches((?i)Durum|Durumu|Status)) .summary-content")
                ?.text()
                ?.trim()
            ?: ""
        val status = when {
            rawStatus.equals("ongoing", true) || rawStatus.equals("ongoing", true) -> "Ongoing"
            rawStatus.equals("completed", true) || rawStatus.equals("finished", true) -> "Completed"
            rawStatus.isBlank() -> "Unknown"
            else -> rawStatus
        }
        Logger.withTag("asjlfhlasfaslkfjklasjfklasjfkasfasfasf").i { status.toString() }
        // Thumbnail
        val thumbnailUrl = doc.selectFirst("div.summary_image a img")?.attr("src").orEmpty()

        // Rating
        val rating = postContent?.selectFirst(".post-rating .post-total-rating .total_votes")
            ?.text()?.trim().orEmpty()

        // Author
        val author = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Yazar)) .author-content")
            ?.text()?.trim()?.takeIf { it != "Güncelleniyor" }.orEmpty()

        // Artist
        val artist = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Çizer)) .artist-content")
            ?.text()?.trim()?.takeIf { it != "Güncelleniyor" }.orEmpty()

        // Year (if exists)
        val year = postContent?.selectFirst(".post-content_item:has(.summary-heading:contains(Çıkış Yılı)) .summary-content a")
            ?.text()?.trim().orEmpty()

        return MangaInfo(
            title = title,
            imageUrl = thumbnailUrl,
            rating = rating,
            description = fullDescription.toString(),
            author = author,
            genres = genres,
            status = status.toString(),
            chapters = chapters,
            api = API,
            url = baseUrl,
            language = LANGUAGE
        )
    }
    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Ksoup.parse(html)

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

        // Images are in div.reading-content with class "page-break no-gaps"
        // They use lazy loading with data-wpfc-original-src attribute
        val imgs = doc.select("div.reading-content div.page-break img.wp-manga-chapter-img")
            .mapNotNull { img ->
                // Try to get the actual image URL from different possible attributes
                val imgurl = img.attr("data-wpfc-original-src").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() && !it.contains("blank.gif") }

                imgurl?.trim()
            }
            .filter { url ->
                // Filter out fake/dummy URLs and watermark images
                val isValidDomain = url.startsWith("https://cdn-2.webtoon-oku.net/") ||
                        url.startsWith("https://webtoonhatti.club/wp-content/uploads/WP-manga/data/")

                val isNotFakeUrl = !url.contains("fkneko.whatthe") &&
                        !url.contains("dont-hakuneko.plase") &&
                        !url.contains("bicxxxxxo.yaaxa")

                val isNotWatermark = !url.contains("/wp-content/uploads/2024/") &&
                        !url.contains("/wp-content/uploads/2025/") &&
                        !url.contains("tr2.png") &&
                        !url.contains("z9.jpg")

                isValidDomain && isNotFakeUrl && isNotWatermark
            }

        Logger.withTag("jkafhdjkfdasjfdskfjsdfsfs").i { imgs.toString() }
        return imgs
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
