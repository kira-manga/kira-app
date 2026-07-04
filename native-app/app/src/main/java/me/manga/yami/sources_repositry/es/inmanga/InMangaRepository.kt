package me.manga.yamiapk.sources_repositry.es.inmanga


import android.util.Log
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
import me.manga.yamiapk.sources_repositry.common.SeparatedDetailsSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.es.inmanga.model.InMangaChapterDto
import me.manga.yamiapk.sources_repositry.es.inmanga.model.InMangaResultDto
import me.manga.yamiapk.sources_repositry.es.inmanga.model.InMangaResultObjectDto
import okhttp3.FormBody
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class InMangaRepository @Inject constructor(
    private val api: IMangaDataApiServices,
    private val dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
): SeparatedDetailsSites(dataStore, api, sourcesRepository) {

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        } }

    override val mangaSource: MangaSource
        get() = MangaSource.INMANGA

    override val homeUrl: String
        get() = "${baseUrl.ifBlank { BASE_URL }}manga/getMangasConsultResult"

    override val popularUrl: String
        get() = "${baseUrl.ifBlank { BASE_URL }}manga/getMangasConsultResult"

    override var imgBaseUrl: String = "https://pack-yak.intomanga.com/"
    override var imgUrlVersion: Int = 0

    override var homeGet: Boolean = false
    override var searchGet: Boolean = false

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }}manga/getMangasConsultResult"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "${baseUrl.ifBlank { BASE_URL }}manga/getMangasConsultResult"
    }

//    override suspend fun initSite(): Int {
//        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
//        _cachedHeaders = headers
//        return super.initSite()
//    }
    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() = _cachedHeaders ?: mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest"
        )

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        val skip = (page) * 20
        val sortBy = if (popular) "1" else "3" // 1 = Popular, 3 = Latest

        return FormBody.Builder()
            .add("filter[generes][]", "-1")
            .add("filter[queryString]", "")
            .add("filter[skip]", skip.toString())
            .add("filter[take]", "w0")
            .add("filter[sortby]", sortBy)
            .add("filter[broadcastStatus]", "0")
            .add("filter[onlyFavorites]", "false")
            .add("d", "")
            .build()
    }

    override fun createInfoUrl(mangaId: String): String {
        return "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}$mangaId"
    }

    override fun createChaptersUrl(mangaId: String): String {
        val mangaIdentification = mangaId.substringAfterLast("/")
        return "${baseUrl.ifBlank { BASE_URL }}chapter/getall?mangaIdentification=$mangaIdentification"
    }

    override fun handelSearchFormBody(
        page: Int,
        searchType: SearchType.Normal
    ): FormBody? {

        return FormBody.Builder()
            .add("filter[generes][]", "-1")
            .add("filter[queryString]", searchType.query)
            .add("filter[skip]", 0.toString())
            .add("filter[take]", "25")
            .add("filter[sortby]", "1")
            .add("filter[broadcastStatus]", "0")
            .add("filter[onlyFavorites]", "false")
            .add("d", "")
            .build()
    }

    override fun parseChapters(html: String): List<ChapterItem> {
        return try {
            val dataWrapper = jsonParser.decodeFromString<InMangaResultDto>(html)
            if (dataWrapper.data.isNullOrEmpty()) {
                return emptyList()
            }

            val result = jsonParser.decodeFromString<InMangaResultObjectDto<InMangaChapterDto>>(dataWrapper.data)
            if (!result.success) {
                return emptyList()
            }

            result.result.map { chapter ->
                ChapterItem(
                    number = chapter.friendlyChapterNumber ?: chapter.number?.toString() ?: "0",
                    name = "Chapter ${chapter.friendlyChapterNumber ?: chapter.number?.toString() ?: "0"}",
                    url = "/chapter/chapterIndexControls?identification=${chapter.identification}",
                    date = parseChapterDate(chapter.registrationDate)
                )
            }.reversed()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun parseChapterDate(dateString: String?): LocalDate? {
        return try {
            if (dateString.isNullOrEmpty()) return null
            LocalDate.parse(
                dateString.substringBefore("T"),
                DateTimeFormatter.ISO_LOCAL_DATE
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<MangaItem>()

        doc.select("body > a").forEach { element ->

                val title = element.select("h4.m0").text().trim()
            val url = element.attr("href").trim()
            val imageUrl = element.select("img").attr("data-src").ifEmpty {
                element.select("img").attr("src")
            }


            items += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = 0,
                chapters = listOf(),
                genres = emptyList()
            )
        }

        return items
    }

    override fun extractMangaList(html: String): List<PopularManga> {
       return extractHomeMangaItems(html).toPopularMangaList()
    }

    override fun extractMangaInfo(html: String, baseUrl: String, combinUrl: String): MangaInfo {
        val document = Jsoup.parse(html)

        // Extract title
        val title = document.select("div.col-md-9 h1").text().trim()

        // Extract thumbnail
        val thumbnailUrl = document.select("div.col-md-3 div.panel.widget img").attr("src")

        // Extract description
        val description = document.select("div.col-md-9 div.panel-body").text().trim()

        // Extract status
        val statusText = document.select("div.col-md-3 a.list-group-item:contains(estado) span").text().trim()
        val status = when {
            statusText.contains("En emisión") -> "ONGOING"
            statusText.contains("Finalizado") -> "COMPLETED"
            else -> "UNKNOWN"
        }
        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = combinUrl,
            title = title,
            imageUrl = thumbnailUrl,
            rating = "0",
            ratingCount = "0",
            description = description,
            otherNames = "",
            author = "",
            artist = "",
            genres = emptyList(),
            tags = emptyList(),
            yearOfProduction = "",
            status = status,
            favoritesCount = "0",
            chapters = mutableListOf()
        )
    }

    override fun getSearchResults(html: String): List<MangaItem> {
       return extractHomeMangaItems(html)
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        _cachedHeaders = newHeaders
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun getChapterImages(html: String): List<String> {
        return try {
            val doc = Jsoup.parse(html)

            val cid = doc.selectFirst("#ChapterIdentification")?.attr("value")?.trim().orEmpty()
            val mid = doc.selectFirst("#MangaIdentification")?.attr("value")?.trim().orEmpty()

            if (cid.isBlank() || mid.isBlank()) return emptyList()

            // الأفضل: نجيب الـ image ids من PageList لأنها أكيد كاملة حتى لو الصور lazy
            val pageIds = doc.select("#PageList option")
                .mapNotNull { it.attr("value").trim() }
                .filter { it.isNotBlank() }
                .distinct()

            pageIds.map { imageId ->
                "https://cdn1.intomanga.com/i/m/$mid/c/$cid/o/$imageId.jpg"
            }

        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }


    fun logBig(tag: String, text: String) {
        val chunkSize = 3000 // آمن أقل من limit
        var i = 0
        while (i < text.length) {
            val end = (i + chunkSize).coerceAtMost(text.length)
            Log.i(tag, text.substring(i, end))
            i = end
        }
    }

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language
}

