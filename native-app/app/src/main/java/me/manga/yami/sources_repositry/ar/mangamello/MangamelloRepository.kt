package me.manga.yamiapk.sources_repositry.ar.mangamello

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.size.Dimension
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.emptyMangaInfo
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.ar.mangamello.models.chapters.DataCh
import me.manga.yamiapk.sources_repositry.ar.mangamello.models.chapters.MelloChapters
import me.manga.yamiapk.sources_repositry.ar.mangamello.models.home.Data
import me.manga.yamiapk.sources_repositry.ar.mangamello.models.home.MelloHome
import me.manga.yamiapk.sources_repositry.ar.mangamello.models.info.DataIn
import me.manga.yamiapk.sources_repositry.ar.mangamello.models.info.MelloInfo
import me.manga.yamiapk.sources_repositry.ar.mangamello.models.pages.MelloPages
import me.manga.yamiapk.sources_repositry.ar.mangamello.models.search.DataSh
import me.manga.yamiapk.sources_repositry.ar.mangamello.models.search.MelloSearch
import me.manga.yamiapk.sources_repositry.common.NormalSites
import me.manga.yamiapk.sources_repositry.common.SeparatedDetailsSites
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.en.comick_io.models.home.ComickItem
import okhttp3.FormBody
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import me.manga.yamiapk.core.states.State


