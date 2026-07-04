package me.manga.yamiapk.sources_repositry.es.manhwaweb

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.common.NormalSitesv2
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.es.manhwaweb.models.chaptes.ChaptersResponse
import me.manga.yamiapk.sources_repositry.es.manhwaweb.models.home.ManhwasEsp
import me.manga.yamiapk.sources_repositry.es.manhwaweb.models.home.ManhwawebResponse
import me.manga.yamiapk.sources_repositry.es.manhwaweb.models.info.InfoResponse
import me.manga.yamiapk.sources_repositry.es.manhwaweb.models.library.Data
import me.manga.yamiapk.sources_repositry.es.manhwaweb.models.library.LibraryResponse
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

class ManhwawebEsRepository @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
    ): NormalSitesv2(dataStore,api,sourcesRepository){


    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        } }
    override val mangaSource: MangaSource
        get() = MangaSource.MANHOWAWEB

    override val homeUrl: String by lazy { "${baseUrl.ifBlank { "https://manhwawebbackend-production.up.railway.app" }}/latest/new-manhwa" }
    override val popularUrl: String
        get() = ""

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }
    override val BASE_URL: String
        get() = "https://manhwawebbackend-production.up.railway.app"
    override val API: String
        get() = mangaSource.API
    override val LANGUAGE: String
        get() =  mangaSource.LANGUAGE.Language

    override var imgBaseUrl: String = "https://imagizer.imageshack.com/"
    override var imgUrlVersion: Int = 0
   override var customParseHome = true
    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            // Merge cached headers with Referer; if cached also contains "Referer", this ensures your value wins:
            return base
        }


    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // Option A: merge here so that cache always has Referer, and if you persist headers you want to include it
        _cachedHeaders = newHeaders


        dataStore.saveHeadersForApi(API, newHeaders)

    }
    override fun handelLoadMoreUrl(page: Int): String {
        val url = "${baseUrl.ifBlank { BASE_URL }}/manhwa/library".toHttpUrl().newBuilder()
            .addQueryParameter("tipo", "")
            .addQueryParameter("demografia", "")
            .addQueryParameter("estado", "")
            .addQueryParameter("erotico", "no")
            .addQueryParameter("order_dir", "desc")
            .addQueryParameter("order_item", "alfabetico")
            .addQueryParameter("page", page.toString())

        return url.toString()

    }

    override fun handelSearchUrl(searchType: SearchType): String {
        return when (searchType) {


            is SearchType.Normal ->"${baseUrl.ifBlank { BASE_URL }}/manhwa/library?buscar=${searchType.query}&tipo=&demografia=&estado=&erotico=no&order_dir=desc&order_item=alfabetico&page=0"

            is SearchType.GENRES ->  "${baseUrl.ifBlank { BASE_URL }}/manhwa/library?buscar=${searchType.query}&tipo=&demografia=&estado=&erotico=no&generes=${getGeneroValue(searchType.genres)}&order_dir=desc&order_item=alfabetico&page=0"

            is SearchType.SORT -> "${baseUrl.ifBlank { BASE_URL }}/manhwa/library?buscar=${searchType.query}&tipo=&demografia=&estado=&erotico=no&generes=${getGeneroValue(searchType.genres)}&order_dir=desc&order_item=${getSortTypeComment(searchType.sortType)}&page=0"

        }
        }

    override val sortTypes: Set<String>
        get() = setOf(
            "Alphabetical" ,//alfabetico
            "Creation",//creacion
            "Number of Chapters" //num_chapter
        )

    fun getSortTypeComment(sortType: String): String? {
        val map = mapOf(
            "Alphabetical" to "alfabetico",
            "Creation" to "creacion",
            "Number of Chapters" to "num_chapter"
        )
        return map[sortType]
    }
    override val allGenres: Set<String>
        get() = setOf(
            "Acción",               // Action
            "Aventura",             // Adventure
            "Comedia",              // Comedy
            "Drama",                // Drama
            "Recuentos de la vida", // Slice of Life
            "Romance",              // Romance
            "Venganza",             // Revenge
            "Harem",                // Harem
            "Fantasía",             // Fantasy
            "Sobrenatural",         // Supernatural
            "Tragedia",             // Tragedy
            "Psicológico",          // Psychological
            "Horror",               // Horror
            "Thriller",             // Thriller
            "Historias cortas",     // Short Stories
            "Gore",                 // Gore
            "Reencarnación",        // Reincarnation
            "Sistema de niveles",   // Level System
            "Ciencia ficción",      // Science Fiction
            "Apocalíptico",         // Apocalyptic
            "Artes marciales",      // Martial Arts
            "Superpoderes",         // Superpowers
            "Cultivación (cultivo)",// Cultivation
        )

    fun getGeneroValue(genero: String): String? {
        val generosMap = mapOf(
            "Acción" to "3",
            "Aventura" to "29",
            "Comedia" to "18",
            "Drama" to "1",
            "Recuentos de la vida" to "42",
            "Romance" to "2",
            "Venganza" to "5",
            "Harem" to "6",
            "Fantasía" to "23",
            "Sobrenatural" to "31",
            "Tragedia" to "25",
            "Psicológico" to "43",
            "Horror" to "32",
            "Thriller" to "44",
            "Historias cortas" to "28",
            "Ecchi" to "30",
            "Gore" to "34",
            "Girls love" to "27",
            "Boys love" to "45",
            "Reencarnación" to "41",
            "Sistema de niveles" to "37",
            "Ciencia ficción" to "33",
            "Apocalíptico" to "38",
            "Artes marciales" to "39",
            "Superpoderes" to "40",
            "Cultivación (cultivo)" to "35",
            "Milf" to "8"
        )
        return generosMap[genero]
    }

    override val blackListGenres: Set<String>
        get() = setOf(
//            "Ecchi",                // Ecchi
            "Girls love",           // Girls' Love / Yuri
            "Boys love",            // Boys' Love / Yaoi
            "Milf",                 // MILF

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

    override fun extractCustomHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val dilarItems: LibraryResponse = jsonParser.decodeFromString(json)
            val items = dilarItems.data.toMangaItemsLib()

                items
        } catch (e: Exception) {
            mutableListOf()
        }

    }

    override fun extractHomeMangaItems(json: String): MutableList<MangaItem> {
        return try {
            val dilarItems: ManhwawebResponse = jsonParser.decodeFromString(json)
            val items = dilarItems.manhwas?.manhwas_esp.toMangaItems()
            if (items.isNullOrEmpty()){
                mutableListOf()

            }else{
                items
            }

        } catch (e: Exception) {
            mutableListOf()
        }
    }

    override fun extractMangaList(string: String): List<PopularManga> {
       return listOf()
    }

    override suspend fun extractMangaInfo(
        json: String,
        baseUrl: String
    ): MangaInfo {
        return try {
            val dilarItems: InfoResponse = jsonParser.decodeFromString(json)

           val info = dilarItems.toMangaInfo()
                info


        } catch (e: Exception) {
            MangaInfo(
                api = "",
                language = "",
                url = "",
                title = "",
                imageUrl = "",
                rating = "",
                ratingCount = "",
                description = "",
                otherNames = "",
                author = "",
                artist = "",
                genres =listOf(),
                tags = listOf(),
                yearOfProduction = "",
                status = "",
                favoritesCount = "",
                chapters = mutableListOf()
            )
        }

    }

    override suspend fun getSearchResults(json: String): List<MangaItem> {
        return try {
            val dilarItems: LibraryResponse = jsonParser.decodeFromString(json)
            val items = dilarItems.data.toMangaItemsLib()

            items
        } catch (e: Exception) {
            mutableListOf()
        }
    }



    override fun getChapterImages(json: String): List<String> {
        return try {
            val chaptersResponse: ChaptersResponse = jsonParser.decodeFromString(json)
            chaptersResponse.chapter
                ?.img
                ?.filterNotNull()
                ?: emptyList()

        } catch (e: Exception) {
            listOf()
        }
    }



    fun List<Data?>?.toMangaItemsLib(): MutableList<MangaItem> {
        // if list is null or empty, return an empty MutableList


        return this
            // drop any null entries
            ?.mapNotNull { data ->
                data?.let {

                    MangaItem(
                        api       = API,          // e.g. "manhwaweb"
                        language  = LANGUAGE,   // e.g. "KR"
                        title     = it.the_real_name ?: "—",
                        url       =  if (!it.real_id.isNullOrEmpty() ) "${baseUrl.ifBlank { BASE_URL }}/manhwa/see/${it.real_id}" else "${baseUrl.ifBlank { BASE_URL }}/manhwa/see/${it._id}",
                        imageUrl  = it._imagen      ?: "",
                        rating    = null,                          // no source field, so null
                        chapters  = emptyList<ChapterItem>(),      // fill in if you have chapter data
                        genres    = it._categoris
                            // map each Int? → String?
                            ?.mapNotNull { catId -> catId.toString() }
                            ?: emptyList()
                    )
                }
            }
            // produce a MutableList
            ?.toMutableList()
        // if original list was null, fall back to empty
            ?: mutableListOf()
    }
    fun List<ManhwasEsp?>?.toMangaItems(): MutableList<MangaItem>? {
        return this?.map { esp ->
            // 2) build up the genres list, filtering out blank/nulls


            val genres = listOfNotNull(
                esp?._demografi,
                esp?._plataforma,
                esp?._tipo,
                esp?.lgbt


            ).filter { it.isNotBlank() }

            MangaItem(
                api       = API,                              // your source identifier
                language  = LANGUAGE,                                     // Spanish
                title     = esp?.name_manhwa.orEmpty(),                // e.g. "Solo Leveling"
                url       = "${baseUrl.ifBlank { BASE_URL }}/manhwa/see/${esp?.id_rel}",                     // see helper below
                imageUrl  = esp?.img.orEmpty(),                        // cover URL
                rating    = null,                                     // no rating in this model
                chapters  = emptyList<ChapterItem>(),                 // you'd fill this later if you fetch chapters
                genres    = genres
            )
        }?.toMutableList()
    }


    fun InfoResponse.toMangaInfo(): MangaInfo {
        val userZone: ZoneId = ZoneId.systemDefault()

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url =  if (!real_id.isNullOrEmpty())"${baseUrl.ifBlank { BASE_URL }}/manhwa/see/${real_id}" else "${baseUrl.ifBlank { BASE_URL }}/manhwa/see/${_id}" ,
            title = the_real_name.orEmpty(),
            imageUrl = _imagen.orEmpty(),
            rating = "",               // No rating field in InfoResponse
            ratingCount = "",          // No count field in InfoResponse
            description = _sinopsis.orEmpty(),
            otherNames = listOfNotNull(name_esp, name_raw, _name)
                .filter { it.isNotBlank() }
                .joinToString(", "),
            author = "",               // No author field available
            artist = "",               // No artist field available
            genres = _categoris
                ?.mapNotNull { it?.entries?.firstOrNull() }
                ?.mapNotNull { (key, value) ->
                    key.toIntOrNull()?.let { id -> value.takeIf { it.isNotBlank() } }
                }
                .orEmpty(),
            tags = listOfNotNull(_demografi, _erotico)
                .filter { it.isNotBlank() },
            yearOfProduction = _creation.orEmpty(),
            status = _status.orEmpty(),
            favoritesCount = __v?.toString().orEmpty(),
            chapters = chapters
                ?.mapNotNull { it }
                ?.map { chapter ->
                    val slug = chapter.link?.removeSuffix("/")?.substringAfterLast("/")
                    val date = chapter.create
                        ?.let { Instant.ofEpochMilli(it) }
                        ?.atZone(userZone)
                        ?.toLocalDate()
                    ChapterItem(
                        name = "Capítulo ${chapter.chapter}",
                        number = chapter.chapter.toString(),
                        url = "${baseUrl.ifBlank { BASE_URL }}/chapters/see/$slug",
                        date = date

                    )
                }?.reversed()
                ?.toMutableList()
                ?: mutableListOf()
        )
    }

}