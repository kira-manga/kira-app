package me.manga.yamiapk.sources_repositry.ru.mangahub

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
import me.manga.yamiapk.sources_repositry.common.SeparatedDetailsSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

class MangahubRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
) : SeparatedDetailsSites(dataStore, api, sourcesRepository) {


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
                        "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 " +
                                "Safari/537.36 Edg/100.0.$userAgentRandomizer"
                    )
                    put("Referer", baseUrl.ifBlank { BASE_URL })
                }
        }

    override val homeUrl: String by lazy {   "${baseUrl.ifBlank { BASE_URL }}explore/genres-is-nor-erotica-nor-omegavers-nor-shoujo_ai-nor-shounen_ai-nor-yaoi-nor-yuri/sort-is-update"}
    override val popularUrl: String  by lazy {    "${baseUrl.ifBlank { BASE_URL }}explore/genres-is-nor-erotica-nor-omegavers-nor-shoujo_ai-nor-shounen_ai-nor-yaoi-nor-yuri/sort-is-rating"}

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // persist in background
            dataStore.saveHeadersForApi(API, newHeaders)

        // update the cache immediately
        _cachedHeaders = newHeaders
    }

    override fun getChapterImages(html: String): List<String> {
        val document = Jsoup.parse(html)
        val images = document.select("img.reader-viewer-img")
            return images.map {
               it.attr("data-src").let { if (it.startsWith("//")) "https:$it" else it }
            } }

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

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        return null
    }

    override fun createInfoUrl(mangaId: String): String {
        return mangaId
    }

    override fun createChaptersUrl(mangaId: String): String {
        return "${mangaId}/chapters"
    }

    override fun handelSearchFormBody(page: Int, searchType: SearchType.Normal): FormBody? {
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
        val doc = Jsoup.parse(html)
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

            // Numeric rating (e.g. “8.5”)
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
                genres = emptyList()      // no genre tags here
            )
        }

        return items
    }

    override fun extractMangaInfo(html: String, baseUrl: String, combinUrl: String): MangaInfo {

        val document = Jsoup.parse(html)

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
                ignoreCase = true
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
            ratingCount = "",
            description = description.toString(),
            otherNames = otherNames,
            author = author,
            artist = "",
            genres = genres,
            tags = emptyList(),
            yearOfProduction = "",
            status = status,
            favoritesCount = "",
            chapters = mutableListOf()
        )
    }

    override fun parseChapters(html: String): List<ChapterItem> {
        val document = Jsoup.parse(html)
        val chapterRows = document.select("div.py-2.px-3")
        val chapters = mutableListOf<ChapterItem>()
        val javaTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US)

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
                LocalDate.parse(dateText, javaTimeFormatter)
            }.getOrElse { LocalDate.now() }

            chapters += ChapterItem(
                name = chapterTitle.orEmpty(),
                date = localDate,
                number = chapterTitle?.replace("Chapter", "")?.trim().orEmpty(),
                url = chapterUrl
            )
        }

        return chapters
    }

}