class MangamelloRepository  @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): SeparatedDetailsSites(dataStore,api,sourcesRepository,) {
    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        } }
    private  val TAG = "MelloParser"


    override var imgBaseUrl: String = "https://raw.githubusercontent.com/"
    override var imgUrlVersion: Int = 0
    override val mangaSource: MangaSource
        get() = MangaSource.MANGAMELLO
    override val BASE_URL: String by lazy { "${baseUrl.ifBlank { mangaSource.BASEURL }}api/v1/mangas/" }
    override val API: String = mangaSource.API
    override val LANGUAGE: String by lazy {  mangaSource.LANGUAGE.Language }
    override val homeUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL}}api/v1/mangas?sort_by=updated_at&page=1" }

    override val popularUrl: String by lazy { "${baseUrl.ifBlank { BASE_URL}}api/v1/mangas?sort_by=views&page=1"  }

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { BASE_URL }.dropTrailingSlash()}api/v1/mangas?sort_by=updated_at&page=$page"
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal  -> "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas/search?per_page=40&title=${searchType.query}"
            is SearchType.GENRES  -> ""
            is SearchType.SORT    -> ""
        }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()


    @Volatile
    private var _cachedHeaders: Map<String, String>? = null


    val refererHeader = mapOf(
        "accept" to "application/json",
//        "accept-encoding" to "gzip",
        "authorization" to "Bearer null",
        "content-type" to "application/json",
        "host" to "plus.mangamello.com",
        "installer" to "com.google.android.packageinstaller",
        "user-agent" to "Dart/3.3 (dart:io)",
        "vsesion" to "1.1.7",
        "zone" to java.util.TimeZone.getDefault().id
    )

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures your value wins:
            return base + refererHeader
        }


    override fun handelFormBody(page: Int, popular: Boolean): FormBody? {
        return null
    }

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchDataWithHeaders({
            val normalized = normalizeLegacyUrl(url)
            val fullUrl =
                if (normalized.startsWith("http", ignoreCase = true)) normalized
                else "${baseUrl.ifBlank { BASE_URL }}$normalized"

            api.get(fullUrl, defaultHeaders)
        }) { html ->
            getChapterImages(html)
        }
    override fun createInfoUrl(mangaId: String): String {
        return normalizeLegacyUrl(mangaId)
    }

    override fun createChaptersUrl(mangaId: String): String {
        val fixed = normalizeLegacyUrl(mangaId)
        return "$fixed/chapters?per_page=2000"
    }



    override fun handelSearchFormBody(page: Int, searchType: SearchType.Normal): FormBody? {
        return null
    }



    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // Option A: merge here so that cache always has Referer, and if you persist headers you want to include it
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged

        dataStore.saveHeadersForApi(API, merged)

    }
    override fun getChapterImages(json: String): List<String> {
        return try {
            val items: MelloPages = jsonParser.decodeFromString(json)
            items.toImageUrlList()
        } catch (e: Exception) {
            Log.e(TAG, "getChapterImages: failed to parse chapter images: ${e.message}", e)
            emptyList()
        }
    }


    override fun parseChapters(json: String): List<ChapterItem> {
        return try {
            val items: MelloChapters = jsonParser.decodeFromString(json)
            items.data.toChapterItems()
                .sortedBy { it.number.toDoubleOrNull() }
                .reversed()
        } catch (e: Exception) {
            Log.e(TAG, "parseChapters: failed to parse chapters: ${e.message}", e)
            emptyList()
        }
    }

    override fun extractHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val items: MelloHome = jsonParser.decodeFromString(json)
            val mangas = items.data
            mangas.toMangaItems().toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "extractHomeMangaItems: failed to parse home manga items: ${e.message}", e)
            mutableListOf()
        }
    }

    override fun extractMangaList(json: String): List<PopularManga> {
        return try {
            val items: MelloHome = jsonParser.decodeFromString(json)
            val mangas = items.data
            mangas.toPopularManga()
        } catch (e: Exception) {
            Log.e(TAG, "extractMangaList: failed to parse manga list: ${e.message}", e)
            emptyList()
        }
    }

    override fun extractMangaInfo(json: String, baseUrl: String, combinUrl: String): MangaInfo {
        return try {
            val items: MelloInfo = jsonParser.decodeFromString(json)
            items.data?.toMangaInfo(baseUrl) ?: emptyMangaInfo
        } catch (e: Exception) {
            Log.e(TAG, "extractMangaInfo: failed to parse manga info: ${e.message}", e)
            emptyMangaInfo
        }
    }

    override fun getSearchResults(json: String): List<MangaItem> {
        return try {
            val items: MelloSearch = jsonParser.decodeFromString(json)
            items.data.toSearchMangaItems()
        } catch (e: Exception) {
            Log.e(TAG, "getSearchResults: failed to parse search results: ${e.message}", e)
            emptyList()
        }
    }

    fun List<DataCh?>?.toChapterItems(

        dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    ): List<ChapterItem> = this
        .orEmpty()                         // if list is null, treat as empty
        .mapNotNull { data ->
            data ?: return@mapNotNull null
            // require both manga_id and id to build a URL
            val mangaId = data.manga_id ?: return@mapNotNull null
            val chapterId = data.id ?: return@mapNotNull null

            ChapterItem(
                number = data.order?.toString()
                    ?: data.title?.toInt().toString() ?: data.title.orEmpty()          // fallback if order is null
                ,
                name = data.title.orEmpty(),
                url  = "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas/$mangaId/chapters/$chapterId?relations=chapterImages",
                date = data.created_at
                    ?.let { dateFormatter.parse(it) }
                    ?.let { LocalDate.from(it) },
                isDownloaded  = false,
                isBookmarked  = false,
                chaptersImages = emptyList()
            )
        }


    fun List<Data?>?.toPopularManga(
    ): List<PopularManga> = this
        .orEmpty()
        .mapNotNull { data ->
            data ?: return@mapNotNull null
            // build the detail page URL however your backend expects:
            val mangaUrl = "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas/${data.id}"
            PopularManga(
                api      = API,
                language = LANGUAGE,
                title    = data.title.orEmpty(),
                url      = mangaUrl,
                imageUrl = data.img.orEmpty()
            )
        }
    fun MelloPages.toImageUrlList(): List<String> =
        this.data
            ?.chapterImages
            .orEmpty()
            .mapNotNull { img ->
                img?.src
                    ?.takeIf { it.isNotBlank() }
                    ?: img?.originalSrc
                        ?.takeIf { it.isNotBlank() }
            }

    fun List<Data?>?.toMangaItems(
    ): List<MangaItem> {
        return this
            .orEmpty()                                // turn null into empty list
            .mapNotNull { data ->
                data ?: return@mapNotNull null       // skip null entries
                // build the URL to your manga details page however your API uses it:
                val mangaUrl = "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas/${data.id}"

                MangaItem(
                    api         = API,
                    language    = LANGUAGE,
                    title       = data.title.orEmpty(),
                    url         = mangaUrl,
                    imageUrl    = data.img.orEmpty(),
                    rating      = data.rate?.toInt(),        // or use ten_rate, average_rate, etc.
                    chapters    = emptyList(),               // you'll fill this later when you fetch chapters
                    genres      = emptyList()                // same here — map your genres when you have them
                )
            }
    }
    fun DataIn.toMangaInfo(
        url: String,
        genres: List<String> = emptyList(),
        tags:   List<String> = emptyList(),
        chapters: MutableList<ChapterItem> = mutableListOf()
    ): MangaInfo = MangaInfo(
        api              = API,
        language         = LANGUAGE,
        url              = url,
        title            = title.orEmpty(),
        imageUrl         = img.orEmpty(),
        // you can choose whichever “rate” makes sense: avg, ten-point or zero-to-five
        rating           = ten_rate?.toString().orEmpty(),
        ratingCount      = views?.toString().orEmpty(),
        description      = summary.orEmpty(),
        otherNames       = "",                       // DataIn doesn’t have alt-titles
        author           = "",
        artist           = "",
        genres           = genres,
        tags             = tags,
        yearOfProduction = year.orEmpty(),
        // convert your status integer (e.g. 0/1) into a human string if you like
        status           = when (is_completed) {
            1 -> "مكتمل"
            else -> "مستمر"
        },
        favoritesCount   = last?.toString().orEmpty(), // DataIn.last could mean favorite-count?
        chapters         = chapters
    )
    fun List<DataSh?>?.toSearchMangaItems(
    ): List<MangaItem> = this
        .orEmpty()               // treat null list as empty
        .mapNotNull { dto ->
            dto ?: return@mapNotNull null
            val id = dto.id ?: return@mapNotNull null

            MangaItem(
                api       = API,
                language  = LANGUAGE,
                title     = dto.title.orEmpty(),
                url       = "${baseUrl.ifBlank { BASE_URL }}api/v1/mangas/$id",
                imageUrl  = dto.img.orEmpty(),
                rating    = dto.average_rate?.toInt(),
                chapters  = listOf(),
                genres    = dto.genres
                    .orEmpty()                       // null → emptyList
                    .mapNotNull { it?.name }         // skip null genres
            )
        }

    override fun buildItemsImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int
    ): ImageRequest {
        val coilHeaders = NetworkHeaders.Builder()
            .apply {
//                defaultHeaders.forEach { (key, value) ->
//                    add(key, value)
//                }
            }
            .build()

        return ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(coilHeaders)

            .crossfade(true)
            .build()
    }


    private fun normalizeLegacyUrl(url: String): String {
        return url
    }
    override fun buildImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int

    ): ImageRequest {
        val coilHeaders = NetworkHeaders.Builder()
            .apply {
//                defaultHeaders.forEach { (key, value) ->
//                    add(key, value)
//                    Log.i("AddingHeader", "$key: $value")
//                }
            }
            .build()

        return ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(coilHeaders)
            .apply {
                if (screenWidthPx != 0){
                    size(Dimension.Pixels(screenWidthPx), Dimension.Undefined)

                }
            }
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(true)
            .build()
    }

}