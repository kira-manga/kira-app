package me.manga.yamiapk.sources_repositry.pt.mediocretoons

import android.util.Log
import kotlinx.serialization.json.Json
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.emptyMangaInfo
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
import me.manga.yamiapk.sources_repositry.pt.mediocretoons.models.Capitulo
import me.manga.yamiapk.sources_repositry.pt.mediocretoons.models.MangaData
import me.manga.yamiapk.sources_repositry.pt.mediocretoons.models.MediocretoonsChapter
import me.manga.yamiapk.sources_repositry.pt.mediocretoons.models.MediocretoonsHome
import me.manga.yamiapk.sources_repositry.pt.mediocretoons.models.MediocretoonsMangaInfo

import okhttp3.FormBody
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlin.collections.plus

class MediocretoonsRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
): NormalSitesv2(dataStore,api,sourcesRepository){
    private val dateFormatter = DateTimeFormatter.ISO_DATE_TIME

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        } }

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override val mangaSource: MangaSource
        get() = MangaSource.MEDIOCRETOONS


    val chapterimgUrl = "https://storage.mediocretoons.com/"
    override var imgBaseUrl: String = "https://cdn.mediocretoons.site/"
    override var imgUrlVersion: Int = 0

    override val BASE_URL: String
        get() = mangaSource.BASEURL
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() = mangaSource.LANGUAGE.Language

    private val apiUrl by lazy { "${baseUrl.ifBlank { BASE_URL }}obras"}

    override val homeUrl: String by lazy { "$apiUrl?limite=40&pagina=1&formato=5" }
    override val popularUrl: String by lazy { "$apiUrl?limite=40&pagina=1&formato=" }


    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    /**
     * Just like your old `defaultHeaders` – will block once on first call,
     * then return the in‑memory copy thereafter.
     */
    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures your value wins:
            return base + customHeaders
        }

    private val customHeaders = mapOf(
        "Referer" to "https://mediocretoons.com",
        "Origin" to "https://mediocretoons.com",
        "Accept" to "application/json"
    )

    override fun handelLoadMoreUrl(page: Int): String {
        return "$apiUrl?limite=50&pagina=$page&formato=5"
    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return "$apiUrl?limite=10&pagina=1&temCapitulo=true&string=${searchType.toNormalQuery()}"
    }

    override val sortTypes: Set<String>
        get() = setOf()
    override val allGenres: Set<String>
        get() = setOf()
    override val blackListGenres: Set<String>
        get() = setOf()

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

    override fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem> {
        return mutableListOf()
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        return try {
            Log.i("fghdsflgsdfgdfgdfsgsdfgdfg1",html.toString())
            val apiResponse = jsonParser.decodeFromString<MediocretoonsHome>(html)
            apiResponse.toMangaItems().toMutableList()
        } catch (e: Exception) {
            Log.i("fghdsflgsdfgdfgdfsgsdfgdfg2",e.toString())

            e.printStackTrace()
            mutableListOf()
        }
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        return try {
            val apiResponse = jsonParser.decodeFromString<MediocretoonsHome>(html)
            apiResponse.toMangaItems().toMutableList().toPopularMangaList()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    override suspend fun extractMangaInfo(html: String, baseUrl: String): MangaInfo {
        return try {
            val apiResponse = jsonParser.decodeFromString<MediocretoonsMangaInfo>(html)
            apiResponse.toMangaInfo()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMangaInfo
        }
    }

    override suspend fun getSearchResults(html: String): List<MangaItem> {
        return try {
            val apiResponse = jsonParser.decodeFromString<MediocretoonsHome>(html)
            apiResponse.toMangaItems().toMutableList()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + customHeaders

        _cachedHeaders = merged
        dataStore.saveHeadersForApi(API, newHeaders)
    }

    override fun getChapterImages(html: String): List<String> {
        return try {
            val chapter = jsonParser.decodeFromString<MediocretoonsChapter>(html)
            chapter.paginas?.map { page ->
                buildImageUrl(chapter.obra?.id ?: 0, chapter.numero ?: "0", page.src ?: "")
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            listOf()
        }
    }

    private fun buildImageUrl(mangaId: Int, chapterNum: String, imageName: String): String {
        return "${imgBaseUrl}obras/$mangaId/capitulos/$chapterNum/$imageName"
    }

    private fun MediocretoonsHome.toMangaItems(): List<MangaItem> =
        data?.filterNotNull()?.map { it.toMangaItem() } ?: emptyList()

    private fun MangaData.toMangaItem(): MangaItem =
        MangaItem(
            api = API,
            language = LANGUAGE,
            title = nome ?: "",
            url = "${baseUrl.ifBlank { BASE_URL }}obras/$id",
            imageUrl = if (!imagem.isNullOrBlank()) "https://cdn.mediocretoons.site/obras/${id}/${imagem}" else "https://mediocretoons.site/logo.png",
            rating = null,
            genres = tags?.mapNotNull { it?.nome }?.filter { it.isNotBlank() } ?: emptyList(),
            chapters = emptyList()
        )

    private fun MediocretoonsMangaInfo.toMangaInfo(): MangaInfo =
        MangaInfo(
            api = API,
            language = LANGUAGE,
            url = "${baseUrl.ifBlank { BASE_URL }}obras/$id",
            title = nome ?: "",
            imageUrl = if (!imagem.isNullOrBlank()) "https://cdn.mediocretoons.site/obras/${id}/${imagem}" else "https://mediocretoons.site/logo.png",
            rating = "",
            ratingCount = "",
            description = descricao ?: "",
            otherNames = "",
            author = agente?.nome ?: "",
            artist = "",
            genres = tags?.mapNotNull { it?.nome }?.filter { it.isNotBlank() } ?: emptyList(),
            tags = emptyList(),
            yearOfProduction = criada_em?.substring(0, 4) ?: "",
            status = status?.nome ?: "",
            favoritesCount = total_usuarios_lendo?.toString() ?: "",
            chapters = capitulos?.mapNotNull { chapter ->
                chapter?.toChapterItem()
            }?.toMutableList() ?: mutableListOf()
        )

    private fun Capitulo.toChapterItem(): ChapterItem {
        // Prefer lancado_em (release date) over criado_em (created date)
        val chapterDate = parseDate(lancado_em) ?: parseDate(criado_em)

        return ChapterItem(
            number = numero ?: "0",
            name = nome ?: "Chapter ${numero ?: "0"}",
            url = "${baseUrl.ifBlank { BASE_URL }}capitulos/${id ?: 0}",
            date = chapterDate,
            isDownloaded = false,
            isBookmarked = false,
            chaptersImages = emptyList()
        )
    }

    private fun parseDate(dateString: String?): LocalDate? {
        return try {
            if (dateString.isNullOrBlank()) return null
            val dateTime = LocalDateTime.parse(dateString, dateFormatter)
            dateTime.toLocalDate()
        } catch (e: DateTimeParseException) {
            Log.w("MediocretoonsRepo", "Failed to parse date: $dateString", e)
            null
        }
    }
}