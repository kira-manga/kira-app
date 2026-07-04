package me.manga.kira.sources_repositry.ru.mangahub

/**
 * Migration note (Phase 7.8): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * Gson -> kotlinx.serialization, @Inject dropped, android.util.Log -> Kermit Logger,
 * java.time -> kotlinx.datetime.
 *
 * Chapter-date parsing uses kotlinx.datetime `LocalDate.Format` (numeric `dd.MM.yyyy`) — no
 * locale-aware text months needed for this site, so the port stays mechanical.
 */

import co.touchlab.kermit.Logger
import com.fleeksoft.ksoup.Ksoup
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.todayIn
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.common.SeparatedDetailsSites
import me.manga.kira.sources_repositry.data.MangaSource

@OptIn(ExperimentalTime::class)
class MangahubRepository(
    private val dataStore: DataStoreHelper,
    private val api: ApiClient,
    sourcesRepository: SourcesDao,
) : SeparatedDetailsSites(api, sourcesRepository) {


    override val mangaSource: MangaSource
        get() = MangaSource.MANGAHUB
    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    override var imgBaseUrl: String = "https://p1.statichub.org/"
    override var imgUrlVersion: Int = 0

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    val userAgentRandomizer: String
        get() = (100..999).random().toString()

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null


    override val defaultHeaders: Map<String, String> by lazy {
        val baseHeaders = _cachedHeaders ?: emptyMap()

        // Return a new map with existing headers + updated User-Agent and Referer
        baseHeaders.toMutableMap()
            .apply {
                put(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 " +
                        "Safari/537.36 Edg/100.0.$userAgentRandomizer",
                )
                put("Referer", baseUrl.ifBlank { BASE_URL })
            }
    }

    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}explore/genres-is-nor-erotica-nor-omegavers-nor-shoujo_ai-nor-shounen_ai-nor-yaoi-nor-yuri/sort-is-update" }
    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL }}explore/genres-is-nor-erotica-nor-omegavers-nor-shoujo_ai-nor-shounen_ai-nor-yaoi-nor-yuri/sort-is-rating" }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // persist in background
        dataStore.saveHeadersForApi(API, newHeaders)

        // update the cache immediately
        _cachedHeaders = newHeaders
    }

    override fun getChapterImages(html: String): List<String> {
        val document = Ksoup.parse(html)
        val images = document.select("img.reader-viewer-img")
        return images.map {
            it.attr("data-src").let { if (it.startsWith("//")) "https:$it" else it }
        }
    }

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}explore/genres-is-nor-erotica-nor-omegavers-nor-shoujo_ai-nor-shounen_ai-nor-yaoi-nor-yuri/sort-is-update?page=$page"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { BASE_URL }}search/title?query=${searchType.toNormalQuery()}"
    }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf("Хентай", "Hentai")

    override fun handelFormBody(page: Int, popular: Boolean): Map<String, String>? {
        return null
    }

    override fun createInfoUrl(mangaId: String): String {
        return mangaId
    }

    override fun createChaptersUrl(mangaId: String): String {
        return "${mangaId}/chapters"
    }

    override fun handelSearchFormBody(page: Int, searchType: SearchType.Normal): Map<String, String>? {
        return null
    }


    override fun getSearchResults(html: String): List<MangaItem> {
        return extractHomeItems(html)
    }


    override fun extractMangaList(html: String): List<PopularManga> {
        return extractHomeItems(html).toPopularMangaList()
    }


    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        return extractHomeItems(html)
    }

    fun extractHomeItems(html: String): MutableList<MangaItem> {
        val doc = Ksoup.parse(html)
        val items = mutableListOf<MangaItem>()

        // Each manga tile is wrapped in a <div class="item-grid" id="title_...">…
        val elements = doc.select("div.item-grid")  // :contentReference[oaicite:3]{index=3}

        for (el in elements) {
            // The main link around the cover
            val linkEl = el.selectFirst("a.d-block.position-relative")

            val detailUrl = el.selectFirst("a.fw-medium")?.attr("href").orEmpty()

            // Cover image
            val imgEl = linkEl?.selectFirst("img.item-grid-image")
            val imageUrl = imgEl?.absUrl("src").orEmpty()

            // Numeric rating (e.g. "8.5")
            val rating = linkEl
                ?.selectFirst("div.label-rating")
                ?.text()
                ?.toDoubleOrNull()

            // Title text and its own link
            val titleAnchor = el.selectFirst("div.text-line-clamp a.fw-medium")
            val title = titleAnchor?.text()?.trim().orEmpty()
            val titleUrl = titleAnchor?.absUrl("href").orEmpty()



            items += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = titleUrl.ifEmpty { "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$detailUrl" },
                imageUrl = imageUrl,
                rating = rating?.toInt(),
                chapters = emptyList(),     // no chapter info on catalog page
                genres = emptyList(),       // no genre tags here
            )
        }

        return items
    }

    override fun extractMangaInfo(html: String, baseUrl: String, combinUrl: String): MangaInfo {

        val document = Ksoup.parse(html)

// 1) Use selectFirst with a more specific path
        val title = document
            .selectFirst("div.text-line-clamp span.align-middle")
            ?.text()
            ?: "No title"

        val otherNames = document.select("#Judul p.j2").text().trim()
        val description = document.selectFirst(".markdown-style.text-expandable-content")?.text()
        val thumbnailUrl = document.select("img.cover-detail").attr("src")
        val author =
            document.select("table.inftable tr:has(td:contains(Pengarang)) td:nth-child(2)").text()
                .trim()
        val statusElement = document.selectFirst(".attr-name:contains(Томов) + .attr-value")?.text()
        val status = when {
            statusElement?.contains("продолжается", ignoreCase = true) == true -> "Продолжается"
            statusElement?.contains("приостановлен", ignoreCase = true) == true -> "Приостановлен"
            statusElement?.contains(
                "завершен",
                ignoreCase = true,
            ) == true || statusElement?.contains("выпуск прекращён", ignoreCase = true) == true ->
                if (document
                        .selectFirst(".attr-name:contains(Перевод) + .attr-value")
                        ?.text()
                        ?.contains("Завершен", ignoreCase = true) == true
                ) {
                    "Завершен"
                } else {
                    "Выпуск завершён"
                }

            else -> "Неизвестно"
        }


        val genreElements = document.select(".tags a")
        val genres = genreElements.map { it.text().trim() }




        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = baseUrl,
            title = title,
            imageUrl = thumbnailUrl,
            rating = "",
            description = description.toString(),
            author = author,
            genres = genres,
            status = status,
            chapters = mutableListOf(),
        )
    }

    override fun parseChapters(html: String): List<ChapterItem> {
        val document = Ksoup.parse(html)
        val chapterRows = document.select("div.py-2.px-3")
        val chapters = mutableListOf<ChapterItem>()

        for (i in 1 until chapterRows.size) {
            val row = chapterRows[i]
            val chapterLink = row.selectFirst("div.align-items-center > a")
            val chapterTitle = chapterLink?.text()?.trim()
            val chapterHref = chapterLink?.attr("href")
            val chapterUrl = if (chapterHref?.startsWith("http") == true) {
                chapterHref
            } else {
                baseUrl.ifBlank { BASE_URL }.removeSuffix("/") + chapterHref
            }
            val dateText = row.selectFirst("div.text-muted")?.text()?.trim().orEmpty()
            val localDate = runCatching {
                LocalDate.parse(dateText, slashOrDotDateFormatter)
            }.getOrElse { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

            chapters += ChapterItem(
                name = chapterTitle.orEmpty(),
                date = localDate,
                number = chapterTitle?.replace("Chapter", "")?.trim().orEmpty(),
                url = chapterUrl,
            )
        }

        return chapters
    }

    private fun List<MangaItem>.toPopularMangaList(): List<PopularManga> = this.map {
        PopularManga(
            api = it.api,
            language = it.language,
            title = it.title,
            url = it.url,
            imageUrl = it.imageUrl,
        )
    }

    // dd.MM.yyyy — purely numeric, no locale dependency. Source used
    // `DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US)`.
    private val slashOrDotDateFormatter = LocalDate.Format {
        day()
        char('.')
        monthNumber()
        char('.')
        year()
    }

}
