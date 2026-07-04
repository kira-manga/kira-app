package me.manga.yamiapk.sources_repositry.tr.webtoonatti

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

class WebtoonhattiRepository  @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSitesv2(dataStore,api,sourcesRepository) {
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
     * Just like your old `defaultHeaders` – will block once on first call,
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
        val postContent = summaryContent?.selectFirst("div.post-status")

        // Extract chapters - they're in a different location
        val chapters = doc.select("div.listing-chapters_wrap ul.main.version-chap li.wp-manga-chapter").map { el ->
            val link = el.selectFirst("a")
            val chapterName = link?.text()?.trim().orEmpty()
            val chapterNumber = chapterName.replace(Regex("[^\\d.]"), "").trim()
            val chapterUrl = link?.attr("href").orEmpty()

            val rawDate = el.selectFirst("span.chapter-release-date i")?.text()?.trim().orEmpty()

            val parsed = try {
                when {
                    rawDate.contains("saat", true) || rawDate.contains("ago", true) -> {
                        // "3 saat ago" or just "ago"
                        LocalDate.now()
                    }
                    rawDate.contains("gün", true) -> {
                        // "1 gün ago" or "2 gün ago"
                        val num = rawDate.filter { it.isDigit() }.toLongOrNull() ?: 1L
                        LocalDate.now().minusDays(num)
                    }
                    rawDate.contains("hafta", true) -> {
                        val num = rawDate.filter { it.isDigit() }.toLongOrNull() ?: 1L
                        LocalDate.now().minusWeeks(num)
                    }
                    rawDate.contains("ay", true) -> {
                        val num = rawDate.filter { it.isDigit() }.toLongOrNull() ?: 1L
                        LocalDate.now().minusMonths(num)
                    }
                    rawDate.contains("yıl", true) -> {
                        val num = rawDate.filter { it.isDigit() }.toLongOrNull() ?: 1L
                        LocalDate.now().minusYears(num)
                    }
                    rawDate.contains("/") -> {
                        // Date format: "13/10/2025" or "DD/MM/YYYY"
                        val parts = rawDate.split("/")
                        if (parts.size == 3) {
                            val day = parts[0].toIntOrNull() ?: 1
                            val month = parts[1].toIntOrNull() ?: 1
                            val year = parts[2].toIntOrNull() ?: LocalDate.now().year
                            LocalDate.of(year, month, day)
                        } else {
                            LocalDate.now()
                        }
                    }
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
        Log.i("asjlfhlasfaslkfjklasjfklasjfkasfasfasf",status.toString())
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
            ratingCount = "",
            description = fullDescription.toString(),
            otherNames = altName.orEmpty(),
            author = author,
            artist = artist,
            genres = genres,
            tags = emptyList(),
            yearOfProduction = year,
            status = status.toString(),
            favoritesCount = "",
            chapters = chapters,
            api = API,
            url = baseUrl,
            language = LANGUAGE
        )
    }
    override suspend fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)

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

        Log.i("jkafhdjkfdasjfdskfjsdfsfs", imgs.toString())
        return imgs
    }


    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language


}

